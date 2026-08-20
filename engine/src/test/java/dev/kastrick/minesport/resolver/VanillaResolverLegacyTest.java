package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class VanillaResolverLegacyTest {
    @TempDir
    Path tempDir;

    @Test
    void minecraft152ResolvesHistoricalTextureNames() throws Exception {
        File jar = tempDir.resolve("1.5.2.jar").toFile();
        int expected = 0xff234567;
        writeJar(jar, Map.of(
            "textures/blocks/stone.png", png(0xff777777),
            "textures/blocks/oreDiamond.png", png(expected)
        ));

        try (ResolverClose resolver = new ResolverClose(new VanillaResolver(jar))) {
            BufferedImage image = resolver.value.resolveTexture("minecraft:block/diamond_ore");
            assertNotNull(image);
            assertEquals(expected, image.getRGB(0, 0));
            assertTrue(resolver.value.usesSyntheticLegacyModels());
        }
    }

    @Test
    void minecraft15WithoutJsonModelsSynthesizesBlockGeometry() throws Exception {
        File jar = tempDir.resolve("1.5.jar").toFile();
        writeJar(jar, Map.of("textures/blocks/stone.png", png(0xff777777)));

        try (ResolverClose resolver = new ResolverClose(new VanillaResolver(jar))) {
            BlockState state = resolver.value.resolveBlockState("minecraft:glass");
            assertNotNull(state);

            var applications = state.resolve(Map.of("legacy_data", "0"), 0, 64, 0);
            assertEquals(1, applications.size());
            assertEquals("minecraft:minesport_legacy/glass/0", applications.getFirst().modelPath);

            BlockModel model = resolver.value.resolveModel(applications.getFirst().modelPath);
            assertNotNull(model);
            assertFalse(model.isEmpty());
            assertEquals(6, model.elements.getFirst().faces.size());
            assertEquals("minecraft:block/glass", model.elements.getFirst().faces.get("north").texture);
        }
    }

    @Test
    void legacyGrassUsesTintOnlyOnTopFace() throws Exception {
        File jar = tempDir.resolve("1.5.2.jar").toFile();
        writeJar(jar, Map.of("textures/blocks/stone.png", png(0xff777777)));

        try (ResolverClose resolver = new ResolverClose(new VanillaResolver(jar))) {
            BlockState state = resolver.value.resolveBlockState("minecraft:grass_block");
            var app = state.resolve(Map.of("legacy_data", "0"), 0, 64, 0).getFirst();
            BlockModel model = resolver.value.resolveModel(app.modelPath);
            var faces = model.elements.getFirst().faces;

            assertEquals(0, faces.get("up").tintindex);
            assertEquals(-1, faces.get("north").tintindex);
            assertEquals(-1, faces.get("down").tintindex);
            assertEquals("minecraft:block/dirt", faces.get("down").texture);
        }
    }

    @Test
    void jsonModelEraDoesNotInventSyntheticModels() throws Exception {
        File jar = tempDir.resolve("1.12.2.jar").toFile();
        writeJar(jar, Map.of("assets/minecraft/textures/blocks/stone.png", png(0xff777777)));

        try (ResolverClose resolver = new ResolverClose(new VanillaResolver(jar))) {
            assertFalse(resolver.value.usesSyntheticLegacyModels());
            assertNull(resolver.value.resolveBlockState("minecraft:glass"));
        }
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        return bytes.toByteArray();
    }

    private static void writeJar(File jar, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static final class ResolverClose implements AutoCloseable {
        private final VanillaResolver value;
        private ResolverClose(VanillaResolver value) { this.value = value; }
        @Override public void close() { value.close(); }
    }
}
