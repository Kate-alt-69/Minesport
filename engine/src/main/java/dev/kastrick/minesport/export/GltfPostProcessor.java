package dev.kastrick.minesport.export;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Post-processes generated .gltf JSON for format-level fixes that do not belong in mesh generation. */
public final class GltfPostProcessor {
    private static final int REPEAT = 10497;
    private static final int CLAMP_TO_EDGE = 33071;
    private static final int NEAREST = 9728;

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

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(gltfFile.toPath(), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

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

        try (Writer writer = Files.newBufferedWriter(gltfFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(root, writer);
        }
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
