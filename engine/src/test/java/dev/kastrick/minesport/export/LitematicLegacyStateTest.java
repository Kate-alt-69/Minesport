package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.region.LegacyBlockIds;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitematicLegacyStateTest {
    @Test
    void resolvesLegacyNeighboursAndDoesNotSerializeInternalMetadata() throws Exception {
        BlockData westChest = legacy(15, 64, 0, 54, 2);
        BlockData eastChest = legacy(16, 64, 0, 54, 2);
        BlockData grass = legacy(15, 64, 1, 2, 0);
        BlockData snow = legacy(15, 65, 1, 78, 0);
        File output = Files.createTempFile("minesport-legacy-state-", ".litematic").toFile();

        try {
            LitematicExporter.export(
                List.of(westChest, eastChest, grass, snow),
                15, 64, 0,
                16, 65, 1,
                "Legacy state test",
                "Minesport",
                "",
                0,
                output
            );

            assertEquals("left", westChest.properties.get("type"));
            assertEquals("right", eastChest.properties.get("type"));
            assertEquals("true", grass.properties.get("snowy"));

            String rawNbt = gunzipAsSingleByteText(output);
            assertFalse(rawNbt.contains("legacy_id"));
            assertFalse(rawNbt.contains("legacy_data"));
            assertTrue(rawNbt.contains("facing"));
            assertTrue(rawNbt.contains("type"));
            assertTrue(rawNbt.contains("snowy"));
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }

    private static String gunzipAsSingleByteText(File file) throws Exception {
        try (
            GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(file.toPath()));
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            gzip.transferTo(output);
            return output.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static BlockData legacy(int x, int y, int z, int id, int data) {
        LegacyBlockIds.DecodedBlock decoded = LegacyBlockIds.decode(id, data);
        return new BlockData(x, y, z, decoded.blockId(), decoded.properties());
    }
}
