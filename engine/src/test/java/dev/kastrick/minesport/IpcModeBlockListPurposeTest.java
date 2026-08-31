package dev.kastrick.minesport;

import com.google.gson.stream.JsonWriter;
import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcModeBlockListPurposeTest {
    @Test
    void preflightSkipsPreviewAssets() {
        assertFalse(IpcMode.listBlocksNeedsPreviewAssets("preflight"));
        assertFalse(IpcMode.listBlocksNeedsPreviewAssets("  PreFlight  "));
    }

    @Test
    void preflightWriterStreamsIdOnlyAndPreservesSelectionFiltering() throws Exception {
        StringWriter output = new StringWriter();
        int count;
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.beginArray();
            count = IpcMode.writePreflightBlockIds(
                writer,
                List.of(
                    new BlockData(0, 64, 0, "minecraft:stone", Map.of()),
                    new BlockData(1, 64, 0, "minecraft:light", Map.of()),
                    new BlockData(20, 64, 0, "minecraft:dirt", Map.of())
                ),
                0, 64, 0,
                2, 2, 2
            );
            writer.endArray();
        }
        assertEquals(1, count);
        assertEquals("[{\"id\":\"minecraft:stone\"}]", output.toString());
    }

    @Test
    void previewAndLegacyRequestsKeepPreviewAssets() {
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets("preview"));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets(""));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets(null));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets("legacy-client"));
    }
}
