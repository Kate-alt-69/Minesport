package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.region.BlockData;
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

class MinecraftLightExporterTest {
    @Test
    void vanillaTorchKeepsMinecraftLevelFourteen() {
        BlockData torch = new BlockData(10, 64, -5, "minecraft:torch", Map.of());
        assertEquals(14, MinecraftLightExporter.lightLevel(torch));

        var resolved = MinecraftLightExporter.resolve(List.of(torch));
        assertEquals(1, resolved.size());
        assertEquals(14, resolved.getFirst().minecraftLevel());
        assertEquals(14.5, resolved.getFirst().rangeBlocks(), 1e-6);
    }

    @Test
    void invisibleLightBlockUsesItsStateLevelAndNeverBecomesMeshGeometry() {
        BlockData light = new BlockData(1, 2, 3, "minecraft:light", Map.of("level", "6"));
        var resolved = MinecraftLightExporter.resolve(List.of(light));

        assertTrue(light.isAir(), "minecraft:light must be air-like to mesh exporters");
        assertEquals(1, resolved.size(), "air-like geometry must not discard the light descriptor");
        assertEquals(6, resolved.getFirst().minecraftLevel());
        assertTrue(resolved.getFirst().invisibleSource());
        assertEquals(6.5, resolved.getFirst().rangeBlocks(), 1e-6);
    }

    @Test
    void statefulEmittersRespectLitProperty() {
        BlockData offLamp = new BlockData(0, 0, 0, "minecraft:redstone_lamp", Map.of("lit", "false"));
        BlockData onLamp = new BlockData(0, 0, 0, "minecraft:redstone_lamp", Map.of("lit", "true"));
        BlockData litFurnace = new BlockData(0, 0, 0, "minecraft:furnace", Map.of("lit", "true"));

        assertEquals(0, MinecraftLightExporter.lightLevel(offLamp));
        assertEquals(15, MinecraftLightExporter.lightLevel(onLamp));
        assertEquals(13, MinecraftLightExporter.lightLevel(litFurnace));
    }

    @Test
    void plainAndColoredCandlesUseVanillaCandleCount() {
        BlockData onePlain = new BlockData(0, 0, 0, "minecraft:candle", Map.of("lit", "true", "candles", "1"));
        BlockData fourPlain = new BlockData(0, 0, 0, "minecraft:candle", Map.of("lit", "true", "candles", "4"));
        BlockData threeBlue = new BlockData(0, 0, 0, "minecraft:blue_candle", Map.of("lit", "true", "candles", "3"));
        BlockData cake = new BlockData(0, 0, 0, "minecraft:candle_cake", Map.of("lit", "true"));
        BlockData off = new BlockData(0, 0, 0, "minecraft:candle", Map.of("lit", "false", "candles", "4"));

        assertEquals(3, MinecraftLightExporter.lightLevel(onePlain));
        assertEquals(12, MinecraftLightExporter.lightLevel(fourPlain));
        assertEquals(9, MinecraftLightExporter.lightLevel(threeBlue));
        assertEquals(3, MinecraftLightExporter.lightLevel(cake));
        assertEquals(0, MinecraftLightExporter.lightLevel(off));
    }

    @Test
    void bridgeCanSupplyModdedLuminanceWithoutCoreRegistryEntry() {
        BlockData modded = new BlockData(
            0, 0, 0,
            "examplemod:laser_lamp",
            Map.of("minesport_light_level", "9")
        );
        assertEquals(9, MinecraftLightExporter.lightLevel(modded));
    }

    @Test
    void sidecarCoordinatesAreAlreadyBlenderNative() {
        BlockData torch = new BlockData(12, 65, -2, "minecraft:torch", Map.of());
        JsonArray lights = MinecraftLightExporter.sidecarLights(
            List.of(torch),
            new float[]{10.0f, 64.0f, -4.0f}
        );

        assertEquals(1, lights.size());
        JsonObject descriptor = lights.get(0).getAsJsonObject();
        JsonArray position = descriptor.getAsJsonArray("position");
        assertEquals(2.5, position.get(0).getAsDouble(), 1e-6);
        assertEquals(-2.5, position.get(1).getAsDouble(), 1e-6);
        assertEquals(1.72, position.get(2).getAsDouble(), 1e-6);
        assertEquals("minecraft_linear_smooth", descriptor.get("falloff").getAsString());
        assertFalse(descriptor.get("invisibleSource").getAsBoolean());
    }

    @Test
    void gltfPostProcessorAddsNativePunctualLightAndEmission(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("light-test.gltf");
        Files.writeString(file, """
            {
              "asset": {"version": "2.0"},
              "scene": 0,
              "scenes": [{"nodes": [0]}],
              "nodes": [{"name": "Root"}],
              "textures": [{"source": 0}],
              "images": [{"name": "minecraft_block_torch"}],
              "materials": [{
                "name": "minecraft_block_torch",
                "pbrMetallicRoughness": {
                  "baseColorTexture": {"index": 0}
                }
              }]
            }
            """, StandardCharsets.UTF_8);

        BlockData lightBlock = new BlockData(4, 8, 12, "minecraft:light", Map.of("level", "6"));
        BlockData torch = new BlockData(5, 8, 12, "minecraft:torch", Map.of());
        GltfPostProcessor.addMinecraftLights(file.toFile(), List.of(lightBlock, torch));

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonArray used = root.getAsJsonArray("extensionsUsed");
        assertEquals("KHR_lights_punctual", used.get(0).getAsString());

        JsonArray gltfLights = root.getAsJsonObject("extensions")
            .getAsJsonObject("KHR_lights_punctual")
            .getAsJsonArray("lights");
        assertEquals(2, gltfLights.size());
        assertEquals("point", gltfLights.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(6.5, gltfLights.get(0).getAsJsonObject().get("range").getAsDouble(), 1e-6);

        JsonArray nodes = root.getAsJsonArray("nodes");
        assertEquals(3, nodes.size());
        JsonObject lightNode = nodes.get(1).getAsJsonObject();
        assertEquals(6, lightNode.getAsJsonObject("extras").get("minecraftLevel").getAsInt());
        assertTrue(lightNode.getAsJsonObject("extras").get("invisibleSource").getAsBoolean());
        assertEquals(1, nodes.get(0).getAsJsonObject().getAsJsonArray("children").get(0).getAsInt());

        JsonObject material = root.getAsJsonArray("materials").get(0).getAsJsonObject();
        assertEquals(3, material.getAsJsonArray("emissiveFactor").size());
        assertEquals(0, material.getAsJsonObject("emissiveTexture").get("index").getAsInt());
        assertTrue(material.getAsJsonObject("extras").get("minesportEmissive").getAsBoolean());
    }
}
