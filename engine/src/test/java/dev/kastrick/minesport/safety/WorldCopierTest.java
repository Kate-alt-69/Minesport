package dev.kastrick.minesport.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldCopierTest {
    @TempDir
    Path temp;

    @Test
    void copiesLegacyOverworldIntoEngineRegionRoot() throws Exception {
        Path world = temp.resolve("legacy-world");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(region.resolve("r.0.0.mca"), "legacy");

        File copied = WorldCopier.copyToTemp(world.toFile(), null);
        try {
            assertEquals(
                "legacy",
                Files.readString(copied.toPath().resolve("region").resolve("r.0.0.mca"))
            );
            assertEquals("level", Files.readString(copied.toPath().resolve("level.dat")));
        } finally {
            WorldCopier.cleanupTemp(copied);
        }
    }

    @Test
    void modernOverworldWinsWithoutCopyingRedundantDimensionTrees() throws Exception {
        Path world = temp.resolve("modern-world");
        Path modern = world.resolve("dimensions/minecraft/overworld/region");
        Path legacy = world.resolve("region");
        Path nether = world.resolve("dimensions/minecraft/the_nether/region");
        Files.createDirectories(modern);
        Files.createDirectories(legacy);
        Files.createDirectories(nether);
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("level.dat_old"), "old-level");
        Files.writeString(modern.resolve("r.0.0.mca"), "modern");
        Files.writeString(legacy.resolve("r.0.0.mca"), "stale");
        Files.writeString(legacy.resolve("r.9.9.mca"), "stale-only");
        Files.writeString(nether.resolve("r.0.0.mca"), "nether");

        assertEquals(
            modern.toFile().getCanonicalFile(),
            WorldCopier.findOverworldRegionDir(world.toFile()).getCanonicalFile()
        );

        File copied = WorldCopier.copyToTemp(world.toFile(), null);
        try {
            Path normalized = copied.toPath().resolve("region");
            assertEquals("modern", Files.readString(normalized.resolve("r.0.0.mca")));
            assertFalse(Files.exists(normalized.resolve("r.9.9.mca")));
            assertEquals("old-level", Files.readString(copied.toPath().resolve("level.dat_old")));

            // Minesport's readers consume only temp/region. Keeping duplicate
            // source-layout dimensions would waste disk I/O and temp space.
            assertFalse(Files.exists(copied.toPath().resolve("dimensions")));
        } finally {
            WorldCopier.cleanupTemp(copied);
        }
    }

    @Test
    void rejectsWorldWithoutLevelMetadata() throws Exception {
        Path world = temp.resolve("missing-level");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        Files.writeString(region.resolve("r.0.0.mca"), "region");

        IOException error = assertThrows(
            IOException.class,
            () -> WorldCopier.copyToTemp(world.toFile(), null)
        );
        assertTrue(error.getMessage().contains("level.dat"));
    }
}
