package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuiltResolverMetadataFallbackTest {
    @TempDir Path tempDir;

    @Test
    void recognizedQuiltJarKeepsAssetsWhenFriendlyMetadataCannotBeParsed() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path jar = mods.resolve("metadata-edge.jar");
        String blockstate = "{\"variants\":{\"\":{\"model\":\"edge:block/example\"}}}";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("quilt.mod.json"));
            zip.write("{\"schema_version\":1}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/edge/blockstates/test.json"));
            zip.write(blockstate.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            assertTrue(resolver.getDetectedMods().isEmpty());
            assertTrue(resolver.getNamespaces().contains("edge"));
            assertNotNull(resolver.resolveBlockState("edge:test"));
        } finally {
            resolver.close();
        }
    }
}
