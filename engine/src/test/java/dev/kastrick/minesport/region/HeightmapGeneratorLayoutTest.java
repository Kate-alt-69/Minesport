package dev.kastrick.minesport.region;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class HeightmapGeneratorLayoutTest {
    @Test
    void keepsRequestedScaleForNormalWorldSpan() throws Exception {
        HeightmapGenerator.HeightmapLayout layout = HeightmapGenerator.chooseLayout(
            -1, -2, 1, 0, 4
        );
        assertEquals(4, layout.scale());
        assertEquals(128, layout.regionPx());
        assertEquals(384, layout.width());
        assertEquals(384, layout.height());
        assertEquals(-512, layout.minX());
        assertEquals(-1024, layout.minZ());
        assertEquals(1024, layout.maxX());
        assertEquals(512, layout.maxZ());
    }

    @Test
    void automaticallyCoarsensSparseLargeWorldsBeforeAllocation() throws Exception {
        HeightmapGenerator.HeightmapLayout layout = HeightmapGenerator.chooseLayout(
            0, 0, 127, 127, 1
        );
        assertEquals(16, layout.scale());
        assertEquals(4096, layout.width());
        assertEquals(4096, layout.height());
        assertTrue(
            (long)layout.width() * layout.height() <= HeightmapGenerator.MAX_HEIGHTMAP_PIXELS
        );
    }

    @Test
    void rejectsRegionCoordinatesThatCannotBeRepresentedByDesktopProtocol() {
        IOException error = assertThrows(
            IOException.class,
            () -> HeightmapGenerator.chooseLayout(
                Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0, 1
            )
        );
        assertTrue(error.getMessage().contains("supported world bounds"));
    }

    @Test
    void recognizesLegacyAndAnvilRegionFiles() {
        assertTrue(HeightmapGenerator.isRegionFile("r.-2.7.mca"));
        assertTrue(HeightmapGenerator.isRegionFile("r.-2.7.mcr"));
        assertFalse(HeightmapGenerator.isRegionFile("r.-2.7.tmp"));
    }
}
