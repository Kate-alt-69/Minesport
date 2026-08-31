package dev.kastrick.minesport.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyNeighbourStateResolverTest {
    @Test
    void derivesSnowyStateFromLegacyBlockAbove() {
        BlockData grass = legacy(0, 64, 0, 2, 0);
        BlockData snow = legacy(0, 65, 0, 78, 0);
        BlockData mycelium = legacy(2, 64, 0, 110, 0);
        BlockData snowBlock = legacy(2, 65, 0, 80, 0);
        BlockData bareGrass = legacy(4, 64, 0, 2, 0);

        LegacyStateResolver.resolve(List.of(grass, snow, mycelium, snowBlock, bareGrass));

        assertEquals("true", grass.properties.get("snowy"));
        assertEquals("true", mycelium.properties.get("snowy"));
        assertEquals("false", bareGrass.properties.get("snowy"));
    }

    @Test
    void pairsDoubleChestAcrossChunkBoundary() {
        BlockData westHalf = legacy(15, 64, 0, 54, 2); // facing north
        BlockData eastHalf = legacy(16, 64, 0, 54, 2);

        assertEquals(1, LegacyStateResolver.resolve(List.of(westHalf, eastHalf)));
        assertEquals("left", westHalf.properties.get("type"));
        assertEquals("right", eastHalf.properties.get("type"));
    }

    @Test
    void keepsNormalAndTrappedChestsSeparate() {
        BlockData normal = legacy(0, 64, 0, 54, 2);
        BlockData trapped = legacy(1, 64, 0, 146, 2);

        assertEquals(0, LegacyStateResolver.resolve(List.of(normal, trapped)));
        assertEquals("single", normal.properties.get("type"));
        assertEquals("single", trapped.properties.get("type"));
    }

    @Test
    void ambiguousTripleChestFailsClosed() {
        BlockData west = legacy(-1, 64, 0, 54, 2);
        BlockData middle = legacy(0, 64, 0, 54, 2);
        BlockData east = legacy(1, 64, 0, 54, 2);

        assertEquals(0, LegacyStateResolver.resolve(List.of(west, middle, east)));
        assertEquals("single", west.properties.get("type"));
        assertEquals("single", middle.properties.get("type"));
        assertEquals("single", east.properties.get("type"));
    }

    @Test
    void laterGlobalPassDoesNotForgetRecoveredDoorHalfState() {
        BlockData lower = legacy(0, 64, 0, 64, 5);
        BlockData upper = legacy(0, 65, 0, 64, 9);
        LegacyStateResolver.resolve(List.of(lower, upper));
        assertEquals("left", lower.properties.get("hinge"));

        // Simulate a later selection filter that keeps only the lower half.
        LegacyStateResolver.resolve(List.of(lower));
        assertEquals("south", lower.properties.get("facing"));
        assertEquals("true", lower.properties.get("open"));
        assertEquals("left", lower.properties.get("hinge"));
        assertEquals("false", lower.properties.get("powered"));
    }

    private static BlockData legacy(int x, int y, int z, int id, int data) {
        LegacyBlockIds.DecodedBlock decoded = LegacyBlockIds.decode(id, data);
        return new BlockData(x, y, z, decoded.blockId(), decoded.properties());
    }
}
