package dev.kastrick.minesport.safety;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/**
 * Copies a Minecraft world folder to a temp directory before processing.
 * This ensures we NEVER touch the original world files.
 */
public class WorldCopier {
    private static final List<String> OVERWORLD_REGION_PATHS = List.of(
        "dimensions" + File.separator + "minecraft" + File.separator + "overworld" + File.separator + "region",
        "region"
    );

    /**
     * Copy a world folder into the system temp directory.
     * Returns the path to the copied world.
     */
    public static File copyToTemp(File worldFolder, Consumer<String> statusCallback) throws IOException {
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        // Create temp dir: e.g. /tmp/minesport_MyWorld_1234567890/
        String tempName = "minesport_" + worldFolder.getName() + "_" + System.currentTimeMillis();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), tempName);
        tempDir.mkdirs();

        if (statusCallback != null) {
            statusCallback.accept("Creating temp copy at: " + tempDir.getAbsolutePath());
        }

        // We only need region files + level.dat — skip logs, crash reports etc.
        copySelective(worldFolder.toPath(), tempDir.toPath(), statusCallback);

        // Existing engine readers expect the Overworld at temp/region. Keep the
        // copied source layout intact, then mirror the preferred Overworld there.
        // Minecraft 26.1+ uses dimensions/minecraft/overworld/region; modern
        // storage wins if a stale legacy world/region directory also exists.
        mirrorPreferredOverworldRegion(worldFolder, tempDir, statusCallback);

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
            file.isFile() && (file.getName().endsWith(".mca") || file.getName().endsWith(".mcr"))
        );
        return files != null && files.length > 0;
    }

    private static void mirrorPreferredOverworldRegion(
        File worldFolder,
        File tempDir,
        Consumer<String> log
    ) throws IOException {
        File sourceRegion = findOverworldRegionDir(worldFolder);
        Path destination = tempDir.toPath().resolve("region");

        if (Files.exists(destination)) {
            deleteTree(destination);
        }
        Files.createDirectories(destination);

        File[] files = sourceRegion.listFiles(file ->
            file.isFile() && (file.getName().endsWith(".mca") || file.getName().endsWith(".mcr"))
        );
        if (files == null || files.length == 0) {
            throw new IOException("Selected Overworld region directory became empty: " + sourceRegion);
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            Files.copy(
                file.toPath(),
                destination.resolve(file.getName()),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            );
        }

        if (log != null) {
            log.accept("Using Overworld region folder: " + sourceRegion.getAbsolutePath());
        }
    }

    /** Selectively copy only what Minesport needs. */
    private static void copySelective(Path src, Path dest, Consumer<String> log) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";

                // Skip folders we definitely don't need
                if (name.equals("logs") || name.equals("crash-reports")
                        || name.equals("playerdata") || name.equals("stats")
                        || name.equals("advancements") || name.equals("datapacks")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path destDir = dest.resolve(src.relativize(dir));
                Files.createDirectories(destDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();

                // Only copy files we actually need
                boolean needed = name.endsWith(".mca")     // region files
                              || name.endsWith(".mcr")     // old region format
                              || name.equals("level.dat")
                              || name.equals("level.dat_old");

                if (needed) {
                    Path destFile = dest.resolve(src.relativize(file));
                    Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                    if (log != null) log.accept("Copied: " + name);
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
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
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[WARN] Could not fully clean temp dir: " + e.getMessage());
        }
    }
}
