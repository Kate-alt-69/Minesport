package dev.kastrick.minesport.export;

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
        Files.writeString(target.toPath(), "{\"state\":\"old\"}");

        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, writer -> {
            writer.write("{\"state\":\"new\"");
            throw new IOException("simulated publication failure");
        }));

        assertEquals("{\"state\":\"old\"}", Files.readString(target.toPath()));
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
        Files.writeString(target.toPath(), "{\"state\":\"old\"}");

        AtomicFileWriter.write(target, writer ->
            writer.write("{\"state\":\"new ✓\"}")
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
