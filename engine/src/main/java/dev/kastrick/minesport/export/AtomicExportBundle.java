package dev.kastrick.minesport.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stages a multi-file export beside its final destination and publishes the
 * complete bundle with rollback. Companion files are installed before the main
 * descriptor so a visible OBJ/glTF never points at companions that have not
 * been published yet.
 */
public final class AtomicExportBundle implements AutoCloseable {
    private final Path finalMain;
    private final Path finalRoot;
    private final Path stagingRoot;
    private final Path backupRoot;
    private final Set<Path> removeOnPublish = new LinkedHashSet<>();
    private boolean published;
    private boolean closed;

    private AtomicExportBundle(Path finalMain, Path finalRoot, Path stagingRoot, Path backupRoot) {
        this.finalMain = finalMain;
        this.finalRoot = finalRoot;
        this.stagingRoot = stagingRoot;
        this.backupRoot = backupRoot;
    }

    public static AtomicExportBundle create(File finalMainFile) throws IOException {
        if (finalMainFile == null) throw new IOException("Export output is missing");
        Path finalMain = finalMainFile.toPath().toAbsolutePath().normalize();
        Path finalRoot = finalMain.getParent();
        if (finalRoot == null) throw new IOException("Export output has no parent directory");
        Files.createDirectories(finalRoot);
        if (!Files.isDirectory(finalRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Export parent is not a directory: " + finalRoot);
        }

        String safeName = finalMain.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        Path stagingRoot = Files.createTempDirectory(finalRoot, "." + safeName + ".stage-");
        Path backupRoot = Files.createTempDirectory(finalRoot, "." + safeName + ".backup-");
        return new AtomicExportBundle(finalMain, finalRoot, stagingRoot, backupRoot);
    }

    public File stagedMain() {
        return stagingRoot.resolve(finalMain.getFileName()).toFile();
    }

    /** Remove a stale companion only after the new bundle is otherwise ready. */
    public void removeOnPublish(File finalFile) throws IOException {
        ensureOpen();
        Path target = finalFile.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(finalRoot) || target.equals(finalRoot)) {
            throw new IOException("Export companion escapes output directory: " + target);
        }
        removeOnPublish.add(finalRoot.relativize(target));
    }

