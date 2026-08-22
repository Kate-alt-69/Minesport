package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.*;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.socket.BridgeSender;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_DONE;

public class MinesportBridge implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportBridge] Initializing runtime registry worker...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            hideWorkerWindow(client);
            System.out.println("[MinesportBridge] Client resources ready — starting registry/model dump");
            Thread t = new Thread(() -> runDump(client), "MinesportBridge-Dump");
            t.setDaemon(false);
            t.start();
        });
    }

    private void hideWorkerWindow(Minecraft client) {
        if (!"1".equals(System.getenv("MINESPORT_BRIDGE_WORKER"))) return;
        try {
            long handle = client.getWindow().getWindow();
            if (handle != 0L) {
                GLFW.glfwHideWindow(handle);
            }
        } catch (Throwable ignored) {
            // Window hiding is an optimization only. Registry capture must still
            // work on compatibility targets where GLFW/window APIs differ.
        }
    }

    private void runDump(Minecraft client) {
        try (BridgeSender sender = new BridgeSender()) {
            String modeEnv = System.getenv("MINESPORT_BRIDGE_MODE");
            String mode = (modeEnv != null) ? modeEnv : "all";

            String nsEnv = System.getenv("MINESPORT_BRIDGE_NS");
            Set<String> targetNs = null;
            if (nsEnv != null && !nsEnv.isEmpty()) {
                targetNs = new HashSet<>(Arrays.asList(nsEnv.split(",")));
            }

            var allBlocks = new ArrayList<Block>();
            for (Block block : BuiltInRegistries.BLOCK) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) continue;
                String ns = id.getNamespace();
                if ("modded_only".equals(mode) && ns.equals("minecraft")) continue;
                if (targetNs != null && !targetNs.contains(ns)) continue;
                allBlocks.add(block);
            }

            boolean polymerPresent = isPolymerPresent();
            sender.send("hello", new Hello(
                net.minecraft.SharedConstants.getCurrentVersion().id(),
                net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("fabricloader")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("?"),
                allBlocks.size(),
                polymerPresent,
                getLoadedMods()
            ));

            System.out.println("[MinesportBridge] Dumping " + allBlocks.size() + " registered block types...");
            for (Block block : allBlocks) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                String blockId = id.toString();
                String vanillaMapping = polymerPresent ? tryGetPolymerMapping(block) : null;
                String loaderType = vanillaMapping != null
                    ? "polymer"
                    : (id.getNamespace().equals("minecraft") ? "vanilla" : "fabric");

                // Geometry contains texture IDs only. Minesport resolves the actual
                // PNG/JPEG from resource packs, the mod JAR, local vanilla assets,
                // Mojang Piston recovery, or the normal missing-texture fallback.
                List<BlockVariant> variants = extractSafe(client, block);
                sender.send(TYPE_BLOCK_ENTRY, new BlockEntry(blockId, vanillaMapping, loaderType, variants));

                List<LightState> lightStates = extractLightStates(block);
                if (!lightStates.isEmpty()) {
                    sender.send(TYPE_BLOCK_LIGHT, new BlockLightEntry(blockId, lightStates));
                }
            }

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", allBlocks.size()));
            System.out.println("[MinesportBridge] Registry/model dump complete. Exiting worker.");
            Thread.sleep(500);

        } catch (Exception e) {
            System.err.println("[MinesportBridge] Fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            Minecraft.getInstance().stop();
        }
    }

    private List<BlockVariant> extractSafe(Minecraft client, Block block) {
        try {
            var future = new java.util.concurrent.CompletableFuture<List<BlockVariant>>();
            client.execute(() -> {
                try {
                    future.complete(BlockGeometryExtractor.extractBlock(block, client));
                } catch (Exception e) {
                    future.complete(List.of());
                }
            });
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<LightState> extractLightStates(Block block) {
        var result = new ArrayList<LightState>();
        for (var state : block.getStateDefinition().getPossibleStates()) {
            int level;
            try {
                level = Math.max(0, Math.min(15, state.getLightEmission()));
            } catch (Exception ignored) {
                continue;
            }
            if (level <= 0) continue;

            Map<String, String> properties = new LinkedHashMap<>();
            state.getValues().forEach((property, value) ->
                properties.put(property.getName(), String.valueOf(value))
            );
            result.add(new LightState(properties, level));
        }
        return result;
    }

    private boolean isPolymerPresent() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("polymer");
    }

    private String tryGetPolymerMapping(Block block) {
        try {
            Class<?> polymerBlock = Class.forName("eu.pb4.polymer.core.api.block.PolymerBlock");
            if (!polymerBlock.isInstance(block)) return null;
            var getPolymerState = polymerBlock.getMethod("getPolymerBlockState",
                net.minecraft.world.level.block.state.BlockState.class,
                net.minecraft.server.level.ServerPlayer.class);
            var vanillaState = (net.minecraft.world.level.block.state.BlockState)
                getPolymerState.invoke(block, block.defaultBlockState(), null);
            if (vanillaState == null) return null;
            ResourceLocation vid = BuiltInRegistries.BLOCK.getKey(vanillaState.getBlock());
            StringBuilder sb = new StringBuilder(vid.toString());
            if (!vanillaState.getValues().isEmpty()) {
                sb.append("[");
                var it = vanillaState.getValues().entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    sb.append(e.getKey().getName()).append("=").append(e.getValue());
                    if (it.hasNext()) sb.append(",");
                }
                sb.append("]");
            }
            return sb.toString();
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> getLoadedMods() {
        var mods = new ArrayList<String>();
        net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().forEach(mod ->
            mods.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString()));
        Collections.sort(mods);
        return mods;
    }
}
