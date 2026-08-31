package dev.kastrick.minesport.region;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Numeric block ID compatibility for pre-flattening Anvil worlds (1.2-1.12).
 *
 * The initial table is deliberately anchored to the Minecraft 1.5-era vanilla
 * registry. Later legacy releases can extend this without changing the chunk
 * decoder. Metadata is always preserved as legacy_data even when we translate
 * a variant to its modern namespaced block ID.
 */
public final class LegacyBlockIds {
    private LegacyBlockIds() {}

    public record DecodedBlock(String blockId, Map<String, String> properties) {}

    public static DecodedBlock decode(int numericId, int metadata) {
        int data = metadata & 0xF;
        String id = switch (numericId) {
            case 0 -> "minecraft:air";
            case 1 -> "minecraft:stone";
            case 2 -> "minecraft:grass_block";
            case 3 -> "minecraft:dirt";
            case 4 -> "minecraft:cobblestone";
            case 5 -> wood("planks", data);
            case 6 -> wood("sapling", data);
            case 7 -> "minecraft:bedrock";
            case 8, 9 -> "minecraft:water";
            case 10, 11 -> "minecraft:lava";
            case 12 -> "minecraft:sand";
            case 13 -> "minecraft:gravel";
            case 14 -> "minecraft:gold_ore";
            case 15 -> "minecraft:iron_ore";
            case 16 -> "minecraft:coal_ore";
            case 17 -> log(data);
            case 18 -> wood("leaves", data);
            case 19 -> "minecraft:sponge";
            case 20 -> "minecraft:glass";
            case 21 -> "minecraft:lapis_ore";
            case 22 -> "minecraft:lapis_block";
            case 23 -> "minecraft:dispenser";
            case 24 -> sandstone(data);
            case 25 -> "minecraft:note_block";
            case 26 -> "minecraft:red_bed";
            case 27 -> "minecraft:powered_rail";
            case 28 -> "minecraft:detector_rail";
            case 29 -> "minecraft:sticky_piston";
            case 30 -> "minecraft:cobweb";
            case 31 -> tallPlant(data);
            case 32 -> "minecraft:dead_bush";
            case 33 -> "minecraft:piston";
            case 34 -> "minecraft:piston_head";
            case 35 -> wool(data);
            case 36 -> "minecraft:moving_piston";
            case 37 -> "minecraft:dandelion";
            case 38 -> "minecraft:poppy";
            case 39 -> "minecraft:brown_mushroom";
            case 40 -> "minecraft:red_mushroom";
            case 41 -> "minecraft:gold_block";
            case 42 -> "minecraft:iron_block";
            case 43, 44 -> stoneSlab(data);
            case 45 -> "minecraft:bricks";
            case 46 -> "minecraft:tnt";
            case 47 -> "minecraft:bookshelf";
            case 48 -> "minecraft:mossy_cobblestone";
            case 49 -> "minecraft:obsidian";
            case 50 -> torch(data, "minecraft:torch", "minecraft:wall_torch");
            case 51 -> "minecraft:fire";
            case 52 -> "minecraft:spawner";
            case 53 -> "minecraft:oak_stairs";
            case 54 -> "minecraft:chest";
            case 55 -> "minecraft:redstone_wire";
            case 56 -> "minecraft:diamond_ore";
            case 57 -> "minecraft:diamond_block";
            case 58 -> "minecraft:crafting_table";
            case 59 -> "minecraft:wheat";
            case 60 -> "minecraft:farmland";
            case 61, 62 -> "minecraft:furnace";
            case 63 -> "minecraft:oak_sign";
            case 64 -> "minecraft:oak_door";
            case 65 -> "minecraft:ladder";
            case 66 -> "minecraft:rail";
            case 67 -> "minecraft:cobblestone_stairs";
            case 68 -> "minecraft:oak_wall_sign";
            case 69 -> "minecraft:lever";
            case 70 -> "minecraft:stone_pressure_plate";
            case 71 -> "minecraft:iron_door";
            case 72 -> "minecraft:oak_pressure_plate";
            case 73, 74 -> "minecraft:redstone_ore";
            case 75, 76 -> torch(data, "minecraft:redstone_torch", "minecraft:redstone_wall_torch");
            case 77 -> "minecraft:stone_button";
            case 78 -> "minecraft:snow";
            case 79 -> "minecraft:ice";
            case 80 -> "minecraft:snow_block";
            case 81 -> "minecraft:cactus";
            case 82 -> "minecraft:clay";
            case 83 -> "minecraft:sugar_cane";
            case 84 -> "minecraft:jukebox";
            case 85 -> "minecraft:oak_fence";
            case 86 -> "minecraft:carved_pumpkin";
            case 87 -> "minecraft:netherrack";
            case 88 -> "minecraft:soul_sand";
            case 89 -> "minecraft:glowstone";
            case 90 -> "minecraft:nether_portal";
            case 91 -> "minecraft:jack_o_lantern";
            case 92 -> "minecraft:cake";
            case 93, 94 -> "minecraft:repeater";
            case 95 -> "minecraft:chest";
            case 96 -> "minecraft:oak_trapdoor";
            case 97 -> infested(data);
            case 98 -> stoneBricks(data);
            case 99 -> "minecraft:brown_mushroom_block";
            case 100 -> "minecraft:red_mushroom_block";
            case 101 -> "minecraft:iron_bars";
            case 102 -> "minecraft:glass_pane";
            case 103 -> "minecraft:melon";
            case 104 -> "minecraft:pumpkin_stem";
            case 105 -> "minecraft:melon_stem";
            case 106 -> "minecraft:vine";
            case 107 -> "minecraft:oak_fence_gate";
            case 108 -> "minecraft:brick_stairs";
            case 109 -> "minecraft:stone_brick_stairs";
            case 110 -> "minecraft:mycelium";
            case 111 -> "minecraft:lily_pad";
            case 112 -> "minecraft:nether_bricks";
            case 113 -> "minecraft:nether_brick_fence";
            case 114 -> "minecraft:nether_brick_stairs";
            case 115 -> "minecraft:nether_wart";
            case 116 -> "minecraft:enchanting_table";
            case 117 -> "minecraft:brewing_stand";
            case 118 -> data == 0 ? "minecraft:cauldron" : "minecraft:water_cauldron";
            case 119 -> "minecraft:end_portal";
            case 120 -> "minecraft:end_portal_frame";
            case 121 -> "minecraft:end_stone";
            case 122 -> "minecraft:dragon_egg";
            case 123, 124 -> "minecraft:redstone_lamp";
            case 125, 126 -> woodSlab(data);
            case 127 -> "minecraft:cocoa";
            case 128 -> "minecraft:sandstone_stairs";
            case 129 -> "minecraft:emerald_ore";
            case 130 -> "minecraft:ender_chest";
            case 131 -> "minecraft:tripwire_hook";
            case 132 -> "minecraft:tripwire";
            case 133 -> "minecraft:emerald_block";
            case 134 -> "minecraft:spruce_stairs";
            case 135 -> "minecraft:birch_stairs";
            case 136 -> "minecraft:jungle_stairs";
            case 137 -> "minecraft:command_block";
            case 138 -> "minecraft:beacon";
            case 139 -> (data & 1) == 1 ? "minecraft:mossy_cobblestone_wall" : "minecraft:cobblestone_wall";
            case 140 -> "minecraft:flower_pot";
            case 141 -> "minecraft:carrots";
            case 142 -> "minecraft:potatoes";
            case 143 -> "minecraft:oak_button";
            case 144 -> "minecraft:skeleton_skull";
            case 145 -> anvil(data);
            case 146 -> "minecraft:trapped_chest";
            case 147 -> "minecraft:light_weighted_pressure_plate";
            case 148 -> "minecraft:heavy_weighted_pressure_plate";
            case 149, 150 -> "minecraft:comparator";
            case 151 -> "minecraft:daylight_detector";
            case 152 -> "minecraft:redstone_block";
            case 153 -> "minecraft:nether_quartz_ore";
            case 154 -> "minecraft:hopper";
            case 155 -> quartz(data);
            case 156 -> "minecraft:quartz_stairs";
            case 157 -> "minecraft:activator_rail";
            case 158 -> "minecraft:dropper";
            default -> "minesport:legacy_block_" + numericId;
        };

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("legacy_id", Integer.toString(numericId));
        properties.put("legacy_data", Integer.toString(data));
        addRenderState(numericId, data, properties);
        return new DecodedBlock(id, properties);
    }

