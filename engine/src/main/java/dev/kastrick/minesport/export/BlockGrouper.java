package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.*;

/**
 * Groups touching blocks of the same type into connected components.
 * Two blocks are "touching" if they share a face (6-connectivity, no diagonals).
 *
 * Multi-block structures are detected first by MultiBlockStructureResolver.
 * Their members get a dedicated compound group so a door/bed/custom multi-part
 * structure stays together and does not accidentally merge with unrelated
 * blocks nearby. Individual export mode still bypasses this grouping and keeps
 * every physical block as its own object.
 */
public class BlockGrouper {

    /**
     * Run connected-component analysis on the block list.
     * Returns a map: blockId+coords → groupId string (e.g. "oak_bench_group_0")
     */
    public static Map<BlockData, String> computeGroups(List<BlockData> blocks) {
        Map<Long, BlockData> index = new HashMap<>(blocks.size());
        for (BlockData b : blocks) {
            if (!b.isAir()) index.put(key(b.x, b.y, b.z), b);
        }

        Map<BlockData, String> result = new IdentityHashMap<>(blocks.size());
        Map<BlockData, Integer> componentId = new IdentityHashMap<>(blocks.size());

        // Resolve compound relationships once before ordinary same-state grouping.
        Map<BlockData, String> compoundGroups = MultiBlockStructureResolver.resolve(blocks);
        Set<BlockData> assignedCompound = Collections.newSetFromMap(new IdentityHashMap<>());

        for (BlockData b : blocks) {
            String compoundId = compoundGroups.get(b);
            if (compoundId == null || assignedCompound.contains(b) || b.isAir()) continue;

            // The resolver guarantees this id represents one connected compound.
            // Assigning the same exact group name keeps the structure separate
            // from ordinary blocks around it.
            for (var entry : compoundGroups.entrySet()) {
                if (compoundId.equals(entry.getValue())) {
                    result.put(entry.getKey(), compoundId);
                    assignedCompound.add(entry.getKey());
                    componentId.put(entry.getKey(), 0);
                }
            }
        }

        // Ordinary grouping for everything that isn't part of a compound.
        Map<String, Integer> typeCounter = new HashMap<>();

        for (BlockData b : blocks) {
            if (b.isAir() || componentId.containsKey(b)) continue;

            // BFS flood fill for this block's connected component
            String blockType = typeKey(b);
            int cid = typeCounter.merge(blockType, 0, (old, v) -> old + 1);
            typeCounter.put(blockType, cid + 1);

            String baseName = shortName(b.blockId) + stateSuffix(b.properties);
            String groupName = cid == 0
                ? baseName
                : baseName + "_g" + cid;

            Queue<BlockData> queue = new ArrayDeque<>();
            queue.add(b);
            componentId.put(b, cid);

            while (!queue.isEmpty()) {
                BlockData cur = queue.poll();
                result.put(cur, groupName);

                // Check all 6 face neighbours
                for (int[] d : NEIGHBOURS) {
                    long nk = key(cur.x + d[0], cur.y + d[1], cur.z + d[2]);
                    BlockData neighbour = index.get(nk);
                    if (neighbour == null) continue;
                    if (compoundGroups.containsKey(neighbour)) continue;
                    if (!typeKey(neighbour).equals(blockType)) continue;
                    if (componentId.containsKey(neighbour)) continue;
                    componentId.put(neighbour, cid);
                    queue.add(neighbour);
                }
            }
        }

        return result;
    }

    /** Combined block ID + block state key — the real unit of "same type" for grouping. */
    private static String typeKey(BlockData b) {
        return b.blockId + "[" + stateKey(b.properties) + "]";
    }

    /**
     * Canonical, order-independent state key for a property map.
     * {facing=north, lit=true} → "facing=north,lit=true".
     */
    public static String stateKey(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) return "";
        List<String> keys = new ArrayList<>(properties.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k).append('=').append(properties.get(k));
        }
        return sb.toString();
    }

    /**
     * Object/group-name-safe suffix carrying the block's state.
     */
    public static String stateSuffix(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) return "";
        List<String> keys = new ArrayList<>(properties.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append('_').append(sanitize(k)).append('-').append(sanitize(properties.get(k)));
        }
        return sb.toString();
    }

    /** Strips characters that are awkward in OBJ/glTF object names. */
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final int[][] NEIGHBOURS = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };

    private static long key(int x, int y, int z) {
        return ((long)(x + 1048576) << 42)
             | ((long)(y + 1048576) << 21)
             |  (long)(z + 1048576);
    }

    /**
     * Strip namespace from block ID and return just the block name.
     */
    public static String shortName(String blockId) {
        int colon = blockId.indexOf(':');
        return colon >= 0 ? blockId.substring(colon + 1) : blockId;
    }

    /**
     * Calculate the bounding box center of all blocks.
     * Used to center the export at world origin.
     */
    public static float[] boundingBoxCenter(List<BlockData> blocks) {
        if (blocks.isEmpty()) return new float[]{0, 0, 0};

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockData b : blocks) {
            if (b.isAir()) continue;
            minX = Math.min(minX, b.x); maxX = Math.max(maxX, b.x + 1);
            minY = Math.min(minY, b.y); maxY = Math.max(maxY, b.y + 1);
            minZ = Math.min(minZ, b.z); maxZ = Math.max(maxZ, b.z + 1);
        }

        return new float[]{
            (minX + maxX) / 2f,
            (minY + maxY) / 2f,
            (minZ + maxZ) / 2f
        };
    }
}