    public void publish() throws IOException {
        ensureOpen();
        Path stagedMainPath = stagingRoot.resolve(finalMain.getFileName());
        if (!Files.isRegularFile(stagedMainPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staged export main file is missing: " + stagedMainPath);
        }

        List<Path> stagedFiles = collectStagedFiles();
        Path mainRelative = stagingRoot.relativize(stagedMainPath);
        stagedFiles.sort((left, right) -> {
            Path leftRelative = stagingRoot.relativize(left);
            Path rightRelative = stagingRoot.relativize(right);
            boolean leftMain = leftRelative.equals(mainRelative);
            boolean rightMain = rightRelative.equals(mainRelative);
            if (leftMain != rightMain) return leftMain ? 1 : -1;
            return leftRelative.toString().compareTo(rightRelative.toString());
        });

        Set<Path> stagedRelatives = new LinkedHashSet<>();
        for (Path staged : stagedFiles) stagedRelatives.add(stagingRoot.relativize(staged));

        List<Path> backedUp = new ArrayList<>();
        List<Path> installed = new ArrayList<>();
        List<Path> createdDirectories = new ArrayList<>();
        try {
            for (Path relative : removeOnPublish) {
                if (stagedRelatives.contains(relative)) continue;
                backupExisting(relative, backedUp, createdDirectories);
            }

            for (Path staged : stagedFiles) {
                Path relative = stagingRoot.relativize(staged);
                Path destination = finalRoot.resolve(relative).normalize();
                if (!destination.startsWith(finalRoot)) {
                    throw new IOException("Staged export path escapes destination: " + relative);
                }
                ensureDestinationParent(destination.getParent(), createdDirectories);
                backupExisting(relative, backedUp, createdDirectories);
                moveReplacing(staged, destination);
                installed.add(relative);
            }
        } catch (IOException failure) {
            IOException rollbackFailure = rollback(installed, backedUp, createdDirectories);
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            throw failure;
        }

        published = true;
        cleanupTreeBestEffort(backupRoot);
        cleanupTreeBestEffort(stagingRoot);
    }

    private List<Path> collectStagedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(stagingRoot)) {
            for (Path path : paths.toList()) {
                if (path.equals(stagingRoot)) continue;
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Refusing symbolic link in staged export: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Refusing special file in staged export: " + path);
                }
                files.add(path);
            }
        }
        return files;
    }

    private void backupExisting(
        Path relative,
        List<Path> backedUp,
        List<Path> createdDirectories
    ) throws IOException {
        Path destination = finalRoot.resolve(relative).normalize();
        if (!destination.startsWith(finalRoot)) {
            throw new IOException("Export destination escapes output directory: " + destination);
        }
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(destination)
            || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace non-regular export target: " + destination);
        }

        Path backup = backupRoot.resolve(relative);
        Files.createDirectories(backup.getParent());
        moveReplacing(destination, backup);
        backedUp.add(relative);
    }

    private void ensureDestinationParent(Path parent, List<Path> createdDirectories)
        throws IOException {
        if (parent == null || parent.equals(finalRoot)) return;
        if (!parent.startsWith(finalRoot)) {
            throw new IOException("Export parent escapes output directory: " + parent);
        }

        List<Path> missing = new ArrayList<>();
        Path current = parent;
        while (!current.equals(finalRoot)
            && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current);
            current = current.getParent();
            if (current == null) throw new IOException("Export parent has no root: " + parent);
        }
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Export parent is not a directory: " + current);
        }
        Collections.reverse(missing);
        for (Path directory : missing) {
            Files.createDirectory(directory);
            createdDirectories.add(directory);
        }
    }

    private IOException rollback(
        List<Path> installed,
        List<Path> backedUp,
        List<Path> createdDirectories
    ) {
        IOException failure = null;
        List<Path> reverseInstalled = new ArrayList<>(installed);
        Collections.reverse(reverseInstalled);
        for (Path relative : reverseInstalled) {
            try {
                Files.deleteIfExists(finalRoot.resolve(relative));
            } catch (IOException error) {
                failure = collect(failure, error);
            }
        }

        List<Path> reverseBackups = new ArrayList<>(backedUp);
        Collections.reverse(reverseBackups);
        for (Path relative : reverseBackups) {
            Path backup = backupRoot.resolve(relative);
            Path destination = finalRoot.resolve(relative);
            try {
                if (destination.getParent() != null) Files.createDirectories(destination.getParent());
                moveReplacing(backup, destination);
            } catch (IOException error) {
                failure = collect(failure, error);
            }
        }

        List<Path> reverseDirectories = new ArrayList<>(createdDirectories);
        reverseDirectories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : reverseDirectories) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                // It is safe to leave a directory that is no longer empty.
            }
        }
        return failure;
    }

    private static IOException collect(IOException current, IOException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanupTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        IOException failure = null;
        try (var paths = Files.walk(root)) {
            List<Path> entries = new ArrayList<>(paths.toList());
            entries.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path path : entries) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    failure = collect(failure, error);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private static void cleanupTreeBestEffort(Path root) {
        try {
            cleanupTree(root);
        } catch (IOException ignored) {
            // Publication is already committed. Scratch cleanup must never turn
            // a valid export into a reported failure.
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Export bundle is already closed");
        if (published) throw new IOException("Export bundle is already published");
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        if (!published) {
            try {
                cleanupTree(stagingRoot);
            } catch (IOException error) {
                failure = collect(failure, error);
            }
            try {
                cleanupTree(backupRoot);
            } catch (IOException error) {
                failure = collect(failure, error);
            }
        }
        if (failure != null) throw failure;
    }
}
