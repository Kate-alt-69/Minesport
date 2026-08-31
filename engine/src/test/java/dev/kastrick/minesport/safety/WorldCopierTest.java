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
        Path entities = world.resolve("dimensions/minecraft/overworld/entities");
        Files.createDirectories(modern);
        Files.createDirectories(legacy);
        Files.createDirectories(nether);
        Files.createDirectories(entities);
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("level.dat_old"), "old-level");
        Files.writeString(modern.resolve("r.0.0.mca"), "modern");
        Files.writeString(legacy.resolve("r.0.0.mca"), "stale");
        Files.writeString(legacy.resolve("r.9.9.mca"), "stale-only");
        Files.writeString(nether.resolve("r.0.0.mca"), "nether");
        Files.writeString(entities.resolve("r.0.0.mca"), "entities");

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
            assertFalse(Files.exists(copied.toPath().resolve("entities")));

            assertTrue(WorldCopier.copyOverworldEntitiesToTemp(
                world.toFile(),
                copied,
                null
            ));
            assertEquals(
                "entities",
                Files.readString(copied.toPath().resolve("entities").resolve("r.0.0.mca"))
            );
        } finally {
            WorldCopier.cleanupTemp(copied);
        }
    }

    @Test
    void snapshotDirectoriesAreUniqueDirectChildrenOfSystemTemp() throws Exception {
        Path world = temp.resolve("same-world");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(region.resolve("r.0.0.mca"), "region");

        File first = WorldCopier.copyToTemp(world.toFile(), null);
        File second = WorldCopier.copyToTemp(world.toFile(), null);
        try {
            assertNotEquals(first.getCanonicalFile(), second.getCanonicalFile());
            Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            assertEquals(systemTemp, first.toPath().getParent().toRealPath());
            assertEquals(systemTemp, second.toPath().getParent().toRealPath());
            assertTrue(first.getName().startsWith("minesport_same-world_"));
            assertTrue(second.getName().startsWith("minesport_same-world_"));
        } finally {
            WorldCopier.cleanupTemp(first);
            WorldCopier.cleanupTemp(second);
        }
    }

    @Test
    void cleanupRefusesNestedForeignDirectoryEvenWithMinesportName() throws Exception {
        Path foreign = temp.resolve("minesport_foreign");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("keep.txt"), "keep");

        WorldCopier.cleanupTemp(foreign.toFile());

        assertTrue(Files.exists(foreign.resolve("keep.txt")));
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
