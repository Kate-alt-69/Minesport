package dev.kastrick.minesport.region;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Completes legacy block states whose pre-flattening metadata is split across
 * neighbouring blocks.
 *
 * Minecraft 1.2+ doors store facing/open on the lower block and hinge (plus
 * powered in later pre-flattening versions) on the upper block. RegionReader
 * invokes this after the full region has been decoded so door halves can be
 * paired even when they cross chunk or section boundaries.
 */
public final class LegacyStateResolver {
    private LegacyStateResolver() {}

    /** Resolve neighbour-dependent legacy state and return the number of completed pairs. */
    public static int resolve(List<BlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) return 0;

        Map<Position, BlockData> doors = new HashMap<>();
        for (BlockData block : blocks) {
            if (!isLegacyDoor(block)) continue;
            applyStandaloneDoorState(block);
            doors.put(new Position(block.x, block.y, block.z), block);
        }

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

    private static boolean isLegacyDoor(BlockData block) {
        if (block == null || block.properties == null) return false;
        String legacyId = block.properties.get("legacy_id");
        return "64".equals(legacyId) || "71".equals(legacyId);
    }

    private static boolean sameLegacyDoor(BlockData lower, BlockData upper) {
        return lower.blockId.equals(upper.blockId)
            && lower.properties.get("legacy_id").equals(upper.properties.get("legacy_id"));
    }

    private static int legacyData(BlockData block) {
        try {
            return Integer.parseInt(block.properties.getOrDefault("legacy_data", "0")) & 0xF;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * A cropped selection can contain only one door half. Populate a complete,
     * renderable state from the metadata available on that half, then overwrite
     * the guessed cross-half fields when its neighbour is present.
     */
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

    private record Position(int x, int y, int z) {}
}
