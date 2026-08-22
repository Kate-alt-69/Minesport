package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockEntry;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockLightEntry;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockVariant;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.Hello;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.LightState;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.socket.BridgeSender;

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

import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.BridgeProtocol.TYPE_DONE;

public final class MinesportBridge implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportBridge] Initializing 26.x runtime registry worker...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            hideWorkerWindow(client);
            Thread dumpThread = new Thread(() -> runDump(client), "MinesportBridge-Dump");
            dumpThread.setDaemon(false);
            dumpThread.start();
        });
    }

    private void hideWorkerWindow(Minecraft client) {
        if (!"1".equals(System.getenv("MINESPORT_BRIDGE_WORKER"))) return;
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
        try (BridgeSender sender = new BridgeSender()) {
            String mode = System.getenv("MINESPORT_BRIDGE_MODE");
            if (mode == null || mode.isBlank()) {
                mode = "all";
            }

            Set<String> targetNamespaces = null;
            String namespaceEnv = System.getenv("MINESPORT_BRIDGE_NS");
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

            System.out.println("[MinesportBridge] Dumping " + blocks.size() + " registered block types...");
            for (Block block : blocks) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) continue;

                List<BlockVariant> variants = extractSafe(client, block);
                String vanillaMapping = polymerPresent ? tryGetPolymerMapping(block) : null;
                String loaderType = vanillaMapping != null
                    ? "polymer"
                    : ("minecraft".equals(id.getNamespace()) ? "vanilla" : "fabric");

                // Cache baked quads plus texture identifiers only. Image bytes are
                // resolved later by Minesport from packs, mod JARs, vanilla/Piston.
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

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", blocks.size()));
            System.out.println("[MinesportBridge] Registry/model dump complete.");
        } catch (Exception exception) {
            System.err.println("[MinesportBridge] Fatal: " + exception.getMessage());
            exception.printStackTrace();
        } finally {
            client.execute(client::stop);
        }
    }

    private List<BlockVariant> extractSafe(Minecraft client, Block block) {
        try {
            var future = new java.util.concurrent.CompletableFuture<List<BlockVariant>>();
            client.execute(() -> {
                try {
                    future.complete(BlockGeometryExtractor.extractBlock(block, client));
                } catch (Exception exception) {
                    future.complete(List.of());
                }
            });
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            return List.of();
        }
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

    /** Polymer remains optional; resolve its API dynamically. */
    private String tryGetPolymerMapping(Block block) {
        try {
            Class<?> polymerBlock = Class.forName("eu.pb4.polymer.core.api.block.PolymerBlock");
            if (!polymerBlock.isInstance(block)) return null;

            Method method = Arrays.stream(polymerBlock.getMethods())
                .filter(candidate ->
                    candidate.getName().equals("getPolymerBlockState")
                    && candidate.getParameterCount() >= 1
                    && candidate.getParameterTypes()[0].isAssignableFrom(BlockState.class)
                )
                .findFirst()
                .orElse(null);
            if (method == null) return null;

            Object[] args = new Object[method.getParameterCount()];
            args[0] = block.defaultBlockState();
            for (int i = 1; i < args.length; i++) args[i] = null;

            Object result = method.invoke(block, args);
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
        } catch (Exception ignored) {
            return null;
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
