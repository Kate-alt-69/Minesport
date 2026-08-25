package dev.kastrick.minesport.export;

import dev.kastrick.minesport.nbt.NbtCompound;
import dev.kastrick.minesport.nbt.NbtReader;
import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LitematicExporterTest {
    @Test
    void writesVersionSixSingleRegionWithDenseBitPacking() throws Exception {
        var blocks = new ArrayList<BlockData>();
        blocks.add(new BlockData(0, 0, 0, "minecraft:stone", Map.of()));
        blocks.add(new BlockData(1, 0, 0, "minecraft:dirt", Map.of()));
        blocks.add(new BlockData(2, 0, 0, "minecraft:oak_planks", Map.of()));
        blocks.add(new BlockData(3, 0, 0, "minecraft:glass", Map.of()));
        blocks.add(new BlockData(
            21, 0, 0,
            "minecraft:oak_stairs",
            Map.of("half", "bottom", "facing", "east")
        ));

        var output = Files.createTempFile("minesport-", ".litematic").toFile();
        try {
            var stats = LitematicExporter.export(
                blocks,
                0, 0, 0,
                63, 0, 0,
                "Dense Test",
                "Minesport",
                "unit test",
                4189,
                output
            );

            assertEquals(5, stats.blockCount());
            assertEquals(6, stats.paletteSize()); // air + five states
            assertEquals(64, stats.volume());

            NbtCompound root = NbtReader.readGzip(output);
            assertEquals(6, root.getInt("Version"));
            assertEquals(1, root.getInt("SubVersion"));
            assertEquals(4189, root.getInt("MinecraftDataVersion"));

            NbtCompound metadata = root.getCompound("Metadata");
            assertEquals("Dense Test", metadata.getString("Name"));
            assertEquals(5, metadata.getInt("TotalBlocks"));
            assertEquals(64, metadata.getInt("TotalVolume"));
            assertEquals(64, metadata.getCompound("EnclosingSize").getInt("x"));

            NbtCompound region = root.getCompound("Regions").getCompound("Dense Test");
            assertEquals(64, region.getCompound("Size").getInt("x"));
            assertEquals(6, region.getList("BlockStatePalette").size());

            long[] states = region.getLongArray("BlockStates");
            // 64 entries * 3 bits = exactly 3 longs. A padded vanilla-style
            // array would need 4 longs (21 entries per long).
            assertEquals(3, states.length);
            assertEquals(5, readDense(states, 21, 3));

            NbtCompound stairs = (NbtCompound) region.getList("BlockStatePalette").get(5);
            assertEquals("minecraft:oak_stairs", stairs.getString("Name"));
            assertEquals("east", stairs.getCompound("Properties").getString("facing"));
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }

    private static int readDense(long[] words, int index, int bits) {
        long mask = (1L << bits) - 1L;
        long bitIndex = (long) index * bits;
        int wordIndex = (int) (bitIndex >>> 6);
        int bitOffset = (int) (bitIndex & 63L);
        long value = words[wordIndex] >>> bitOffset;
        if (bitOffset + bits > 64) {
            value |= words[wordIndex + 1] << (64 - bitOffset);
        }
        return (int) (value & mask);
    }
}
