package dev.kastrick.minesport.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completes pre-flattening block states that depend on neighbouring blocks.
 *
 * This runs once during legacy chunk decoding so information such as a door's
 * split upper/lower metadata is captured before later selection filters remove
 * neighbours. Mesh exports run it again on the final selected world list so
 * states that can cross chunk/region boundaries (notably double chests and
 * stair corners) are corrected globally.
 */
public final class LegacyStateResolver {
    private static final Set<String> LEGACY_STAIR_IDS = Set.of(
        "53", "67", "108", "109", "114", "128", "134", "135", "136", "156"
    );

    private LegacyStateResolver() {}

    /** Resolve neighbour-derived legacy structures and return completed pair count. */
    public static int resolve(List<BlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return 0;

        Map<Position, BlockData> world = new HashMap<>(Math.max(16, blocks.size() * 2));
        Map<Position, BlockData> doors = new HashMap<>();
        Map<Position, BlockData> chests = new HashMap<>();

        for (BlockData block : blocks) {
            if (block == null) continue;
            Position position = new Position(block.x, block.y, block.z);
            world.put(position, block);

            if (isLegacyDoor(block)) {
                // Do not overwrite a complete state recovered by an earlier
                // chunk-local pass if a later cropped selection omits its mate.
                if (!hasCompleteDoorState(block)) applyStandaloneDoorState(block);
                doors.put(position, block);
            }
            if (isLegacyChest(block)) {
                // Chest type is selection-topology-dependent and is therefore
                // safe to recompute on every pass.
                block.properties.put("type", "single");
                block.properties.put("waterlogged", "false");
                chests.put(position, block);
            }
        }

        resolveLegacySnow(world);
        resolveLegacyStairs(world);

        int resolved = resolveDoors(doors);
        resolved += resolveChests(chests);
        return resolved;
    }

    private static int resolveDoors(Map<Position, BlockData> doors) {
        int resolved = 0;
        for (BlockData lower : doors.values()) {
            int lowerData = legacyData(lower);
            if ((lowerData & 0x8) != 0 || lower.y == Integer.MAX_VALUE) continue;

            BlockData upper = doors.get(new Position(lower.x, lower.y + 1, lower.z));
            if (upper == null || !sameLegacyDoor(lower, upper)) continue;
            int upperData = legacyData(upper);
            if ((upperData & 0x8) == 0) continue;

            applyDoorPair(lower, upper, lowerData, upperData);
            resolved++;
        }
        return resolved;
    }

    private static void resolveLegacySnow(Map<Position, BlockData> world) {
        for (BlockData block : world.values()) {
            if (!isLegacySnowyDirt(block) || block.properties.containsKey("snowy")) continue;
            BlockData above = block.y == Integer.MAX_VALUE
                ? null
                : world.get(new Position(block.x, block.y + 1, block.z));
            block.properties.put("snowy", boolText(isSnowCover(above)));
        }
    }

    /**
     * Pre-flattening stair metadata stores only facing + top/bottom. Minecraft
     * derives straight/inner/outer corner shape from neighbouring stairs at
     * render time, so reproduce that rule before modern blockstate resolution.
     */
    private static void resolveLegacyStairs(Map<Position, BlockData> world) {
        for (Map.Entry<Position, BlockData> entry : world.entrySet()) {
            BlockData stair = entry.getValue();
            if (!isLegacyStair(stair)) continue;

            String resolvedShape = stairShape(entry.getKey(), stair, world);
            String previousShape = stair.prop("shape");
            // A chunk-local pass may already have recovered a real corner. If a
            // later exact-selection pass removes its partner, preserve the world
            // shape rather than downgrading the retained stair to straight.
            if (!"straight".equals(resolvedShape)
                || previousShape.isEmpty()
                || "straight".equals(previousShape)) {
                stair.properties.put("shape", resolvedShape);
            }
        }
    }

