from pathlib import Path


def replace_once(path, old, new):
    path = Path(path)
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    path.write_text(text.replace(old, new, 1))


def write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


write(
    "engine/src/main/java/dev/kastrick/minesport/region/ScheduledTickData.java",
    '''package dev.kastrick.minesport.region;\n\n/** Resolved Minecraft scheduled tick with a world-space position and relative delay. */\npublic record ScheduledTickData(\n    int x, int y, int z,\n    String id,\n    int delay,\n    int priority\n) {}\n'''
)

# RegionReader: collect resolved block/fluid ticks only for the rich Litematica path.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''    public record RegionContents(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities\n    ) {}''',
    '''    public record RegionContents(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        List<ScheduledTickData> blockTicks,\n        List<ScheduledTickData> fluidTicks\n    ) {}'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''            progress,\n            true,\n            false,\n            false\n        ).blocks();''',
    '''            progress,\n            true,\n            false,\n            false,\n            false\n        ).blocks();'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''            progress,\n            true,\n            true,\n            true\n        );''',
    '''            progress,\n            true,\n            true,\n            true,\n            true\n        );'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''            null,\n            false,\n            false,\n            true\n        ).entities();''',
    '''            null,\n            false,\n            false,\n            true,\n            false\n        ).entities();'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''            ProgressCallback progress,\n            boolean decodeBlocks,\n            boolean includeBlockEntities,\n            boolean includeEntities\n    ) throws IOException {''',
    '''            ProgressCallback progress,\n            boolean decodeBlocks,\n            boolean includeBlockEntities,\n            boolean includeEntities,\n            boolean includeScheduledTicks\n    ) throws IOException {'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''        var blocks = new ArrayList<BlockData>();\n        var blockEntities = new ArrayList<BlockEntityData>();\n        var entities = new ArrayList<EntityData>();''',
    '''        var blocks = new ArrayList<BlockData>();\n        var blockEntities = new ArrayList<BlockEntityData>();\n        var entities = new ArrayList<EntityData>();\n        var blockTicks = new ArrayList<ScheduledTickData>();\n        var fluidTicks = new ArrayList<ScheduledTickData>();'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''            if (raf.length() < SECTOR_SIZE * 2L) {\n                return new RegionContents(blocks, blockEntities, entities);\n            }''',
    '''            if (raf.length() < SECTOR_SIZE * 2L) {\n                return new RegionContents(blocks, blockEntities, entities, blockTicks, fluidTicks);\n            }'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''                    if (includeEntities) {\n                        extractEntities(\n                            chunkNbt,\n                            minX, minY, minZ,\n                            maxX, maxY, maxZ,\n                            entities\n                        );\n                    }''',
    '''                    if (includeEntities) {\n                        extractEntities(\n                            chunkNbt,\n                            minX, minY, minZ,\n                            maxX, maxY, maxZ,\n                            entities\n                        );\n                    }\n                    if (includeScheduledTicks) {\n                        extractScheduledTicks(\n                            chunkNbt,\n                            minX, minY, minZ,\n                            maxX, maxY, maxZ,\n                            blockTicks,\n                            fluidTicks\n                        );\n                    }'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''        return new RegionContents(blocks, blockEntities, entities);\n    }\n\n    static void extractBlockEntities''',
    '''        return new RegionContents(blocks, blockEntities, entities, blockTicks, fluidTicks);\n    }\n\n    static void extractBlockEntities'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java",
    '''    private static byte[] decompress(byte[] data, int type) {''',
    '''    static void extractScheduledTicks(\n        NbtCompound chunk,\n        int minX, int minY, int minZ,\n        int maxX, int maxY, int maxZ,\n        List<ScheduledTickData> blockOut,\n        List<ScheduledTickData> fluidOut\n    ) {\n        if (chunk == null) return;\n\n        NbtCompound level = chunk;\n        if (chunk.has("Level")) {\n            try {\n                level = chunk.getCompound("Level");\n            } catch (Exception ignored) {}\n        }\n\n        appendTicks(findTickList(chunk, level, "block_ticks", "TileTicks"),\n            minX, minY, minZ, maxX, maxY, maxZ, blockOut);\n        appendTicks(findTickList(chunk, level, "fluid_ticks", "LiquidTicks"),\n            minX, minY, minZ, maxX, maxY, maxZ, fluidOut);\n    }\n\n    private static List<Object> findTickList(\n        NbtCompound root,\n        NbtCompound level,\n        String modernKey,\n        String legacyKey\n    ) {\n        try {\n            if (root.has(modernKey)) return root.getList(modernKey);\n            if (level.has(legacyKey)) return level.getList(legacyKey);\n            if (root.has(legacyKey)) return root.getList(legacyKey);\n        } catch (Exception ignored) {}\n        return List.of();\n    }\n\n    private static void appendTicks(\n        List<Object> entries,\n        int minX, int minY, int minZ,\n        int maxX, int maxY, int maxZ,\n        List<ScheduledTickData> out\n    ) {\n        if (entries == null || out == null) return;\n        for (Object entry : entries) {\n            if (!(entry instanceof NbtCompound tick)) continue;\n            try {\n                String id = tick.getString("i");\n                int x = tick.getInt("x");\n                int y = tick.getInt("y");\n                int z = tick.getInt("z");\n                if (\n                    x < minX || x > maxX ||\n                    y < minY || y > maxY ||\n                    z < minZ || z > maxZ\n                ) continue;\n                out.add(new ScheduledTickData(\n                    x, y, z, id,\n                    tick.getInt("t", 0),\n                    tick.getInt("p", 0)\n                ));\n            } catch (Exception ignored) {\n                // Malformed tick: keep exporting the surrounding chunk.\n            }\n        }\n    }\n\n    private static byte[] decompress(byte[] data, int type) {'''
)

# IPC: carry ticks through box/bubble/exact selections and into the exporter.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''            var allBlocks = new ArrayList<BlockData>();\n            var allBlockEntities = new ArrayList<BlockEntityData>();\n            var allEntities = new ArrayList<EntityData>();''',
    '''            var allBlocks = new ArrayList<BlockData>();\n            var allBlockEntities = new ArrayList<BlockEntityData>();\n            var allEntities = new ArrayList<EntityData>();\n            var allBlockTicks = new ArrayList<ScheduledTickData>();\n            var allFluidTicks = new ArrayList<ScheduledTickData>();'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''                    allBlocks.addAll(contents.blocks());\n                    allBlockEntities.addAll(contents.blockEntities());\n                    if (!separateEntityRegions) {''',
    '''                    allBlocks.addAll(contents.blocks());\n                    allBlockEntities.addAll(contents.blockEntities());\n                    allBlockTicks.addAll(contents.blockTicks());\n                    allFluidTicks.addAll(contents.fluidTicks());\n                    if (!separateEntityRegions) {'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''                    ? " · block entities: " + allBlockEntities.size()\n                        + " · entities: " + allEntities.size()\n                    : "")''',
    '''                    ? " · block entities: " + allBlockEntities.size()\n                        + " · entities: " + allEntities.size()\n                        + " · block ticks: " + allBlockTicks.size()\n                        + " · fluid ticks: " + allFluidTicks.size()\n                    : "")'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''                allEntities.removeIf(entity -> !insideEllipsoidPoint(\n                    entity.x(), entity.y(), entity.z(),\n                    centerX, centerY, centerZ,\n                    Math.max(radiusX, 1),\n                    Math.max(radiusY, 1),\n                    Math.max(radiusZ, 1)\n                ));''',
    '''                allEntities.removeIf(entity -> !insideEllipsoidPoint(\n                    entity.x(), entity.y(), entity.z(),\n                    centerX, centerY, centerZ,\n                    Math.max(radiusX, 1),\n                    Math.max(radiusY, 1),\n                    Math.max(radiusZ, 1)\n                ));\n                allBlockTicks.removeIf(tick -> !insideEllipsoid(\n                    tick.x(), tick.y(), tick.z(),\n                    centerX, centerY, centerZ,\n                    Math.max(radiusX, 1),\n                    Math.max(radiusY, 1),\n                    Math.max(radiusZ, 1)\n                ));\n                allFluidTicks.removeIf(tick -> !insideEllipsoid(\n                    tick.x(), tick.y(), tick.z(),\n                    centerX, centerY, centerZ,\n                    Math.max(radiusX, 1),\n                    Math.max(radiusY, 1),\n                    Math.max(radiusZ, 1)\n                ));'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''                allEntities.removeIf(entity ->\n                    !exact.contains(SpatialKey.of(\n                        (int)Math.floor(entity.x()),\n                        (int)Math.floor(entity.y()),\n                        (int)Math.floor(entity.z())\n                    ))\n                );''',
    '''                allEntities.removeIf(entity ->\n                    !exact.contains(SpatialKey.of(\n                        (int)Math.floor(entity.x()),\n                        (int)Math.floor(entity.y()),\n                        (int)Math.floor(entity.z())\n                    ))\n                );\n                allBlockTicks.removeIf(tick ->\n                    !exact.contains(SpatialKey.of(tick.x(), tick.y(), tick.z()))\n                );\n                allFluidTicks.removeIf(tick ->\n                    !exact.contains(SpatialKey.of(tick.x(), tick.y(), tick.z()))\n                );'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''                    allBlocks,\n                    allBlockEntities,\n                    allEntities,\n                    minX, minY, minZ,''',
    '''                    allBlocks,\n                    allBlockEntities,\n                    allEntities,\n                    allBlockTicks,\n                    allFluidTicks,\n                    minX, minY, minZ,'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''                    + schematicStats.entityCount() + " entities, "\n                    + schematicStats.paletteSize() + " palette states, "''',
    '''                    + schematicStats.entityCount() + " entities, "\n                    + schematicStats.blockTickCount() + " block ticks, "\n                    + schematicStats.fluidTickCount() + " fluid ticks, "\n                    + schematicStats.paletteSize() + " palette states, "'''
)

# Exporter: add tick-aware overload and serialize Litematica's Pending*Ticks lists.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''import dev.kastrick.minesport.region.EntityData;''',
    '''import dev.kastrick.minesport.region.EntityData;\nimport dev.kastrick.minesport.region.ScheduledTickData;'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''        int volume,\n        int blockEntityCount,\n        int entityCount\n    ) {}''',
    '''        int volume,\n        int blockEntityCount,\n        int entityCount,\n        int blockTickCount,\n        int fluidTickCount\n    ) {}'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''            blocks,\n            List.of(),\n            List.of(),\n            firstX, firstY, firstZ,''',
    '''            blocks,\n            List.of(),\n            List.of(),\n            List.of(),\n            List.of(),\n            firstX, firstY, firstZ,'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''            blocks,\n            blockEntities,\n            List.of(),\n            firstX, firstY, firstZ,''',
    '''            blocks,\n            blockEntities,\n            List.of(),\n            List.of(),\n            List.of(),\n            firstX, firstY, firstZ,'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''    public static ExportStats export(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        int firstX, int firstY, int firstZ,''',
    '''    public static ExportStats export(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        int firstX, int firstY, int firstZ,\n        int secondX, int secondY, int secondZ,\n        String name,\n        String author,\n        String description,\n        int minecraftDataVersion,\n        File output\n    ) throws IOException {\n        return export(\n            blocks, blockEntities, entities, List.of(), List.of(),\n            firstX, firstY, firstZ,\n            secondX, secondY, secondZ,\n            name, author, description, minecraftDataVersion, output\n        );\n    }\n\n    public static ExportStats export(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        List<ScheduledTickData> blockTicks,\n        List<ScheduledTickData> fluidTicks,\n        int firstX, int firstY, int firstZ,'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''        LinkedHashMap<String, Object> region = new LinkedHashMap<>();''',
    '''        List<Object> blockTickTags = writeTicks(blockTicks, "Block", minX, minY, minZ, maxX, maxY, maxZ);\n        List<Object> fluidTickTags = writeTicks(fluidTicks, "Fluid", minX, minY, minZ, maxX, maxY, maxZ);\n\n        LinkedHashMap<String, Object> region = new LinkedHashMap<>();'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''        region.put("PendingBlockTicks", List.of());\n        region.put("PendingFluidTicks", List.of());''',
    '''        region.put("PendingBlockTicks", blockTickTags);\n        region.put("PendingFluidTicks", fluidTickTags);'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''            volume,\n            tileEntities.size(),\n            entityTags.size()\n        );''',
    '''            volume,\n            tileEntities.size(),\n            entityTags.size(),\n            blockTickTags.size(),\n            fluidTickTags.size()\n        );'''
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java",
    '''    private static LinkedHashMap<String, Object> xyz(int x, int y, int z) {''',
    '''    private static List<Object> writeTicks(\n        List<ScheduledTickData> ticks,\n        String idKey,\n        int minX, int minY, int minZ,\n        int maxX, int maxY, int maxZ\n    ) {\n        List<Object> result = new ArrayList<>();\n        if (ticks == null) return result;\n        long subTick = 0L;\n        for (ScheduledTickData tick : ticks) {\n            if (\n                tick.x() < minX || tick.x() > maxX ||\n                tick.y() < minY || tick.y() > maxY ||\n                tick.z() < minZ || tick.z() > maxZ\n            ) continue;\n            LinkedHashMap<String, Object> tag = new LinkedHashMap<>();\n            tag.put(idKey, tick.id());\n            tag.put("Priority", tick.priority());\n            tag.put("Time", tick.delay());\n            tag.put("SubTick", subTick++);\n            tag.put("x", tick.x() - minX);\n            tag.put("y", tick.y() - minY);\n            tag.put("z", tick.z() - minZ);\n            result.add(tag);\n        }\n        return result;\n    }\n\n    private static LinkedHashMap<String, Object> xyz(int x, int y, int z) {'''
)

write(
    "engine/src/test/java/dev/kastrick/minesport/region/RegionReaderScheduledTickTest.java",
    r'''package dev.kastrick.minesport.region;\n\nimport dev.kastrick.minesport.nbt.NbtCompound;\nimport org.junit.jupiter.api.Test;\n\nimport java.util.*;\n\nimport static org.junit.jupiter.api.Assertions.*;\n\nclass RegionReaderScheduledTickTest {\n    @Test\n    void extractsModernResolvedBlockAndFluidTicks() {\n        var block = tick("minecraft:redstone_wire", 101, 64, -29, 7, 2);\n        var outside = tick("minecraft:stone", 500, 64, 500, 1, 0);\n        var fluid = tick("minecraft:water", 102, 63, -28, -2, -1);\n        var chunk = new NbtCompound(Map.of(\n            "block_ticks", List.of(block, outside),\n            "fluid_ticks", List.of(fluid)\n        ));\n\n        var blocks = new ArrayList<ScheduledTickData>();\n        var fluids = new ArrayList<ScheduledTickData>();\n        RegionReader.extractScheduledTicks(\n            chunk, 100, 60, -30, 110, 70, -20, blocks, fluids\n        );\n\n        assertEquals(1, blocks.size());\n        assertEquals("minecraft:redstone_wire", blocks.getFirst().id());\n        assertEquals(7, blocks.getFirst().delay());\n        assertEquals(2, blocks.getFirst().priority());\n        assertEquals(1, fluids.size());\n        assertEquals("minecraft:water", fluids.getFirst().id());\n        assertEquals(-2, fluids.getFirst().delay());\n        assertEquals(-1, fluids.getFirst().priority());\n    }\n\n    @Test\n    void extractsLegacyLevelTileAndLiquidTicks() {\n        var level = new NbtCompound(Map.of(\n            "TileTicks", List.of(tick("minecraft:sand", 4, 70, 5, 3, 1)),\n            "LiquidTicks", List.of(tick("minecraft:lava", 5, 69, 5, 9, 0))\n        ));\n        var chunk = new NbtCompound(Map.of("Level", level));\n\n        var blocks = new ArrayList<ScheduledTickData>();\n        var fluids = new ArrayList<ScheduledTickData>();\n        RegionReader.extractScheduledTicks(\n            chunk, 0, 0, 0, 15, 255, 15, blocks, fluids\n        );\n\n        assertEquals("minecraft:sand", blocks.getFirst().id());\n        assertEquals(3, blocks.getFirst().delay());\n        assertEquals("minecraft:lava", fluids.getFirst().id());\n        assertEquals(9, fluids.getFirst().delay());\n    }\n\n    private static NbtCompound tick(String id, int x, int y, int z, int delay, int priority) {\n        var tag = new LinkedHashMap<String, Object>();\n        tag.put("i", id);\n        tag.put("x", x);\n        tag.put("y", y);\n        tag.put("z", z);\n        tag.put("t", delay);\n        tag.put("p", priority);\n        return new NbtCompound(tag);\n    }\n}\n'''
)

write(
    "engine/src/test/java/dev/kastrick/minesport/export/LitematicTickExporterTest.java",
    r'''package dev.kastrick.minesport.export;\n\nimport dev.kastrick.minesport.nbt.NbtCompound;\nimport dev.kastrick.minesport.nbt.NbtReader;\nimport dev.kastrick.minesport.region.ScheduledTickData;\nimport org.junit.jupiter.api.Test;\n\nimport java.nio.file.Files;\nimport java.util.List;\n\nimport static org.junit.jupiter.api.Assertions.*;\n\nclass LitematicTickExporterTest {\n    @Test\n    void writesRelativeBlockAndFluidTicks() throws Exception {\n        var blockTicks = List.of(new ScheduledTickData(101, 64, -29, "minecraft:redstone_wire", 7, 2));\n        var fluidTicks = List.of(new ScheduledTickData(102, 63, -28, "minecraft:water", -2, -1));\n        var output = Files.createTempFile("minesport-ticks-", ".litematic").toFile();\n        try {\n            var stats = LitematicExporter.export(\n                List.of(), List.of(), List.of(), blockTicks, fluidTicks,\n                100, 60, -30, 102, 66, -28,\n                "Tick Test", "Minesport", "unit test", 4189, output\n            );\n\n            assertEquals(1, stats.blockTickCount());\n            assertEquals(1, stats.fluidTickCount());\n            NbtCompound root = NbtReader.readGzip(output);\n            assertEquals(7, root.getInt("Version"));\n            NbtCompound region = root.getCompound("Regions").getCompound("Tick Test");\n\n            NbtCompound block = (NbtCompound) region.getList("PendingBlockTicks").getFirst();\n            assertEquals("minecraft:redstone_wire", block.getString("Block"));\n            assertEquals(2, block.getInt("Priority"));\n            assertEquals(7, block.getInt("Time"));\n            assertEquals(0L, block.getLong("SubTick"));\n            assertEquals(1, block.getInt("x"));\n            assertEquals(4, block.getInt("y"));\n            assertEquals(1, block.getInt("z"));\n\n            NbtCompound fluid = (NbtCompound) region.getList("PendingFluidTicks").getFirst();\n            assertEquals("minecraft:water", fluid.getString("Fluid"));\n            assertEquals(-1, fluid.getInt("Priority"));\n            assertEquals(-2, fluid.getInt("Time"));\n            assertEquals(0L, fluid.getLong("SubTick"));\n            assertEquals(2, fluid.getInt("x"));\n            assertEquals(3, fluid.getInt("y"));\n            assertEquals(2, fluid.getInt("z"));\n        } finally {\n            Files.deleteIfExists(output.toPath());\n        }\n    }\n}\n'''
)
