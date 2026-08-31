package dev.kastrick.minesport.export;

import com.google.gson.*;
import dev.kastrick.minesport.region.BlockData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/** Post-processes generated .gltf JSON for format-level fixes that do not belong in mesh generation. */
public final class GltfPostProcessor {
    private static final int REPEAT = 10497;
    private static final int CLAMP_TO_EDGE = 33071;
    private static final int NEAREST = 9728;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String[] EMISSIVE_TOKENS = {
        "torch", "lantern", "glowstone", "sea_lantern", "shroomlight", "froglight",
        "end_rod", "jack_o_lantern", "fire", "lava", "glow_lichen", "amethyst_cluster",
        "amethyst_bud", "crying_obsidian", "magma", "candle", "cave_vines_lit",
        "furnace_front_on", "blast_furnace_front_on", "smoker_front_on",
        "redstone_torch", "redstone_lamp_on"
    };

    private GltfPostProcessor() {}

    /** Normalize Minecraft texture samplers and preserve transparent material semantics. */
    public static void fixSamplers(File gltfFile) throws IOException {
        if (gltfFile == null || !gltfFile.isFile()) return;

        JsonObject root = readRoot(gltfFile);
        JsonArray samplers = root.has("samplers") && root.get("samplers").isJsonArray()
                ? root.getAsJsonArray("samplers") : new JsonArray();
        if (samplers.isEmpty()) {
            samplers.add(sampler(REPEAT));
            root.add("samplers", samplers);
        } else {
            JsonObject repeat = samplers.get(0).getAsJsonObject();
            repeat.addProperty("magFilter", NEAREST);
            repeat.addProperty("minFilter", NEAREST);
            repeat.addProperty("wrapS", REPEAT);
            repeat.addProperty("wrapT", REPEAT);
        }

        int clampSamplerIndex = findOrCreateClampSampler(samplers);
        JsonArray images = root.has("images") && root.get("images").isJsonArray()
                ? root.getAsJsonArray("images") : new JsonArray();
        JsonArray textures = root.has("textures") && root.get("textures").isJsonArray()
                ? root.getAsJsonArray("textures") : new JsonArray();

        for (int texIndex = 0; texIndex < textures.size(); texIndex++) {
            JsonObject texture = textures.get(texIndex).getAsJsonObject();
            if (!texture.has("source")) continue;
            int source = texture.get("source").getAsInt();
            if (source < 0 || source >= images.size()) continue;
            JsonObject image = images.get(source).getAsJsonObject();
            String name = image.has("name") ? image.get("name").getAsString() : "";
            texture.addProperty("sampler", name.endsWith("_atlas") ? clampSamplerIndex : 0);
        }

        markTransparentMaterials(root);
        writeRoot(gltfFile, root);
    }