    private static String stairShape(
        Position position,
        BlockData stair,
        Map<Position, BlockData> world
    ) {
        HorizontalDirection facing = HorizontalDirection.from(stair.prop("facing"));
        if (facing == null) return "straight";

        BlockData front = world.get(position.offset(facing));
        if (sameStairHalf(stair, front)) {
            HorizontalDirection frontFacing = HorizontalDirection.from(front.prop("facing"));
            if (frontFacing != null
                && frontFacing.horizontalAxis() != facing.horizontalAxis()
                && canTakeStairShape(stair, position, frontFacing.opposite(), world)) {
                return frontFacing == facing.counterClockwise()
                    ? "outer_left"
                    : "outer_right";
            }
        }

        BlockData back = world.get(position.offset(facing.opposite()));
        if (sameStairHalf(stair, back)) {
            HorizontalDirection backFacing = HorizontalDirection.from(back.prop("facing"));
            if (backFacing != null
                && backFacing.horizontalAxis() != facing.horizontalAxis()
                && canTakeStairShape(stair, position, backFacing, world)) {
                return backFacing == facing.counterClockwise()
                    ? "inner_left"
                    : "inner_right";
            }
        }

        return "straight";
    }

    private static boolean canTakeStairShape(
        BlockData stair,
        Position position,
        HorizontalDirection side,
        Map<Position, BlockData> world
    ) {
        BlockData sideStair = world.get(position.offset(side));
        if (!isLegacyStair(sideStair)) return true;
        return !stair.prop("facing").equals(sideStair.prop("facing"))
            || !stair.prop("half").equals(sideStair.prop("half"));
    }

    private static boolean sameStairHalf(BlockData first, BlockData second) {
        return isLegacyStair(first)
            && isLegacyStair(second)
            && first.prop("half").equals(second.prop("half"));
    }

    private static int resolveChests(Map<Position, BlockData> chests) {
        Set<Position> paired = new HashSet<>();
        int resolved = 0;

        for (Map.Entry<Position, BlockData> entry : chests.entrySet()) {
            Position position = entry.getKey();
            BlockData chest = entry.getValue();
            if (paired.contains(position)) continue;

            List<Position> candidates = chestPartners(position, chest, chests);
            if (candidates.size() != 1) continue;

            Position partnerPosition = candidates.getFirst();
            if (paired.contains(partnerPosition)) continue;
            BlockData partner = chests.get(partnerPosition);
            if (partner == null) continue;

            // A malformed triple chest can make an end block see one neighbour
            // while the middle sees two. Require the relationship to be unique
            // from both sides before assigning modern left/right state.
            List<Position> reverse = chestPartners(partnerPosition, partner, chests);
            if (reverse.size() != 1 || !reverse.getFirst().equals(position)) continue;

            int dx = partner.x - chest.x;
            int dz = partner.z - chest.z;
            chest.properties.put("type", chestTypeForPartner(chest.prop("facing"), dx, dz));
            partner.properties.put("type", chestTypeForPartner(partner.prop("facing"), -dx, -dz));
            paired.add(position);
            paired.add(partnerPosition);
            resolved++;
        }
        return resolved;
    }

    private static List<Position> chestPartners(
        Position position,
        BlockData chest,
        Map<Position, BlockData> chests
    ) {
        String facing = chest.prop("facing");
        int[][] offsets = switch (facing) {
            case "north", "south" -> new int[][]{{-1, 0}, {1, 0}};
            case "east", "west" -> new int[][]{{0, -1}, {0, 1}};
            default -> new int[0][0];
        };

        List<Position> result = new ArrayList<>(2);
        for (int[] offset : offsets) {
            Position candidatePosition = new Position(
                position.x + offset[0],
                position.y,
                position.z + offset[1]
            );
            BlockData candidate = chests.get(candidatePosition);
            if (candidate == null || !sameLegacyChest(chest, candidate)) continue;
            if (!facing.equals(candidate.prop("facing"))) continue;
            result.add(candidatePosition);
        }
        return result;
    }

    private static String chestTypeForPartner(String facing, int dx, int dz) {
        int clockwiseX;
        int clockwiseZ;
        switch (facing) {
            case "north" -> { clockwiseX = 1; clockwiseZ = 0; }
            case "east" -> { clockwiseX = 0; clockwiseZ = 1; }
            case "south" -> { clockwiseX = -1; clockwiseZ = 0; }
            case "west" -> { clockwiseX = 0; clockwiseZ = -1; }
            default -> { return "single"; }
        }
        return dx == clockwiseX && dz == clockwiseZ ? "left" : "right";
    }

    private static boolean isLegacyDoor(BlockData block) {
        if (block == null || block.properties == null) return false;
        String legacyId = block.properties.get("legacy_id");
        return "64".equals(legacyId) || "71".equals(legacyId);
    }

    private static boolean isLegacyChest(BlockData block) {
        if (block == null || block.properties == null) return false;
        String legacyId = block.properties.get("legacy_id");
        return "54".equals(legacyId) || "146".equals(legacyId);
    }

