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
            case 8 -> "minecraft:water";
            case 9 -> "minecraft:water";
            case 10 -> "minecraft:lava";
            case 11 -> "minecraft:lava";
            case 12 -> "minecraft:sand";
            case 13 -> "minecraft:gravel";
            case 14 -> "minecraft:gold_ore";
            case 15 -> "minecraft:iron_ore";
            case 16 -> "minecraft:coal_ore";
            case 17 -> wood("log", data);
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
            case 43 -> stoneSlab(data);
            case 44 -> stoneSlab(data);
            case 45 -> "minecraft:bricks";
            case 46 -> "minecraft:tnt";
            case 47 -> "minecraft:bookshelf";
            case 48 -> "minecraft:mossy_cobblestone";
            case 49 -> "minecraft:obsidian";
            case 50 -> "minecraft:torch";
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
            case 75, 76 -> "minecraft:redstone_torch";
            case 77 -> "minecraft:stone_button";
            case 78 -> "minecraft:snow";
            case 79 -> "minecraft:ice";
            case 80 -> "minecraft:snow_block";
            case 81 -> "minecraft:cactus";
            case 82 -> "minecraft:clay";
            case 83 -> "minecraft:sugar_cane";
            case 84 -> "minecraft:jukebox";
            case 85 -> "minecraft:oak_fence";
            case 86 -> "minecraft:pumpkin";
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
            case 118 -> "minecraft:cauldron";
            case 119 -> "minecraft:end_portal";
            case 120 -> "minecraft:end_portal_frame";
            case 121 -> "minecraft:end_stone";
            case 122 -> "minecraft:dragon_egg";
            case 123, 124 -> "minecraft:redstone_lamp";
            case 125 -> woodSlab(data);
            case 126 -> woodSlab(data);
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
        return new DecodedBlock(id, properties);
    }

    private static String wood(String suffix, int data) {
        String species = switch (data & 3) {
            case 1 -> "spruce";
            case 2 -> "birch";
            case 3 -> "jungle";
            default -> "oak";
        };
        return "minecraft:" + species + "_" + suffix;
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
        String species = switch (data & 3) {
            case 1 -> "spruce";
            case 2 -> "birch";
            case 3 -> "jungle";
            default -> "oak";
        };
        return "minecraft:" + species + "_slab";
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
        return switch (data & 3) {
            case 1 -> "minecraft:chiseled_quartz_block";
            case 2 -> "minecraft:quartz_pillar";
            default -> "minecraft:quartz_block";
        };
    }
}