    /**
     * Adds real KHR_lights_punctual lights and also performs format-level
     * material semantic repair. Material repair runs even when the scene has no
     * light emitters, because every GltfExporter invocation calls this method.
     */
    public static void addMinecraftLights(File gltfFile, List<BlockData> blocks) throws IOException {
        if (gltfFile == null || !gltfFile.isFile()) return;

        JsonObject root = readRoot(gltfFile);
        markTransparentMaterials(root);
        markMinecraftEmissiveMaterials(root);

        List<MinecraftLightExporter.LightSource> sources = blocks == null
            ? List.of()
            : MinecraftLightExporter.resolve(blocks);
        if (sources.isEmpty()) {
            writeRoot(gltfFile, root);
            return;
        }

        JsonArray nodes = array(root, "nodes");
        JsonArray scenes = array(root, "scenes");
        if (scenes.isEmpty()) {
            writeRoot(gltfFile, root);
            return;
        }

        int sceneIndex = root.has("scene") ? root.get("scene").getAsInt() : 0;
        if (sceneIndex < 0 || sceneIndex >= scenes.size()) sceneIndex = 0;
        JsonObject scene = scenes.get(sceneIndex).getAsJsonObject();
        JsonArray sceneNodes = scene.has("nodes") && scene.get("nodes").isJsonArray()
            ? scene.getAsJsonArray("nodes") : new JsonArray();
        if (!scene.has("nodes")) scene.add("nodes", sceneNodes);

        int rootNodeIndex = sceneNodes.isEmpty() ? -1 : sceneNodes.get(0).getAsInt();
        JsonObject rootNode = rootNodeIndex >= 0 && rootNodeIndex < nodes.size()
            ? nodes.get(rootNodeIndex).getAsJsonObject() : null;
        JsonArray children = rootNode != null && rootNode.has("children") && rootNode.get("children").isJsonArray()
            ? rootNode.getAsJsonArray("children") : new JsonArray();
        if (rootNode != null && !rootNode.has("children")) rootNode.add("children", children);

        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        JsonArray gltfLights = new JsonArray();
        for (MinecraftLightExporter.LightSource source : sources) {
            int lightIndex = gltfLights.size();
            JsonObject light = new JsonObject();
            light.addProperty("name", source.name());
            light.addProperty("type", "point");
            light.addProperty("intensity", source.intensity());
            light.addProperty("range", source.rangeBlocks());
            JsonArray color = new JsonArray();
            color.add(source.red());
            color.add(source.green());
            color.add(source.blue());
            light.add("color", color);
            gltfLights.add(light);

            JsonObject node = new JsonObject();
            node.addProperty("name", source.name());
            JsonArray translation = new JsonArray();
            translation.add(source.x() - center[0]);
            translation.add(source.y() - center[1]);
            translation.add(source.z() - center[2]);
            node.add("translation", translation);

            JsonObject nodeExtensions = new JsonObject();
            JsonObject punctualRef = new JsonObject();
            punctualRef.addProperty("light", lightIndex);
            nodeExtensions.add("KHR_lights_punctual", punctualRef);
            node.add("extensions", nodeExtensions);

            JsonObject extras = new JsonObject();
            extras.addProperty("minesportType", "MINECRAFT_LIGHT");
            extras.addProperty("sourceBlock", source.sourceBlock());
            extras.addProperty("minecraftLevel", source.minecraftLevel());
            extras.addProperty("rangeBlocks", source.rangeBlocks());
            extras.addProperty("invisibleSource", source.invisibleSource());
            node.add("extras", extras);

            nodes.add(node);
            int nodeIndex = nodes.size() - 1;
            if (rootNode != null) children.add(nodeIndex);
            else sceneNodes.add(nodeIndex);
        }

        ensureExtensionUsed(root, "KHR_lights_punctual");
        JsonObject extensions = root.has("extensions") && root.get("extensions").isJsonObject()
            ? root.getAsJsonObject("extensions") : new JsonObject();
        JsonObject punctual = new JsonObject();
        punctual.add("lights", gltfLights);
        extensions.add("KHR_lights_punctual", punctual);
        root.add("extensions", extensions);
        root.add("nodes", nodes);

        writeRoot(gltfFile, root);
    }

    /** Upgrade water/glass from generic alpha-mask materials to real translucent materials. */
    private static void markTransparentMaterials(JsonObject root) {
        JsonArray materials = root.has("materials") && root.get("materials").isJsonArray()
            ? root.getAsJsonArray("materials") : null;
        if (materials == null) return;

        boolean usedTransmission = false;
        for (JsonElement element : materials) {
            if (!element.isJsonObject()) continue;
            JsonObject material = element.getAsJsonObject();
            String name = material.has("name") ? material.get("name").getAsString() : "";
            MaterialSemantics.Kind kind = MaterialSemantics.classify(name);
            if (kind == MaterialSemantics.Kind.DEFAULT) continue;

            material.addProperty("alphaMode", "BLEND");
            material.remove("alphaCutoff");
            material.addProperty("doubleSided", true);

            JsonObject pbr = material.has("pbrMetallicRoughness") && material.get("pbrMetallicRoughness").isJsonObject()
                ? material.getAsJsonObject("pbrMetallicRoughness") : new JsonObject();
            pbr.addProperty("metallicFactor", 0.0);
            pbr.addProperty("roughnessFactor", MaterialSemantics.roughness(name));

            JsonArray baseFactor = new JsonArray();
            baseFactor.add(1.0);
            baseFactor.add(1.0);
            baseFactor.add(1.0);
            baseFactor.add(kind == MaterialSemantics.Kind.WATER ? 0.72 : 0.88);
            pbr.add("baseColorFactor", baseFactor);
            material.add("pbrMetallicRoughness", pbr);

            JsonObject materialExtensions = material.has("extensions") && material.get("extensions").isJsonObject()
                ? material.getAsJsonObject("extensions") : new JsonObject();
            JsonObject transmission = new JsonObject();
            transmission.addProperty("transmissionFactor", kind == MaterialSemantics.Kind.GLASS ? 0.92 : 0.35);
            materialExtensions.add("KHR_materials_transmission", transmission);
            material.add("extensions", materialExtensions);
            usedTransmission = true;

            JsonObject extras = material.has("extras") && material.get("extras").isJsonObject()
                ? material.getAsJsonObject("extras") : new JsonObject();
            extras.addProperty("minesportMaterialClass", kind.name());
            extras.addProperty("minesportTranslucent", true);
            material.add("extras", extras);
        }

        if (usedTransmission) ensureExtensionUsed(root, "KHR_materials_transmission");
    }

