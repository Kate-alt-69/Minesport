package dev.kastrick.minesport.export;

import dev.kastrick.minesport.nbt.NbtCompound;
import dev.kastrick.minesport.nbt.NbtReader;
import dev.kastrick.minesport.region.ScheduledTickData;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LitematicTickExporterTest {
    @Test
    void writesRelativeBlockAndFluidTicks() throws Exception {
        var blockTicks = List.of(new ScheduledTickData(101, 64, -29, "minecraft:redstone_wire", 7, 2));
        var fluidTicks = List.of(new ScheduledTickData(102, 63, -28, "minecraft:water", -2, -1));
        var output = Files.createTempFile("minesport-ticks-", ".litematic").toFile();
        try {
            var stats = LitematicExporter.export(
                List.of(), List.of(), List.of(), blockTicks, fluidTicks,
                100, 60, -30, 102, 66, -28,
                "Tick Test", "Minesport", "unit test", 4189, output
            );

            assertEquals(1, stats.blockTickCount());
            assertEquals(1, stats.fluidTickCount());
            NbtCompound root = NbtReader.readGzip(output);
            assertEquals(7, root.getInt("Version"));
            NbtCompound region = root.getCompound("Regions").getCompound("Tick Test");

            NbtCompound block = (NbtCompound) region.getList("PendingBlockTicks").getFirst();
            assertEquals("minecraft:redstone_wire", block.getString("Block"));
            assertEquals(2, block.getInt("Priority"));
            assertEquals(7, block.getInt("Time"));
            assertEquals(0L, block.getLong("SubTick"));
            assertEquals(1, block.getInt("x"));
            assertEquals(4, block.getInt("y"));
            assertEquals(1, block.getInt("z"));

            NbtCompound fluid = (NbtCompound) region.getList("PendingFluidTicks").getFirst();
            assertEquals("minecraft:water", fluid.getString("Fluid"));
            assertEquals(-1, fluid.getInt("Priority"));
            assertEquals(-2, fluid.getInt("Time"));
            assertEquals(0L, fluid.getLong("SubTick"));
            assertEquals(2, fluid.getInt("x"));
            assertEquals(3, fluid.getInt("y"));
            assertEquals(2, fluid.getInt("z"));
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }
}
