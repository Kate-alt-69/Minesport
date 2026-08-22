package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.region.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves Minecraft block-light emitters into DCC-friendly point-light descriptors.
 *
 * Minecraft's logical light level is retained as the source of truth (0..15). The
 * Blender translator and glTF exporter are free to render that discrete reach with
 * smooth falloff while still preserving the original level for editing/round trips.
 */
public final class MinecraftLightExporter {
    private static final Set<String> LEVEL_15 = Set.of(
        "minecraft:beacon",
        "minecraft:conduit",
        "minecraft:fire",
        "minecraft:glowstone",
        "minecraft:jack_o_lantern",
        "minecraft:lava",
        "minecraft:sea_lantern",
        "minecraft:shroomlight",
        "minecraft:ochre_froglight",
        "minecraft:verdant_froglight",
        "minecraft:pearlescent_froglight"
    );

    private static final Set<String> LEVEL_14 = Set.of(
        "minecraft:torch",
        "minecraft:wall_torch",
        "minecraft:end_rod"
    );

    private static final Set<String> LEVEL_10 = Set.of(
        "minecraft:soul_torch",
        "minecraft:soul_wall_torch",
        "minecraft:soul_lantern",
        "minecraft:soul_fire"
    );

    private MinecraftLightExporter() {}

    public record LightSource(
        String name,
        String sourceBlock,
        double x,
        double y,
        double z,
        int minecraftLevel,
        float red,
        float green,
        float blue,
        boolean invisibleSource
    ) {
        public double rangeBlocks() {
            return minecraftLevel + 0.5;
        }

        /** Conservative glTF/Blender-friendly intensity; level remains separately editable. */
        public double intensity() {
            return 45.0 * minecraftLevel;
        }
    }

    public static List<LightSource> resolve(List<BlockData> blocks) {
        List<LightSource> result = new ArrayList<>();
        if (blocks == null) return result;

        for (BlockData block : blocks) {
            if (block == null) continue;
            int level = lightLevel(block);
            if (level <= 0) continue;

            float[] color = lightColor(block);
            double[] origin = lightOrigin(block);
            boolean invisible = "minecraft:light".equals(block.blockId);
            String name = safeName(block.blockId) + "_Light_" + block.x + "_" + block.y + "_" + block.z;
            result.add(new LightSource(
                name,
                block.blockId,
                origin[0], origin[1], origin[2],
                level,
                color[0], color[1], color[2],
                invisible
            ));
        }
        return result;
    }

    public static JsonArray sidecarLights(List<BlockData> blocks, float[] center) {
        JsonArray lights = new JsonArray();
        for (LightSource source : resolve(blocks)) {
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("name", source.name());
            descriptor.addProperty("source", source.sourceBlock());
            descriptor.addProperty("type", "point");
            descriptor.addProperty("minecraftLevel", source.minecraftLevel());
            descriptor.addProperty("rangeBlocks", source.rangeBlocks());
            descriptor.addProperty("intensity", source.intensity());
            descriptor.addProperty("falloff", "minecraft_linear_smooth");
            descriptor.addProperty("invisibleSource", source.invisibleSource());

            // Sidecar coordinates are Blender-native: X right, Y forward, Z up.
            JsonArray position = new JsonArray();
            position.add(source.x() - center[0]);
            position.add(-(source.z() - center[2]));
            position.add(source.y() - center[1]);
            descriptor.add("position", position);

            JsonArray color = new JsonArray();
            color.add(source.red());
            color.add(source.green());
            color.add(source.blue());
            descriptor.add("color", color);
            lights.add(descriptor);
        }
        return lights;
    }

