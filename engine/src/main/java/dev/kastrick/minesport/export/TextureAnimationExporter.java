package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts Minecraft .png.mcmeta animation schedules into DCC-neutral texture
 * animation descriptors. The geometry scan is deduplicated by block state so a
 * large world does not pay another per-block export pass merely to discover the
 * handful of materials it actually uses.
 */
public final class TextureAnimationExporter {
    private static final int MAX_EXPANDED_TICKS = 65_536;

    private TextureAnimationExporter() {}

    private record FrameSpec(int index, int ticks) {}

    public static JsonArray describe(
        List<BlockData> blocks,
        GeometryBuilder geometry,
        boolean enabled
    ) {
        JsonArray result = new JsonArray();
        if (!enabled || blocks == null || geometry == null) return result;

        ResolverChain resolvers = geometry.getResolvers();
        Map<String, MaterialKey> materials = usedMaterialsByName(blocks, geometry);
        for (MaterialKey material : materials.values()) {
            JsonObject descriptor = describeMaterial(material, resolvers);
            if (descriptor != null) result.add(descriptor);
        }
        return result;
    }

    private static Map<String, MaterialKey> usedMaterialsByName(
        List<BlockData> blocks,
        GeometryBuilder geometry
    ) {
        Set<String> seenVariants = new HashSet<>();
        Map<String, BlockState> resolvedStates = new LinkedHashMap<>();
        Set<String> unresolvedBlockStates = new HashSet<>();
        Map<String, MaterialKey> materials = new LinkedHashMap<>();
        ResolverChain resolvers = geometry.getResolvers();
        for (BlockData block : blocks) {
            if (block == null || block.isAir()) continue;
            String discoveryKey = materialDiscoveryKey(
                block,
                resolvers,
                resolvedStates,
                unresolvedBlockStates
            );
            if (!seenVariants.add(discoveryKey)) continue;
            List<Quad> quads;
            try {
                quads = geometry.buildBlock(block);
            } catch (Exception ignored) {
                continue;
            }
            if (quads == null) continue;
            for (Quad quad : quads) {
                if (quad == null) continue;
                MaterialKey key = MaterialKey.forQuad(quad);
                materials.putIfAbsent(key.materialName(), key);
            }
        }
        return materials;
    }

    /**
     * Deduplicate animation discovery by the model application that this exact
     * coordinate resolves to, not merely by logical block state. Minecraft's
     * weighted variants deliberately use position as part of their stable
     * selection, so two equal states can legitimately render different models
     * (and therefore different animated textures).
     */
    private static String materialDiscoveryKey(
        BlockData block,
        ResolverChain resolvers,
        Map<String, BlockState> resolvedStates,
        Set<String> unresolvedBlockStates
    ) {
        String stateKey = block.blockId + "[" + BlockGrouper.stateKey(block.properties) + "]";
        if (resolvers == null || unresolvedBlockStates.contains(block.blockId)) return stateKey;

        BlockState state = resolvedStates.get(block.blockId);
        if (state == null) {
            try {
                state = resolvers.resolveBlockState(block.blockId);
            } catch (Exception ignored) {
                state = null;
            }
            if (state == null) {
                unresolvedBlockStates.add(block.blockId);
                return stateKey;
            }
            resolvedStates.put(block.blockId, state);
        }

        List<BlockState.ModelApplication> applications;
        try {
            applications = state.resolve(
                block.properties,
                block.x,
                block.y,
                block.z
            );
        } catch (Exception ignored) {
            return stateKey;
        }
        if (applications == null || applications.isEmpty()) return stateKey;

        StringBuilder signature = new StringBuilder(stateKey).append("|models=");
        for (BlockState.ModelApplication application : applications) {
            if (application == null) continue;
            signature.append(application.modelPath == null ? "" : application.modelPath)
                .append(';');
        }
        return signature.toString();
    }

