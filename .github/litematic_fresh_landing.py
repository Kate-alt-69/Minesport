from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} match(es), found {actual}: {old[:120]!r}")
    p.write_text(text.replace(old, new, count))


def write(path, content):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)


# ---------------------------------------------------------------------------
# RegionReader: retain scheduled block/fluid ticks for Litematica exports.
# ---------------------------------------------------------------------------
path = 'engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java'
replace(path,
'''    public record RegionContents(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities
    ) {}''',
'''    public record RegionContents(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities,
        List<ScheduledTickData> blockTicks,
        List<ScheduledTickData> fluidTicks
    ) {}''')
replace(path,
'''            progress,
            true,
            false,
            false
        ).blocks();''',
'''            progress,
            true,
            false,
            false,
            false
        ).blocks();''')
replace(path,
'''            progress,
            true,
            true,
            true
        );''',
'''            progress,
            true,
            true,
            true,
            true
        );''')
replace(path,
'''            progress,
            false,
            false,
            true
        ).entities();''',
'''            progress,
            false,
            false,
            true,
            false
        ).entities();''')
replace(path,
'''            ProgressCallback progress,
            boolean decodeBlocks,
            boolean includeBlockEntities,
            boolean includeEntities
    ) throws IOException {''',
'''            ProgressCallback progress,
            boolean decodeBlocks,
            boolean includeBlockEntities,
            boolean includeEntities,
            boolean includeScheduledTicks
    ) throws IOException {''')
replace(path,
'''        var blocks = new ArrayList<BlockData>();
        var blockEntities = new ArrayList<BlockEntityData>();
        var entities = new ArrayList<EntityData>();''',
'''        var blocks = new ArrayList<BlockData>();
        var blockEntities = new ArrayList<BlockEntityData>();
        var entities = new ArrayList<EntityData>();
        var blockTicks = new ArrayList<ScheduledTickData>();
        var fluidTicks = new ArrayList<ScheduledTickData>();''')
replace(path,
'''            if (raf.length() < SECTOR_SIZE * 2L) {
                return new RegionContents(blocks, blockEntities, entities);
            }''',
'''            if (raf.length() < SECTOR_SIZE * 2L) {
                return new RegionContents(blocks, blockEntities, entities, blockTicks, fluidTicks);
            }''')
replace(path,
'''                    if (includeEntities) {
                        extractEntities(
                            chunkNbt,
                            minX, minY, minZ,
                            maxX, maxY, maxZ,
                            entities
                        );
                    }
''',
'''                    if (includeEntities) {
                        extractEntities(
                            chunkNbt,
                            minX, minY, minZ,
                            maxX, maxY, maxZ,
                            entities
                        );
                    }
                    if (includeScheduledTicks) {
                        extractScheduledTicks(
                            chunkNbt,
                            minX, minY, minZ,
                            maxX, maxY, maxZ,
                            blockTicks,
                            fluidTicks
                        );
                    }
''')
replace(path,
'''        return new RegionContents(blocks, blockEntities, entities);
    }

    static void extractBlockEntities''',
'''        return new RegionContents(blocks, blockEntities, entities, blockTicks, fluidTicks);
    }

    static void extractBlockEntities''')
