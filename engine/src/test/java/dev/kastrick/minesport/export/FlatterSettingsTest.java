package dev.kastrick.minesport.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlatterSettingsTest {
    @Test
    void acceptedCellSizesRemainStable() {
        assertEquals(8, FlatterSettings.normalizeCellSize(8));
        assertEquals(16, FlatterSettings.normalizeCellSize(16));
        assertEquals(32, FlatterSettings.normalizeCellSize(32));
        assertEquals(64, FlatterSettings.normalizeCellSize(64));
    }

    @Test
    void invalidCellSizesFallBackToBalancedDefault() {
        assertEquals(FlatterSettings.DEFAULT_CELL_SIZE, FlatterSettings.normalizeCellSize(0));
        assertEquals(FlatterSettings.DEFAULT_CELL_SIZE, FlatterSettings.normalizeCellSize(13));
        assertEquals(FlatterSettings.DEFAULT_CELL_SIZE, FlatterSettings.normalizeCellSize(-32));
        assertEquals(FlatterSettings.DEFAULT_CELL_SIZE, FlatterSettings.normalizeCellSize(128));
    }

    @Test
    void systemPropertyOverridesPersistedCellSize() {
        String previous = System.getProperty("minesport.flatterCellSize");
        try {
            System.setProperty("minesport.flatterCellSize", "32");
            assertEquals(32, FlatterSettings.cellSize());

            System.setProperty("minesport.flatterCellSize", "banana");
            assertEquals(FlatterSettings.DEFAULT_CELL_SIZE, FlatterSettings.cellSize());
        } finally {
            if (previous == null) System.clearProperty("minesport.flatterCellSize");
            else System.setProperty("minesport.flatterCellSize", previous);
        }
    }
}
