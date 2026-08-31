package dev.kastrick.minesport.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyBlockIdsStateTest {
    @Test
    void decodesGeometryCriticalLegacyMetadataIntoModernStates() {
        assertBlock(17, 5, "minecraft:spruce_log", "axis", "x");
        assertBlock(17, 12, "minecraft:oak_wood", "axis", "y");

        assertBlock(53, 5, "minecraft:oak_stairs", "facing", "west");
        assertProperty(53, 5, "half", "top");
        assertProperty(53, 5, "shape", "straight");

        assertBlock(27, 10, "minecraft:powered_rail", "shape", "ascending_east");
        assertProperty(27, 10, "powered", "true");
        assertBlock(66, 9, "minecraft:rail", "shape", "north_east");

        assertBlock(29, 13, "minecraft:sticky_piston", "facing", "east");
        assertProperty(29, 13, "extended", "true");

        assertBlock(50, 1, "minecraft:wall_torch", "facing", "east");
        assertBlock(75, 4, "minecraft:redstone_wall_torch", "facing", "north");
        assertProperty(75, 4, "lit", "false");

        assertBlock(26, 11, "minecraft:red_bed", "facing", "east");
        assertProperty(26, 11, "part", "head");
        assertProperty(26, 11, "occupied", "false");

        assertBlock(62, 2, "minecraft:furnace", "facing", "north");
        assertProperty(62, 2, "lit", "true");

        assertBlock(96, 12, "minecraft:oak_trapdoor", "facing", "north");
        assertProperty(96, 12, "open", "true");
        assertProperty(96, 12, "half", "top");

        assertBlock(107, 5, "minecraft:oak_fence_gate", "facing", "west");
        assertProperty(107, 5, "open", "true");

        assertBlock(94, 11, "minecraft:repeater", "facing", "west");
        assertProperty(94, 11, "delay", "3");
        assertProperty(94, 11, "powered", "true");

        assertBlock(149, 14, "minecraft:comparator", "facing", "south");
        assertProperty(149, 14, "mode", "subtract");
        assertProperty(149, 14, "powered", "true");

        assertBlock(154, 13, "minecraft:hopper", "facing", "east");
        assertProperty(154, 13, "enabled", "false");

        assertBlock(118, 3, "minecraft:water_cauldron", "level", "3");
        assertBlock(86, 2, "minecraft:carved_pumpkin", "facing", "north");
        assertBlock(155, 3, "minecraft:quartz_pillar", "axis", "x");

        assertProperty(106, 9, "south", "true");
        assertProperty(106, 9, "east", "true");
        assertProperty(106, 9, "north", "false");
        assertProperty(78, 7, "layers", "8");
        assertProperty(6, 9, "stage", "1");
    }

    @Test
    void preservesRawLegacyIdentityAlongsideTranslatedState() {
        LegacyBlockIds.DecodedBlock block = LegacyBlockIds.decode(53, 5);
        assertEquals("53", block.properties().get("legacy_id"));
        assertEquals("5", block.properties().get("legacy_data"));
    }

    private static void assertBlock(int id, int data, String expectedId, String key, String value) {
        LegacyBlockIds.DecodedBlock decoded = LegacyBlockIds.decode(id, data);
        assertEquals(expectedId, decoded.blockId());
        assertEquals(value, decoded.properties().get(key));
    }

    private static void assertProperty(int id, int data, String key, String value) {
        assertEquals(value, LegacyBlockIds.decode(id, data).properties().get(key));
    }
}
