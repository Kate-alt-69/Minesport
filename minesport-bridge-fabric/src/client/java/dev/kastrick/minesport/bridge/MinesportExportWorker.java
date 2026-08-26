package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.*;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.socket.ExportWorkerSender;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.*;

import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_DONE;

public class MinesportExportWorker implements ClientModInitializer {

    private static final int EXTRACTION_BATCH_SIZE = 256;

    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportExportWorker] Initializing runtime registry worker...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            hideWorkerWindow(client);
            System.out.println("[MinesportExportWorker] Client resources ready — starting registry/model dump");
            runDump(client);
        });
    }

    private void hideWorkerWindow(Minecraft client) {
        if (!"1".equals(System.getenv("MINESPORT_EXPORT_WORKER"))) return;
        try {
            Object window = client.getWindow();
            long handle = findWindowHandle(window);
            if (handle != 0L) GLFW.glfwHideWindow(handle);
        } catch (Throwable ignored) {
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
            }
        }
        return 0L;
    }

    private void runDump(Minecraft client) {
        try (ExportWorkerSender sender = new ExportWorkerSender()) {
            String modeEnv = System.getenv("MINESPORT_EXPORT_WORKER_MODE");
            String mode = (modeEnv != null) ? modeEnv : "all";

            String nsEnv = System.getenv("MINESPORT_EXPORT_WORKER_NS");
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
            PolymerApi polymerApi = polymerPresent ? PolymerApi.resolve() : null;
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

            System.out.println("[MinesportExportWorker] Dumping " + allBlocks.size() + " registered block types from baked client models...");
            for (int start = 0; start < allBlocks.size(); start += EXTRACTION_BATCH_SIZE) {
                int end = Math.min(start + EXTRACTION_BATCH_SIZE, allBlocks.size());
                List<Block> batch = new ArrayList<>(allBlocks.subList(start, end));
                List<List<BlockVariant>> extracted = extractBatchSafe(client, batch);

                for (int index = 0; index < batch.size(); index++) {
                    Block block = batch.get(index);
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                    if (id == null) continue;
                    String blockId = id.toString();
                    String vanillaMapping = polymerApi != null ? polymerApi.tryGetMapping(block) : null;
                    String loaderType = vanillaMapping != null
                        ? "polymer"
                        : (id.getNamespace().equals("minecraft") ? "vanilla" : "fabric");
                    List<BlockVariant> variants = index < extracted.size()
                        ? extracted.get(index)
                        : List.of();
                    sender.send(TYPE_BLOCK_ENTRY, new BlockEntry(blockId, vanillaMapping, loaderType, variants));

                    List<LightState> lightStates = extractLightStates(block);
                    if (!lightStates.isEmpty()) {
                        sender.send(TYPE_BLOCK_LIGHT, new BlockLightEntry(blockId, lightStates));
                    }
                }

                sender.flush();
                System.out.println("[MinesportExportWorker] Baked model extraction " + end + "/" + allBlocks.size());
            }

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", allBlocks.size()));
            sender.flush();
            System.out.println("[MinesportExportWorker] Registry/model dump complete. Exiting worker.");
        } catch (Exception e) {
            System.err.println("[MinesportExportWorker] Fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            Minecraft.getInstance().stop();
        }
    }

    private List<List<BlockVariant>> extractBatchSafe(Minecraft client, List<Block> blocks) {
        var result = new ArrayList<List<BlockVariant>>(blocks.size());
        for (Block block : blocks) {
            try {
                result.add(BlockGeometryExtractor.extractBlock(block, client));
            } catch (Exception ignored) {
                result.add(List.of());
            }
        }
        return result;
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
                ResourceLocation vid = BuiltInRegistries.BLOCK.getKey(vanillaState.getBlock());
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