    private static boolean isLegacyStair(BlockData block) {
        if (block == null || block.properties == null) return false;
        return LEGACY_STAIR_IDS.contains(block.properties.get("legacy_id"));
    }

    private static boolean isLegacySnowyDirt(BlockData block) {
        if (block == null || block.properties == null) return false;
        String legacyId = block.properties.get("legacy_id");
        return "2".equals(legacyId) || "110".equals(legacyId);
    }

    private static boolean isSnowCover(BlockData block) {
        if (block == null) return false;
        return "minecraft:snow".equals(block.blockId)
            || "minecraft:snow_block".equals(block.blockId);
    }

    private static boolean sameLegacyDoor(BlockData lower, BlockData upper) {
        return lower.blockId.equals(upper.blockId)
            && lower.properties.get("legacy_id").equals(upper.properties.get("legacy_id"));
    }

    private static boolean sameLegacyChest(BlockData first, BlockData second) {
        return first.blockId.equals(second.blockId)
            && first.properties.get("legacy_id").equals(second.properties.get("legacy_id"));
    }

    private static int legacyData(BlockData block) {
        try {
            return Integer.parseInt(block.properties.getOrDefault("legacy_data", "0")) & 0xF;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean hasCompleteDoorState(BlockData block) {
        return block.properties.containsKey("half")
            && block.properties.containsKey("facing")
            && block.properties.containsKey("open")
            && block.properties.containsKey("hinge")
            && block.properties.containsKey("powered");
    }

    private static void applyStandaloneDoorState(BlockData block) {
        int data = legacyData(block);
        if ((data & 0x8) != 0) {
            applyDoorState(
                block,
                "upper",
                "north",
                "false",
                (data & 0x1) != 0 ? "left" : "right",
                boolText((data & 0x2) != 0)
            );
        } else {
            applyDoorState(
                block,
                "lower",
                doorFacing(data & 0x3),
                boolText((data & 0x4) != 0),
                "right",
                "false"
            );
        }
    }

    private static void applyDoorPair(
        BlockData lower,
        BlockData upper,
        int lowerData,
        int upperData
    ) {
        String facing = doorFacing(lowerData & 0x3);
        String open = boolText((lowerData & 0x4) != 0);
        String hinge = (upperData & 0x1) != 0 ? "left" : "right";
        String powered = boolText((upperData & 0x2) != 0);

        applyDoorState(lower, "lower", facing, open, hinge, powered);
        applyDoorState(upper, "upper", facing, open, hinge, powered);
    }

    private static void applyDoorState(
        BlockData block,
        String half,
        String facing,
        String open,
        String hinge,
        String powered
    ) {
        block.properties.put("half", half);
        block.properties.put("facing", facing);
        block.properties.put("open", open);
        block.properties.put("hinge", hinge);
        block.properties.put("powered", powered);
    }

    static String doorFacing(int value) {
        return switch (value & 0x3) {
            case 1 -> "south";
            case 2 -> "west";
            case 3 -> "north";
            default -> "east";
        };
    }

    private static String boolText(boolean value) {
        return value ? "true" : "false";
    }

    private enum HorizontalDirection {
        NORTH(0, -1, 0),
        EAST(1, 0, 1),
        SOUTH(0, 1, 0),
        WEST(-1, 0, 1);

        private final int dx;
        private final int dz;
        private final int horizontalAxis;

        HorizontalDirection(int dx, int dz, int horizontalAxis) {
            this.dx = dx;
            this.dz = dz;
            this.horizontalAxis = horizontalAxis;
        }

        static HorizontalDirection from(String value) {
            return switch (value) {
                case "north" -> NORTH;
                case "east" -> EAST;
                case "south" -> SOUTH;
                case "west" -> WEST;
                default -> null;
            };
        }

        int horizontalAxis() {
            return horizontalAxis;
        }

        HorizontalDirection opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case EAST -> WEST;
                case SOUTH -> NORTH;
                case WEST -> EAST;
            };
        }

        HorizontalDirection counterClockwise() {
            return switch (this) {
                case NORTH -> WEST;
                case WEST -> SOUTH;
                case SOUTH -> EAST;
                case EAST -> NORTH;
            };
        }
    }

    private record Position(int x, int y, int z) {
        Position offset(HorizontalDirection direction) {
            return new Position(x + direction.dx, y, z + direction.dz);
        }
    }
}