replace(path,
'''    private static byte[] decompress(byte[] data, int type) {''',
'''    static void extractScheduledTicks(
        NbtCompound chunk,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<ScheduledTickData> blockOut,
        List<ScheduledTickData> fluidOut
    ) {
        if (chunk == null) return;

        NbtCompound level = chunk;
        if (chunk.has("Level")) {
            try {
                level = chunk.getCompound("Level");
            } catch (Exception ignored) {}
        }

        appendTicks(findTickList(chunk, level, "block_ticks", "TileTicks"),
            minX, minY, minZ, maxX, maxY, maxZ, blockOut);
        appendTicks(findTickList(chunk, level, "fluid_ticks", "LiquidTicks"),
            minX, minY, minZ, maxX, maxY, maxZ, fluidOut);
    }

    private static List<Object> findTickList(
        NbtCompound root,
        NbtCompound level,
        String modernKey,
        String legacyKey
    ) {
        try {
            if (root.has(modernKey)) return root.getList(modernKey);
            if (level.has(legacyKey)) return level.getList(legacyKey);
            if (root.has(legacyKey)) return root.getList(legacyKey);
        } catch (Exception ignored) {}
        return List.of();
    }

    private static void appendTicks(
        List<Object> entries,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<ScheduledTickData> out
    ) {
        if (entries == null || out == null) return;
        for (Object entry : entries) {
            if (!(entry instanceof NbtCompound tick)) continue;
            try {
                String id = tick.getString("i");
                int x = tick.getInt("x");
                int y = tick.getInt("y");
                int z = tick.getInt("z");
                if (
                    x < minX || x > maxX ||
                    y < minY || y > maxY ||
                    z < minZ || z > maxZ
                ) continue;
                out.add(new ScheduledTickData(
                    x, y, z, id,
                    tick.getInt("t", 0),
                    tick.getInt("p", 0)
                ));
            } catch (Exception ignored) {
                // Malformed scheduled tick: preserve the surrounding chunk.
            }
        }
    }

    private static byte[] decompress(byte[] data, int type) {''')

write('engine/src/main/java/dev/kastrick/minesport/region/ScheduledTickData.java',
'''package dev.kastrick.minesport.region;

/** Resolved Minecraft scheduled tick with a world-space position and relative delay. */
public record ScheduledTickData(
    int x, int y, int z,
    String id,
    int delay,
    int priority
) {}
''')

# ---------------------------------------------------------------------------
# IPC: carry ticks through selection filters and into the staged exporter.
# ---------------------------------------------------------------------------
path = 'engine/src/main/java/dev/kastrick/minesport/IpcMode.java'
replace(path,
'''            var allBlocks = new ArrayList<BlockData>();
            var allBlockEntities = new ArrayList<BlockEntityData>();
            var allEntities = new ArrayList<EntityData>();''',
'''            var allBlocks = new ArrayList<BlockData>();
            var allBlockEntities = new ArrayList<BlockEntityData>();
            var allEntities = new ArrayList<EntityData>();
            var allBlockTicks = new ArrayList<ScheduledTickData>();
            var allFluidTicks = new ArrayList<ScheduledTickData>();''')
replace(path,
'''                    allBlocks.addAll(contents.blocks());
                    allBlockEntities.addAll(contents.blockEntities());
                    if (!separateEntityRegions) {''',
'''                    allBlocks.addAll(contents.blocks());
                    allBlockEntities.addAll(contents.blockEntities());
                    allBlockTicks.addAll(contents.blockTicks());
                    allFluidTicks.addAll(contents.fluidTicks());
                    if (!separateEntityRegions) {''')
replace(path,
'''                    ? " · block entities: " + allBlockEntities.size()
                        + " · entities: " + allEntities.size()
                    : "")''',
'''                    ? " · block entities: " + allBlockEntities.size()
                        + " · entities: " + allEntities.size()
                        + " · block ticks: " + allBlockTicks.size()
                        + " · fluid ticks: " + allFluidTicks.size()
                    : "")''')
