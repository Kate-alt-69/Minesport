package dev.kastrick.minesport.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.region.BlockData;

import java.io.*;
import java.util.List;

/**
 * Writes DCC-neutral Minesport translation metadata next to an export.
 *
 * The sidecar deliberately stores capabilities and animation descriptors, not
 * a duplicate record for every ordinary block in the world. Dynamic bridge
 * providers can append only the objects that actually need translation
 * (animated textures, rigid parts, state transitions, custom render data).
 */
public final class BlenderMetadataExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BlenderMetadataExporter() {}

    public static File write(
        File exportFile,
        List<BlockData> blocks,
        ObjExporter.ExportMode mode,
        String format,
        String animationMode
    ) throws IOException {
        String baseName = exportFile.getName().replaceFirst("(?i)\\.(gltf|obj)$", "");
        File sidecar = new File(exportFile.getParentFile(), baseName + ".minesport.json");

        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("generator", "Minesport");
        root.addProperty("exportName", safeName(baseName));
        root.addProperty("format", format);
        root.addProperty("objectMode", mode.name());
        root.addProperty("animationMode", animationMode == null ? "animate_export" : animationMode);

        int solidBlocks = 0;
        for (BlockData block : blocks) {
            if (!block.isAir()) solidBlocks++;
        }
        root.addProperty("blockCount", solidBlocks);

        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("dynamicDescriptors", true);
        capabilities.addProperty("bridgeDescriptorSchema", 1);
        capabilities.addProperty("stateAnimations", !"animate_static".equals(animationMode));
        capabilities.addProperty("continuousTextureAnimations", true);
        root.add("capabilities", capabilities);

        // DCC translators consume generic descriptors from here. This starts
        // empty when no runtime/bridge descriptor provider was available; it
        // never falls back to a hardcoded block-ID animation registry.
        root.add("animations", new JsonArray());

        try (Writer writer = new BufferedWriter(new FileWriter(sidecar))) {
            GSON.toJson(root, writer);
        }
        return sidecar;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "Minesport_Export";
        return value.replace(':', '_').replace('/', '_').replace('\\', '_').trim();
    }
}
