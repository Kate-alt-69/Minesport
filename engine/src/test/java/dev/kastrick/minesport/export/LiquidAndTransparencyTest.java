package dev.kastrick.minesport.export;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquidAndTransparencyTest {
    @Test
    void sourceWaterExportsFluidSurfaceInsteadOfFallbackCube() {
        BlockData water = new BlockData(2, 10, -4, "minecraft:water", Map.of("level", "0"));
        List<Quad> quads = LiquidGeometryBuilder.build(water, Map.of());

        assertEquals(6, quads.size());
        assertTrue(quads.stream().allMatch(q -> "fluid".equals(q.partName())));
        assertTrue(quads.stream().allMatch(q -> !q.texturePath().startsWith("MISSING_")));
        assertTrue(quads.stream().anyMatch(q -> "minecraft:block/water_still".equals(q.texturePath())));
        assertTrue(quads.stream().anyMatch(q -> "minecraft:block/water_flow".equals(q.texturePath())));

        double maxY = quads.stream()
            .flatMap(q -> java.util.Arrays.stream(q.verts()))
            .mapToDouble(v -> v[1])
            .max()
            .orElseThrow();
        assertEquals(10.0 + 8.0 / 9.0, maxY, 1e-5);
    }

    @Test
    void flowingWaterLevelChangesSurfaceHeight() {
        BlockData shallow = new BlockData(0, 0, 0, "minecraft:water", Map.of("level", "7"));
        List<Quad> quads = LiquidGeometryBuilder.build(shallow, Map.of());
        double maxY = quads.stream()
            .flatMap(q -> java.util.Arrays.stream(q.verts()))
            .mapToDouble(v -> v[1])
            .max()
            .orElseThrow();
        assertEquals(1.0 / 9.0, maxY, 1e-5);
    }

    @Test
    void stackedWaterRemovesInternalTopAndBottomFaces() {
        BlockData lower = new BlockData(0, 0, 0, "minecraft:water", Map.of("level", "0"));
        BlockData upper = new BlockData(0, 1, 0, "minecraft:water", Map.of("level", "0"));
        Map<Long, BlockData> index = Map.of(
            SpatialKey.of(0, 0, 0), lower,
            SpatialKey.of(0, 1, 0), upper
        );

        List<Quad> lowerQuads = LiquidGeometryBuilder.build(lower, index);
        List<Quad> upperQuads = LiquidGeometryBuilder.build(upper, index);
        assertFalse(lowerQuads.stream().anyMatch(q -> "up".equals(q.cullface())));
        assertFalse(upperQuads.stream().anyMatch(q -> "down".equals(q.cullface())));
    }

    @Test
    void liquidsNeverBecomeFlatterOpaqueObjects() {
        ResolverChain resolvers = new ResolverChain();
        BlockData a = new BlockData(0, 0, 0, "minecraft:water", Map.of("level", "0"));
        BlockData b = new BlockData(1, 0, 0, "minecraft:water", Map.of("level", "0"));
        FlatterOptimizer.Result result = FlatterOptimizer.compile(List.of(a, b), resolvers, 16);
        assertTrue(result.isEmpty());
    }

    @Test
    void materialSemanticsRecognizeWaterAndGlass() {
        assertEquals(MaterialSemantics.Kind.WATER, MaterialSemantics.classify("minecraft:block/water_still"));
        assertEquals(MaterialSemantics.Kind.GLASS, MaterialSemantics.classify("minecraft_block_blue_stained_glass"));
        assertEquals(MaterialSemantics.Kind.DEFAULT, MaterialSemantics.classify("minecraft:block/oak_planks"));
    }

    @Test
    void gltfPostProcessorPromotesWaterAndGlassToBlendTransmission(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("transparent.gltf");
        Files.writeString(file, """
            {
              "asset": {"version": "2.0"},
              "scene": 0,
              "scenes": [{"nodes": [0]}],
              "nodes": [{"name": "Root"}],
              "materials": [
                {"name": "minecraft_block_water_still__tint_3f76e4", "alphaMode": "MASK", "pbrMetallicRoughness": {}},
                {"name": "minecraft_block_glass", "alphaMode": "MASK", "pbrMetallicRoughness": {}}
              ]
            }
            """, StandardCharsets.UTF_8);

        GltfPostProcessor.addMinecraftLights(file.toFile(), List.of());
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject water = root.getAsJsonArray("materials").get(0).getAsJsonObject();
        JsonObject glass = root.getAsJsonArray("materials").get(1).getAsJsonObject();

        assertEquals("BLEND", water.get("alphaMode").getAsString());
        assertEquals("BLEND", glass.get("alphaMode").getAsString());
        assertEquals(
            "WATER",
            water.getAsJsonObject("extras").get("minesportMaterialClass").getAsString()
        );
        assertEquals(
            "GLASS",
            glass.getAsJsonObject("extras").get("minesportMaterialClass").getAsString()
        );
        assertTrue(root.getAsJsonArray("extensionsUsed").toString().contains("KHR_materials_transmission"));
        assertEquals(
            0.92,
            glass.getAsJsonObject("extensions")
                .getAsJsonObject("KHR_materials_transmission")
                .get("transmissionFactor").getAsDouble(),
            1e-6
        );
    }
}
