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
    private static final List<String> OVERWORLD_REGION_PATHS = List.of(
        "dimensions" + File.separator + "minecraft" + File.separator + "overworld" + File.separator + "region",
        "region"
    );
    private static final List<String> OVERWORLD_ENTITY_PATHS = List.of(
        "dimensions" + File.separator + "minecraft" + File.separator + "overworld" + File.separator + "entities",
        "entities"
    );

    /**
     * Copy only level metadata and the active Overworld region into the system
     * temp directory. Engine readers consume temp/region, so copying every
     * dimension and then mirroring the Overworld again is pure duplicate I/O.
     */
    public static File copyToTemp(File worldFolder, Consumer<String> statusCallback) throws IOException {
        return copyToTemp(
            worldFolder,
            Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE,
            statusCallback
        );
    }

    /** Copy only the Overworld region files that can intersect selected X/Z bounds. */
    public static File copyToTemp(
        File worldFolder,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> statusCallback
    ) throws IOException {
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        String tempName = "minesport_" + worldFolder.getName() + "_" + System.currentTimeMillis();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), tempName);
        if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
            throw new IOException("Could not create Minesport temp directory: " + tempDir);
        }

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

    /**
     * Locate the active Overworld region directory. Modern 26.1+ storage is
     * checked first, then the legacy world/region layout.
     */
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

    /**
     * Copy modern separate entity-region files only when a caller needs them.
     * Geometry exports deliberately never call this method.
     */
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

    /** Copy only entity-region files that can intersect selected X/Z bounds. */
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
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "entities/" + file.getName()
            );
        }
        if (log != null) {
            log.accept("Selected entity regions: " + files.length);
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
        return name.endsWith(".mca") || name.endsWith(".mcr");
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
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "region/" + file.getName()
            );
        }

        if (log != null) {
            log.accept("Selected block regions: " + files.length);
        }
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

    /** Clean up a previously created temp copy. */
    public static void cleanupTemp(File tempDir) {
        if (tempDir == null || !tempDir.exists()) return;
        if (!tempDir.getAbsolutePath().contains("minesport_")) return; // safety check

        try {
            Files.walkFileTree(tempDir.toPath(), new SimpleFileVisitor<>() {
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
