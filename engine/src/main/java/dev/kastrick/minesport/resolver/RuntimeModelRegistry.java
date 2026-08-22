package dev.kastrick.minesport.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.export.Quad;
import dev.kastrick.minesport.region.BlockData;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Read-only geometry registry captured from Minecraft's own baked model manager.
 *
 * Schema 4 uses compact registry.data storage. Texture pixels deliberately remain
 * outside this cache and continue through the normal Minesport resolver chain
 * (resource packs -> mod JARs -> vanilla/Piston).
 */
public final class RuntimeModelRegistry {
    public static final int SNAPSHOT_SCHEMA = RuntimeRegistryDataReader.SCHEMA;
    private static final int LEGACY_JSON_SCHEMA = 3;

    /** Distinguishes absence from Minecraft explicitly baking an empty model. */
    public enum StateKind {
        UNKNOWN,
        BAKED,
        EMPTY_BAKED_MODEL
    }

    private record RuntimeQuad(
        float[] vertices,
        String textureId,
        int face,
        boolean shade,
        int tintIndex
    ) {}

    private record Variant(Map<String, String> properties, List<RuntimeQuad> quads) {}

    private record RuntimeBlock(
        String vanillaMapping,
        String loaderType,
        List<Variant> variants
    ) {}

    private final Map<String, RuntimeBlock> blocks;
    private final String minecraftVersion;
    private final String modsFingerprint;

    private RuntimeModelRegistry(
        Map<String, RuntimeBlock> blocks,
        String minecraftVersion,
        String modsFingerprint
    ) {
        this.blocks = Map.copyOf(blocks);
        this.minecraftVersion = minecraftVersion;
        this.modsFingerprint = modsFingerprint;
    }

    public static RuntimeModelRegistry load(
        File snapshot,
        String expectedMinecraftVersion,
        Consumer<String> log
    ) {
        if (snapshot == null || !snapshot.isFile()) return null;
        if (snapshot.getName().toLowerCase().endsWith(".data")) {
            return loadData(snapshot, expectedMinecraftVersion, log);
        }
        return loadLegacyJson(snapshot, expectedMinecraftVersion, log);
    }

    private static RuntimeModelRegistry loadData(
        File snapshot,
        String expectedMinecraftVersion,
        Consumer<String> log
    ) {
        RuntimeRegistryDataReader.DataSnapshot root;
        try {
            root = RuntimeRegistryDataReader.read(snapshot);
        } catch (Exception exception) {
            warn(log, "Runtime model registry could not be read: " + exception.getMessage());
            return null;
        }
        if (root.schema() != SNAPSHOT_SCHEMA) {
            warn(log, "Runtime model registry schema " + root.schema()
                + " is not geometry-compatible with schema " + SNAPSHOT_SCHEMA);
            return null;
        }

        String capturedVersion = root.minecraftVersion() == null ? "" : root.minecraftVersion().trim();
        String expected = expectedMinecraftVersion == null ? "" : expectedMinecraftVersion.trim();
        if (!expected.isEmpty() && !capturedVersion.equals(expected)) {
            warn(log, "Ignoring runtime models for Minecraft " + capturedVersion
                + " while exporting " + expected);
            return null;
        }

        Map<String, RuntimeBlock> parsed = new LinkedHashMap<>();
        int variantCount = 0;
        int quadCount = 0;
        int emptyVariantCount = 0;
        for (var blockEntry : root.blocks().entrySet()) {
            RuntimeRegistryDataReader.DataBlock source = blockEntry.getValue();
            if (source == null || source.variants() == null || source.variants().isEmpty()) continue;

            List<Variant> variants = new ArrayList<>(source.variants().size());
            for (RuntimeRegistryDataReader.DataVariant sourceVariant : source.variants()) {
                List<RuntimeQuad> quads = new ArrayList<>(sourceVariant.quads().size());
                for (RuntimeRegistryDataReader.DataQuad sourceQuad : sourceVariant.quads()) {
                    float[] vertices = sourceQuad.vertices();
                    if (vertices == null || vertices.length < 32) continue;
                    quads.add(new RuntimeQuad(
                        vertices.clone(),
                        sourceQuad.textureId(),
                        sourceQuad.face(),
                        sourceQuad.shade(),
                        sourceQuad.tintIndex()
                    ));
                }
                if (quads.isEmpty()) emptyVariantCount++;
                quadCount += quads.size();
                variants.add(new Variant(
                    sourceVariant.properties() == null ? Map.of() : Map.copyOf(sourceVariant.properties()),
                    List.copyOf(quads)
                ));
            }
            if (variants.isEmpty()) continue;
            parsed.put(blockEntry.getKey(), new RuntimeBlock(
                source.vanillaMapping() == null ? "" : source.vanillaMapping(),
                source.loaderType() == null ? "" : source.loaderType(),
                List.copyOf(variants)
            ));
            variantCount += variants.size();
        }
        if (parsed.isEmpty()) return null;

        if (log != null) {
            log.accept("Runtime model registry loaded from registry.data: " + parsed.size() + " block type(s), "
                + variantCount + " state variant(s), " + quadCount + " baked quad(s), "
                + emptyVariantCount + " known empty state(s)");
        }
        return new RuntimeModelRegistry(parsed, capturedVersion, root.modsFingerprint());
    }