    /** Makes the source texture itself glow as well as creating a scene light. */
    private static void markMinecraftEmissiveMaterials(JsonObject root) {
        JsonArray materials = root.has("materials") && root.get("materials").isJsonArray()
            ? root.getAsJsonArray("materials") : null;
        if (materials == null) return;

        for (JsonElement element : materials) {
            if (!element.isJsonObject()) continue;
            JsonObject material = element.getAsJsonObject();
            String name = material.has("name") ? material.get("name").getAsString() : "";
            if (!looksEmissive(name)) continue;

            JsonArray factor = new JsonArray();
            factor.add(1.0);
            factor.add(1.0);
            factor.add(1.0);
            material.add("emissiveFactor", factor);

            JsonObject pbr = material.has("pbrMetallicRoughness") && material.get("pbrMetallicRoughness").isJsonObject()
                ? material.getAsJsonObject("pbrMetallicRoughness") : null;
            if (pbr != null && pbr.has("baseColorTexture") && pbr.get("baseColorTexture").isJsonObject()) {
                JsonObject baseTexture = pbr.getAsJsonObject("baseColorTexture");
                if (baseTexture.has("index")) {
                    JsonObject emissiveTexture = new JsonObject();
                    emissiveTexture.addProperty("index", baseTexture.get("index").getAsInt());
                    material.add("emissiveTexture", emissiveTexture);
                }
            }

            JsonObject extras = material.has("extras") && material.get("extras").isJsonObject()
                ? material.getAsJsonObject("extras") : new JsonObject();
            extras.addProperty("minesportEmissive", true);
            material.add("extras", extras);
        }
    }

    private static boolean looksEmissive(String rawName) {
        String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
        if (name.contains("redstone_lamp") && !name.contains("redstone_lamp_on")) return false;
        if (name.contains("furnace_front") && !name.contains("furnace_front_on")) return false;
        if (name.contains("blast_furnace_front") && !name.contains("blast_furnace_front_on")) return false;
        if (name.contains("smoker_front") && !name.contains("smoker_front_on")) return false;
        for (String token : EMISSIVE_TOKENS) if (name.contains(token)) return true;
        return false;
    }

    private static void ensureExtensionUsed(JsonObject root, String name) {
        JsonArray extensionsUsed = root.has("extensionsUsed") && root.get("extensionsUsed").isJsonArray()
            ? root.getAsJsonArray("extensionsUsed") : new JsonArray();
        for (JsonElement element : extensionsUsed) {
            if (element.isJsonPrimitive() && name.equals(element.getAsString())) {
                root.add("extensionsUsed", extensionsUsed);
                return;
            }
        }
        extensionsUsed.add(name);
        root.add("extensionsUsed", extensionsUsed);
    }

    private static JsonObject readRoot(File file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void writeRoot(File file, JsonObject root) throws IOException {
        AtomicFileWriter.write(file, writer -> GSON.toJson(root, writer));
    }

    private static JsonArray array(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonArray()) return root.getAsJsonArray(key);
        JsonArray result = new JsonArray();
        root.add(key, result);
        return result;
    }

    private static int findOrCreateClampSampler(JsonArray samplers) {
        for (int i = 0; i < samplers.size(); i++) {
            JsonObject s = samplers.get(i).getAsJsonObject();
            if (intProp(s, "wrapS", -1) == CLAMP_TO_EDGE && intProp(s, "wrapT", -1) == CLAMP_TO_EDGE) {
                return i;
            }
        }
        samplers.add(sampler(CLAMP_TO_EDGE));
        return samplers.size() - 1;
    }

    private static JsonObject sampler(int wrap) {
        JsonObject s = new JsonObject();
        s.addProperty("magFilter", NEAREST);
        s.addProperty("minFilter", NEAREST);
        s.addProperty("wrapS", wrap);
        s.addProperty("wrapT", wrap);
        return s;
    }

    private static int intProp(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }
}
