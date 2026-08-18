package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.*;

/**
 * Detects multi-block structures without hard-coding block names.
 *
 * The resolver intentionally works from block-state semantics rather than
 * knowing that a particular block is a door, bed, etc.  Vanilla and mods
 * commonly express multi-block relationships with complementary state values
 * such as half=lower/upper or part=bottom/top.  Directional boolean states
 * (north=true, south=true, ...) are also used as a generic connection signal.
 *
 * A structure is only created when there is an actual neighboring counterpart.
 * That means a lone lower half does not invent a missing upper half.
 */
public final class MultiBlockStructureResolver {
    private static final List<String> PART_KEYS = List.of(
        "half", "part", "section", "segment", "piece"
    );

    private static final Map<String, String> COMPLEMENT = Map.ofEntries(
        Map.entry("lower", "upper"),
        Map.entry("bottom", "top"),
        Map.entry("upper", "lower"),
        Map.entry("top", "bottom"),
        Map.entry("base", "top"),
        Map.entry("foot", "head"),
        Map.entry("head", "foot"),
        Map.entry("first", "second"),
        Map.entry("second", "first")
    );

    private static final Map<String, int[]> DIRECTION = Map.of(
        "north", new int[]{0, 0, -1},
        "south", new int[]{0, 0, 1},
        "east",  new int[]{1, 0, 0},
        "west",  new int[]{-1, 0, 0},
        "up",    new int[]{0, 1, 0},
        "down",  new int[]{0, -1, 0}
    );

    private MultiBlockStructureResolver() {}

    /**
     * Returns a stable structure id for every block that belongs to a
     * detected multi-block structure.  Blocks not belonging to one are absent.
     */
    public static Map<BlockData, String> resolve(List<BlockData> blocks) {
        Map<Long, BlockData> index = new HashMap<>(Math.max(16, blocks.size() * 2));
        for (BlockData block : blocks) {
            if (!block.isAir()) index.put(key(block.x, block.y, block.z), block);
        }

        Map<BlockData, Set<BlockData>> members = new IdentityHashMap<>();
        for (BlockData block : blocks) {
            if (block.isAir()) continue;

            // Explicit complementary-part state (door halves, bed halves,
            // many modded two-piece blocks, etc.).
            for (String key : PART_KEYS) {
                String value = block.prop(key);
                String otherValue = COMPLEMENT.get(value);
                if (otherValue == null) continue;

                for (int[] dir : sixNeighbors()) {
                    BlockData other = index.get(key(block.x + dir[0], block.y + dir[1], block.z + dir[2]));
                    if (other == null || !sameFamily(block, other)) continue;
                    if (other.prop(key).equals(otherValue)) {
                        link(members, block, other);
                    }
                }
            }

            // Generic directional connection: if a block explicitly says it
            // connects toward a neighboring block, treat that pair as one
            // compound structure. This also works across different block IDs,
            // which is useful for custom/modded connector pieces.
            for (var entry : DIRECTION.entrySet()) {
                if (!isConnectionEnabled(block, entry.getKey())) continue;
                int[] d = entry.getValue();
                BlockData other = index.get(key(block.x + d[0], block.y + d[1], block.z + d[2]));
                if (other != null && !other.isAir()) {
                    link(members, block, other);
                }
            }
        }

        // Collapse pair links into connected components. Directional chains
        // naturally become one structure, while isolated blocks stay alone.
        Map<BlockData, String> result = new IdentityHashMap<>();
        Set<BlockData> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (BlockData start : blocks) {
            if (start.isAir() || visited.contains(start) || !members.containsKey(start)) continue;

            Set<BlockData> component = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<BlockData> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            while (!queue.isEmpty()) {
                BlockData current = queue.removeFirst();
                component.add(current);
                for (BlockData next : members.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) queue.addLast(next);
                }
            }

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (BlockData b : component) {
                minX = Math.min(minX, b.x);
                minY = Math.min(minY, b.y);
                minZ = Math.min(minZ, b.z);
            }

            String id = "compound_" + minX + "_" + minY + "_" + minZ;
            for (BlockData b : component) result.put(b, id);
        }

        return result;
    }

    private static boolean sameFamily(BlockData a, BlockData b) {
        return a.blockId.equals(b.blockId);
    }

    private static boolean isConnectionEnabled(BlockData block, String direction) {
        String value = block.prop(direction);
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("connect");
    }

    private static void link(Map<BlockData, Set<BlockData>> members, BlockData a, BlockData b) {
        members.computeIfAbsent(a, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(b);
        members.computeIfAbsent(b, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(a);
    }

    private static List<int[]> sixNeighbors() {
        return List.of(
            new int[]{1, 0, 0}, new int[]{-1, 0, 0},
            new int[]{0, 1, 0}, new int[]{0, -1, 0},
            new int[]{0, 0, 1}, new int[]{0, 0, -1}
        );
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1048576) << 42)
             | ((long) (y + 1048576) << 21)
             | (long) (z + 1048576);
    }
}