    private static RuntimeModelRegistry loadLegacyJson(
        File snapshot,
        String expectedMinecraftVersion,
        Consumer<String> log
    ) {
        JsonObject root;
        try (Reader reader = new FileReader(snapshot)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            warn(log, "Legacy runtime model registry could not be read: " + exception.getMessage());
            return null;
        }

        int schema = safeInt(root.get("schema"), -1);
        if (schema != LEGACY_JSON_SCHEMA) {
            warn(log, "Legacy runtime model registry schema " + schema
                + " is not compatible with schema " + LEGACY_JSON_SCHEMA);
            return null;
        }

        String capturedVersion = safeString(root.get("minecraftVersion"));
        String expected = expectedMinecraftVersion == null ? "" : expectedMinecraftVersion.trim();
        if (!expected.isEmpty() && !capturedVersion.equals(expected)) {
            warn(log, "Ignoring runtime models for Minecraft " + capturedVersion
                + " while exporting " + expected);
            return null;
        }

        String fingerprint = safeString(root.get("modsFingerprint"));
        JsonObject blockObject = root.has("blocks") && root.get("blocks").isJsonObject()
            ? root.getAsJsonObject("blocks")
            : null;
        if (blockObject == null || blockObject.size() == 0) return null;

        Map<String, RuntimeBlock> parsed = new LinkedHashMap<>();
        int variantCount = 0;
        int quadCount = 0;
        int emptyVariantCount = 0;
        for (var blockEntry : blockObject.entrySet()) {
            if (!blockEntry.getValue().isJsonObject()) continue;
            JsonObject block = blockEntry.getValue().getAsJsonObject();
            List<Variant> variants = parseVariants(block.get("variants"));
            if (variants.isEmpty()) continue;

            int localQuads = 0;
            for (Variant variant : variants) {
                localQuads += variant.quads().size();
                if (variant.quads().isEmpty()) emptyVariantCount++;
            }
            parsed.put(blockEntry.getKey(), new RuntimeBlock(
                safeString(block.get("vanillaMapping")),
                safeString(block.get("loaderType")),
                variants
            ));
            variantCount += variants.size();
            quadCount += localQuads;
        }
        if (parsed.isEmpty()) return null;

        if (log != null) {
            log.accept("Legacy runtime model registry loaded: " + parsed.size() + " block type(s), "
                + variantCount + " state variant(s), " + quadCount + " baked quad(s), "
                + emptyVariantCount + " known empty state(s)");
        }
        return new RuntimeModelRegistry(parsed, capturedVersion, fingerprint);
    }

    public boolean hasBlock(String blockId) {
        return blockId != null && blocks.containsKey(blockId);
    }

    public boolean shouldOverride(BlockData block) {
        return stateKind(block) != StateKind.UNKNOWN;
    }

    public StateKind stateKind(BlockData block) {
        if (block == null || block.blockId == null) return StateKind.UNKNOWN;
        RuntimeBlock runtime = blocks.get(block.blockId);
        if (runtime == null) return StateKind.UNKNOWN;
        Variant variant = bestVariant(block, runtime.variants());
        if (variant == null) return StateKind.UNKNOWN;
        return variant.quads().isEmpty() ? StateKind.EMPTY_BAKED_MODEL : StateKind.BAKED;
    }

