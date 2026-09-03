package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PolymerResolverFallbackTest {
    @TempDir Path tempDir;

    @Test
    void nonDirectionalPolymerBlockUsesNeutralDefaultWithoutMaskingFacing() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        writeFabricJar(mods.resolve("polytest.jar"));

        FabricResolver fabric = FabricResolver.load(mods.toFile(), null);
        try {
            PolymerResolver polymer = new PolymerResolver(fabric);
            BlockState state = polymer.resolveBlockState("polytest:lamp");
            assertNotNull(state);

            var neutral = state.resolve(Map.of(), 12, 70, -8);
            assertEquals(1, neutral.size());
            assertEquals("polytest:block/lamp", neutral.getFirst().modelPath);
            assertEquals(0, neutral.getFirst().y);

            var west = state.resolve(Map.of("facing", "west"), 12, 70, -8);
            assertEquals(1, west.size());
            assertEquals("polytest:block/lamp", west.getFirst().modelPath);
            assertEquals(270, west.getFirst().y);
        } finally {
            fabric.close();
        }
    }

    private static void writeFabricJar(Path jar) throws Exception {
        String metadata = "{\"schemaVersion\":1,\"id\":\"polytest\","
            + "\"version\":\"1\",\"name\":\"Poly Test\"}";
        String model = "{\"textures\":{\"all\":\"minecraft:block/stone\"},"
            + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
            + "\"faces\":{\"north\":{\"texture\":\"#all\"}}}]}";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/polytest/models/block/lamp.json"));
            zip.write(model.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