replace(path,
'''                allEntities.removeIf(entity -> !insideEllipsoidPoint(
                    entity.x(), entity.y(), entity.z(),
                    centerX, centerY, centerZ,
                    Math.max(radiusX, 1),
                    Math.max(radiusY, 1),
                    Math.max(radiusZ, 1)
                ));''',
'''                allEntities.removeIf(entity -> !insideEllipsoidPoint(
                    entity.x(), entity.y(), entity.z(),
                    centerX, centerY, centerZ,
                    Math.max(radiusX, 1),
                    Math.max(radiusY, 1),
                    Math.max(radiusZ, 1)
                ));
                allBlockTicks.removeIf(tick -> !insideEllipsoidPoint(
                    tick.x() + 0.5, tick.y() + 0.5, tick.z() + 0.5,
                    centerX, centerY, centerZ,
                    Math.max(radiusX, 1),
                    Math.max(radiusY, 1),
                    Math.max(radiusZ, 1)
                ));
                allFluidTicks.removeIf(tick -> !insideEllipsoidPoint(
                    tick.x() + 0.5, tick.y() + 0.5, tick.z() + 0.5,
                    centerX, centerY, centerZ,
                    Math.max(radiusX, 1),
                    Math.max(radiusY, 1),
                    Math.max(radiusZ, 1)
                ));''')
replace(path,
'''                allEntities.removeIf(entity ->
                    !exact.contains(SpatialKey.of(
                        (int)Math.floor(entity.x()),
                        (int)Math.floor(entity.y()),
                        (int)Math.floor(entity.z())
                    ))
                );''',
'''                allEntities.removeIf(entity ->
                    !exact.contains(SpatialKey.of(
                        (int)Math.floor(entity.x()),
                        (int)Math.floor(entity.y()),
                        (int)Math.floor(entity.z())
                    ))
                );
                allBlockTicks.removeIf(tick ->
                    !exact.contains(SpatialKey.of(tick.x(), tick.y(), tick.z()))
                );
                allFluidTicks.removeIf(tick ->
                    !exact.contains(SpatialKey.of(tick.x(), tick.y(), tick.z()))
                );''')
replace(path,
'''                    allBlocks,
                    allBlockEntities,
                    allEntities,
                    minX, minY, minZ,''',
'''                    allBlocks,
                    allBlockEntities,
                    allEntities,
                    allBlockTicks,
                    allFluidTicks,
                    minX, minY, minZ,''')
replace(path,
'''                    + schematicStats.entityCount() + " entities, "
                    + schematicStats.paletteSize() + " palette states, "''',
'''                    + schematicStats.entityCount() + " entities, "
                    + schematicStats.blockTickCount() + " block ticks, "
                    + schematicStats.fluidTickCount() + " fluid ticks, "
                    + schematicStats.paletteSize() + " palette states, "''')
replace(path,
'''        int interval = Math.max(1, total / 100);
        if (done <= 1 || done >= total || done % interval == 0) {
            progressIndeterminate(message + " · " + done + "/" + total + " chunks");
        }''',
'''        int interval = Math.max(1, total / 100);
        if (done <= 1 || done >= total || done % interval == 0) {
            int percent = (int)Math.round(done * 100.0 / total);
            progress(
                Math.max(1, Math.min(100, percent)),
                message + " · " + done + "/" + total + " chunks"
            );
        }''')

# ---------------------------------------------------------------------------
# LitematicExporter: keep the write-progress overload and add tick-aware ones.
# ---------------------------------------------------------------------------
path = 'engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java'
replace(path, 'import dev.kastrick.minesport.region.EntityData;',
             'import dev.kastrick.minesport.region.EntityData;\nimport dev.kastrick.minesport.region.ScheduledTickData;')
replace(path,
'''        int volume,
        int blockEntityCount,
        int entityCount
    ) {}''',
'''        int volume,
        int blockEntityCount,
        int entityCount,
        int blockTickCount,
        int fluidTickCount
    ) {}''')
replace(path,
'''        return export(
            blocks, blockEntities, entities,
            firstX, firstY, firstZ,
            secondX, secondY, secondZ,
            name, author, description,
            minecraftDataVersion, output, null
        );''',
'''        return export(
            blocks, blockEntities, entities, List.of(), List.of(),
            firstX, firstY, firstZ,
            secondX, secondY, secondZ,
            name, author, description,
            minecraftDataVersion, output, null
        );''')
