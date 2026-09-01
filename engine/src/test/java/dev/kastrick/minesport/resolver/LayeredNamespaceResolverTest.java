package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredNamespaceResolverTest {
    private static final String BLOCKSTATE =
        "{\"variants\":{\"\":{\"model\":\"shared:block/example\"}}}";
    private static final String MODEL =
        "{\"textures\":{\"all\":\"minecraft:block/stone\"},"
            + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
            + "\"faces\":{\"north\":{\"texture\":\"#all\"}}}]}";
    private static final String ANIMATION_METADATA =
        "{\"animation\":{\"frametime\":2,\"interpolate\":true}}";

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

    @Test
    void quiltAnimationMetadataReadsLayeredNamespaceSources() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt-metadata"));
        writeJar(mods.resolve("a.jar"), "quilt.mod.json", quiltMeta("quilt_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeTextureJar(mods.resolve("b.jar"), "quilt.mod.json", quiltMeta("quilt_b"), true, true);

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            assertEquals(
                ANIMATION_METADATA,
                ModJarTextureMetadata.read(resolver, "shared:block/animated")
            );
        } finally {
            resolver.close();
        }
    }

    @Test
    void forgeAnimationMetadataReadsLayeredNamespaceSources() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("forge-metadata"));
        writeJar(mods.resolve("a.jar"), "META-INF/mods.toml", forgeMeta("forge_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeTextureJar(mods.resolve("b.jar"), "META-INF/mods.toml", forgeMeta("forge_b"), true, true);

        ForgeResolver resolver = ForgeResolver.load(mods.toFile(), null);
        try {
            assertEquals(
                ANIMATION_METADATA,
                ModJarTextureMetadata.read(resolver, "shared:block/animated")
            );
        } finally {
            resolver.close();
        }
    }

    @Test
    void fabricMetadataDoesNotFallThroughPastWinningPng() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("fabric-winner"));
        writeTextureJar(mods.resolve("a.jar"), "fabric.mod.json", fabricMeta("fabric_a"), true, false);
        writeTextureJar(mods.resolve("b.jar"), "fabric.mod.json", fabricMeta("fabric_b"), false, true);
        assertNull(resolveMetadata(FabricResolver.load(mods.toFile(), null)));
    }

    @Test
    void quiltMetadataDoesNotFallThroughPastWinningPng() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt-winner"));
        writeTextureJar(mods.resolve("a.jar"), "quilt.mod.json", quiltMeta("quilt_a"), true, false);
        writeTextureJar(mods.resolve("b.jar"), "quilt.mod.json", quiltMeta("quilt_b"), false, true);
        assertNull(resolveMetadata(QuiltResolver.load(mods.toFile(), null)));
    }

    @Test
    void forgeMetadataDoesNotFallThroughPastWinningPng() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("forge-winner"));
        writeTextureJar(mods.resolve("a.jar"), "META-INF/mods.toml", forgeMeta("forge_a"), true, false);
        writeTextureJar(mods.resolve("b.jar"), "META-INF/mods.toml", forgeMeta("forge_b"), false, true);
        assertNull(resolveMetadata(ForgeResolver.load(mods.toFile(), null)));
    }

    private static String resolveMetadata(AssetResolver resolver) {
        try (ResolverChain chain = new ResolverChain()) {
            chain.addResolver(resolver);
            return chain.resolveTextureMetadata("shared:block/animated");
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
            writeEntry(zip, metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, assetPath, asset.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeTextureJar(
        Path jar,
        String metadataPath,
        String metadata,
        boolean includePng,
        boolean includeAnimationMetadata
    ) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeEntry(zip, metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
            if (includePng) {
                writeEntry(zip, "assets/shared/textures/block/animated.png", tinyPng());
            }
            if (includeAnimationMetadata) {
                writeEntry(
                    zip,
                    "assets/shared/textures/block/animated.png.mcmeta",
                    ANIMATION_METADATA.getBytes(StandardCharsets.UTF_8)
                );
            }
        }
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }
}
