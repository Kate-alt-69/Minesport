package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderMetadataParserTest {
    @TempDir Path tempDir;

    @Test
    void fabricUsesTopLevelJsonFields() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("fabric"));
        writeJar(
            mods.resolve("fabric.jar"),
            "fabric.mod.json",
            "{\"custom\":{\"id\":\"wrong_nested_id\"},"
                + "\"schemaVersion\":1,\"id\":\"real_mod\","
                + "\"version\":\"1.2.3\",\"name\":\"Real Mod\"}",
            "assets/real_mod/models/block/example.json"
        );

        FabricResolver resolver = FabricResolver.load(mods.toFile(), null);
        try {
            Map<String, FabricResolver.ModInfo> byId = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(FabricResolver.ModInfo::modId, Function.identity()));
            assertTrue(byId.containsKey("real_mod"));
            assertFalse(byId.containsKey("wrong_nested_id"));
            assertEquals("Real Mod", byId.get("real_mod").name());
            assertEquals("1.2.3", byId.get("real_mod").version());
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltReadsOnlyTheQuiltLoaderObject() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt"));
        writeJar(
            mods.resolve("quilt.jar"),
            "quilt.mod.json",
            "{\"schema_version\":1,\"unrelated\":{\"id\":\"wrong_outer_id\"},"
                + "\"quilt_loader\":{\"id\":\"quilt_real\","
                + "\"version\":\"9.8.7\","
                + "\"metadata\":{\"name\":\"Braces } { Mod\"}}}",
            "assets/quilt_real/models/block/example.json"
        );

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            Map<String, QuiltResolver.ModInfo> byId = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(QuiltResolver.ModInfo::modId, Function.identity()));
            assertTrue(byId.containsKey("quilt_real"));
            assertFalse(byId.containsKey("wrong_outer_id"));
            assertEquals("Braces } { Mod", byId.get("quilt_real").name());
            assertEquals("9.8.7", byId.get("quilt_real").version());
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltFabricCompatUsesTopLevelFabricFields() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("compat"));
        writeJar(
            mods.resolve("compat.jar"),
            "fabric.mod.json",
            "{\"nested\":{\"id\":\"not_this_one\"},"
                + "\"id\":\"compat_real\",\"version\":\"4.5.6\","
                + "\"name\":\"Compat Mod\"}",
            "assets/compat_real/models/block/example.json"
        );

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            QuiltResolver.ModInfo info = resolver.getDetectedMods().iterator().next();
            assertEquals("compat_real", info.modId());
            assertEquals("Compat Mod", info.name());
            assertEquals("4.5.6", info.version());
            assertTrue(info.fabricCompat());
        } finally {
            resolver.close();
        }
    }

    private static void writeJar(Path jar, String metadataPath, String metadata, String assetPath)
        throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(metadataPath));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(assetPath));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
