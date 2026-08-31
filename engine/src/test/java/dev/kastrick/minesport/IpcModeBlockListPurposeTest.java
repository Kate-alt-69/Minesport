package dev.kastrick.minesport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcModeBlockListPurposeTest {
    @Test
    void preflightSkipsPreviewAssets() {
        assertFalse(IpcMode.listBlocksNeedsPreviewAssets("preflight"));
        assertFalse(IpcMode.listBlocksNeedsPreviewAssets("  PreFlight  "));
    }

    @Test
    void previewAndLegacyRequestsKeepPreviewAssets() {
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets("preview"));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets(""));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets(null));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets("legacy-client"));
    }
}