    static JsonObject describeMaterial(MaterialKey material, ResolverChain resolvers) {
        if (material == null || resolvers == null) return null;
        String metadata = resolvers.resolveTextureMetadata(material.texturePath());
        if (metadata == null || metadata.isBlank()) return null;

        BufferedImage image;
        try {
            image = resolvers.resolveTexture(material.texturePath());
        } catch (Exception ignored) {
            return null;
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;

        try {
            JsonElement parsed = JsonParser.parseString(metadata);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("animation") || !root.get("animation").isJsonObject()) return null;
            JsonObject animation = root.getAsJsonObject("animation");

            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            int frameWidth = positiveInt(animation, "width", imageWidth);
            int frameHeight = positiveInt(animation, "height", frameWidth);
            if (frameWidth <= 0 || frameHeight <= 0) return null;
            if (imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) return null;

            int columns = imageWidth / frameWidth;
            int rows = imageHeight / frameHeight;
            int frameCount = columns * rows;
            if (frameCount <= 1) return null;

            int defaultTicks = positiveInt(animation, "frametime", 1);
            List<FrameSpec> schedule = parseSchedule(animation, frameCount, defaultTicks);
            if (schedule.isEmpty()) return null;

            JsonArray expanded = new JsonArray();
            JsonArray timeline = new JsonArray();
            int tick = 0;
            int previous = -1;
            for (FrameSpec frame : schedule) {
                if (tick + frame.ticks() > MAX_EXPANDED_TICKS) return null;
                if (frame.index() != previous) {
                    JsonObject change = new JsonObject();
                    change.addProperty("tick", tick);
                    change.addProperty("textureFrame", frame.index());
                    change.addProperty("ticks", frame.ticks());
                    timeline.add(change);
                    previous = frame.index();
                }
                for (int i = 0; i < frame.ticks(); i++) expanded.add(frame.index());
                tick += frame.ticks();
            }
            if (expanded.isEmpty()) return null;

            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("kind", "texture_frames");
            descriptor.addProperty("material", material.materialName());
            descriptor.addProperty("texture", material.texturePath());
            descriptor.addProperty("imageWidth", imageWidth);
            descriptor.addProperty("imageHeight", imageHeight);
            descriptor.addProperty("frameWidth", frameWidth);
            descriptor.addProperty("frameHeight", frameHeight);
            descriptor.addProperty("columns", columns);
            descriptor.addProperty("rows", rows);
            descriptor.addProperty("frameCount", frameCount);
            // Expanded sequence uses one Minecraft tick per entry. This keeps
            // Blender's driver tiny while preserving custom per-frame durations.
            descriptor.addProperty("frameTime", 1);
            descriptor.addProperty("cycleTicks", tick);
            descriptor.addProperty(
                "interpolate",
                animation.has("interpolate") && animation.get("interpolate").getAsBoolean()
            );
            descriptor.add("frames", expanded);
            descriptor.add("timeline", timeline);
            return descriptor;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<FrameSpec> parseSchedule(JsonObject animation, int frameCount, int defaultTicks) {
        List<FrameSpec> frames = new ArrayList<>();
        if (!animation.has("frames") || !animation.get("frames").isJsonArray()) {
            for (int i = 0; i < frameCount; i++) frames.add(new FrameSpec(i, defaultTicks));
            return frames;
        }

        for (JsonElement element : animation.getAsJsonArray("frames")) {
            int index;
            int ticks = defaultTicks;
            try {
                if (element.isJsonPrimitive()) {
                    index = element.getAsInt();
                } else if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (!object.has("index")) continue;
                    index = object.get("index").getAsInt();
                    ticks = positiveInt(object, "time", defaultTicks);
                } else {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            if (index < 0 || index >= frameCount) continue;
            frames.add(new FrameSpec(index, Math.max(1, ticks)));
        }
        return frames;
    }

    private static int positiveInt(JsonObject object, String key, int fallback) {
        try {
            if (!object.has(key)) return fallback;
            int value = object.get(key).getAsInt();
            return value > 0 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
