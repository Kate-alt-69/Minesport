package dev.kastrick.minesport.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyStairShapeResolverTest {
    @Test
    void resolvesOuterCornersFromTheFrontPerpendicularStair() {
        BlockData centerLeft = stair(0, 64, 0, 53, 0);   // east
        BlockData frontLeft = stair(1, 64, 0, 67, 3);   // north
        LegacyStateResolver.resolve(List.of(centerLeft, frontLeft));
        assertEquals("outer_left", centerLeft.prop("shape"));

        BlockData centerRight = stair(10, 64, 0, 53, 0); // east
        BlockData frontRight = stair(11, 64, 0, 67, 2); // south
        LegacyStateResolver.resolve(List.of(centerRight, frontRight));
        assertEquals("outer_right", centerRight.prop("shape"));
    }

    @Test
    void resolvesInnerCornersFromTheRearPerpendicularStair() {
        BlockData centerLeft = stair(0, 64, 0, 53, 0);    // east
        BlockData rearLeft = stair(-1, 64, 0, 108, 3);   // north
        LegacyStateResolver.resolve(List.of(centerLeft, rearLeft));
        assertEquals("inner_left", centerLeft.prop("shape"));

        BlockData centerRight = stair(10, 64, 0, 53, 0);  // east
        BlockData rearRight = stair(9, 64, 0, 108, 2);   // south
        LegacyStateResolver.resolve(List.of(centerRight, rearRight));
        assertEquals("inner_right", centerRight.prop("shape"));
    }

    @Test
    void doesNotCornerAcrossDifferentStairHalves() {
        BlockData bottom = stair(0, 64, 0, 53, 0);       // east, bottom
        BlockData topFront = stair(1, 64, 0, 67, 7);     // north, top
        LegacyStateResolver.resolve(List.of(bottom, topFront));
        assertEquals("straight", bottom.prop("shape"));
    }

    @Test
    void sideGuardPreventsFalseOuterCornerInTwoByTwoLayout() {
        BlockData center = stair(0, 64, 0, 53, 0);       // east
        BlockData front = stair(1, 64, 0, 67, 3);       // north
        BlockData blockingSide = stair(0, 64, 1, 128, 0); // east, same half

        LegacyStateResolver.resolve(List.of(center, front, blockingSide));
        assertEquals("straight", center.prop("shape"));
    }

    @Test
    void cornerShapeWorksAcrossDifferentLegacyStairMaterials() {
        BlockData oak = stair(0, 64, 0, 53, 0);
        BlockData quartz = stair(1, 64, 0, 156, 3);

        LegacyStateResolver.resolve(List.of(oak, quartz));
        assertEquals("outer_left", oak.prop("shape"));
    }

    @Test
    void recoveredCornerSurvivesLaterCroppedPass() {
        BlockData center = stair(0, 64, 0, 53, 0);
        BlockData front = stair(1, 64, 0, 67, 3);
        LegacyStateResolver.resolve(List.of(center, front));
        assertEquals("outer_left", center.prop("shape"));

        LegacyStateResolver.resolve(List.of(center));
        assertEquals("outer_left", center.prop("shape"));
    }

    private static BlockData stair(int x, int y, int z, int numericId, int metadata) {
        LegacyBlockIds.DecodedBlock decoded = LegacyBlockIds.decode(numericId, metadata);
        return new BlockData(x, y, z, decoded.blockId(), decoded.properties());
    }
}