    /** Returns null when the registry has no matching state; empty means a real empty model. */
    public List<Quad> build(BlockData block) {
        if (block == null) return null;
        RuntimeBlock runtime = blocks.get(block.blockId);
        if (runtime == null) return null;
        Variant variant = bestVariant(block, runtime.variants());
        if (variant == null) return null;

        List<Quad> result = new ArrayList<>(variant.quads().size());
        for (RuntimeQuad quad : variant.quads()) {
            Quad converted = convert(block, runtime, quad);
            if (converted != null) result.add(converted);
        }
        return result;
    }

    public String minecraftVersion() { return minecraftVersion; }
    public String modsFingerprint() { return modsFingerprint; }
    public int blockTypeCount() { return blocks.size(); }

    private static Variant bestVariant(BlockData block, List<Variant> variants) {
        Variant best = null;
        int specificity = -1;
        for (Variant variant : variants) {
            if (!matches(block, variant.properties())) continue;
            int score = variant.properties().size();
            if (score > specificity) {
                best = variant;
                specificity = score;
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

    private static Quad convert(BlockData block, RuntimeBlock runtime, RuntimeQuad quad) {
        float[] packed = quad.vertices();
        if (packed == null || packed.length < 32) return null;

        float[][] vertices = new float[4][3];
        float[] uv = new float[8];
        for (int index = 0; index < 4; index++) {
            int source = index * 8;
            float x = packed[source];
            float y = packed[source + 1];
            float z = packed[source + 2];
            float u = packed[source + 6];
            float v = packed[source + 7];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(u) || !Float.isFinite(v)) {
                return null;
            }
            vertices[index][0] = block.x + x;
            vertices[index][1] = block.y + y;
            vertices[index][2] = block.z + z;
            uv[index * 2] = u;
            uv[index * 2 + 1] = v;
        }

        String texture = quad.textureId();
        if (texture == null || texture.isBlank() || texture.equals("missing")) {
            texture = "minecraft:block/missingno";
        }
        String cullface = faceName(quad.face());

        return new Quad(
            vertices,
            uv,
            texture,
            new float[3],
            cullface,
            quad.tintIndex(),
            null
        );
    }

    private static List<Variant> parseVariants(JsonElement value) {
        if (value == null || !value.isJsonArray()) return List.of();
        List<Variant> variants = new ArrayList<>();
        for (JsonElement variantElement : value.getAsJsonArray()) {
            if (!variantElement.isJsonObject()) continue;
            JsonObject variant = variantElement.getAsJsonObject();
            Map<String, String> properties = parseProperties(variant.get("properties"));
            List<RuntimeQuad> quads = parseQuads(variant.get("quads"));
            variants.add(new Variant(properties, quads));
        }
        return variants;
    }

    private static Map<String, String> parseProperties(JsonElement value) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (value == null || !value.isJsonObject()) return properties;
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                properties.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return properties;
    }

    private static List<RuntimeQuad> parseQuads(JsonElement value) {
        if (value == null || !value.isJsonArray()) return List.of();
        List<RuntimeQuad> quads = new ArrayList<>();
        for (JsonElement quadElement : value.getAsJsonArray()) {
            if (!quadElement.isJsonObject()) continue;
            JsonObject quad = quadElement.getAsJsonObject();
            float[] vertices = parseFloatArray(quad.get("vertices"));
            if (vertices.length < 32) continue;
            quads.add(new RuntimeQuad(
                vertices,
                safeString(quad.get("textureId")),
                safeInt(quad.get("face"), -1),
                safeBoolean(quad.get("shade"), true),
                safeInt(quad.get("tintIndex"), -1)
            ));
        }
        return quads;
    }

    private static float[] parseFloatArray(JsonElement value) {
        if (value == null || !value.isJsonArray()) return new float[0];
        JsonArray array = value.getAsJsonArray();
        int count = Math.min(array.size(), 32);
        float[] result = new float[count];
        try {
            for (int i = 0; i < count; i++) result[i] = array.get(i).getAsFloat();
        } catch (Exception ignored) {
            return new float[0];
        }
        return result;
    }

    private static String faceName(int face) {
        return switch (face) {
            case 0 -> "down";
            case 1 -> "up";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> null;
        };
    }

    private static String safeString(JsonElement value) {
        try {
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int safeInt(JsonElement value, int fallback) {
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean safeBoolean(JsonElement value, boolean fallback) {
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void warn(Consumer<String> log, String message) {
        if (log != null) log.accept("[WARN] " + message);
    }
}
