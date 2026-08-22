package dev.kastrick.minesport.export;

import com.google.gson.*;
import dev.kastrick.minesport.region.BlockData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Post-processes generated .gltf JSON for format-level fixes that do not belong in mesh generation. */
public final class GltfPostProcessor {
    private static final int REPEAT = 10497;
    private static final int CLAMP_TO_EDGE = 33071;
    private static final int NEAREST = 9728;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private GltfPostProcessor() {}

    /**
     * Makes standalone Minecraft textures use ordinary repeat wrapping and
     * gives atlas textures their own clamp-to-edge sampler. A single shared
     * MIRRORED_REPEAT sampler is incorrect for both cases: repeated model UVs
     * must repeat rather than mirror, while atlas borders must never wrap into
     * neighboring tiles.
     */
    public static void fixSamplers(File gltfFile) throws IOException {
        if (gltfFile == null || !gltfFile.isFile()) return;

        JsonObject root = readRoot(gltfFile);
        JsonArray samplers = root.has("samplers") && root.get("samplers").isJsonArray()
                ? root.getAsJsonArray("samplers") : new JsonArray();
        if (samplers.isEmpty()) {
            JsonObject repeat = sampler(REPEAT);
            samplers.add(repeat);
            root.add("samplers", samplers);
        } else {
            // Existing exporter sampler is slot 0. Normalize it to ordinary repeat.
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
            if (name.endsWith("_atlas")) {
                texture.addProperty("sampler", clampSamplerIndex);
            } else {
                texture.addProperty("sampler", 0);
            }
        }

        writeRoot(gltfFile, root);
    }

    /**
     * Adds real glTF KHR_lights_punctual point lights for Minecraft emitters.
     * The same source list is also written to the Minesport sidecar for OBJ and
     * for Blender-specific editing semantics.
     */
    public static void addMinecraftLights(File gltfFile, List<BlockData> blocks) throws IOException {
        if (gltfFile == null || !gltfFile.isFile() || blocks == null || blocks.isEmpty()) return;
        List<MinecraftLightExporter.LightSource> sources = MinecraftLightExporter.resolve(blocks);
        if (sources.isEmpty()) return;

        JsonObject root = readRoot(gltfFile);
        JsonArray nodes = array(root, "nodes");
        JsonArray scenes = array(root, "scenes");
        if (scenes.isEmpty()) return;

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
            // glTF is Y-up, matching Minesport's Minecraft-space mesh export.
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

        JsonArray extensionsUsed = root.has("extensionsUsed") && root.get("extensionsUsed").isJsonArray()
            ? root.getAsJsonArray("extensionsUsed") : new JsonArray();
        boolean hasPunctual = false;
        for (JsonElement element : extensionsUsed) {
            if (element.isJsonPrimitive() && "KHR_lights_punctual".equals(element.getAsString())) {
                hasPunctual = true;
                break;
            }
        }
        if (!hasPunctual) extensionsUsed.add("KHR_lights_punctual");
        root.add("extensionsUsed", extensionsUsed);

        JsonObject extensions = root.has("extensions") && root.get("extensions").isJsonObject()
            ? root.getAsJsonObject("extensions") : new JsonObject();
        JsonObject punctual = new JsonObject();
        punctual.add("lights", gltfLights);
        extensions.add("KHR_lights_punctual", punctual);
        root.add("extensions", extensions);
        root.add("nodes", nodes);

        writeRoot(gltfFile, root);
    }

    private static JsonObject readRoot(File file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void writeRoot(File file, JsonObject root) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
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
