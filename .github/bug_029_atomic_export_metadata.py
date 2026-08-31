from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


atomic_path = Path("engine/src/main/java/dev/kastrick/minesport/export/AtomicFileWriter.java")
if atomic_path.exists():
    raise SystemExit(f"unexpected existing atomic writer: {atomic_path}")
atomic_path.write_text(
    '''package dev.kastrick.minesport.export;

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
''',
    encoding="utf-8",
)

blender_path = Path("engine/src/main/java/dev/kastrick/minesport/export/BlenderMetadataExporter.java")
blender = blender_path.read_text(encoding="utf-8")
blender = replace_once(
    blender,
    '''        try (Writer writer = new BufferedWriter(new FileWriter(sidecar))) {
            GSON.toJson(root, writer);
        }
        return sidecar;''',
    '''        AtomicFileWriter.write(sidecar, writer -> GSON.toJson(root, writer));
        return sidecar;''',
    "Blender metadata atomic publication",
)
blender_path.write_text(blender, encoding="utf-8")

flatter_path = Path("engine/src/main/java/dev/kastrick/minesport/export/FlatterMetadataExporter.java")
flatter = flatter_path.read_text(encoding="utf-8")
flatter = replace_once(
    flatter,
    '''    private static void writeJson(File file, JsonObject root) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try (Writer writer = new BufferedWriter(new FileWriter(file))) {
            GSON.toJson(root, writer);
        }
    }''',
    '''    private static void writeJson(File file, JsonObject root) throws IOException {
        AtomicFileWriter.write(file, writer -> GSON.toJson(root, writer));
    }''',
    "FLATTER metadata and glTF marker atomic publication",
)
flatter_path.write_text(flatter, encoding="utf-8")


test_path = Path("engine/src/test/java/dev/kastrick/minesport/export/AtomicFileWriterTest.java")
if test_path.exists():
    raise SystemExit(f"unexpected existing atomic writer test: {test_path}")
test_path.write_text(
    '''package dev.kastrick.minesport.export;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFileWriterTest {
    @Test
    void failedPublicationPreservesLastGoodTargetAndCleansScratch() throws Exception {
        var directory = Files.createTempDirectory("minesport-atomic-write-");
        var target = directory.resolve("scene.minesport.json").toFile();
        Files.writeString(target.toPath(), "{\\\"state\\\":\\\"old\\\"}");

        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, writer -> {
            writer.write("{\\\"state\\\":\\\"new\\\"");
            throw new IOException("simulated publication failure");
        }));

        assertEquals("{\\\"state\\\":\\\"old\\\"}", Files.readString(target.toPath()));
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
        Files.deleteIfExists(target.toPath());
        Files.deleteIfExists(directory);
    }

    @Test
    void successfulPublicationReplacesTargetWithCompleteUtf8Json() throws Exception {
        var directory = Files.createTempDirectory("minesport-atomic-write-");
        var target = directory.resolve("scene.gltf").toFile();
        Files.writeString(target.toPath(), "{\\\"state\\\":\\\"old\\\"}");

        AtomicFileWriter.write(target, writer ->
            writer.write("{\\\"state\\\":\\\"new ✓\\\"}")
        );

        var parsed = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        assertEquals("new ✓", parsed.get("state").getAsString());
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
        Files.deleteIfExists(target.toPath());
        Files.deleteIfExists(directory);
    }
}
''',
    encoding="utf-8",
)

print("BUG-029: metadata and post-export JSON publication is atomic and failure-preserving")
