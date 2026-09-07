from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


bundle_source = r'''package dev.kastrick.minesport.export;

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
        cleanupTree(backupRoot);
        cleanupTree(stagingRoot);
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
'''
Path("engine/src/main/java/dev/kastrick/minesport/export/AtomicExportBundle.java").write_text(
    bundle_source,
    encoding="utf-8",
)


test_source = r'''package dev.kastrick.minesport.export;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicExportBundleTest {
    @Test
    void completeBundleReplacesOldFilesAndRemovesStaleSidecar() throws Exception {
        var directory = Files.createTempDirectory("minesport-export-bundle-");
        var main = directory.resolve("scene.gltf").toFile();
        var bin = directory.resolve("scene.bin");
        var sidecar = directory.resolve("scene.minesport.json").toFile();
        Files.writeString(main.toPath(), "old-main");
        Files.writeString(bin, "old-bin");
        Files.writeString(sidecar.toPath(), "old-sidecar");

        try (var bundle = AtomicExportBundle.create(main)) {
            Files.writeString(bundle.stagedMain().toPath(), "new-main");
            Files.writeString(bundle.stagedMain().toPath().getParent().resolve("scene.bin"), "new-bin");
            bundle.removeOnPublish(sidecar);
            bundle.publish();
        }

        assertEquals("new-main", Files.readString(main.toPath()));
        assertEquals("new-bin", Files.readString(bin));
        assertFalse(sidecar.exists());
        try (var entries = Files.list(directory)) {
            assertEquals(2L, entries.count());
        }
        Files.deleteIfExists(main.toPath());
        Files.deleteIfExists(bin);
        Files.deleteIfExists(directory);
    }

    @Test
    void failedCompanionPublicationRestoresLastGoodBundle() throws Exception {
        var directory = Files.createTempDirectory("minesport-export-bundle-");
        var main = directory.resolve("scene.obj").toFile();
        var companion = directory.resolve("a.mtl");
        var blocked = directory.resolve("z.block");
        Files.writeString(main.toPath(), "old-main");
        Files.writeString(companion, "old-companion");
        Files.createDirectory(blocked);

        try (var bundle = AtomicExportBundle.create(main)) {
            Files.writeString(bundle.stagedMain().toPath(), "new-main");
            var stage = bundle.stagedMain().toPath().getParent();
            Files.writeString(stage.resolve("a.mtl"), "new-companion");
            Files.writeString(stage.resolve("z.block"), "cannot-replace-directory");

            assertThrows(IOException.class, bundle::publish);
            assertEquals("old-main", Files.readString(main.toPath()));
            assertEquals("old-companion", Files.readString(companion));
            assertEquals(true, Files.isDirectory(blocked));
        }

        Files.deleteIfExists(main.toPath());
        Files.deleteIfExists(companion);
        Files.deleteIfExists(blocked);
        Files.deleteIfExists(directory);
    }
}
'''
Path("engine/src/test/java/dev/kastrick/minesport/export/AtomicExportBundleTest.java").write_text(
    test_source,
    encoding="utf-8",
)


path = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "        File stagedOutput = null;\n        ResolverChain chain = null;",
    "        File stagedOutput = null;\n        AtomicExportBundle exportBundle = null;\n        ResolverChain chain = null;",
    "IPC export bundle state",
)
text = replace_once(
    text,
    '''            log("Exporting as " + format.toUpperCase() + "...");
            ObjExporter.ExportStats stats;
            if (format.equals("gltf")) {''',
    '''            exportBundle = AtomicExportBundle.create(outFile);
            exportBundle.removeOnPublish(FlatterMetadataExporter.sidecarFor(outFile));
            File exportFile = exportBundle.stagedMain();

            log("Exporting as " + format.toUpperCase() + "...");
            ObjExporter.ExportStats stats;
            if (format.equals("gltf")) {''',
    "IPC create export bundle",
)
text = replace_once(
    text,
    '''                    outFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {''',
    '''                    exportFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {''',
    "IPC glTF staged target",
)
text = replace_once(
    text,
    '''                GltfPostProcessor.fixSamplers(outFile);
                log("glTF sampler normalization complete");''',
    '''                GltfPostProcessor.fixSamplers(exportFile);
                log("glTF sampler normalization complete");''',
    "IPC glTF staged postprocess",
)
text = replace_once(
    text,
    '''                    geometryBuilder,
                    outFile,
                    mode,
                    optimize,''',
    '''                    geometryBuilder,
                    exportFile,
                    mode,
                    optimize,''',
    "IPC OBJ staged target",
)
text = replace_once(
    text,
    '''                File metadata = BlenderMetadataExporter.write(
                    outFile,
                    allBlocks,''',
    '''                File metadata = BlenderMetadataExporter.write(
                    exportFile,
                    allBlocks,''',
    "IPC staged Blender metadata",
)
text = replace_once(
    text,
    '''                log("Blender translation metadata: " + metadata.getName());
            }

            progress(100, "Done");''',
    '''                log("Blender translation metadata: " + metadata.getName());
            }

            progress(98, "Publishing export files");
            exportBundle.publish();
            exportBundle = null;
            progress(100, "Done");''',
    "IPC publish export bundle",
)
text = replace_once(
    text,
    '''            if (stagedOutput != null) {
                try {
                    Files.deleteIfExists(stagedOutput.toPath());
                } catch (IOException ignored) {}
            }
            if (tempDir != null) WorldCopier.cleanupTemp(tempDir);''',
    '''            if (stagedOutput != null) {
                try {
                    Files.deleteIfExists(stagedOutput.toPath());
                } catch (IOException ignored) {}
            }
            if (exportBundle != null) {
                try {
                    exportBundle.close();
                } catch (IOException ignored) {}
            }
            if (tempDir != null) WorldCopier.cleanupTemp(tempDir);''',
    "IPC discard failed export bundle",
)
path.write_text(text, encoding="utf-8")


path = Path("engine/src/main/java/dev/kastrick/minesport/export/ObjExporter.java")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''            emittedVertices = vertexOffset - 1;
        }''',
    '''            if (writer.checkError()) {
                throw new IOException("OBJ writer reported an output failure for " + outputFile);
            }
            emittedVertices = vertexOffset - 1;
        }''',
    "OBJ PrintWriter failure check",
)
path.write_text(text, encoding="utf-8")


path = Path("engine/src/main/java/dev/kastrick/minesport/export/MtlExporter.java")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''                ImageIO.write(img, "PNG", pngFile);
''',
    '''                if (!ImageIO.write(img, "PNG", pngFile)) {
                    throw new IOException("No PNG writer is available for " + pngFile);
                }
''',
    "MTL texture write verification",
)
text = replace_once(
    text,
    '''                w.println();
            }
        }
    }''',
    '''                w.println();
            }
            if (w.checkError()) {
                throw new IOException("MTL writer reported an output failure for " + mtlFile);
            }
        }
    }''',
    "MTL PrintWriter failure check",
)
text = replace_once(
    text,
    '''        ImageIO.write(alpha, "PNG", output);
''',
    '''        if (!ImageIO.write(alpha, "PNG", output)) {
            throw new IOException("No PNG writer is available for " + output);
        }
''',
    "MTL alpha write verification",
)
path.write_text(text, encoding="utf-8")


path = Path("engine/src/main/java/dev/kastrick/minesport/export/GltfExporter.java")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            writer.println(gson.toJson(root));
        }''',
    '''        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            writer.println(gson.toJson(root));
            if (writer.checkError()) {
                throw new IOException("glTF writer reported an output failure for " + outputFile);
            }
        }''',
    "glTF PrintWriter failure check",
)
path.write_text(text, encoding="utf-8")
