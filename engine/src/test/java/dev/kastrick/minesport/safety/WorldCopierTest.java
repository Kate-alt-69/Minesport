package dev.kastrick.minesport.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
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
        } finally {
            WorldCopier.cleanupTemp(copied);
        }
    }

    @Test
    void modernOverworldWinsOverStaleLegacyRegion() throws Exception {
        Path world = temp.resolve("modern-world");
        Path modern = world.resolve("dimensions/minecraft/overworld/region");
        Path legacy = world.resolve("region");
        Files.createDirectories(modern);
        Files.createDirectories(legacy);
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(modern.resolve("r.0.0.mca"), "modern");
        Files.writeString(legacy.resolve("r.0.0.mca"), "stale");
        Files.writeString(legacy.resolve("r.9.9.mca"), "stale-only");

        assertEquals(
            modern.toFile().getCanonicalFile(),
            WorldCopier.findOverworldRegionDir(world.toFile()).getCanonicalFile()
        );

        File copied = WorldCopier.copyToTemp(world.toFile(), null);
        try {
            Path mirrored = copied.toPath().resolve("region");
            assertEquals("modern", Files.readString(mirrored.resolve("r.0.0.mca")));
            assertFalse(Files.exists(mirrored.resolve("r.9.9.mca")));
            assertTrue(Files.exists(copied.toPath().resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        } finally {
            WorldCopier.cleanupTemp(copied);
        }
    }
}