replace(path,
'''    public static ExportStats export(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities,
        int firstX, int firstY, int firstZ,
        int secondX, int secondY, int secondZ,
        String name,
        String author,
        String description,
        int minecraftDataVersion,
        File output,
        NbtWriter.ProgressCallback writeProgress
    ) throws IOException {
        int minX = Math.min(firstX, secondX);''',
'''    public static ExportStats export(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities,
        int firstX, int firstY, int firstZ,
        int secondX, int secondY, int secondZ,
        String name,
        String author,
        String description,
        int minecraftDataVersion,
        File output,
        NbtWriter.ProgressCallback writeProgress
    ) throws IOException {
        return export(
            blocks, blockEntities, entities, List.of(), List.of(),
            firstX, firstY, firstZ,
            secondX, secondY, secondZ,
            name, author, description,
            minecraftDataVersion, output, writeProgress
        );
    }

    public static ExportStats export(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities,
        List<ScheduledTickData> blockTicks,
        List<ScheduledTickData> fluidTicks,
        int firstX, int firstY, int firstZ,
        int secondX, int secondY, int secondZ,
        String name,
        String author,
        String description,
        int minecraftDataVersion,
        File output
    ) throws IOException {
        return export(
            blocks, blockEntities, entities, blockTicks, fluidTicks,
            firstX, firstY, firstZ,
            secondX, secondY, secondZ,
            name, author, description,
            minecraftDataVersion, output, null
        );
    }

    public static ExportStats export(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities,
        List<ScheduledTickData> blockTicks,
        List<ScheduledTickData> fluidTicks,
        int firstX, int firstY, int firstZ,
        int secondX, int secondY, int secondZ,
        String name,
        String author,
        String description,
        int minecraftDataVersion,
        File output,
        NbtWriter.ProgressCallback writeProgress
    ) throws IOException {
        int minX = Math.min(firstX, secondX);''')
replace(path,
'''        LinkedHashMap<String, Object> region = new LinkedHashMap<>();''',
'''        List<Object> blockTickTags = writeTicks(blockTicks, "Block", minX, minY, minZ, maxX, maxY, maxZ);
        List<Object> fluidTickTags = writeTicks(fluidTicks, "Fluid", minX, minY, minZ, maxX, maxY, maxZ);

        LinkedHashMap<String, Object> region = new LinkedHashMap<>();''')
replace(path,
'''        region.put("PendingBlockTicks", List.of());
        region.put("PendingFluidTicks", List.of());''',
'''        region.put("PendingBlockTicks", blockTickTags);
        region.put("PendingFluidTicks", fluidTickTags);''')
replace(path,
'''            volume,
            tileEntities.size(),
            entityTags.size()
        );''',
'''            volume,
            tileEntities.size(),
            entityTags.size(),
            blockTickTags.size(),
            fluidTickTags.size()
        );''')
replace(path,
'''    private static LinkedHashMap<String, Object> xyz(int x, int y, int z) {''',
'''    private static List<Object> writeTicks(
        List<ScheduledTickData> ticks,
        String idKey,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
    ) {
        List<Object> result = new ArrayList<>();
        if (ticks == null) return result;
        long subTick = 0L;
        for (ScheduledTickData tick : ticks) {
            if (
                tick.x() < minX || tick.x() > maxX ||
                tick.y() < minY || tick.y() > maxY ||
                tick.z() < minZ || tick.z() > maxZ
            ) continue;
            LinkedHashMap<String, Object> tag = new LinkedHashMap<>();
            tag.put(idKey, tick.id());
            tag.put("Priority", tick.priority());
            tag.put("Time", tick.delay());
            tag.put("SubTick", subTick++);
            tag.put("x", tick.x() - minX);
            tag.put("y", tick.y() - minY);
            tag.put("z", tick.z() - minZ);
            result.add(tag);
        }
        return result;
    }

    private static LinkedHashMap<String, Object> xyz(int x, int y, int z) {''')