    public static int lightLevel(BlockData block) {
        String id = block.blockId;
        if (id == null || id.isBlank()) return 0;

        if ("minecraft:light".equals(id)) {
            return clampLevel(parseInt(block.prop("level"), 15));
        }
        if (LEVEL_15.contains(id)) return 15;
        if (LEVEL_14.contains(id)) return 14;
        if (LEVEL_10.contains(id)) return 10;
        if ("minecraft:lantern".equals(id)) return 15;
        if ("minecraft:redstone_torch".equals(id) || "minecraft:redstone_wall_torch".equals(id)) {
            return isFalse(block.prop("lit")) ? 0 : 7;
        }
        if ("minecraft:redstone_lamp".equals(id)) {
            return isTrue(block.prop("lit")) ? 15 : 0;
        }
        if ("minecraft:furnace".equals(id) || "minecraft:blast_furnace".equals(id)
            || "minecraft:smoker".equals(id)) {
            return isTrue(block.prop("lit")) ? 13 : 0;
        }
        if ("minecraft:campfire".equals(id)) {
            return isFalse(block.prop("lit")) ? 0 : 15;
        }
        if ("minecraft:soul_campfire".equals(id)) {
            return isFalse(block.prop("lit")) ? 0 : 10;
        }

        boolean candle = "minecraft:candle".equals(id) || id.endsWith("_candle");
        boolean candleCake = "minecraft:candle_cake".equals(id) || id.endsWith("_candle_cake");
        if (candle || candleCake) {
            if (!isTrue(block.prop("lit"))) return 0;
            int candles = candleCake ? 1 : Math.max(1, parseInt(block.prop("candles"), 1));
            return clampLevel(candles * 3);
        }

        if ("minecraft:glow_lichen".equals(id)) return 7;
        if ("minecraft:cave_vines".equals(id) || "minecraft:cave_vines_plant".equals(id)) {
            return isTrue(block.prop("berries")) ? 14 : 0;
        }
        if ("minecraft:amethyst_cluster".equals(id)) return 5;
        if ("minecraft:large_amethyst_bud".equals(id)) return 4;
        if ("minecraft:medium_amethyst_bud".equals(id)) return 2;
        if ("minecraft:small_amethyst_bud".equals(id)) return 1;
        if ("minecraft:crying_obsidian".equals(id)) return 10;
        if ("minecraft:magma_block".equals(id)) return 3;

        // Bridge/plugin adapters can expose a luminance property without teaching
        // the core exporter every modded glowing block forever.
        String bridged = block.prop("minesport_light_level");
        if (!bridged.isBlank()) return clampLevel(parseInt(bridged, 0));
        bridged = block.prop("luminance");
        if (!bridged.isBlank()) return clampLevel(parseInt(bridged, 0));
        return 0;
    }

    private static double[] lightOrigin(BlockData block) {
        double x = block.x + 0.5;
        double y = block.y + 0.5;
        double z = block.z + 0.5;
        String id = block.blockId;

        if (id.endsWith("torch") && !id.contains("wall_torch")) {
            y = block.y + 0.72;
        } else if (id.contains("wall_torch")) {
            y = block.y + 0.62;
            switch (block.prop("facing")) {
                case "north" -> z = block.z + 0.82;
                case "south" -> z = block.z + 0.18;
                case "east" -> x = block.x + 0.18;
                case "west" -> x = block.x + 0.82;
                default -> { }
            }
        } else if (id.contains("lantern")) {
            y = block.y + ("true".equals(block.prop("hanging")) ? 0.52 : 0.38);
        } else if (id.contains("campfire")) {
            y = block.y + 0.35;
        }
        return new double[]{x, y, z};
    }

    private static float[] lightColor(BlockData block) {
        String id = block.blockId == null ? "" : block.blockId;
        if (id.contains("soul_")) return new float[]{0.28f, 0.78f, 1.0f};
        if (id.contains("torch") || id.contains("lantern") || id.contains("campfire")
            || id.equals("minecraft:fire") || id.equals("minecraft:lava")
            || id.equals("minecraft:jack_o_lantern") || id.contains("candle")) {
            return new float[]{1.0f, 0.58f, 0.26f};
        }
        if (id.contains("froglight")) return new float[]{1.0f, 0.86f, 0.64f};
        if (id.equals("minecraft:sea_lantern") || id.equals("minecraft:conduit")) {
            return new float[]{0.68f, 0.93f, 1.0f};
        }
        return new float[]{1.0f, 0.92f, 0.78f};
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value);
    }

    private static boolean isFalse(String value) {
        return "false".equalsIgnoreCase(value);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clampLevel(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "Minesport";
        return value.toLowerCase(Locale.ROOT)
            .replace(':', '_')
            .replace('/', '_')
            .replace('\\', '_');
    }
}
