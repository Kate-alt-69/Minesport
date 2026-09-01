package dev.kastrick.minesport.safety;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/**
 * Copies the minimum safe Minecraft world data Minesport needs into a temp
 * directory before processing. The original world is never modified.
 */
public class WorldCopier {
    private static final int SECTOR_SIZE = 4096;
    private static final int REGION_HEADER_BYTES = SECTOR_SIZE * 2;

    private static final List<String> OVERWORLD_REGION_PATHS = List.of(
        "dimensions" + File.separator + "minecraft" + File.separator + "overworld" + File.separator + "region",
        "region"
    );
    private static final List<String> OVERWORLD_ENTITY_PATHS = List.of(
        "dimensions" + File.separator + "minecraft" + File.separator + "overworld" + File.separator + "entities",
        "entities"
    );

    public static File copyToTemp(File worldFolder, Consumer<String> statusCallback) throws IOException {
        return copyToTemp(
            worldFolder,
            Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE,
            statusCallback
        );
    }

    public static File copyToTemp(
        File worldFolder,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> statusCallback
    ) throws IOException {
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        File tempDir = createTempSnapshotDirectory(worldFolder);

        if (statusCallback != null) {
            statusCallback.accept("Preparing selected world data");
        }

        try {
            copyLevelMetadata(worldFolder, tempDir, statusCallback);
            copyPreferredOverworldRegion(
                worldFolder,
                tempDir,
                minX, minZ,
                maxX, maxZ,
                statusCallback
            );
        } catch (IOException exception) {
            cleanupTemp(tempDir);
            throw exception;
        }

        if (statusCallback != null) {
            statusCallback.accept("Selected world data ready");
        }
        return tempDir;
    }

    private static File createTempSnapshotDirectory(File worldFolder) throws IOException {
        Path tempRoot = systemTempRoot();
        String worldName = worldFolder.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (worldName.isBlank()) worldName = "world";
        return Files.createTempDirectory(tempRoot, "minesport_" + worldName + "_").toFile();
    }

