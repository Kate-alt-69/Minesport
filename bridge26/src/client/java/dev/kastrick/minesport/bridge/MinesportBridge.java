package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockEntry;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockVariant;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.Hello;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.TextureEntry;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.registry.TextureExtractor;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MinesportBridge implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportBridge] Initializing 26.x bridge...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Thread dumpThread = new Thread(() -> runDump(client), "MinesportBridge-Dump");
            dumpThread.setDaemon(false);
            dumpThread.start();
        });
    }

    private void runDump(Minecraft client) {
        try (BridgeSender sender = new BridgeSender()) {
            String mode = System.getenv("MINESPORT_BRIDGE_MODE");
            if (mode == null || mode.isBlank()) {
                mode = "modded_only";
            }

            Set<String> targetNamespaces = null;
            String namespaceEnv = System.getenv("MINESPORT_BRIDGE_NS");
            if (namespaceEnv != null && !namespaceEnv.isBlank()) {
                targetNamespaces = new HashSet<>(Arrays.asList(namespaceEnv.split(",")));
            }

            var blocks = new ArrayList<Block>();
            for (Block block : BuiltInRegistries.BLOCK) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) {
                    continue;
                }
                String namespace = id.getNamespace();
                if ("modded_only".equals(mode) && "minecraft".equals(namespace)) {
                    continue;
                }
                if (targetNamespaces != null && !targetNamespaces.contains(namespace)) {
                    continue;
                }
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

            Set<String> textureIds = new LinkedHashSet<>();
            for (Block block : blocks) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id == null) {
                    continue;
                }

                List<BlockVariant> variants = extractSafe(client, block);
                for (BlockVariant variant : variants) {
                    variant.quads().forEach(quad -> {
                        if (quad.textureId() != null && !"missing".equals(quad.textureId())) {
                            textureIds.add(quad.textureId());
                        }
                    });
                }

                String vanillaMapping = polymerPresent ? tryGetPolymerMapping(block) : null;
                sender.send("block", new BlockEntry(
                    id.toString(),
                    vanillaMapping,
                    vanillaMapping == null ? "fabric" : "polymer",
                    variants
                ));
            }

            for (String textureId : textureIds) {
                TextureEntry texture = TextureExtractor.extractTexture(textureId, client);
                if (texture != null) {
                    sender.send("texture", texture);
                }
            }

            sender.sendRaw(Map.of(
                "type", "done",
                "blocks", blocks.size(),
                "textures", textureIds.size()
            ));
            System.out.println("[MinesportBridge] Dump complete.");
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

    /**
     * Polymer remains optional: discover its method dynamically so the bridge
     * does not require a particular Polymer build just to compile.
     */
    private String tryGetPolymerMapping(Block block) {
        try {
            Class<?> polymerBlock = Class.forName("eu.pb4.polymer.core.api.block.PolymerBlock");
            if (!polymerBlock.isInstance(block)) {
                return null;
            }

            Method method = Arrays.stream(polymerBlock.getMethods())
                .filter(candidate ->
                    candidate.getName().equals("getPolymerBlockState")
                    && candidate.getParameterCount() >= 1
                    && candidate.getParameterTypes()[0].isAssignableFrom(BlockState.class)
                )
                .findFirst()
                .orElse(null);
            if (method == null) {
                return null;
            }

            Object[] args = new Object[method.getParameterCount()];
            args[0] = block.defaultBlockState();
            for (int i = 1; i < args.length; i++) {
                args[i] = null;
            }

            Object result = method.invoke(block, args);
            if (!(result instanceof BlockState vanillaState)) {
                return null;
            }

            Identifier vanillaId = BuiltInRegistries.BLOCK.getKey(vanillaState.getBlock());
            if (vanillaId == null) {
                return null;
            }

            StringBuilder text = new StringBuilder(vanillaId.toString());
            if (!vanillaState.getValues().isEmpty()) {
                text.append("[");
                var iterator = vanillaState.getValues().entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    text.append(entry.getKey().getName()).append("=").append(entry.getValue());
                    if (iterator.hasNext()) {
                        text.append(",");
                    }
                }
                text.append("]");
            }
            return text.toString();
        } catch (Exception ignored) {
            return null;
        }
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
        return mods;
    }
}
