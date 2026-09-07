package dev.kastrick.minesport.export;

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