    private static void addRenderState(int numericId, int data, Map<String, String> properties) {
        switch (numericId) {
            case 6 -> properties.put("stage", (data & 8) != 0 ? "1" : "0");
            case 8, 9, 10, 11 -> properties.put("level", Integer.toString(data));
            case 17 -> properties.put("axis", logAxis(data));
            case 23, 158 -> {
                properties.put("facing", facing6(data & 7));
                properties.put("triggered", boolText((data & 8) != 0));
            }
            case 26 -> {
                properties.put("facing", southWestNorthEast(data & 3));
                properties.put("part", (data & 8) != 0 ? "head" : "foot");
                properties.put("occupied", boolText((data & 4) != 0));
            }
            case 27, 28, 157 -> {
                properties.put("shape", poweredRailShape(data & 7));
                properties.put("powered", boolText((data & 8) != 0));
            }
            case 29, 33 -> {
                properties.put("facing", facing6(data & 7));
                properties.put("extended", boolText((data & 8) != 0));
            }
            case 34 -> {
                properties.put("facing", facing6(data & 7));
                properties.put("type", (data & 8) != 0 ? "sticky" : "normal");
                properties.put("short", "false");
            }
            case 43 -> slabState(properties, true, false);
            case 44 -> slabState(properties, false, (data & 8) != 0);
            case 50 -> wallTorchState(data, properties);
            case 51 -> properties.put("age", Integer.toString(data));
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156 -> stairState(data, properties);
            case 54, 95, 146 -> {
                properties.put("facing", facingHorizontal2To5(data));
                properties.put("type", "single");
                properties.put("waterlogged", "false");
            }
            case 55 -> properties.put("power", Integer.toString(data));
            case 59 -> properties.put("age", Integer.toString(data & 7));
            case 60 -> properties.put("moisture", Integer.toString(Math.min(data, 7)));
            case 61, 62 -> {
                properties.put("facing", facingHorizontal2To5(data));
                properties.put("lit", boolText(numericId == 62));
            }
            case 63 -> {
                properties.put("rotation", Integer.toString(data));
                properties.put("waterlogged", "false");
            }
            case 65 -> {
                properties.put("facing", facingHorizontal2To5(data));
                properties.put("waterlogged", "false");
            }
            case 66 -> {
                properties.put("shape", railShape(data));
                properties.put("waterlogged", "false");
            }
            case 68 -> {
                properties.put("facing", facingHorizontal2To5(data));
                properties.put("waterlogged", "false");
            }
            case 70, 72 -> properties.put("powered", boolText((data & 1) != 0));
            case 75, 76 -> {
                wallTorchState(data, properties);
                properties.put("lit", boolText(numericId == 76));
            }
            case 78 -> properties.put("layers", Integer.toString(Math.min(8, data + 1)));
            case 81, 83 -> properties.put("age", Integer.toString(data));
            case 84 -> properties.put("has_record", boolText(data != 0));
            case 86, 91 -> properties.put("facing", southWestNorthEast(data & 3));
            case 92 -> properties.put("bites", Integer.toString(Math.min(6, data)));
            case 93, 94 -> {
                properties.put("facing", northEastSouthWest(data & 3));
                properties.put("delay", Integer.toString(((data >> 2) & 3) + 1));
                properties.put("locked", "false");
                properties.put("powered", boolText(numericId == 94));
            }
            case 96 -> {
                properties.put("facing", trapdoorFacing(data & 3));
                properties.put("open", boolText((data & 4) != 0));
                properties.put("half", (data & 8) != 0 ? "top" : "bottom");
                properties.put("powered", "false");
                properties.put("waterlogged", "false");
            }
            case 104, 105 -> properties.put("age", Integer.toString(data & 7));
            case 106 -> {
                properties.put("south", boolText((data & 1) != 0));
                properties.put("west", boolText((data & 2) != 0));
                properties.put("north", boolText((data & 4) != 0));
                properties.put("east", boolText((data & 8) != 0));
                properties.put("up", "false");
            }
            case 107 -> {
                properties.put("facing", southWestNorthEast(data & 3));
                properties.put("open", boolText((data & 4) != 0));
                properties.put("powered", "false");
                properties.put("in_wall", "false");
            }
            case 115 -> properties.put("age", Integer.toString(data & 3));
            case 117 -> {
                properties.put("has_bottle_0", boolText((data & 1) != 0));
                properties.put("has_bottle_1", boolText((data & 2) != 0));
                properties.put("has_bottle_2", boolText((data & 4) != 0));
            }
            case 118 -> {
                if (data != 0) properties.put("level", Integer.toString(Math.min(3, data)));
            }
            case 120 -> {
                properties.put("facing", southWestNorthEast(data & 3));
                properties.put("eye", boolText((data & 4) != 0));
            }
            case 123, 124 -> properties.put("lit", boolText(numericId == 124));
            case 125 -> slabState(properties, true, false);
            case 126 -> slabState(properties, false, (data & 8) != 0);
            case 127 -> {
                properties.put("facing", southWestNorthEast(data & 3));
                properties.put("age", Integer.toString(Math.min(2, (data >> 2) & 3)));
            }
            case 130 -> {
                properties.put("facing", facingHorizontal2To5(data));
                properties.put("waterlogged", "false");
            }
            case 141, 142 -> properties.put("age", Integer.toString(data & 7));
            case 145 -> properties.put("facing", northEastSouthWest(data & 3));
            case 147, 148 -> properties.put("power", Integer.toString(data));
            case 149, 150 -> {
                properties.put("facing", northEastSouthWest(data & 3));
                properties.put("mode", (data & 4) != 0 ? "subtract" : "compare");
                properties.put("powered", boolText(numericId == 150 || (data & 8) != 0));
            }
            case 151 -> properties.put("power", Integer.toString(data));
            case 154 -> {
                properties.put("facing", facing6(data & 7));
                properties.put("enabled", boolText((data & 8) == 0));
            }
            case 155 -> {
                if (data >= 2 && data <= 4) properties.put("axis", quartzAxis(data));
            }
            default -> { }
        }
    }

