package dev.kastrick.minesport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcModeRegionFileTest {
    @Test
    void acceptsAnvilAndLegacyMcRegionContainers() {
        assertTrue(IpcMode.isRegionFileName("r.0.0.mca"));
        assertTrue(IpcMode.isRegionFileName("r.-2.7.mcr"));
        assertTrue(IpcMode.isRegionFileName("R.1.1.MCR"));
    }

    @Test
    void rejectsUnrelatedFiles() {
        assertFalse(IpcMode.isRegionFileName("level.dat"));
        assertFalse(IpcMode.isRegionFileName("r.0.0.mca.part"));
        assertFalse(IpcMode.isRegionFileName(null));
    }
}
