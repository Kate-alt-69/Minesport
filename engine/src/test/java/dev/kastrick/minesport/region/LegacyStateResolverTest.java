package dev.kastrick.minesport.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyStateResolverTest {
    @Test
    void combinesLegacyDoorMetadataAcrossBothHalves() {
        BlockData lower = legacyDoor(12, 64, -7, 64, 5); // south + open
        BlockData upper = legacyDoor(12, 65, -7, 64, 9); // upper + left hinge

        assertEquals(1, LegacyStateResolver.resolve(List.of(lower, upper)));

        assertDoorState(lower, "lower", "south", "true", "left", "false");
        assertDoorState(upper, "upper", "south", "true", "left", "false");
    }

    @Test
    void propagatesLaterPreFlatteningPoweredBitFromUpperHalf() {
        BlockData lower = legacyDoor(0, 70, 0, 71, 2);  // west + closed
        BlockData upper = legacyDoor(0, 71, 0, 71, 10); // upper + powered + right hinge

        assertEquals(1, LegacyStateResolver.resolve(List.of(lower, upper)));

        assertDoorState(lower, "lower", "west", "false", "right", "true");
        assertDoorState(upper, "upper", "west", "false", "right", "true");
    }

    @Test
    void leavesCroppedSingleHalfRenderableWithSafeDefaults() {
        BlockData lowerOnly = legacyDoor(3, 40, 9, 64, 7);
        assertEquals(0, LegacyStateResolver.resolve(List.of(lowerOnly)));
        assertDoorState(lowerOnly, "lower", "north", "true", "right", "false");

        BlockData upperOnly = legacyDoor(3, 41, 9, 64, 9);
        assertEquals(0, LegacyStateResolver.resolve(List.of(upperOnly)));
        assertDoorState(upperOnly, "upper", "north", "false", "left", "false");
    }

    @Test
    void doesNotPairDifferentDoorTypesAtTheSameColumn() {
        BlockData oakLower = legacyDoor(1, 90, 1, 64, 0);
        BlockData ironUpper = legacyDoor(1, 91, 1, 71, 8);

        assertEquals(0, LegacyStateResolver.resolve(List.of(oakLower, ironUpper)));
        assertDoorState(oakLower, "lower", "east", "false", "right", "false");
        assertDoorState(ironUpper, "upper", "north", "false", "right", "false");
    }

    private static BlockData legacyDoor(
        int x,
        int y,
        int z,
        int numericId,
        int metadata
    ) {
        LegacyBlockIds.DecodedBlock decoded = LegacyBlockIds.decode(numericId, metadata);
        return new BlockData(x, y, z, decoded.blockId(), decoded.properties());
    }

    private static void assertDoorState(
        BlockData block,
        String half,
        String facing,
        String open,
        String hinge,
        String powered
    ) {
        assertEquals(half, block.properties.get("half"));
        assertEquals(facing, block.properties.get("facing"));
        assertEquals(open, block.properties.get("open"));
        assertEquals(hinge, block.properties.get("hinge"));
        assertEquals(powered, block.properties.get("powered"));
    }
}
