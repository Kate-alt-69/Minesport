package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

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
    void invisibleLightBlockUsesItsStateLevel() {
        BlockData light = new BlockData(1, 2, 3, "minecraft:light", Map.of("level", "6"));
        var resolved = MinecraftLightExporter.resolve(List.of(light));

        assertEquals(1, resolved.size());
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
}
