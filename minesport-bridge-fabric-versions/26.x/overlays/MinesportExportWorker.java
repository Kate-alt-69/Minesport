package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BlockEntry;
import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BlockLightEntry;
import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BlockVariant;
import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.Hello;
import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.LightState;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.socket.ExportWorkerSender;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_DONE;

public final class MinesportExportWorker implements ClientModInitializer {
    private static final int EXTRACTION_BATCH_SIZE = 128;

    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportExportWorker] Initializing 26.x runtime registry worker...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            hideWorkerWindow(client);
            // The worker exists only to capture the runtime registry, so keep
            // extraction on Minecraft's client thread instead of spawning a
            // dump thread that immediately schedules every batch back here and
            // blocks on a CompletableFuture. This matches the canonical bridge
            // and removes one cross-thread round trip per extraction batch.
            runDump(client);
        });
    }

    private void hideWorkerWindow(Minecraft client) {
        if (!"1".equals(System.getenv("MINESPORT_EXPORT_WORKER"))) return;
        try {
            Object window = invokeFirstNoArg(client, "getWindow", "window");
            if (window == null) return;
            Object handleValue = invokeFirstNoArg(window, "handle", "getWindow", "getHandle");
            if (handleValue instanceof Number number && number.longValue() != 0L) {
                GLFW.glfwHideWindow(number.longValue());
            }
        } catch (Throwable ignored) {
            // Window suppression is best-effort. Registry capture remains valid.
        }
    }

    private static Object invokeFirstNoArg(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
                // Try the next mapping-era name.
            }
        }
        return null;
    }

    private void runDump(Minecraft client) {
        try (ExportWorkerSender sender = new ExportWorkerSender()) {
            String mode = System.getenv("MINESPORT_EXPORT_WORKER_MODE");
            if (mode == null || mode.isBlank()) {
                mode = "all";
            }

            Set<String> targetNamespaces = null;
            String namespaceEnv = System.getenv("MINESPORT_EXPORT_WORKER_NS");
            if (namespaceEnv != null && !namespaceEnv.isBlank()) {
                targetNamespaces = new HashSet<>(Arrays.asList(namespaceEnv.split(",")));
            }

            var blocks = new ArrayList<Block>();
            for (Block block : BuiltInRegistries.BLOCK) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) continue;
                String namespace = id.getNamespace();
                if ("modded_only".equals(mode) && "minecraft".equals(namespace)) continue;
                if (targetNamespaces != null && !targetNamespaces.contains(namespace)) continue;
                blocks.add(block);
            }

            boolean polymerPresent = FabricLoader.getInstance().isModLoaded("polymer");
            PolymerApi polymerApi = polymerPresent ? PolymerApi.resolve() : null;
            sender.send("hello", new Hello(
                SharedConstants.getCurrentVersion().id(),
                FabricLoader.getInstance()
                    .getModContainer("fabricloader")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("?"),
                blocks.size(),
                polymerPresent,
                loadedMods()
            ));
            sender.flush();

            System.out.println("[MinesportExportWorker] Dumping " + blocks.size() + " registered block types from baked client models...");
            for (int start = 0; start < blocks.size(); start += EXTRACTION_BATCH_SIZE) {
                int end = Math.min(start + EXTRACTION_BATCH_SIZE, blocks.size());
                List<Block> batch = new ArrayList<>(blocks.subList(start, end));
                List<List<BlockVariant>> extracted = extractBatchSafe(client, batch);

                for (int index = 0; index < batch.size(); index++) {
                    Block block = batch.get(index);
                    Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                    if (id == null) continue;

                    List<BlockVariant> variants = index < extracted.size()
                        ? extracted.get(index)
                        : List.of();
                    String vanillaMapping = polymerApi != null ? polymerApi.tryGetMapping(block) : null;
                    String loaderType = vanillaMapping != null
                        ? "polymer"
                        : ("minecraft".equals(id.getNamespace()) ? "vanilla" : "fabric");

                    sender.send(TYPE_BLOCK_ENTRY, new BlockEntry(
                        id.toString(),
                        vanillaMapping,
                        loaderType,
                        variants
                    ));

                    List<LightState> lightStates = extractLightStates(block);
                    if (!lightStates.isEmpty()) {
                        sender.send(TYPE_BLOCK_LIGHT, new BlockLightEntry(id.toString(), lightStates));
                    }
                }

                sender.flush();
                System.out.println("[MinesportExportWorker] Baked model extraction " + end + "/" + blocks.size());
            }

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", blocks.size()));
            sender.flush();
            System.out.println("[MinesportExportWorker] Registry/model dump complete.");
        } catch (Exception exception) {
            System.err.println("[MinesportExportWorker] Fatal: " + exception.getMessage());
            exception.printStackTrace();
        } finally {
            client.execute(client::stop);
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
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            int level;
            try {
                level = Math.max(0, Math.min(15, state.getLightEmission()));
            } catch (Exception ignored) {
                continue;
            }
            if (level <= 0) continue;

            Map<String, String> properties = new LinkedHashMap<>();
            for (Property<?> property : state.getProperties()) {
                properties.put(property.getName(), propertyValueUnchecked(state, property));
            }
            result.add(new LightState(properties, level));
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueUnchecked(BlockState state, Property<?> property) {
        return String.valueOf(state.getValue((Property) property));
    }

    /** Polymer remains optional; resolve its mapping API once per worker. */
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
                Method method = Arrays.stream(blockType.getMethods())
                    .filter(candidate ->
                        candidate.getName().equals("getPolymerBlockState")
                        && candidate.getParameterCount() >= 1
                        && candidate.getParameterTypes()[0].isAssignableFrom(BlockState.class)
                    )
                    .findFirst()
                    .orElse(null);
                return method == null ? null : new PolymerApi(blockType, method);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private String tryGetMapping(Block block) {
            if (!blockType.isInstance(block)) return null;
            try {
                Object[] args = new Object[getPolymerState.getParameterCount()];
                args[0] = block.defaultBlockState();
                for (int i = 1; i < args.length; i++) args[i] = null;

                Object result = getPolymerState.invoke(block, args);
                if (!(result instanceof BlockState vanillaState)) return null;

                Identifier vanillaId = BuiltInRegistries.BLOCK.getKey(vanillaState.getBlock());
                if (vanillaId == null) return null;

                StringBuilder text = new StringBuilder(vanillaId.toString());
                var values = new ArrayList<String>();
                for (var property : vanillaState.getProperties()) {
                    values.add(property.getName() + "=" + propertyValue(vanillaState, property));
                }
                if (!values.isEmpty()) {
                    text.append("[").append(String.join(",", values)).append("]");
                }
                return text.toString();
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                return null;
            }
        }
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return String.valueOf(state.getValue(property));
    }

    private List<String> loadedMods() {
        var mods = new ArrayList<String>();
        FabricLoader.getInstance().getAllMods().forEach(mod ->
            mods.add(
                mod.getMetadata().getId()
                + "@"
                + mod.getMetadata().getVersion().getFriendlyString()
            )
        );
        Collections.sort(mods);
        return mods;
    }
}
