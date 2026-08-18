package dev.kastrick.minesport.model;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses Minecraft blockstate and block model JSON files using Gson.
 */
public class ModelParser {

    private static final Gson GSON = new Gson();

    // ── BlockState parser ─────────────────────────────────────────────────────

    public static BlockState parseBlockState(InputStream in) throws IOException {
        JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        BlockState bs = new BlockState();

        if (root.has("variants")) {
            bs.format = BlockState.Format.VARIANTS;
            JsonObject variants = root.getAsJsonObject("variants");

            for (var entry : variants.entrySet()) {
                String key = entry.getKey();
                List<BlockState.ModelApplication> apps = new ArrayList<>();

                JsonElement val = entry.getValue();
                if (val.isJsonArray()) {
                    for (JsonElement el : val.getAsJsonArray()) {
                        apps.add(parseModelApplication(el.getAsJsonObject()));
                    }
                } else {
                    apps.add(parseModelApplication(val.getAsJsonObject()));
                }
                bs.variants.put(key, apps);
            }

        } else if (root.has("multipart")) {
            bs.format = BlockState.Format.MULTIPART;

            for (JsonElement el : root.getAsJsonArray("multipart")) {
                JsonObject part = el.getAsJsonObject();
                BlockState.MultipartPart mp = new BlockState.MultipartPart();

                if (part.has("when")) {
                    JsonObject when = part.getAsJsonObject("when");
                    if (when.has("OR")) {
                        mp.whenOr = new ArrayList<>();
                        for (JsonElement orEl : when.getAsJsonArray("OR")) {
                            mp.whenOr.add(parseStringMap(orEl.getAsJsonObject()));
                        }
                    } else {
                        mp.when = parseStringMap(when);
                    }
                }

                JsonElement apply = part.get("apply");
                if (apply.isJsonArray()) {
                    for (JsonElement a : apply.getAsJsonArray()) {
                        mp.apply.add(parseModelApplication(a.getAsJsonObject()));
                    }
                } else {
                    mp.apply.add(parseModelApplication(apply.getAsJsonObject()));
                }

                bs.multiparts.add(mp);
            }
        }

        return bs;
    }

    // ── BlockModel parser ─────────────────────────────────────────────────────

    public static BlockModel parseBlockModel(InputStream in) throws IOException {
        JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        BlockModel model = new BlockModel();

        if (root.has("parent")) {
            model.parentId = root.get("parent").getAsString();
        }

        if (root.has("ambientocclusion")) {
            model.ambientOcclusion = root.get("ambientocclusion").getAsBoolean();
        }

        if (root.has("textures")) {
            for (var entry : root.getAsJsonObject("textures").entrySet()) {
                model.textures.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        if (root.has("elements")) {
            for (JsonElement el : root.getAsJsonArray("elements")) {
                model.elements.add(parseElement(el.getAsJsonObject()));
            }
        }

        return model;
    }

    // ── Internal parsers ──────────────────────────────────────────────────────

    private static BlockState.ModelApplication parseModelApplication(JsonObject obj) {
        var app = new BlockState.ModelApplication();
        app.modelPath = obj.get("model").getAsString();
        if (obj.has("x"))      app.x      = obj.get("x").getAsInt();
        if (obj.has("y"))      app.y      = obj.get("y").getAsInt();
        if (obj.has("uvlock")) app.uvlock = obj.get("uvlock").getAsBoolean();
        if (obj.has("weight")) app.weight = obj.get("weight").getAsFloat();
        return app;
    }

    private static BlockModel.Element parseElement(JsonObject obj) {
        var el = new BlockModel.Element();

        el.from = parseFloatArray(obj.getAsJsonArray("from"));
        el.to   = parseFloatArray(obj.getAsJsonArray("to"));

        if (obj.has("shade")) el.shade = obj.get("shade").getAsBoolean();

        if (obj.has("rotation")) {
            JsonObject rot = obj.getAsJsonObject("rotation");
            el.rotation = new BlockModel.Rotation();
            el.rotation.origin  = parseFloatArray(rot.getAsJsonArray("origin"));
            el.rotation.axis    = rot.get("axis").getAsString();
            el.rotation.angle   = rot.get("angle").getAsFloat();
            if (rot.has("rescale")) el.rotation.rescale = rot.get("rescale").getAsBoolean();
        }

        if (obj.has("faces")) {
            for (var entry : obj.getAsJsonObject("faces").entrySet()) {
                el.faces.put(entry.getKey(), parseFace(entry.getValue().getAsJsonObject()));
            }
        }

        return el;
    }

    private static BlockModel.Face parseFace(JsonObject obj) {
        var face = new BlockModel.Face();
        face.texture = obj.get("texture").getAsString();
        if (obj.has("uv"))        face.uv        = parseFloatArray(obj.getAsJsonArray("uv"));
        if (obj.has("cullface"))  face.cullface  = obj.get("cullface").getAsString();
        if (obj.has("rotation"))  face.rotation  = obj.get("rotation").getAsInt();
        if (obj.has("tintindex")) face.tintindex = obj.get("tintindex").getAsInt();
        return face;
    }

    private static float[] parseFloatArray(JsonArray arr) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsFloat();
        return out;
    }

    private static Map<String, String> parseStringMap(JsonObject obj) {
        var map = new LinkedHashMap<String, String>();
        for (var entry : obj.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }
}
