package dev.kastrick.minesport.region;

import dev.kastrick.minesport.export.ExportWorldContext;
import dev.kastrick.minesport.export.SpatialKey;

import java.util.*;

/**
 * Pass 2 — Multipart connection resolver.
 *
 * After all blocks are collected in Pass 1, this scans every
 * flagged multipart block and checks its + neighbours in the
 * 3x3 XZ grid:
 *
 *     010
 *     111
 *     010
 *
 * If a neighbour qualifies as a connection target, the corresponding
 * direction flag is set on the BlockData. The geometry builder in
 * Pass 3 then picks the right model variant based on those flags.
 *
 * Also checks Y+1 (up) for wall/post height logic.
 */
public class MultipartResolver {

    // ── Which block IDs are multipart-capable ─────────────────────────────────
    // Maps blockId → connection group name
    // Blocks in the same group connect to each other.
    // "solid" is a special group — solid blocks connect TO multipart blocks
    // but don't need resolving themselves.

    private static final Map<String, String> MULTIPART_GROUPS = new HashMap<>();

    static {
        // Fences connect to same wood type + fence gates + solid blocks
        for (String wood : new String[]{
            "oak","spruce","birch","jungle","acacia","dark_oak",
            "mangrove","cherry","bamboo","crimson","warped"
        }) {
            MULTIPART_GROUPS.put("minecraft:" + wood + "_fence", "fence");
            MULTIPART_GROUPS.put("minecraft:" + wood + "_fence_gate", "fence_gate");
        }

        // Nether brick fence
        MULTIPART_GROUPS.put("minecraft:nether_brick_fence", "fence");

        // Glass panes + iron bars
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

        // Walls
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

    // ── Which groups connect to which ─────────────────────────────────────────
    // "solid" = any solid full-cube block
    private static final Map<String, Set<String>> CONNECTS_TO = Map.of(
        "fence",      Set.of("fence", "fence_gate", "solid"),
        "fence_gate", Set.of("fence", "solid"),
        "pane",       Set.of("pane", "solid"),
        "wall",       Set.of("wall", "solid")
    );

    // ── Solid blocks (abbreviated — expand as needed) ─────────────────────────
    // In practice we check this by exclusion: if it's not air, not a multipart
    // transparent block, and has a full cube shape, treat as solid.
    // For now a simple heuristic: not in MULTIPART_GROUPS and not air/plant/etc.
    private static final Set<String> KNOWN_NON_SOLID = Set.of(
        "minecraft:air","minecraft:cave_air","minecraft:void_air",
        "minecraft:water","minecraft:lava",
        "minecraft:grass","minecraft:tall_grass","minecraft:fern",
        "minecraft:large_fern","minecraft:dead_bush","minecraft:torch",
        "minecraft:wall_torch","minecraft:sign","minecraft:wall_sign",
        "minecraft:ladder","minecraft:vine","minecraft:snow"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Run Pass 2 on the full block list.
     * Mutates BlockData objects in place (sets connectN/S/E/W/Up flags).
     */
    public static void resolve(List<BlockData> blocks) {
        // Publish the complete selected world before any geometry builder is
        // created. Liquids, waterlogged hosts, transparent blocks and FLATTER
        // therefore see the same neighbour map even when culling is disabled.
        ExportWorldContext.set(blocks);

        // Build a collision-free spatial lookup map for normal Minecraft coordinates.
        Map<Long, BlockData> blockMap = new HashMap<>(blocks.size());
        for (BlockData b : blocks) {
            blockMap.put(SpatialKey.of(b.x, b.y, b.z), b);
        }

        // Flag multipart blocks in Pass 1 result
        for (BlockData b : blocks) {
            if (MULTIPART_GROUPS.containsKey(b.blockId)) {
                b.isMultipart = true;
            }
        }

        // Resolve connections for each multipart block
        for (BlockData b : blocks) {
            if (!b.isMultipart) continue;

            String myGroup = MULTIPART_GROUPS.get(b.blockId);
            Set<String> validTargets = CONNECTS_TO.getOrDefault(myGroup, Set.of());

            b.connectNorth = connects(blockMap, b.x,     b.y, b.z - 1, validTargets);
            b.connectSouth = connects(blockMap, b.x,     b.y, b.z + 1, validTargets);
            b.connectEast  = connects(blockMap, b.x + 1, b.y, b.z,     validTargets);
            b.connectWest  = connects(blockMap, b.x - 1, b.y, b.z,     validTargets);
            b.connectUp    = connects(blockMap, b.x,     b.y + 1, b.z, validTargets); // wall post height
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean connects(
            Map<Long, BlockData> map,
            int x, int y, int z,
            Set<String> validTargets
    ) {
        BlockData neighbour = map.get(SpatialKey.of(x, y, z));
        if (neighbour == null) return false; // air / out of export bounds

        String neighbourGroup = MULTIPART_GROUPS.get(neighbour.blockId);

        if (neighbourGroup != null) {
            // Neighbour is also a multipart block — check group compatibility
            return validTargets.contains(neighbourGroup);
        } else {
            // Neighbour is a regular block — treat as solid if not in non-solid list
            boolean isSolid = !KNOWN_NON_SOLID.contains(neighbour.blockId);
            return isSolid && validTargets.contains("solid");
        }
    }

    /** Get a human-readable connection string for a block (debug). */
    public static String connectionString(BlockData b) {
        if (!b.isMultipart) return "not-multipart";
        var sb = new StringBuilder();
        if (b.connectNorth) sb.append("N");
        if (b.connectSouth) sb.append("S");
        if (b.connectEast)  sb.append("E");
        if (b.connectWest)  sb.append("W");
        if (b.connectUp)    sb.append("U");
        return sb.isEmpty() ? "isolated" : sb.toString();
    }
}
