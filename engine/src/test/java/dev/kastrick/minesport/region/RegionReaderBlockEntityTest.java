package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegionReaderBlockEntityTest {
    @Test
    void extractsModernBlockEntitiesAndFiltersBounds() {
        NbtCompound inside = blockEntity("minecraft:chest", 4, 70, -2);
        NbtCompound outside = blockEntity("minecraft:sign", 40, 70, -2);
        NbtCompound chunk = new NbtCompound(new LinkedHashMap<>(Map.of(
            "block_entities", List.of(inside, outside)
        )));

        var result = new ArrayList<BlockEntityData>();
        RegionReader.extractBlockEntities(
            chunk,
            0, 60, -10,
            10, 80, 10,
            result
        );

        assertEquals(1, result.size());
        assertEquals("minecraft:chest", result.getFirst().nbt().getString("id"));
        assertEquals(4, result.getFirst().x());
    }

    @Test
    void extractsLegacyLevelTileEntities() {
        NbtCompound furnace = blockEntity("minecraft:furnace", -3, 12, 9);
        var levelTags = new LinkedHashMap<String, Object>();
        levelTags.put("TileEntities", List.of(furnace));

        var chunkTags = new LinkedHashMap<String, Object>();
        chunkTags.put("Level", new NbtCompound(levelTags));

        var result = new ArrayList<BlockEntityData>();
        RegionReader.extractBlockEntities(
            new NbtCompound(chunkTags),
            -10, 0, 0,
            0, 20, 20,
            result
        );

        assertEquals(1, result.size());
        assertEquals("minecraft:furnace", result.getFirst().nbt().getString("id"));
        assertEquals(9, result.getFirst().z());
    }

    private static NbtCompound blockEntity(String id, int x, int y, int z) {
        var tags = new LinkedHashMap<String, Object>();
        tags.put("id", id);
        tags.put("x", x);
        tags.put("y", y);
        tags.put("z", z);
        return new NbtCompound(tags);
    }
}
