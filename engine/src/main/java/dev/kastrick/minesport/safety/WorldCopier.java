package dev.kastrick.minesport.safety;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * Copies a Minecraft world folder to a temp directory before processing.
 * This ensures we NEVER touch the original world files.
 */
public class WorldCopier {

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

        // We only need region files + level.dat — skip logs, crash reports etc
        copySelective(worldFolder.toPath(), tempDir.toPath(), statusCallback);

        if (statusCallback != null) {
            statusCallback.accept("World copy complete.");
        }

        return tempDir;
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
