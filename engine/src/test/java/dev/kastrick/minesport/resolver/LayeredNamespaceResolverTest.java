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

class LayeredNamespaceResolverTest {
    private static final String BLOCKSTATE =
        "{\"variants\":{\"\":{\"model\":\"shared:block/example\"}}}";
    private static final String MODEL =
        "{\"textures\":{\"all\":\"minecraft:block/stone\"},"
            + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
            + "\"faces\":{\"north\":{\"texture\":\"#all\"}}}]}";

    @TempDir Path tempDir;

    @Test
    void fabricFindsResourcesAcrossJarsSharingOneNamespace() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("fabric"));
        writeJar(mods.resolve("a.jar"), "fabric.mod.json", fabricMeta("fabric_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeJar(mods.resolve("b.jar"), "fabric.mod.json", fabricMeta("fabric_b"),
            "assets/shared/models/block/example.json", MODEL);

        FabricResolver resolver = FabricResolver.load(mods.toFile(), null);
        try {
            assertNotNull(resolver.resolveBlockState("shared:test"));
            assertTrue(resolver.listModels("shared").contains("example"));
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltFindsResourcesAcrossJarsSharingOneNamespace() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt"));
        writeJar(mods.resolve("a.jar"), "quilt.mod.json", quiltMeta("quilt_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeJar(mods.resolve("b.jar"), "quilt.mod.json", quiltMeta("quilt_b"),
            "assets/shared/models/block/example.json", MODEL);

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            assertNotNull(resolver.resolveBlockState("shared:test"));
            assertTrue(resolver.listModels("shared").contains("example"));
        } finally {
            resolver.close();
        }
    }

    @Test
    void forgeFindsResourcesAcrossJarsSharingOneNamespace() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("forge"));
        writeJar(mods.resolve("a.jar"), "META-INF/mods.toml", forgeMeta("forge_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeJar(mods.resolve("b.jar"), "META-INF/mods.toml", forgeMeta("forge_b"),
            "assets/shared/models/block/example.json", MODEL);

        ForgeResolver resolver = ForgeResolver.load(mods.toFile(), null);
        try {
            assertNotNull(resolver.resolveBlockState("shared:test"));
            assertTrue(resolver.listModels("shared").contains("example"));
        } finally {
            resolver.close();
        }
    }

    private static String fabricMeta(String id) {
        return "{\"schemaVersion\":1,\"id\":\"" + id
            + "\",\"version\":\"1\",\"name\":\"" + id + "\"}";
    }

    private static String quiltMeta(String id) {
        return "{\"schema_version\":1,\"quilt_loader\":{\"id\":\"" + id
            + "\",\"version\":\"1\",\"metadata\":{\"name\":\"" + id + "\"}}}";
    }

    private static String forgeMeta(String id) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
            + "[[mods]]\nmodId=\"" + id + "\"\nversion=\"1\"\n"
            + "displayName=\"" + id + "\"\n";
    }

    private static void writeJar(
        Path jar,
        String metadataPath,
        String metadata,
        String assetPath,
        String asset
    ) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(metadataPath));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(assetPath));
            zip.write(asset.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