    private static void slabState(Map<String, String> properties, boolean doubled, boolean top) {
        properties.put("type", doubled ? "double" : (top ? "top" : "bottom"));
        properties.put("waterlogged", "false");
    }

    private static void stairState(int data, Map<String, String> properties) {
        properties.put("facing", stairsFacing(data & 3));
        properties.put("half", (data & 4) != 0 ? "top" : "bottom");
        properties.put("shape", "straight");
        properties.put("waterlogged", "false");
    }

    private static void wallTorchState(int data, Map<String, String> properties) {
        if (data >= 1 && data <= 4) properties.put("facing", torchFacing(data));
    }

    private static String torch(int data, String standing, String wall) {
        return data >= 1 && data <= 4 ? wall : standing;
    }

    private static String log(int data) {
        String species = woodSpecies(data);
        return "minecraft:" + species + (((data & 12) == 12) ? "_wood" : "_log");
    }

    private static String logAxis(int data) {
        return switch (data & 12) {
            case 4 -> "x";
            case 8 -> "z";
            default -> "y";
        };
    }

    private static String wood(String suffix, int data) {
        return "minecraft:" + woodSpecies(data) + "_" + suffix;
    }

    private static String woodSpecies(int data) {
        return switch (data & 3) {
            case 1 -> "spruce";
            case 2 -> "birch";
            case 3 -> "jungle";
            default -> "oak";
        };
    }

