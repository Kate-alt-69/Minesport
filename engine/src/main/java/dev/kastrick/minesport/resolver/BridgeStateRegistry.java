package dev.kastrick.minesport.resolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.region.BlockData;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Applies semantic metadata captured by the Minecraft/Fabric runtime worker. */
public final class BridgeStateRegistry {
    public static final int SNAPSHOT_SCHEMA = 2;

    private BridgeStateRegistry() {}

    private record LightState(Map<String, String> properties, int lightLevel) {}

    /**
     * Legacy compatibility entrypoint. Runtime registries are fingerprint-scoped,
     * so production exports should pass an explicit verified snapshot path.
     */
    public static int applyDefault(
        String minecraftVersion,
        List<BlockData> blocks,
        Consumer<String> log
    ) {
        return 0;
    }

    public static int apply(
        File snapshot,
        String expectedMinecraftVersion,
        List<BlockData> blocks,
        Consumer<String> log
    ) {
        if (snapshot == null || !snapshot.isFile() || blocks == null || blocks.isEmpty()) {
            return 0;
        }

        JsonObject root;
        try (Reader reader = new FileReader(snapshot)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            warn(log, "Runtime registry could not be read: " + exception.getMessage());
            return 0;
        }

        int schema = root.has("schema") ? safeInt(root.get("schema"), -1) : -1;
        if (schema != 1 && schema != SNAPSHOT_SCHEMA) {
            warn(log, "Ignoring runtime registry schema " + schema + " (expected 1 or " + SNAPSHOT_SCHEMA + ")");
            return 0;
        }

        String capturedVersion = root.has("minecraftVersion")
            ? root.get("minecraftVersion").getAsString().trim()
            : "";
        String expected = expectedMinecraftVersion == null ? "" : expectedMinecraftVersion.trim();
        if (!expected.isEmpty() && !capturedVersion.equals(expected)) {
            warn(log, "Ignoring runtime registry for Minecraft " + capturedVersion
                + " while exporting " + expected);
            return 0;
        }

        JsonObject blockObject = root.has("blocks") && root.get("blocks").isJsonObject()
            ? root.getAsJsonObject("blocks")
            : null;
        if (blockObject == null || blockObject.size() == 0) return 0;

        Map<String, List<LightState>> registry = new LinkedHashMap<>();
        for (var blockEntry : blockObject.entrySet()) {
            JsonElement source = blockEntry.getValue();
            // Schema 1 stored the light-state array directly under the block ID.
            // Schema 2 stores a full runtime block object with a "lights" array.
            JsonElement lights = source;
            if (schema >= 2 && source.isJsonObject()) {
                JsonObject block = source.getAsJsonObject();
                lights = block.has("lights") ? block.get("lights") : null;
            }
            if (lights == null || !lights.isJsonArray()) continue;

            List<LightState> states = new ArrayList<>();
            for (JsonElement element : lights.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject state = element.getAsJsonObject();
                int level = clampLevel(safeInt(state.get("lightLevel"), 0));
                if (level <= 0) continue;

                Map<String, String> properties = new LinkedHashMap<>();
                if (state.has("properties") && state.get("properties").isJsonObject()) {
                    for (var property : state.getAsJsonObject("properties").entrySet()) {
                        if (property.getValue().isJsonNull()) continue;
                        properties.put(property.getKey(), property.getValue().getAsString());
                    }
                }
                states.add(new LightState(properties, level));
            }
            if (!states.isEmpty()) registry.put(blockEntry.getKey(), states);
        }
        if (registry.isEmpty()) return 0;

        int enriched = 0;
        for (int index = 0; index < blocks.size(); index++) {
            BlockData block = blocks.get(index);
            if (block == null) continue;
            List<LightState> states = registry.get(block.blockId);
            if (states == null || states.isEmpty()) continue;

            LightState match = bestMatch(block, states);
            if (match == null || match.lightLevel() <= 0) continue;
            if (Integer.toString(match.lightLevel()).equals(block.prop("minesport_light_level"))) {
                continue;
            }

            Map<String, String> properties = new LinkedHashMap<>(block.properties);
            properties.put("minesport_light_level", Integer.toString(match.lightLevel()));
            blocks.set(index, copyWithProperties(block, properties));
            enriched++;
        }

        if (enriched > 0 && log != null) {
            log.accept("Runtime registry: applied Minecraft light emission to " + enriched
                + " world block(s) from " + snapshot.getName());
        }
        return enriched;
    }

    private static LightState bestMatch(BlockData block, List<LightState> states) {
        LightState best = null;
        int specificity = -1;
        for (LightState state : states) {
            if (!matches(block, state.properties())) continue;
            int size = state.properties().size();
            if (size > specificity) {
                best = state;
                specificity = size;
            }
        }
        return best;
    }

    private static boolean matches(BlockData block, Map<String, String> required) {
        for (var property : required.entrySet()) {
            if (!property.getValue().equals(block.prop(property.getKey()))) return false;
        }
        return true;
    }

    private static BlockData copyWithProperties(BlockData source, Map<String, String> properties) {
        BlockData copy = new BlockData(source.x, source.y, source.z, source.blockId, properties);
        copy.connectNorth = source.connectNorth;
        copy.connectSouth = source.connectSouth;
        copy.connectEast = source.connectEast;
        copy.connectWest = source.connectWest;
        copy.connectUp = source.connectUp;
        copy.isMultipart = source.isMultipart;
        return copy;
    }

    private static int safeInt(JsonElement value, int fallback) {
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clampLevel(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static void warn(Consumer<String> log, String message) {
        if (log != null) log.accept("[WARN] " + message);
    }
}
