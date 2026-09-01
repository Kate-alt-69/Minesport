package dev.kastrick.minesport.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
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
    void boundedSnapshotCopiesOnlyIntersectingRegionSectors() throws Exception {
        Path world = temp.resolve("bounded-world");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        Files.writeString(world.resolve("level.dat"), "level");
        Path source = region.resolve("r.0.0.mca");
        writeSyntheticRegion(source);

        File copied = WorldCopier.copyToTemp(
            world.toFile(),
            0, 0,
            15, 15,
            null
        );
        try {
            byte[] snapshot = Files.readAllBytes(
                copied.toPath().resolve("region").resolve("r.0.0.mca")
            );
            assertEquals(3 * 4096, snapshot.length);
            assertEquals(2, locationOffset(snapshot, 0));
            assertEquals(1, locationCount(snapshot, 0));
            assertEquals(0, locationOffset(snapshot, 1023));
            assertEquals(0, locationCount(snapshot, 1023));
            assertEquals((byte)0x11, snapshot[2 * 4096]);

            byte[] original = Files.readAllBytes(source);
            assertEquals(4 * 4096, original.length);
            assertEquals(3, locationOffset(original, 1023));
            assertEquals((byte)0x22, original[3 * 4096]);
        } finally {
            WorldCopier.cleanupTemp(copied);
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

    private static void writeSyntheticRegion(Path path) throws IOException {
        byte[] bytes = new byte[4 * 4096];
        setLocation(bytes, 0, 2, 1);
        setLocation(bytes, 1023, 3, 1);
        Arrays.fill(bytes, 2 * 4096, 3 * 4096, (byte)0x11);
        Arrays.fill(bytes, 3 * 4096, 4 * 4096, (byte)0x22);
        Files.write(path, bytes);
    }

    private static void setLocation(byte[] region, int index, int sector, int count) {
        int offset = index * 4;
        region[offset] = (byte)((sector >>> 16) & 0xFF);
        region[offset + 1] = (byte)((sector >>> 8) & 0xFF);
        region[offset + 2] = (byte)(sector & 0xFF);
        region[offset + 3] = (byte)count;
    }

    private static int locationOffset(byte[] region, int index) {
        int offset = index * 4;
        return ((region[offset] & 0xFF) << 16)
            | ((region[offset + 1] & 0xFF) << 8)
            | (region[offset + 2] & 0xFF);
    }

    private static int locationCount(byte[] region, int index) {
        return region[index * 4 + 3] & 0xFF;
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
