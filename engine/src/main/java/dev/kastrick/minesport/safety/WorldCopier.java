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

    /**
     * Copy only level metadata and the active Overworld region into the system
     * temp directory. Engine readers consume temp/region, so copying every
     * dimension and then mirroring the Overworld again is pure duplicate I/O.
     */
    public static File copyToTemp(File worldFolder, Consumer<String> statusCallback) throws IOException {
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        String tempName = "minesport_" + worldFolder.getName() + "_" + System.currentTimeMillis();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), tempName);
        if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
            throw new IOException("Could not create Minesport temp directory: " + tempDir);
        }

        if (statusCallback != null) {
            statusCallback.accept("Creating temp copy at: " + tempDir.getAbsolutePath());
        }

        try {
            copyLevelMetadata(worldFolder, tempDir, statusCallback);
            copyPreferredOverworldRegion(worldFolder, tempDir, statusCallback);
        } catch (IOException exception) {
            cleanupTemp(tempDir);
            throw exception;
        }

        if (statusCallback != null) {
            statusCallback.accept("World copy complete.");
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
        Consumer<String> log
    ) throws IOException {
        File sourceRegion = findOverworldRegionDir(worldFolder);
        Path destination = tempDir.toPath().resolve("region");
        Files.createDirectories(destination);

        File[] files = sourceRegion.listFiles(file -> file.isFile() && isRegionFile(file.getName()));
        if (files == null || files.length == 0) {
            throw new IOException("Selected Overworld region directory became empty: " + sourceRegion);
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "region/" + file.getName()
            );
        }

        if (log != null) {
            log.accept("Using Overworld region folder: " + sourceRegion.getAbsolutePath());
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
