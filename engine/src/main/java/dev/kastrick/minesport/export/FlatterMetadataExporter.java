package dev.kastrick.minesport.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.util.Map;

/** Writes/merges the lossless logical FLATTER block grid beside OBJ/glTF. */
public final class FlatterMetadataExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private FlatterMetadataExporter() {}

    public static File sidecarFor(File exportFile) {
        String baseName = exportFile.getName().replaceFirst("(?i)\\.(gltf|obj)$", "");
        return new File(exportFile.getParentFile(), baseName + ".minesport.json");
    }

    public static void resetForExport(File exportFile) {
        File sidecar = sidecarFor(exportFile);
        if (sidecar.isFile()) sidecar.delete();
    }

    public static File write(
        File exportFile,
        FlatterOptimizer.Result result,
        ObjExporter.ExportMode mode,
        String format,
        float[] center
    ) throws IOException {
        if (result == null || result.isEmpty()) return sidecarFor(exportFile);
        File sidecar = sidecarFor(exportFile);
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("generator", "Minesport");
        root.addProperty("exportName", safeName(exportFile.getName().replaceFirst("(?i)\\.(gltf|obj)$", "")));
        root.addProperty("format", format);
        root.addProperty("objectMode", mode.name());
        root.addProperty("linearUnit", "metre");
        root.addProperty("metresPerBlock", 1.0);
        root.addProperty("flatterSchema", 1);
        root.addProperty("flatterBlockCount", result.blockCount());
        root.add("flatterObjects", flatterObjects(result, center, format));

        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("flatter", true);
        capabilities.addProperty("flatterMaterialization", true);
        root.add("capabilities", capabilities);

        writeJson(sidecar, root);
        return sidecar;
    }

    public static void copyExistingFlatter(File sidecar, JsonObject root) {
        if (sidecar == null || root == null || !sidecar.isFile()) return;
        try (Reader reader = new BufferedReader(new FileReader(sidecar))) {
            JsonObject existing = JsonParser.parseReader(reader).getAsJsonObject();
            for (String key : new String[]{"flatterSchema", "flatterBlockCount", "flatterObjects"}) {
                if (existing.has(key)) root.add(key, existing.get(key).deepCopy());
            }
        } catch (Exception ignored) {
        }
    }

    private static JsonArray flatterObjects(FlatterOptimizer.Result result, float[] center, String format) {
        JsonArray objects = new JsonArray();
        for (FlatterOptimizer.FlatterObject object : result.objects()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", object.id());
            json.addProperty("type", "flatter");
            json.addProperty("meshObject", object.id());
            json.addProperty("chunkSize", FlatterOptimizer.CELL_SIZE);
            json.addProperty("blockCount", object.blockCount());
            json.addProperty("encoding", "palette_rle_v1");
            json.addProperty("format", format);
            json.add("origin", intArray(object.origin()));
            json.add("size", intArray(object.size()));
            json.add("center", floatArray(center));

            JsonArray palette = new JsonArray();
            for (FlatterOptimizer.PaletteEntry entry : object.palette()) {
                JsonObject p = new JsonObject();
                p.addProperty("id", entry.blockId());
                JsonObject properties = new JsonObject();
                for (var property : entry.properties().entrySet()) {
                    properties.addProperty(property.getKey(), property.getValue());
                }
                p.add("properties", properties);
                JsonObject faces = new JsonObject();
                for (Map.Entry<String,FlatterOptimizer.FaceInfo> face : entry.faces().entrySet()) {
                    FlatterOptimizer.FaceInfo info = face.getValue();
                    JsonObject f = new JsonObject();
                    f.addProperty("material", info.material());
                    f.addProperty("texture", info.texturePath());
                    f.addProperty("tint", info.tintRgb());
                    f.add("uv", floatArray(info.uv()));
                    f.add("vertices", floatArray(info.vertices()));
                    faces.add(face.getKey(), f);
                }
                p.add("faces", faces);
                palette.add(p);
            }
            json.add("palette", palette);

            JsonArray runs = new JsonArray();
            for (FlatterOptimizer.Run run : object.runs()) {
                JsonArray compact = new JsonArray();
                compact.add(run.start());
                compact.add(run.length());
                compact.add(run.palette());
                runs.add(compact);
            }
            json.add("runs", runs);
            objects.add(json);
        }
        return objects;
    }

    private static JsonArray intArray(int[] values) {
        JsonArray result = new JsonArray();
        if (values != null) for (int value : values) result.add(value);
        return result;
    }

    private static JsonArray floatArray(float[] values) {
        JsonArray result = new JsonArray();
        if (values != null) for (float value : values) result.add(value);
        return result;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "Minesport_Export";
        return value.replace(':', '_').replace('/', '_').replace('\\', '_').trim();
    }

    private static void writeJson(File file, JsonObject root) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try (Writer writer = new BufferedWriter(new FileWriter(file))) {
            GSON.toJson(root, writer);
        }
    }
}
