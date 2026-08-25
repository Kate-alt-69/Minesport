package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.*;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.socket.BridgeSender;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Method;
import java.util.*;

import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_DONE;

public class MinesportBridge implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportBridge] Initializing 1.19 runtime registry worker...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            System.out.println("[MinesportBridge] Client resources ready — starting registry/model dump");
            // CLIENT_STARTED already runs on Minecraft's client thread. Running
            // the dump here avoids bouncing every single block back through
            // client.execute(...) and waiting on a CompletableFuture.
            runDump(client);
        });
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
            for (Block block : Registry.BLOCK) {
                ResourceLocation id = Registry.BLOCK.getKey(block);
                if (id == null) continue;
                String ns = id.getNamespace();
                if ("modded_only".equals(mode) && ns.equals("minecraft")) continue;
                if (targetNs != null && !targetNs.contains(ns)) continue;
                allBlocks.add(block);
            }

            boolean polymerPresent = isPolymerPresent();
            PolymerApi polymerApi = polymerPresent ? PolymerApi.resolve() : null;
            sender.send("hello", new Hello(
                net.minecraft.SharedConstants.getCurrentVersion().getId(),
                net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("fabricloader")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("?"),
                allBlocks.size(),
                polymerPresent,
                getLoadedMods()
            ));
            sender.flush();

            System.out.println("[MinesportBridge] Dumping " + allBlocks.size() + " registered block types...");
            int sent = 0;
            for (Block block : allBlocks) {
                ResourceLocation id = Registry.BLOCK.getKey(block);
                if (id == null) continue;
                String blockId = id.toString();
                String vanillaMapping = polymerApi != null ? polymerApi.tryGetMapping(block) : null;
                String loaderType = vanillaMapping != null
                    ? "polymer"
                    : (id.getNamespace().equals("minecraft") ? "vanilla" : "fabric");

                // Runtime cache stores baked geometry and texture identifiers only.
                // Minesport resolves image bytes from packs/mod JARs/vanilla/Piston.
                List<BlockVariant> variants = extractSafe(client, block);
                sender.send(TYPE_BLOCK_ENTRY, new BlockEntry(blockId, vanillaMapping, loaderType, variants));

                List<LightState> lightStates = extractLightStates(block);
                if (!lightStates.isEmpty()) {
                    sender.send(TYPE_BLOCK_LIGHT, new BlockLightEntry(blockId, lightStates));
                }

                sent++;
                if ((sent & 127) == 0) sender.flush();
            }

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", allBlocks.size()));
            sender.flush();
            System.out.println("[MinesportBridge] Registry/model dump complete. Exiting worker.");
            Thread.sleep(250);

        } catch (Exception e) {
            System.err.println("[MinesportBridge] Fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            Minecraft.getInstance().stop();
        }
    }

    private List<BlockVariant> extractSafe(Minecraft client, Block block) {
        try {
            return BlockGeometryExtractor.extractBlock(block, client);
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

    private static final class PolymerApi {
        private final Class<?> blockType;
        private final Method getPolymerState;

        private PolymerApi(Class<?> blockType, Method getPolymerState) {
            this.blockType = blockType;
            this.getPolymerState = getPolymerState;
        }

        private static PolymerApi resolve() {
            try {
                Class<?> blockType = Class.forName("eu.pb4.polymer.core.api.block.PolymerBlock");
                Method getPolymerState = blockType.getMethod(
                    "getPolymerBlockState",
                    net.minecraft.world.level.block.state.BlockState.class,
                    net.minecraft.server.level.ServerPlayer.class
                );
                return new PolymerApi(blockType, getPolymerState);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private String tryGetMapping(Block block) {
            if (!blockType.isInstance(block)) return null;
            try {
                var vanillaState = (net.minecraft.world.level.block.state.BlockState)
                    getPolymerState.invoke(block, block.defaultBlockState(), null);
                if (vanillaState == null) return null;
                ResourceLocation vid = Registry.BLOCK.getKey(vanillaState.getBlock());
                if (vid == null) return null;
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
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                return null;
            }
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