    private static String wool(int data) {
        String[] colors = {
            "white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
        };
        return "minecraft:" + colors[data & 15] + "_wool";
    }

    private static String sandstone(int data) {
        return switch (data & 3) {
            case 1 -> "minecraft:chiseled_sandstone";
            case 2 -> "minecraft:cut_sandstone";
            default -> "minecraft:sandstone";
        };
    }

    private static String tallPlant(int data) {
        return switch (data & 3) {
            case 2 -> "minecraft:fern";
            case 1 -> "minecraft:short_grass";
            default -> "minecraft:dead_bush";
        };
    }

    private static String stoneSlab(int data) {
        return switch (data & 7) {
            case 1 -> "minecraft:sandstone_slab";
            case 2 -> "minecraft:oak_slab";
            case 3 -> "minecraft:cobblestone_slab";
            case 4 -> "minecraft:brick_slab";
            case 5 -> "minecraft:stone_brick_slab";
            case 6 -> "minecraft:nether_brick_slab";
            case 7 -> "minecraft:quartz_slab";
            default -> "minecraft:smooth_stone_slab";
        };
    }

    private static String woodSlab(int data) {
        return "minecraft:" + woodSpecies(data) + "_slab";
    }

    private static String infested(int data) {
        return switch (data & 7) {
            case 1 -> "minecraft:infested_cobblestone";
            case 2 -> "minecraft:infested_stone_bricks";
            case 3 -> "minecraft:infested_mossy_stone_bricks";
            case 4 -> "minecraft:infested_cracked_stone_bricks";
            case 5 -> "minecraft:infested_chiseled_stone_bricks";
            default -> "minecraft:infested_stone";
        };
    }

