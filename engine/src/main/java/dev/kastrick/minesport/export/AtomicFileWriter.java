package dev.kastrick.minesport.export;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publishes a text file without exposing a partially-written final target. */
final class AtomicFileWriter {
    @FunctionalInterface
    interface WriterAction {
        void write(Writer writer) throws IOException;
    }

    private AtomicFileWriter() {}

    static void write(File target, WriterAction action) throws IOException {
        if (target == null) throw new IOException("Atomic target is required");
        if (action == null) throw new IOException("Atomic writer action is required");

        File absolute = target.getAbsoluteFile();
        Path targetPath = absolute.toPath();
        Path parent = targetPath.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);

        String prefix = "." + absolute.getName() + ".";
        if (prefix.length() < 3) prefix = "minesport-";
        Path temp = Files.createTempFile(parent, prefix, ".tmp");
        boolean published = false;
        try {
            try (
                FileOutputStream output = new FileOutputStream(temp.toFile());
                Writer writer = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8)
                )
            ) {
                action.write(writer);
                writer.flush();
                output.getFD().sync();
            }

            try {
                Files.move(
                    temp,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            published = true;
        } finally {
            if (!published) Files.deleteIfExists(temp);
        }
    }
}
