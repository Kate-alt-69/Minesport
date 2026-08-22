package dev.kastrick.minesport.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.io.*;
import java.util.List;

/** Writes DCC-neutral Minesport translation metadata next to an export. */
public final class BlenderMetadataExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BlenderMetadataExporter() {}

    /**
     * Normal IPC path. Reuse the ResolverChain active on this export thread and
     * create a runtime-aware builder only for the state-deduplicated animation
     * material scan. This avoids plumbing another parameter through the entire
     * IPC protocol while still using the exact resource-pack/mod/runtime stack.
     */
    public static File write(
        File exportFile,
        List<BlockData> blocks,
        ObjExporter.ExportMode mode,
        String format,
        String animationMode
    ) throws IOException {
        ResolverChain current = ResolverChain.current();
        GeometryBuilder geometry = current == null
            ? null
            : new dev.kastrick.minesport.GeometryBuilder(current);
        return write(exportFile, blocks, mode, format, animationMode, geometry);
    }

    public static File write(
        File exportFile,
        List<BlockData> blocks,
        ObjExporter.ExportMode mode,
        String format,
        String animationMode,
        GeometryBuilder geometry
    ) throws IOException {
        String baseName = exportFile.getName().replaceFirst("(?i)\\.(gltf|obj)$", "");
        File sidecar = new File(exportFile.getParentFile(), baseName + ".minesport.json");

        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("generator", "Minesport");
        root.addProperty("exportName", safeName(baseName));
        root.addProperty("format", format);
        root.addProperty("objectMode", mode.name());
        root.addProperty("linearUnit", "metre");
        root.addProperty("metresPerBlock", 1.0);
        String resolvedAnimationMode = animationMode == null ? "animate_export" : animationMode;
        root.addProperty("animationMode", resolvedAnimationMode);

        int solidBlocks = 0;
        for (BlockData block : blocks) if (!block.isAir()) solidBlocks++;
        root.addProperty("blockCount", solidBlocks);

        FlatterMetadataExporter.copyExistingFlatter(sidecar, root);

        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        JsonArray logicalLightBlocks = MinecraftLightExporter.sidecarLightBlocks(blocks, center);
        JsonArray lights = MinecraftLightExporter.sidecarLights(blocks, center);
        root.add("LIGHT_BLOCK", logicalLightBlocks);
        root.add("lights", lights);

        JsonObject lightModel = new JsonObject();
        lightModel.addProperty("source", "LIGHT_BLOCK");
        lightModel.addProperty("logicalLevels", 15);
        lightModel.addProperty("decay", "one_level_per_block");
        lightModel.addProperty("renderFalloff", "smooth");
        lightModel.addProperty("defaultHelpersVisible", false);
        root.add("lightModel", lightModel);

        JsonArray animations = new JsonArray();
        if (!"animate_static".equals(resolvedAnimationMode)) {
            for (BlockData block : blocks) {
                if (isVanillaChest(block)) animations.add(chestLidDescriptor(block, center));
            }
            if (geometry != null) {
                JsonArray textureAnimations = TextureAnimationExporter.describe(blocks, geometry, true);
                for (JsonElement descriptor : textureAnimations) animations.add(descriptor);
            }
        }
        root.add("animations", animations);

        int textureAnimationCount = 0;
        for (JsonElement descriptor : animations) {
            if (!descriptor.isJsonObject()) continue;
            JsonObject object = descriptor.getAsJsonObject();
            if (object.has("kind") && "texture_frames".equals(object.get("kind").getAsString())) {
                textureAnimationCount++;
            }
        }

        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("dynamicDescriptors", true);
        capabilities.addProperty("bridgeDescriptorSchema", 1);
        capabilities.addProperty("stateAnimations", !"animate_static".equals(resolvedAnimationMode));
        capabilities.addProperty("continuousTextureAnimations", textureAnimationCount > 0);
        capabilities.addProperty("textureTimelineMarkers", textureAnimationCount > 0);
        capabilities.addProperty("flatter", root.has("flatterObjects"));
        capabilities.addProperty("flatterMaterialization", root.has("flatterObjects"));
        capabilities.addProperty("minecraftLights", !logicalLightBlocks.isEmpty());
        capabilities.addProperty("minecraftLightLevels", true);
        capabilities.addProperty("logicalLightBlocks", !logicalLightBlocks.isEmpty());
        capabilities.addProperty("flatterLightPlacement", root.has("flatterObjects"));
        root.add("capabilities", capabilities);

        try (Writer writer = new BufferedWriter(new FileWriter(sidecar))) {
            GSON.toJson(root, writer);
        }
        return sidecar;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "Minesport_Export";
        return value.replace(':', '_').replace('/', '_').replace('\\', '_').trim();
    }

    private static boolean isVanillaChest(BlockData block) {
        return block.blockId.equals("minecraft:chest") || block.blockId.equals("minecraft:trapped_chest");
    }

    private static JsonObject chestLidDescriptor(BlockData block, float[] center) {
        String facing = block.prop("facing");
        float localX = .5f;
        float localZ = .9375f;
        String axis = "X";
        switch (facing) {
            case "south" -> localZ = .0625f;
            case "east" -> { localX = .0625f; localZ = .5f; axis = "Y"; }
            case "west" -> { localX = .9375f; localZ = .5f; axis = "Y"; }
            default -> { }
        }

        float minecraftX = block.x + localX - center[0];
        float minecraftY = block.y + .625f - center[1];
        float minecraftZ = block.z + localZ - center[2];

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("kind", "rigid_bone");
        descriptor.addProperty("object", BlockGrouper.partName(block, "lid"));
        descriptor.addProperty("baseObject", BlockGrouper.partName(block, "base"));
        descriptor.addProperty("bone", "Chest_Lid");
        descriptor.addProperty("action", BlockGrouper.physicalName(block) + "_Open");
        descriptor.addProperty("axis", axis);

        JsonArray pivot = new JsonArray();
        pivot.add(minecraftX);
        pivot.add(-minecraftZ);
        pivot.add(minecraftY);
        descriptor.add("pivot", pivot);

        JsonArray keyframes = new JsonArray();
        keyframes.add(keyframe(1, 0));
        keyframes.add(keyframe(10, -90));
        keyframes.add(keyframe(20, 0));
        descriptor.add("keyframes", keyframes);
        return descriptor;
    }

    private static JsonObject keyframe(int frame, int degrees) {
        JsonObject keyframe = new JsonObject();
        keyframe.addProperty("frame", frame);
        keyframe.addProperty("degrees", degrees);
        return keyframe;
    }
}
