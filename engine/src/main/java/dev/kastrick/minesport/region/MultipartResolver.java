package dev.kastrick.minesport.region;

import dev.kastrick.minesport.export.BlockGeometryClassifier;
import dev.kastrick.minesport.export.BlockGeometryKind;
import dev.kastrick.minesport.export.ExportWorldContext;
import dev.kastrick.minesport.export.SpatialKey;

import java.util.*;

/**
 * Pass 2 — multipart connection resolver.
 *
 * The first pass may run before asset resolvers exist. In that mode Minesport
 * only connects known multipart groups to each other and publishes the world
 * index. Once GeometryBuilder owns the finished ResolverChain it reruns this
 * pass against that same index with BlockGeometryClassifier, allowing ordinary
 * neighbours to count as solid only when their resolved model is a verified
 * full cube.
 */
public class MultipartResolver {
    private static final Map<String, String> MULTIPART_GROUPS = new HashMap<>();

    static {
        for (String wood : new String[]{
            "oak","spruce","birch","jungle","acacia","dark_oak",
            "mangrove","cherry","bamboo","crimson","warped"
        }) {
            MULTIPART_GROUPS.put("minecraft:" + wood + "_fence", "fence");
            MULTIPART_GROUPS.put("minecraft:" + wood + "_fence_gate", "fence_gate");
        }
        MULTIPART_GROUPS.put("minecraft:nether_brick_fence", "fence");

        for (String pane : new String[]{
            "glass_pane","white_stained_glass_pane","orange_stained_glass_pane",
            "magenta_stained_glass_pane","light_blue_stained_glass_pane",
            "yellow_stained_glass_pane","lime_stained_glass_pane",
            "pink_stained_glass_pane","gray_stained_glass_pane",
            "light_gray_stained_glass_pane","cyan_stained_glass_pane",
            "purple_stained_glass_pane","blue_stained_glass_pane",
            "brown_stained_glass_pane","green_stained_glass_pane",
            "red_stained_glass_pane","black_stained_glass_pane"
        }) {
            MULTIPART_GROUPS.put("minecraft:" + pane, "pane");
        }
        MULTIPART_GROUPS.put("minecraft:iron_bars", "pane");

        for (String wall : new String[]{
            "cobblestone_wall","mossy_cobblestone_wall","stone_brick_wall",
            "mossy_stone_brick_wall","andesite_wall","diorite_wall",
            "granite_wall","sandstone_wall","red_sandstone_wall",
            "brick_wall","prismarine_wall","red_nether_brick_wall",
            "nether_brick_wall","end_stone_brick_wall","blackstone_wall",
            "polished_blackstone_wall","polished_blackstone_brick_wall",
            "cobbled_deepslate_wall","polished_deepslate_wall",
            "deepslate_brick_wall","deepslate_tile_wall","tuff_wall",
            "polished_tuff_wall","tuff_brick_wall","mud_brick_wall"
        }) {
            MULTIPART_GROUPS.put("minecraft:" + wall, "wall");
        }
    }

    private static final Map<String, Set<String>> CONNECTS_TO = Map.of(
        "fence",      Set.of("fence", "fence_gate", "solid"),
        "fence_gate", Set.of("fence", "solid"),
        "pane",       Set.of("pane", "solid"),
        "wall",       Set.of("wall", "solid")
    );

    /**
     * Publish the selected world and resolve topology that does not require
     * model knowledge. Ordinary blocks fail closed here; GeometryBuilder reruns
     * against the published index once its resolver chain is available.
     */
    public static void resolve(List<BlockData> blocks) {
        resolve(blocks, null);
    }

    /** Resolve from a block list, optionally using resolved model geometry. */
    public static void resolve(List<BlockData> blocks, BlockGeometryClassifier classifier) {
        // This is the first full-selection pass shared by OBJ/glTF exports.
        // Re-run legacy neighbour reconstruction here so structures such as a
        // double chest can be paired even when its two halves cross a chunk or
        // region boundary.
        LegacyStateResolver.resolve(blocks);
        ExportWorldContext.set(blocks);
        resolveIndex(ExportWorldContext.currentIndex(), classifier);
    }

    /**
     * Resolve connection flags against an existing spatial index. This overload
     * lets the export GeometryBuilder reuse ExportWorldContext's map rather than
     * allocating a second map for geometry-aware solid-neighbour checks.
     */
    public static void resolveIndex(
        Map<Long, BlockData> blockMap,
        BlockGeometryClassifier classifier
    ) {
        if (blockMap == null || blockMap.isEmpty()) return;

        for (BlockData block : blockMap.values()) {
            block.isMultipart = MULTIPART_GROUPS.containsKey(block.blockId);
        }

        Map<Long, Boolean> fullCubeCache = classifier == null
            ? Map.of()
            : new HashMap<>();

        for (BlockData block : blockMap.values()) {
            if (!block.isMultipart) continue;

            String myGroup = MULTIPART_GROUPS.get(block.blockId);
            Set<String> validTargets = CONNECTS_TO.getOrDefault(myGroup, Set.of());

            block.connectNorth = connects(
                blockMap, block.x, block.y, block.z - 1,
                validTargets, classifier, fullCubeCache
            );
            block.connectSouth = connects(
                blockMap, block.x, block.y, block.z + 1,
                validTargets, classifier, fullCubeCache
            );
            block.connectEast = connects(
                blockMap, block.x + 1, block.y, block.z,
                validTargets, classifier, fullCubeCache
            );
            block.connectWest = connects(
                blockMap, block.x - 1, block.y, block.z,
                validTargets, classifier, fullCubeCache
            );
            block.connectUp = connects(
                blockMap, block.x, block.y + 1, block.z,
                validTargets, classifier, fullCubeCache
            );
        }
    }

    private static boolean connects(
        Map<Long, BlockData> map,
        int x, int y, int z,
        Set<String> validTargets,
        BlockGeometryClassifier classifier,
        Map<Long, Boolean> fullCubeCache
    ) {
        long key = SpatialKey.of(x, y, z);
        BlockData neighbour = map.get(key);
        if (neighbour == null) return false;

        String neighbourGroup = MULTIPART_GROUPS.get(neighbour.blockId);
        if (neighbourGroup != null) {
            return validTargets.contains(neighbourGroup);
        }

        if (!validTargets.contains("solid") || classifier == null) return false;

        return fullCubeCache.computeIfAbsent(key, ignored -> {
            try {
                return classifier.classify(neighbour) == BlockGeometryKind.FULL_BLOCK;
            } catch (RuntimeException error) {
                // Asset/model failures must never create a false connection.
                return false;
            }
        });
    }

    /** Get a human-readable connection string for a block (debug). */
    public static String connectionString(BlockData block) {
        if (!block.isMultipart) return "not-multipart";
        var result = new StringBuilder();
        if (block.connectNorth) result.append("N");
        if (block.connectSouth) result.append("S");
        if (block.connectEast) result.append("E");
        if (block.connectWest) result.append("W");
        if (block.connectUp) result.append("U");
        return result.isEmpty() ? "isolated" : result.toString();
    }
}
