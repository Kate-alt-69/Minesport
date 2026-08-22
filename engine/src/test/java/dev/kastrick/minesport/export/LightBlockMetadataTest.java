package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightBlockMetadataTest {
    @Test
    void logicalArrayKeepsMinecraftCoordinatesAndLevel() {
        BlockData light = new BlockData(12, 65, -2, "minecraft:light", Map.of("level", "6"));
        JsonArray records = MinecraftLightExporter.sidecarLightBlocks(
            List.of(light),
            new float[]{10.0f, 64.0f, -4.0f}
        );

        assertEquals(1, records.size());
        JsonObject record = records.get(0).getAsJsonObject();
        assertEquals(12, record.get("x").getAsInt());
        assertEquals(65, record.get("y").getAsInt());
        assertEquals(-2, record.get("z").getAsInt());
        assertEquals(6, record.get("level").getAsInt());
        assertTrue(record.get("invisibleSource").getAsBoolean());

        JsonArray position = record.getAsJsonArray("blenderPosition");
        assertEquals(2.5, position.get(0).getAsDouble(), 1e-6);
        assertEquals(-2.5, position.get(1).getAsDouble(), 1e-6);
        assertEquals(1.5, position.get(2).getAsDouble(), 1e-6);
    }

    @Test
    void visibleEmittersAlsoEnterLogicalArray() {
        BlockData torch = new BlockData(1, 2, 3, "minecraft:torch", Map.of());
        JsonObject record = MinecraftLightExporter.sidecarLightBlocks(
            List.of(torch),
            new float[]{0, 0, 0}
        ).get(0).getAsJsonObject();

        assertEquals(14, record.get("level").getAsInt());
        assertEquals("minecraft:torch", record.get("source").getAsString());
        assertEquals(0.72, record.getAsJsonArray("localOffset").get(1).getAsDouble(), 1e-6);
    }
}
