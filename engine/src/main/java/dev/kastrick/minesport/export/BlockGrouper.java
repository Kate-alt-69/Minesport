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

    public static Map<BlockData, String> computeGroups(List<BlockData> blocks) {
        Map<Long, BlockData> index = new HashMap<>(Math.max(16, blocks.size() * 2));
        for (BlockData b : blocks) {
            if (!b.isAir()) index.put(SpatialKey.of(b.x, b.y, b.z), b);
        }

        Map<BlockData, String> result = new IdentityHashMap<>(blocks.size());
        Map<BlockData, Integer> componentId = new IdentityHashMap<>(blocks.size());

        // Resolve compound relationships once before ordinary same-state grouping.
        Map<BlockData, String> compoundGroups = MultiBlockStructureResolver.resolve(blocks);
        Map<String, List<BlockData>> compoundsById = new LinkedHashMap<>();
        for (var entry : compoundGroups.entrySet()) {
            compoundsById.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        }

        // Assign each detected compound exactly once. The old implementation
        // scanned every compound entry for every block (O(n²) on large builds).
        for (var entry : compoundsById.entrySet()) {
            String compoundId = entry.getKey();
            for (BlockData member : entry.getValue()) {
                result.put(member, compoundId);
                componentId.put(member, 0);
            }
        }

        // Ordinary grouping for everything that isn't part of a compound.
        Map<String, Integer> typeCounter = new HashMap<>();

        for (BlockData b : blocks) {
            if (b.isAir() || componentId.containsKey(b)) continue;

            String blockType = typeKey(b);
            int cid = typeCounter.getOrDefault(blockType, 0);
            typeCounter.put(blockType, cid + 1);

            // Keep the namespace in the group name so modded blocks with the
            // same short name (e.g. two different "stone" blocks) cannot merge.
            String baseName = sanitizeBlockId(b.blockId) + stateSuffix(b.properties);
            String groupName = cid == 0 ? baseName : baseName + "_g" + cid;

            Queue<BlockData> queue = new ArrayDeque<>();
            queue.add(b);
            componentId.put(b, cid);

            while (!queue.isEmpty()) {
                BlockData cur = queue.poll();
                result.put(cur, groupName);

                for (int[] d : NEIGHBOURS) {
                    BlockData neighbour = index.get(SpatialKey.of(cur.x + d[0], cur.y + d[1], cur.z + d[2]));
                    if (neighbour == null || compoundGroups.containsKey(neighbour)) continue;
                    if (!typeKey(neighbour).equals(blockType)) continue;
                    if (componentId.containsKey(neighbour)) continue;
                    componentId.put(neighbour, cid);
                    queue.add(neighbour);
                }
            }
        }

        return result;
    }

    private static String typeKey(BlockData b) {
        return b.blockId + "[" + stateKey(b.properties) + "]";
    }

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

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "");
    }

    /** Names safe for OBJ/glTF object/group names, while retaining namespace identity. */
    private static String sanitizeBlockId(String blockId) {
        return sanitize(blockId.replace(':', '_'));
    }

    private static final int[][] NEIGHBOURS = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };

    public static String shortName(String blockId) {
        int colon = blockId.indexOf(':');
        return colon >= 0 ? blockId.substring(colon + 1) : blockId;
    }

    public static float[] boundingBoxCenter(List<BlockData> blocks) {
        if (blocks.isEmpty()) return new float[]{0, 0, 0};

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean found = false;

        for (BlockData b : blocks) {
            if (b.isAir()) continue;
            found = true;
            minX = Math.min(minX, b.x); maxX = Math.max(maxX, b.x + 1);
            minY = Math.min(minY, b.y); maxY = Math.max(maxY, b.y + 1);
            minZ = Math.min(minZ, b.z); maxZ = Math.max(maxZ, b.z + 1);
        }

        if (!found) return new float[]{0, 0, 0};
        return new float[]{
            (minX + maxX) / 2f,
            (minY + maxY) / 2f,
            (minZ + maxZ) / 2f
        };
    }
}