# ---------------------------------------------------------------------------
# Tests for modern/legacy input and final Litematica NBT.
# ---------------------------------------------------------------------------
write('engine/src/test/java/dev/kastrick/minesport/region/RegionReaderScheduledTickTest.java', r'''package dev.kastrick.minesport.region;

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
''')

write('engine/src/test/java/dev/kastrick/minesport/export/LitematicTickExporterTest.java', r'''package dev.kastrick.minesport.export;

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
''')

# ---------------------------------------------------------------------------
# --fresh: clear project-local Gradle state and force task reruns, while keeping
# global ~/.gradle dependency/download caches and Cargo/JDK caches.
# ---------------------------------------------------------------------------
path = 'build.ps1'
replace(path,
"    Write-Host '[FRESH] Removing Minesport build outputs only...' -ForegroundColor Yellow",
"    Write-Host '[FRESH] Removing Minesport build outputs and project-local Gradle state...' -ForegroundColor Yellow")
replace(path, '    $paths = @(\n', "    $paths = @(\n        (Join-Path $Root '.gradle'),\n")
for project in ('minesport-bridge-fabric', 'minesport-bridge-forge', 'minesport-bridge-neoforge', 'minesport-bridge-quilt', 'engine'):
    old = f"        (Join-Path $Root '{project}\\build'),"
    replace(path, old, old + f"\n        (Join-Path $Root '{project}\\.gradle'),")
replace(path,
"    Write-Host '  Preserved: project .gradle caches, ~/.gradle, Cargo registry/git caches, downloaded JDKs.' -ForegroundColor DarkGray",
"    Write-Host '  Preserved: global ~/.gradle downloads/cache, Cargo registry/git caches, downloaded JDKs.' -ForegroundColor DarkGray")
replace(path,
"if ($Fresh) { Write-Host 'Fresh: YES (outputs only; caches preserved)' -ForegroundColor Yellow }",
"if ($Fresh) { Write-Host 'Fresh: YES (clean rerun; global caches preserved)' -ForegroundColor Yellow }")
replace(path,
'''        & .\gradlew.bat --no-daemon --stacktrace build''',
'''        if ($Fresh) {
            & .\gradlew.bat --no-daemon --stacktrace clean build --rerun-tasks --no-build-cache
        } else {
            & .\gradlew.bat --no-daemon --stacktrace build
        }''', count=2)

path = 'build.sh'
replace(path, "  echo '[FRESH] Removing Minesport build outputs only...'",
              "  echo '[FRESH] Removing Minesport build outputs and project-local Gradle state...'")
replace(path, '  rm -rf \\\n', '  rm -rf \\\n    "$ROOT/.gradle" \\\n')
for project in ('minesport-bridge-fabric', 'minesport-bridge-forge', 'minesport-bridge-neoforge', 'minesport-bridge-quilt', 'engine'):
    old = f'    "$ROOT/{project}/build" \\\n'
    replace(path, old, old + f'    "$ROOT/{project}/.gradle" \\\n')
replace(path,
"  echo '  Preserved: project .gradle caches, ~/.gradle, Cargo registry/git caches, downloaded JDKs.'",
"  echo '  Preserved: global ~/.gradle downloads/cache, Cargo registry/git caches, downloaded JDKs.'")
replace(path,
"$FRESH && printf '%s\\n' 'Fresh: YES (outputs only; caches preserved)'",
"$FRESH && printf '%s\\n' 'Fresh: YES (clean rerun; global caches preserved)'")
replace(path, '    ./gradlew --no-daemon build', '    run_gradle_build', count=2)
replace(path, 'build_bridge() {', '''run_gradle_build() {
  if $FRESH; then
    ./gradlew --no-daemon clean build --rerun-tasks --no-build-cache
  else
    ./gradlew --no-daemon build
  fi
}

build_bridge() {''')

print('Applied permanent Litematica scheduled ticks and hardened --fresh builds.')
