package dev.kastrick.minesport.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.util.List;
import java.util.Map;

/** Writes/merges the lossless logical FLATTER block grid beside OBJ/glTF. */
public final class FlatterMetadataExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int FLATTER_SCHEMA = 2;
    public static final String FLATTER_VERSION = "0.1.0";
    public static final String ACTIVE_EXPORT_TAG = "minesport_v1.5_active_export";
    public static final String ACTIVE_EXPORT_VERSION = "0.1.5";

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
        tagActiveExport(exportFile, format);

        File sidecar = sidecarFor(exportFile);
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("generator", "Minesport");
        root.addProperty("exportName", safeName(exportFile.getName().replaceFirst("(?i)\\.(gltf|obj)$", "")));
        root.addProperty("format", format);
        root.addProperty("objectMode", mode.name());
        root.addProperty("linearUnit", "metre");
        root.addProperty("metresPerBlock", 1.0);
        root.addProperty(ACTIVE_EXPORT_TAG, true);
        root.addProperty("activeExportVersion", ACTIVE_EXPORT_VERSION);
        root.addProperty("flatterSchema", FLATTER_SCHEMA);
        root.addProperty("flatterVersion", FLATTER_VERSION);
        root.addProperty("flatterBlockCount", result.blockCount());
        root.add("flatterObjects", flatterObjects(result, center, format, mode));

        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("flatter", true);
        capabilities.addProperty("flatter3D", true);
        capabilities.addProperty("flatterMaterialization", true);
        capabilities.addProperty("flatterLayeredFaces", true);
        capabilities.addProperty("flatterLogicalOverlay", true);
        capabilities.addProperty("flatterLogical3DOverlay", true);
        root.add("capabilities", capabilities);

        writeJson(sidecar, root);
        return sidecar;
    }

    public static void copyExistingFlatter(File sidecar, JsonObject root) {
        if (sidecar == null || root == null || !sidecar.isFile()) return;
        try (Reader reader = new BufferedReader(new FileReader(sidecar))) {
            JsonObject existing = JsonParser.parseReader(reader).getAsJsonObject();
            for (String key : new String[]{
                ACTIVE_EXPORT_TAG,
                "activeExportVersion",
                "flatterSchema",
                "flatterVersion",
                "flatterBlockCount",
                "flatterObjects",
                "capabilities"
            }) {
                if (existing.has(key)) root.add(key, existing.get(key).deepCopy());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Mark the raw interchange file itself so Translator 0.1.5+ can select the
     * richer Minesport import contract before/alongside the sidecar.
     */
    private static void tagActiveExport(File exportFile, String format) {
        if (exportFile == null || !exportFile.isFile()) return;
        if (!"gltf".equalsIgnoreCase(format)) return; // OBJ writes the comment inline.

        try {
            JsonObject root;
            try (Reader reader = new BufferedReader(new FileReader(exportFile))) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }

            JsonObject asset = root.has("asset") && root.get("asset").isJsonObject()
                ? root.getAsJsonObject("asset")
                : new JsonObject();
            JsonObject extras = asset.has("extras") && asset.get("extras").isJsonObject()
                ? asset.getAsJsonObject("extras")
                : new JsonObject();
            extras.addProperty(ACTIVE_EXPORT_TAG, true);
            extras.addProperty("minesportActiveExportVersion", ACTIVE_EXPORT_VERSION);
            extras.addProperty("minesportFlatterVersion", FLATTER_VERSION);
            asset.add("extras", extras);
            root.add("asset", asset);
            writeJson(exportFile, root);
        } catch (Exception ignored) {
            // The sidecar remains authoritative. Never make a successful export
            // fail just because the convenience raw-file marker could not be injected.
        }
    }

    private static JsonArray flatterObjects(
        FlatterOptimizer.Result result,
        float[] center,
        String format,
        ObjExporter.ExportMode mode
    ) {
        JsonArray objects = new JsonArray();
        for (FlatterOptimizer.FlatterObject object : result.objects()) {
            JsonObject json = new JsonObject();
            int[] size = object.size();
            int width = size.length > 0 ? size[0] : 0;
            int height = size.length > 1 ? size[1] : 0;
            int depth = size.length > 2 ? size[2] : 0;
            long volume = (long) width * height * depth;

            json.addProperty("id", object.id());
            json.addProperty("type", "flatter");
            json.addProperty("flatterVersion", FLATTER_VERSION);
            json.addProperty("meshObject", object.id());
            json.addProperty("chunkSize", FlatterOptimizer.CELL_SIZE);
            json.addProperty("blockCount", object.blockCount());
            json.addProperty("encoding", "palette_rle_v1");
            json.addProperty("format", format);
            json.addProperty("objectMode", mode.name());
            json.add("origin", intArray(object.origin()));
            json.add("size", intArray(size));
            json.add("center", floatArray(center));

            JsonObject dimensions = new JsonObject();
            dimensions.addProperty("width", width);
            dimensions.addProperty("height", height);
            dimensions.addProperty("depth", depth);
            dimensions.addProperty("volume", volume);
            dimensions.addProperty("order", "XYZ");
            json.add("dimensions", dimensions);

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
                for (Map.Entry<String,List<FlatterOptimizer.FaceInfo>> face : entry.faces().entrySet()) {
                    JsonArray layers = new JsonArray();
                    for (FlatterOptimizer.FaceInfo info : face.getValue()) {
                        layers.add(faceJson(info));
                    }
                    faces.add(face.getKey(), layers);
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

    private static JsonObject faceJson(FlatterOptimizer.FaceInfo info) {
        JsonObject face = new JsonObject();
        face.addProperty("material", info.material());
        face.addProperty("texture", info.texturePath());
        face.addProperty("tint", info.tintRgb());
        face.add("uv", floatArray(info.uv()));
        face.add("vertices", floatArray(info.vertices()));
        return face;
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
