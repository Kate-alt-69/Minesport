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

import java.lang.reflect.Method;
import java.util.*;

import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_DONE;

public class MinesportBridge implements ClientModInitializer {

    private static final int EXTRACTION_BATCH_SIZE = 256;

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

    /**
     * Mojang renamed Window's native-handle accessor across supported versions
     * (getWindow -> handle, with getHandle used by some mapping sets). Keep the
     * optional worker-window hiding out of the compile-time compatibility
     * surface so one cosmetic optimization cannot break the Bridge matrix.
     */
    private void hideWorkerWindow(Minecraft client) {
        if (!"1".equals(System.getenv("MINESPORT_BRIDGE_WORKER"))) return;
        try {
            Object window = client.getWindow();
            long handle = findWindowHandle(window);
            if (handle != 0L) GLFW.glfwHideWindow(handle);
        } catch (Throwable ignored) {
            // Hiding is best-effort only. Registry/model capture is still valid
            // if a compatibility target exposes the handle under another name.
        }
    }

    private long findWindowHandle(Object window) {
        if (window == null) return 0L;
        for (String methodName : List.of("handle", "getWindow", "getHandle")) {
            try {
                Method method = window.getClass().getMethod(methodName);
                Object value = method.invoke(window);
                if (value instanceof Number number) return number.longValue();
            } catch (ReflectiveOperationException ignored) {
                // Try the next mapping-era name.
            }
        }
        return 0L;
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
            sender.flush();

            System.out.println("[MinesportBridge] Dumping " + allBlocks.size() + " registered block types from baked client models...");
            for (int start = 0; start < allBlocks.size(); start += EXTRACTION_BATCH_SIZE) {
                int end = Math.min(start + EXTRACTION_BATCH_SIZE, allBlocks.size());
                List<Block> batch = new ArrayList<>(allBlocks.subList(start, end));
                List<List<BlockVariant>> extracted = extractBatchSafe(client, batch);

                for (int index = 0; index < batch.size(); index++) {
                    Block block = batch.get(index);
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                    if (id == null) continue;
                    String blockId = id.toString();
                    String vanillaMapping = polymerPresent ? tryGetPolymerMapping(block) : null;
                    String loaderType = vanillaMapping != null
                        ? "polymer"
                        : (id.getNamespace().equals("minecraft") ? "vanilla" : "fabric");

                    // Geometry comes straight from Minecraft's already-baked
                    // client models. Batching only removes thousands of tiny
                    // client-thread execute/future round-trips; it does not
                    // change the model source or geometry fidelity.
                    List<BlockVariant> variants = index < extracted.size()
                        ? extracted.get(index)
                        : List.of();
                    sender.send(TYPE_BLOCK_ENTRY, new BlockEntry(blockId, vanillaMapping, loaderType, variants));

                    List<LightState> lightStates = extractLightStates(block);
                    if (!lightStates.isEmpty()) {
                        sender.send(TYPE_BLOCK_LIGHT, new BlockLightEntry(blockId, lightStates));
                    }
                }

                // Make one completed extraction batch visible to Rust. The
                // socket remains fully buffered between batches instead of
                // paying a flush syscall for every individual block packet.
                sender.flush();
                System.out.println("[MinesportBridge] Baked model extraction " + end + "/" + allBlocks.size());
            }

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", allBlocks.size()));
            sender.flush();
            System.out.println("[MinesportBridge] Registry/model dump complete. Exiting worker.");
            Thread.sleep(500);

        } catch (Exception e) {
            System.err.println("[MinesportBridge] Fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            Minecraft.getInstance().stop();
        }
    }

    private List<List<BlockVariant>> extractBatchSafe(Minecraft client, List<Block> blocks) {
        try {
            var future = new java.util.concurrent.CompletableFuture<List<List<BlockVariant>>>();
            client.execute(() -> {
                var result = new ArrayList<List<BlockVariant>>(blocks.size());
                for (Block block : blocks) {
                    try {
                        result.add(BlockGeometryExtractor.extractBlock(block, client));
                    } catch (Exception ignored) {
                        result.add(List.of());
                    }
                }
                future.complete(result);
            });
            return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            var fallback = new ArrayList<List<BlockVariant>>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) fallback.add(List.of());
            return fallback;
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
