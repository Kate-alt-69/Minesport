package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RegionReaderScheduledTickTest {
    @Test
    void extractsModernResolvedBlockAndFluidTicks() {
        var block = tick("minecraft:redstone_wire", 101, 64, -29, 7, 2);
        var outside = tick("minecraft:stone", 500, 64, 500, 1, 0);
        var fluid = tick("minecraft:water", 102, 63, -28, -2, -1);
        var chunk = new NbtCompound(Map.of(
            "block_ticks", List.of(block, outside),
            "fluid_ticks", List.of(fluid)
        ));

        var blocks = new ArrayList<ScheduledTickData>();
        var fluids = new ArrayList<ScheduledTickData>();
        RegionReader.extractScheduledTicks(
            chunk, 100, 60, -30, 110, 70, -20, blocks, fluids
        );

        assertEquals(1, blocks.size());
        assertEquals("minecraft:redstone_wire", blocks.getFirst().id());
        assertEquals(7, blocks.getFirst().delay());
        assertEquals(2, blocks.getFirst().priority());
        assertEquals(1, fluids.size());
        assertEquals("minecraft:water", fluids.getFirst().id());
        assertEquals(-2, fluids.getFirst().delay());
        assertEquals(-1, fluids.getFirst().priority());
    }

    @Test
    void extractsLegacyLevelTileAndLiquidTicks() {
        var level = new NbtCompound(Map.of(
            "TileTicks", List.of(tick("minecraft:sand", 4, 70, 5, 3, 1)),
            "LiquidTicks", List.of(tick("minecraft:lava", 5, 69, 5, 9, 0))
        ));
        var chunk = new NbtCompound(Map.of("Level", level));

        var blocks = new ArrayList<ScheduledTickData>();
        var fluids = new ArrayList<ScheduledTickData>();
        RegionReader.extractScheduledTicks(
            chunk, 0, 0, 0, 15, 255, 15, blocks, fluids
        );

        assertEquals("minecraft:sand", blocks.getFirst().id());
        assertEquals(3, blocks.getFirst().delay());
        assertEquals("minecraft:lava", fluids.getFirst().id());
        assertEquals(9, fluids.getFirst().delay());
    }

    private static NbtCompound tick(String id, int x, int y, int z, int delay, int priority) {
        var tag = new LinkedHashMap<String, Object>();
        tag.put("i", id);
        tag.put("x", x);
        tag.put("y", y);
        tag.put("z", z);
        tag.put("t", delay);
        tag.put("p", priority);
        return new NbtCompound(tag);
    }
}