    private static String stoneBricks(int data) {
        return switch (data & 3) {
            case 1 -> "minecraft:mossy_stone_bricks";
            case 2 -> "minecraft:cracked_stone_bricks";
            case 3 -> "minecraft:chiseled_stone_bricks";
            default -> "minecraft:stone_bricks";
        };
    }

    private static String anvil(int data) {
        return switch ((data >> 2) & 3) {
            case 1 -> "minecraft:chipped_anvil";
            case 2, 3 -> "minecraft:damaged_anvil";
            default -> "minecraft:anvil";
        };
    }

    private static String quartz(int data) {
        return switch (data) {
            case 1 -> "minecraft:chiseled_quartz_block";
            case 2, 3, 4 -> "minecraft:quartz_pillar";
            default -> "minecraft:quartz_block";
        };
    }

    private static String quartzAxis(int data) {
        return switch (data) {
            case 3 -> "x";
            case 4 -> "z";
            default -> "y";
        };
    }

    private static String facing6(int value) {
        return switch (value) {
            case 0 -> "down";
            case 1 -> "up";
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "north";
        };
    }

    private static String facingHorizontal2To5(int value) {
        return switch (value & 7) {
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "north";
        };
    }

    private static String southWestNorthEast(int value) {
        return switch (value & 3) {
            case 1 -> "west";
            case 2 -> "north";
            case 3 -> "east";
            default -> "south";
        };
    }

    private static String northEastSouthWest(int value) {
        return switch (value & 3) {
            case 1 -> "east";
            case 2 -> "south";
            case 3 -> "west";
            default -> "north";
        };
    }

    private static String stairsFacing(int value) {
        return switch (value & 3) {
            case 1 -> "west";
            case 2 -> "south";
            case 3 -> "north";
            default -> "east";
        };
    }

    private static String torchFacing(int value) {
        return switch (value) {
            case 1 -> "east";
            case 2 -> "west";
            case 3 -> "south";
            default -> "north";
        };
    }

    private static String trapdoorFacing(int value) {
        return switch (value & 3) {
            case 1 -> "south";
            case 2 -> "west";
            case 3 -> "east";
            default -> "north";
        };
    }

    private static String railShape(int value) {
        return switch (value) {
            case 1 -> "east_west";
            case 2 -> "ascending_east";
            case 3 -> "ascending_west";
            case 4 -> "ascending_north";
            case 5 -> "ascending_south";
            case 6 -> "south_east";
            case 7 -> "south_west";
            case 8 -> "north_west";
            case 9 -> "north_east";
            default -> "north_south";
        };
    }

    private static String poweredRailShape(int value) {
        return switch (value & 7) {
            case 1 -> "east_west";
            case 2 -> "ascending_east";
            case 3 -> "ascending_west";
            case 4 -> "ascending_north";
            case 5 -> "ascending_south";
            default -> "north_south";
        };
    }

    private static String boolText(boolean value) {
        return value ? "true" : "false";
    }
}
