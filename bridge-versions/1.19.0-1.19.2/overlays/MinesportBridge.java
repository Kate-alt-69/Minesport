package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.*;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.registry.TextureExtractor;
import dev.kastrick.minesport.bridge.socket.BridgeSender;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.*;

public class MinesportBridge implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[MinesportBridge] Initializing...");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            System.out.println("[MinesportBridge] Client started — starting geometry dump");
            Thread t = new Thread(() -> runDump(client), "MinesportBridge-Dump");
            t.setDaemon(false);
            t.start();
        });
    }

    private void runDump(Minecraft client) {
        try (BridgeSender sender = new BridgeSender()) {
            String modeEnv = System.getenv("MINESPORT_BRIDGE_MODE");
            String mode = (modeEnv != null) ? modeEnv : "modded_only";

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

            System.out.println("[MinesportBridge] Dumping " + allBlocks.size() + " blocks...");
            Set<String> textureIds = new LinkedHashSet<>();

            for (Block block : allBlocks) {
                ResourceLocation id = Registry.BLOCK.getKey(block);
                String blockId = id.toString();
                String vanillaMapping = polymerPresent ? tryGetPolymerMapping(block) : null;
                String loaderType = vanillaMapping != null ? "polymer" : "fabric";

                List<BlockVariant> variants = extractSafe(client, block);
                for (var variant : variants)
                    for (var quad : variant.quads())
                        if (quad.textureId() != null && !quad.textureId().equals("missing"))
                            textureIds.add(quad.textureId());

                sender.send("block", new BlockEntry(blockId, vanillaMapping, loaderType, variants));
            }

            System.out.println("[MinesportBridge] Dumping " + textureIds.size() + " textures...");
            for (String texId : textureIds) {
                TextureEntry tex = TextureExtractor.extractTexture(texId, client);
                if (tex != null) sender.send("texture", tex);
            }

            sender.sendRaw(Map.of("type", "done", "blocks", allBlocks.size(), "textures", textureIds.size()));
            System.out.println("[MinesportBridge] Dump complete. Exiting.");
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
            ResourceLocation vid = Registry.BLOCK.getKey(vanillaState.getBlock());
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
        return mods;
    }
}
