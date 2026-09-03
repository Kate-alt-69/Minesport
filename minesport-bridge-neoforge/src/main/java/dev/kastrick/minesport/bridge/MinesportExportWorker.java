package dev.kastrick.minesport.bridge;

import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.*;
import dev.kastrick.minesport.bridge.registry.BlockGeometryExtractor;
import dev.kastrick.minesport.bridge.socket.ExportWorkerSender;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_BLOCK_ENTRY;
import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_BLOCK_LIGHT;
import static dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.TYPE_DONE;

@Mod(value = MinesportExportWorker.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MinesportExportWorker.MODID, value = Dist.CLIENT)
public final class MinesportExportWorker {
    public static final String MODID = "minesport_bridge";
    private static final int EXTRACTION_BATCH_SIZE = 256;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    public MinesportExportWorker(ModContainer container) {
    }

    private static boolean isWorkerLaunch() {
        return "1".equals(System.getenv("MINESPORT_EXPORT_WORKER"));
    }

    @SubscribeEvent
    static void onClientStarted(ClientStartedEvent event) {
        if (!isWorkerLaunch() || !STARTED.compareAndSet(false, true)) return;
        Minecraft client = Minecraft.getInstance();
        hideWorkerWindow(client);
        System.out.println("[MinesportExportWorker/NeoForge] Client resources ready — starting registry/model dump");
        runDump(client);
    }

    private static void hideWorkerWindow(Minecraft client) {
        if (!isWorkerLaunch()) return;
        try {
            Object window = client.getWindow();
            long handle = findWindowHandle(window);
            if (handle != 0L) GLFW.glfwHideWindow(handle);
        } catch (Throwable ignored) {
        }
    }

    private static long findWindowHandle(Object window) {
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

    private static void runDump(Minecraft client) {
        try (ExportWorkerSender sender = new ExportWorkerSender()) {
            String modeEnv = System.getenv("MINESPORT_EXPORT_WORKER_MODE");
            String mode = modeEnv != null ? modeEnv : "all";

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
            sender.send("hello", new Hello(
                net.minecraft.SharedConstants.getCurrentVersion().id(),
                loaderVersion(),
                allBlocks.size(),
                polymerPresent,
                getLoadedMods()
            ));
            sender.flush();

            System.out.println("[MinesportExportWorker/NeoForge] Dumping " + allBlocks.size() + " registered block types from baked client models...");
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
                        : (id.getNamespace().equals("minecraft") ? "vanilla" : "neoforge");
                    List<BlockVariant> variants = index < extracted.size() ? extracted.get(index) : List.of();
                    sender.send(TYPE_BLOCK_ENTRY, new BlockEntry(blockId, vanillaMapping, loaderType, variants));

                    List<LightState> lightStates = extractLightStates(block);
                    if (!lightStates.isEmpty()) {
                        sender.send(TYPE_BLOCK_LIGHT, new BlockLightEntry(blockId, lightStates));
                    }
                }

                sender.flush();
                System.out.println("[MinesportExportWorker/NeoForge] Baked model extraction " + end + "/" + allBlocks.size());
            }

            sender.sendRaw(Map.of("type", TYPE_DONE, "blocks", allBlocks.size()));
            sender.flush();
            System.out.println("[MinesportExportWorker/NeoForge] Registry/model dump complete. Exiting worker.");
        } catch (Exception error) {
            System.err.println("[MinesportExportWorker/NeoForge] Fatal: " + error.getMessage());
            error.printStackTrace();
        } finally {
            Minecraft.getInstance().stop();
        }
    }

    private static List<List<BlockVariant>> extractBatchSafe(Minecraft client, List<Block> blocks) {
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

    private static List<LightState> extractLightStates(Block block) {
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
            state.getValues().forEach((property, value) -> properties.put(property.getName(), String.valueOf(value)));
            result.add(new LightState(properties, level));
        }
        return result;
    }

    private static boolean isPolymerPresent() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("polymer");
    }

    private static String loaderVersion() {
        ModList mods = ModList.get();
        if (mods == null) return "?";
        return mods.getMods().stream()
            .filter(mod -> mod.getModId().equals("neoforge"))
            .map(mod -> mod.getVersion().toString())
            .findFirst()
            .orElse("?");
    }

    private static String tryGetPolymerMapping(Block block) {
        try {
            Class<?> polymerBlock = Class.forName("eu.pb4.polymer.core.api.block.PolymerBlock");
            if (!polymerBlock.isInstance(block)) return null;
            var getPolymerState = polymerBlock.getMethod("getPolymerBlockState",
                net.minecraft.world.level.block.state.BlockState.class,
                net.minecraft.server.level.ServerPlayer.class);
            var vanillaState = (net.minecraft.world.level.block.state.BlockState)
                getPolymerState.invoke(block, block.defaultBlockState(), null);
            if (vanillaState == null) return null;
            ResourceLocation vanillaId = BuiltInRegistries.BLOCK.getKey(vanillaState.getBlock());
            if (vanillaId == null) return null;
            StringBuilder result = new StringBuilder(vanillaId.toString());
            if (!vanillaState.getValues().isEmpty()) {
                result.append("[");
                var iterator = vanillaState.getValues().entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    result.append(entry.getKey().getName()).append("=").append(entry.getValue());
                    if (iterator.hasNext()) result.append(",");
                }
                result.append("]");
            }
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> getLoadedMods() {
        var result = new ArrayList<String>();
        ModList mods = ModList.get();
        if (mods != null) {
            mods.getMods().forEach(mod -> result.add(mod.getModId() + "@" + mod.getVersion()));
        }
        Collections.sort(result);
        return result;
    }
}
