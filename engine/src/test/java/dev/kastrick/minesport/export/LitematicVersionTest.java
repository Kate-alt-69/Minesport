package dev.kastrick.minesport.export;

import dev.kastrick.minesport.nbt.NbtCompound;
import dev.kastrick.minesport.nbt.NbtReader;
import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LitematicVersionTest {
    @Test
    void usesVersionSixThroughMinecraft1204() throws Exception {
        var output = Files.createTempFile("minesport-litematic-v6-", ".litematic").toFile();
        try {
            LitematicExporter.export(
                List.of(new BlockData(0, 0, 0, "minecraft:stone", Map.of())),
                0, 0, 0,
                0, 0, 0,
                "v6 boundary",
                "Minesport",
                "unit test",
                3700,
                output
            );

            NbtCompound root = NbtReader.readGzip(output);
            assertEquals(6, root.getInt("Version"));
            assertEquals(1, root.getInt("SubVersion"));
            assertEquals(3700, root.getInt("MinecraftDataVersion"));
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }

    @Test
    void usesVersionSevenAfterMinecraft1204() throws Exception {
        var output = Files.createTempFile("minesport-litematic-v7-", ".litematic").toFile();
        try {
            LitematicExporter.export(
                List.of(new BlockData(0, 0, 0, "minecraft:stone", Map.of())),
                0, 0, 0,
                0, 0, 0,
                "v7 boundary",
                "Minesport",
                "unit test",
                3701,
                output
            );

            NbtCompound root = NbtReader.readGzip(output);
            assertEquals(7, root.getInt("Version"));
            assertEquals(1, root.getInt("SubVersion"));
            assertEquals(3701, root.getInt("MinecraftDataVersion"));
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }
}
