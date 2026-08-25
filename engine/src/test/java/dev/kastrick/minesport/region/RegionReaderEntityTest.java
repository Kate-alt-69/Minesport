package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegionReaderEntityTest {
    @Test
    void extractsModernEntityRegionEntriesAndFiltersBounds() {
        NbtCompound inside = entity("minecraft:armor_stand", 2.5d, 65.0d, -3.25d);
        NbtCompound outside = entity("minecraft:cow", 50.0d, 65.0d, -3.25d);

        var chunkTags = new LinkedHashMap<String, Object>();
        chunkTags.put("Entities", List.of(inside, outside));

        var result = new ArrayList<EntityData>();
        RegionReader.extractEntities(
            new NbtCompound(chunkTags),
            0, 60, -10,
            10, 80, 10,
            result
        );

        assertEquals(1, result.size());
        assertEquals("minecraft:armor_stand", result.getFirst().nbt().getString("id"));
        assertEquals(2.5d, result.getFirst().x(), 0.00001d);
    }

    @Test
    void extractsLegacyLevelEntities() {
        NbtCompound sheep = entity("minecraft:sheep", -2.0d, 12.5d, 9.75d);
        var levelTags = new LinkedHashMap<String, Object>();
        levelTags.put("Entities", List.of(sheep));
        var chunkTags = new LinkedHashMap<String, Object>();
        chunkTags.put("Level", new NbtCompound(levelTags));

        var result = new ArrayList<EntityData>();
        RegionReader.extractEntities(
            new NbtCompound(chunkTags),
            -10, 0, 0,
            0, 20, 20,
            result
        );

        assertEquals(1, result.size());
        assertEquals("minecraft:sheep", result.getFirst().nbt().getString("id"));
        assertEquals(9.75d, result.getFirst().z(), 0.00001d);
    }

    private static NbtCompound entity(String id, double x, double y, double z) {
        var tags = new LinkedHashMap<String, Object>();
        tags.put("id", id);
        tags.put("Pos", List.of(x, y, z));
        return new NbtCompound(tags);
    }
}