    private static Path systemTempRoot() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root.toRealPath();
    }

    public static File findOverworldRegionDir(File worldFolder) throws IOException {
        List<String> checked = new ArrayList<>();
        for (String relativePath : OVERWORLD_REGION_PATHS) {
            File candidate = new File(worldFolder, relativePath);
            checked.add(candidate.getAbsolutePath());
            if (hasRegionFiles(candidate)) {
                return candidate;
            }
        }
        throw new IOException(
            "No Overworld region files found; checked: " + String.join(", ", checked)
        );
    }

    public static boolean copyOverworldEntitiesToTemp(
        File worldFolder,
        File tempDir,
        Consumer<String> log
    ) throws IOException {
        return copyOverworldEntitiesToTemp(
            worldFolder,
            tempDir,
            Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE,
            log
        );
    }

    public static boolean copyOverworldEntitiesToTemp(
        File worldFolder,
        File tempDir,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> log
    ) throws IOException {
        File source = null;
        for (String relativePath : OVERWORLD_ENTITY_PATHS) {
            File candidate = new File(worldFolder, relativePath);
            if (hasRegionFiles(candidate)) {
                source = candidate;
                break;
            }
        }
        if (source == null) return false;

        File[] files = selectedRegionFiles(source, minX, minZ, maxX, maxZ);
        if (files.length == 0) return false;

        Path destination = tempDir.toPath().resolve("entities");
        Files.createDirectories(destination);
        for (File file : files) {
            copySelectedRegionSnapshot(
                file.toPath(),
                destination.resolve(file.getName()),
                minX, minZ,
                maxX, maxZ,
                log,
                "entities/" + file.getName()
            );
        }
        int externalCount = copySelectedExternalChunks(
            source,
            destination,
            minX, minZ,
            maxX, maxZ,
            log,
            "entities/"
        );
        if (log != null) {
            log.accept("Selected entity regions: " + files.length
                + (externalCount > 0 ? " · external chunks: " + externalCount : ""));
        }
        return true;
    }

    private static boolean hasRegionFiles(File directory) {
        if (!directory.isDirectory()) return false;
        File[] files = directory.listFiles(file ->
            file.isFile() && isRegionFile(file.getName())
        );
        return files != null && files.length > 0;
    }

    private static boolean isRegionFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mca") || lower.endsWith(".mcr");
    }

    private static File[] selectedRegionFiles(
        File directory,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        File[] files = directory.listFiles(file ->
            file.isFile()
                && isRegionFile(file.getName())
                && regionIntersects(file.getName(), minX, minZ, maxX, maxZ)
        );
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static boolean regionIntersects(
        String name,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        String[] parts = name.split("\\.");
        if (parts.length < 4 || !"r".equals(parts[0])) return false;
        try {
            long regionX = Long.parseLong(parts[1]);
            long regionZ = Long.parseLong(parts[2]);
            long regionMinX = regionX * 512L;
            long regionMinZ = regionZ * 512L;
            long regionMaxX = regionMinX + 511L;
            long regionMaxZ = regionMinZ + 511L;
            long selectionMinX = Math.min((long) minX, (long) maxX);
            long selectionMaxX = Math.max((long) minX, (long) maxX);
            long selectionMinZ = Math.min((long) minZ, (long) maxZ);
            long selectionMaxZ = Math.max((long) minZ, (long) maxZ);
            return regionMaxX >= selectionMinX && regionMinX <= selectionMaxX
                && regionMaxZ >= selectionMinZ && regionMinZ <= selectionMaxZ;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int copySelectedExternalChunks(
        File source,
        Path destination,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> log,
        String labelPrefix
    ) throws IOException {
        File[] external = source.listFiles(file ->
            file.isFile() && externalChunkIntersects(file.getName(), minX, minZ, maxX, maxZ)
        );
        if (external == null || external.length == 0) return 0;
        Arrays.sort(external, Comparator.comparing(File::getName));
        for (File file : external) {
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                labelPrefix + file.getName()
            );
        }
        return external.length;
    }

    private static boolean externalChunkIntersects(
        String name,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("c.") || !lower.endsWith(".mcc")) return false;
        String[] parts = name.split("\\.");
        if (parts.length != 4) return false;
        try {
            long chunkX = Long.parseLong(parts[1]);
            long chunkZ = Long.parseLong(parts[2]);
            long chunkMinX = chunkX * 16L;
            long chunkMinZ = chunkZ * 16L;
            long chunkMaxX = chunkMinX + 15L;
            long chunkMaxZ = chunkMinZ + 15L;
            long selectionMinX = Math.min((long) minX, (long) maxX);
            long selectionMaxX = Math.max((long) minX, (long) maxX);
            long selectionMinZ = Math.min((long) minZ, (long) maxZ);
            long selectionMaxZ = Math.max((long) minZ, (long) maxZ);
            return chunkMaxX >= selectionMinX && chunkMinX <= selectionMaxX
                && chunkMaxZ >= selectionMinZ && chunkMinZ <= selectionMaxZ;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void copyLevelMetadata(
        File worldFolder,
        File tempDir,
        Consumer<String> log
    ) throws IOException {
        File level = new File(worldFolder, "level.dat");
        if (!level.isFile()) {
            throw new IOException("World is missing level.dat: " + worldFolder);
        }
        copyFile(level.toPath(), tempDir.toPath().resolve("level.dat"), log, "level.dat");

        File old = new File(worldFolder, "level.dat_old");
        if (old.isFile()) {
            copyFile(old.toPath(), tempDir.toPath().resolve("level.dat_old"), log, "level.dat_old");
        }
    }

    private static void copyPreferredOverworldRegion(
        File worldFolder,
        File tempDir,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> log
    ) throws IOException {
        File sourceRegion = findOverworldRegionDir(worldFolder);
        File[] files = selectedRegionFiles(sourceRegion, minX, minZ, maxX, maxZ);
        if (files.length == 0) {
            throw new IOException(
                "No Overworld region files intersect selected X/Z bounds: "
                    + minX + ".." + maxX + ", " + minZ + ".." + maxZ
            );
        }

        Path destination = tempDir.toPath().resolve("region");
        Files.createDirectories(destination);
        for (File file : files) {
            String destinationName = normalizedBlockRegionName(file.getName());
            copySelectedRegionSnapshot(
                file.toPath(),
                destination.resolve(destinationName),
                minX, minZ,
                maxX, maxZ,
                log,
                "region/" + destinationName
            );
        }
        int externalCount = copySelectedExternalChunks(
            sourceRegion,
            destination,
            minX, minZ,
            maxX, maxZ,
            log,
            "region/"
        );

        if (log != null) {
            log.accept("Selected block regions: " + files.length
                + (externalCount > 0 ? " · external chunks: " + externalCount : ""));
        }
    }

    /**
     * The production IPC scanner historically selects temp block regions by
     * .mca suffix. Legacy .mcr data has the same region coordinates and is
     * parsed by RegionReader based on contents, so normalize only the private
     * temporary filename while leaving the original save untouched.
     */
    private static String normalizedBlockRegionName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".mcr")) return name;
        return name.substring(0, name.length() - 4) + ".mca";
    }

    private static void copySelectedRegionSnapshot(
        Path source,
        Path destination,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> log,
        String label
    ) throws IOException {
        long[] coordinates = regionCoordinates(source.getFileName().toString());
        if (coordinates == null || selectionCoversWholeRegion(coordinates[0], coordinates[1], minX, minZ, maxX, maxZ)) {
            copyFile(source, destination, log, label);
            return;
        }

        try (var input = new RandomAccessFile(source.toFile(), "r")) {
            if (input.length() < REGION_HEADER_BYTES) {
                // Preserve the historical copy behavior for malformed/legacy
                // inputs. RegionReader will make the final validity decision.
                copyFile(source, destination, log, label);
                return;
            }

            byte[] sourceHeader = new byte[REGION_HEADER_BYTES];
            input.readFully(sourceHeader);
            byte[] snapshotHeader = new byte[REGION_HEADER_BYTES];
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());

            int nextSector = 2;
            int keptChunks = 0;
            byte[] buffer = new byte[64 * 1024];
            try (var output = new RandomAccessFile(destination.toFile(), "rw")) {
                output.setLength(REGION_HEADER_BYTES);

                for (int index = 0; index < 1024; index++) {
                    int localChunkX = index % 32;
                    int localChunkZ = index / 32;
                    long worldChunkX = coordinates[0] * 32L + localChunkX;
                    long worldChunkZ = coordinates[1] * 32L + localChunkZ;
                    if (!chunkIntersects(worldChunkX, worldChunkZ, minX, minZ, maxX, maxZ)) continue;

                    int locationIndex = index * 4;
                    int sourceSector = ((sourceHeader[locationIndex] & 0xFF) << 16)
                        | ((sourceHeader[locationIndex + 1] & 0xFF) << 8)
                        | (sourceHeader[locationIndex + 2] & 0xFF);
                    int sectorCount = sourceHeader[locationIndex + 3] & 0xFF;
                    if (sourceSector == 0 || sectorCount == 0) continue;

                    long sourceStart = (long)sourceSector * SECTOR_SIZE;
                    long bytesToCopy = (long)sectorCount * SECTOR_SIZE;
                    long sourceEnd = sourceStart + bytesToCopy;
                    if (sourceStart < REGION_HEADER_BYTES || sourceEnd > input.length()) continue;

                    output.seek((long)nextSector * SECTOR_SIZE);
                    input.seek(sourceStart);
                    long remaining = bytesToCopy;
                    while (remaining > 0L) {
                        int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if (read < 0) throw new EOFException("Region sector ended unexpectedly");
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }

                    snapshotHeader[locationIndex] = (byte)((nextSector >>> 16) & 0xFF);
                    snapshotHeader[locationIndex + 1] = (byte)((nextSector >>> 8) & 0xFF);
                    snapshotHeader[locationIndex + 2] = (byte)(nextSector & 0xFF);
                    snapshotHeader[locationIndex + 3] = (byte)sectorCount;
                    int timestampIndex = SECTOR_SIZE + locationIndex;
                    System.arraycopy(sourceHeader, timestampIndex, snapshotHeader, timestampIndex, 4);

                    nextSector += sectorCount;
                    keptChunks++;
                }

                output.seek(0L);
                output.write(snapshotHeader);
                output.setLength((long)nextSector * SECTOR_SIZE);
            }

            Files.setLastModifiedTime(destination, Files.getLastModifiedTime(source));
            if (log != null) {
                log.accept("Snapshot: " + label + " · " + keptChunks + " selected chunk(s)");
            }
        }
    }

    private static long[] regionCoordinates(String name) {
        String[] parts = name.split("\\.");
        if (parts.length < 4 || !"r".equals(parts[0])) return null;
        try {
            return new long[] {
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2])
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean selectionCoversWholeRegion(
        long regionX, long regionZ,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        long selectionMinX = Math.min((long)minX, (long)maxX);
        long selectionMaxX = Math.max((long)minX, (long)maxX);
        long selectionMinZ = Math.min((long)minZ, (long)maxZ);
        long selectionMaxZ = Math.max((long)minZ, (long)maxZ);
        long regionMinX = regionX * 512L;
        long regionMinZ = regionZ * 512L;
        return selectionMinX <= regionMinX
            && selectionMaxX >= regionMinX + 511L
            && selectionMinZ <= regionMinZ
            && selectionMaxZ >= regionMinZ + 511L;
    }

    private static boolean chunkIntersects(
        long chunkX, long chunkZ,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        long chunkMinX = chunkX * 16L;
        long chunkMinZ = chunkZ * 16L;
        long chunkMaxX = chunkMinX + 15L;
        long chunkMaxZ = chunkMinZ + 15L;
        long selectionMinX = Math.min((long)minX, (long)maxX);
        long selectionMaxX = Math.max((long)minX, (long)maxX);
        long selectionMinZ = Math.min((long)minZ, (long)maxZ);
        long selectionMaxZ = Math.max((long)minZ, (long)maxZ);
        return chunkMaxX >= selectionMinX && chunkMinX <= selectionMaxX
            && chunkMaxZ >= selectionMinZ && chunkMinZ <= selectionMaxZ;
    }

    private static void copyFile(Path source, Path destination, Consumer<String> log, String label)
        throws IOException {
        Files.copy(
            source,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
        );
        if (log != null) log.accept("Copied: " + label);
    }

    public static void cleanupTemp(File tempDir) {
        if (tempDir == null || !tempDir.exists()) return;

        Path candidate = tempDir.toPath().toAbsolutePath().normalize();
        try {
            Path parent = candidate.getParent();
            if (parent == null || !parent.toRealPath().equals(systemTempRoot())) return;
            Path fileName = candidate.getFileName();
            if (fileName == null || !fileName.toString().startsWith("minesport_")) return;
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) return;

            Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[WARN] Could not fully clean temp dir: " + e.getMessage());
        }
    }
}
