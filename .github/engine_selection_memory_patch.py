from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start_marker: str, end_marker: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{label}: start marker not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{label}: end marker not found")
    path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


ipc = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
replace_once(
    ipc,
    '''        int copyMinX = minX;\n        int copyMinZ = minZ;\n        int copyMaxX = maxX;\n        int copyMaxZ = maxZ;\n        Integer copyCenterX = getOptionalInt(request, "centerX");\n        Integer copyCenterZ = getOptionalInt(request, "centerZ");\n        Integer copyRadiusX = getOptionalInt(request, "radiusX");\n        Integer copyRadiusZ = getOptionalInt(request, "radiusZ");\n        if (copyCenterX != null && copyCenterZ != null && copyRadiusX != null && copyRadiusZ != null) {\n            int rx = Math.max(copyRadiusX, 1);\n            int rz = Math.max(copyRadiusZ, 1);\n            copyMinX = copyCenterX - rx;\n            copyMaxX = copyCenterX + rx;\n            copyMinZ = copyCenterZ - rz;\n            copyMaxZ = copyCenterZ + rz;\n        }\n''',
    '''        Integer centerX = getOptionalInt(request, "centerX");\n        Integer centerY = getOptionalInt(request, "centerY");\n        Integer centerZ = getOptionalInt(request, "centerZ");\n        Integer radiusX = getOptionalInt(request, "radiusX");\n        Integer radiusY = getOptionalInt(request, "radiusY");\n        Integer radiusZ = getOptionalInt(request, "radiusZ");\n        boolean hasBubbleSelection =\n            centerX != null && centerY != null && centerZ != null &&\n            radiusX != null && radiusY != null && radiusZ != null;\n        String customSelectionFile = getStringOption(request, "customSelectionFile", null);\n        boolean exactSelectionRequested =\n            customSelectionFile != null && !customSelectionFile.isBlank();\n\n        int copyMinX = minX;\n        int copyMinZ = minZ;\n        int copyMaxX = maxX;\n        int copyMaxZ = maxZ;\n        if (centerX != null && centerZ != null && radiusX != null && radiusZ != null) {\n            int rx = Math.max(radiusX, 1);\n            int rz = Math.max(radiusZ, 1);\n            copyMinX = centerX - rx;\n            copyMaxX = centerX + rx;\n            copyMinZ = centerZ - rz;\n            copyMaxZ = centerZ + rz;\n        }\n''',
    "selection parameters",
)
replace_once(
    ipc,
    '''        try {\n            progressIndeterminate("Preparing selected world data");\n''',
    '''        try {\n            final Set<Long> exactSelection = exactSelectionRequested\n                ? loadCustomSelection(new File(customSelectionFile))\n                : Collections.emptySet();\n            if (exactSelectionRequested) {\n                log("Loaded exact selection with " + exactSelection.size() + " coordinate(s)");\n            }\n            progressIndeterminate("Preparing selected world data");\n''',
    "load exact selection before world copy",
)
replace_once(
    ipc,
    '''            var allBlocks = new ArrayList<BlockData>();\n            var allBlockEntities = new ArrayList<BlockEntityData>();\n            var allEntities = new ArrayList<EntityData>();\n            var allBlockTicks = new ArrayList<ScheduledTickData>();\n            var allFluidTicks = new ArrayList<ScheduledTickData>();\n            int inputDoneBase = 0;\n''',
    '''            var allBlocks = new ArrayList<BlockData>();\n            var allBlockEntities = new ArrayList<BlockEntityData>();\n            var allEntities = new ArrayList<EntityData>();\n            var allBlockTicks = new ArrayList<ScheduledTickData>();\n            var allFluidTicks = new ArrayList<ScheduledTickData>();\n            long decodedBlockCount = 0L;\n            int inputDoneBase = 0;\n''',
    "decoded block counter",
)
replace_once(
    ipc,
    '''                    allBlocks.addAll(contents.blocks());\n                    allBlockEntities.addAll(contents.blockEntities());\n                    allBlockTicks.addAll(contents.blockTicks());\n                    allFluidTicks.addAll(contents.fluidTicks());\n                    if (!separateEntityRegions) {\n                        allEntities.addAll(contents.entities());\n                    }\n''',
    '''                    decodedBlockCount += contents.blocks().size();\n                    filterDecodedSelection(\n                        contents.blocks(),\n                        contents.blockEntities(),\n                        separateEntityRegions ? null : contents.entities(),\n                        contents.blockTicks(),\n                        contents.fluidTicks(),\n                        centerX, centerY, centerZ,\n                        radiusX, radiusY, radiusZ,\n                        exactSelectionRequested, exactSelection\n                    );\n                    allBlocks.addAll(contents.blocks());\n                    allBlockEntities.addAll(contents.blockEntities());\n                    allBlockTicks.addAll(contents.blockTicks());\n                    allFluidTicks.addAll(contents.fluidTicks());\n                    if (!separateEntityRegions) {\n                        allEntities.addAll(contents.entities());\n                    }\n''',
    "litematic region-local filtering",
)
replace_once(
    ipc,
    '''                    allBlocks.addAll(RegionReader.readRegion(\n                        regionFile,\n                        minX, minY, minZ,\n                        maxX, maxY, maxZ,\n                        chunkProgress\n                    ));\n''',
    '''                    List<BlockData> regionBlocks = RegionReader.readRegion(\n                        regionFile,\n                        minX, minY, minZ,\n                        maxX, maxY, maxZ,\n                        chunkProgress\n                    );\n                    decodedBlockCount += regionBlocks.size();\n                    filterDecodedSelection(\n                        regionBlocks, null, null, null, null,\n                        centerX, centerY, centerZ,\n                        radiusX, radiusY, radiusZ,\n                        exactSelectionRequested, exactSelection\n                    );\n                    allBlocks.addAll(regionBlocks);\n''',
    "geometry region-local filtering",
)
replace_once(
    ipc,
    '''                    allEntities.addAll(RegionReader.readEntityRegion(\n                        entityFile,\n                        minX, minY, minZ,\n                        maxX, maxY, maxZ,\n                        chunkProgress\n                    ));\n''',
    '''                    List<EntityData> regionEntities = RegionReader.readEntityRegion(\n                        entityFile,\n                        minX, minY, minZ,\n                        maxX, maxY, maxZ,\n                        chunkProgress\n                    );\n                    filterDecodedSelection(\n                        null, null, regionEntities, null, null,\n                        centerX, centerY, centerZ,\n                        radiusX, radiusY, radiusZ,\n                        exactSelectionRequested, exactSelection\n                    );\n                    allEntities.addAll(regionEntities);\n''',
    "entity region-local filtering",
)
replace_between(
    ipc,
    '            Integer centerX = getOptionalInt(request, "centerX");\n',
    '            progressIndeterminate("Preparing export data");\n',
    '''            if (hasBubbleSelection || exactSelectionRequested) {\n                StringBuilder selectionSummary = new StringBuilder(\n                    "Selection filtering: " + allBlocks.size() + " / " + decodedBlockCount\n                        + " decoded blocks retained"\n                );\n                if (hasBubbleSelection) selectionSummary.append(" · bubble");\n                if (exactSelectionRequested) {\n                    selectionSummary\n                        .append(" · exact ")\n                        .append(exactSelection.size())\n                        .append(" coordinate(s)");\n                }\n                log(selectionSummary.toString());\n            }\n\n''',
    "remove whole-export selection filtering",
)
replace_once(
    ipc,
    '''    private static boolean getBoolOption(JsonObject request, String key, boolean fallback) {\n''',
    '''    private static void filterDecodedSelection(\n        List<BlockData> blocks,\n        List<BlockEntityData> blockEntities,\n        List<EntityData> entities,\n        List<ScheduledTickData> blockTicks,\n        List<ScheduledTickData> fluidTicks,\n        Integer centerX, Integer centerY, Integer centerZ,\n        Integer radiusX, Integer radiusY, Integer radiusZ,\n        boolean exactSelectionRequested,\n        Set<Long> exactSelection\n    ) {\n        boolean bubble =\n            centerX != null && centerY != null && centerZ != null &&\n            radiusX != null && radiusY != null && radiusZ != null;\n        if (bubble) {\n            int cx = centerX;\n            int cy = centerY;\n            int cz = centerZ;\n            int rx = Math.max(radiusX, 1);\n            int ry = Math.max(radiusY, 1);\n            int rz = Math.max(radiusZ, 1);\n            if (blocks != null) {\n                blocks.removeIf(block -> !insideEllipsoid(block, cx, cy, cz, rx, ry, rz));\n            }\n            if (blockEntities != null) {\n                blockEntities.removeIf(entity ->\n                    !insideEllipsoid(entity.x(), entity.y(), entity.z(), cx, cy, cz, rx, ry, rz)\n                );\n            }\n            if (entities != null) {\n                entities.removeIf(entity ->\n                    !insideEllipsoidPoint(entity.x(), entity.y(), entity.z(), cx, cy, cz, rx, ry, rz)\n                );\n            }\n            if (blockTicks != null) {\n                blockTicks.removeIf(tick ->\n                    !insideEllipsoidPoint(\n                        tick.x() + 0.5, tick.y() + 0.5, tick.z() + 0.5,\n                        cx, cy, cz, rx, ry, rz\n                    )\n                );\n            }\n            if (fluidTicks != null) {\n                fluidTicks.removeIf(tick ->\n                    !insideEllipsoidPoint(\n                        tick.x() + 0.5, tick.y() + 0.5, tick.z() + 0.5,\n                        cx, cy, cz, rx, ry, rz\n                    )\n                );\n            }\n        }\n\n        if (!exactSelectionRequested) return;\n        if (blocks != null) {\n            blocks.removeIf(block ->\n                !exactSelection.contains(SpatialKey.of(block.x, block.y, block.z))\n            );\n        }\n        if (blockEntities != null) {\n            blockEntities.removeIf(entity ->\n                !exactSelection.contains(SpatialKey.of(entity.x(), entity.y(), entity.z()))\n            );\n        }\n        if (entities != null) {\n            entities.removeIf(entity ->\n                !exactSelection.contains(SpatialKey.of(\n                    (int)Math.floor(entity.x()),\n                    (int)Math.floor(entity.y()),\n                    (int)Math.floor(entity.z())\n                ))\n            );\n        }\n        if (blockTicks != null) {\n            blockTicks.removeIf(tick ->\n                !exactSelection.contains(SpatialKey.of(tick.x(), tick.y(), tick.z()))\n            );\n        }\n        if (fluidTicks != null) {\n            fluidTicks.removeIf(tick ->\n                !exactSelection.contains(SpatialKey.of(tick.x(), tick.y(), tick.z()))\n            );\n        }\n    }\n\n    private static boolean getBoolOption(JsonObject request, String key, boolean fallback) {\n''',
    "region-local selection helper",
)
replace_once(
    ipc,
    '''            var allBlocks = new ArrayList<BlockData>();\n            for (File regionFile : regionFiles) {\n                allBlocks.addAll(RegionReader.readRegion(\n                    regionFile,\n                    minX, minY, minZ,\n                    maxX, maxY, maxZ,\n                    null\n                ));\n            }\n\n            if (\n                centerX != null && centerY != null && centerZ != null &&\n                radiusX != null && radiusY != null && radiusZ != null\n            ) {\n                int cx = centerX;\n                int cy = centerY;\n                int cz = centerZ;\n                int rx = Math.max(radiusX, 1);\n                int ry = Math.max(radiusY, 1);\n                int rz = Math.max(radiusZ, 1);\n                allBlocks.removeIf(block -> !insideEllipsoid(block, cx, cy, cz, rx, ry, rz));\n            }\n\n            allBlocks.removeIf(BlockData::isAir);\n            log("Block list: " + allBlocks.size() + " solid block(s)");\n''',
    '''            var allBlocks = new ArrayList<BlockData>();\n            long previewDecodedBlocks = 0L;\n            for (File regionFile : regionFiles) {\n                List<BlockData> regionBlocks = RegionReader.readRegion(\n                    regionFile,\n                    minX, minY, minZ,\n                    maxX, maxY, maxZ,\n                    null\n                );\n                previewDecodedBlocks += regionBlocks.size();\n                filterDecodedSelection(\n                    regionBlocks, null, null, null, null,\n                    centerX, centerY, centerZ,\n                    radiusX, radiusY, radiusZ,\n                    false, Collections.emptySet()\n                );\n                regionBlocks.removeIf(BlockData::isAir);\n                allBlocks.addAll(regionBlocks);\n            }\n\n            log(\n                "Block list: " + allBlocks.size() + " solid block(s) retained from "\n                    + previewDecodedBlocks + " decoded block(s)"\n            );\n''',
    "preview region-local filtering",
)

audit = Path("doc/engine-runtime-audit.md")
replace_once(
    audit,
    '''`handleExport()` accumulates blocks, block entities, entities, scheduled block ticks, and fluid ticks into `ArrayList`s for the complete selection before later selection filtering and export work.\n\n**Impact:** Multi-million-block selections can create heavy heap usage and GC pressure and can fail from memory exhaustion even when the final output could be produced with a more compact intermediate representation.\n\n**Direction:** Move selection rejection as close to chunk decoding as possible and replace object-heavy whole-world accumulation with chunk/section batches plus compact spatial state needed by multipart resolution and face culling.\n''',
    '''`handleExport()` still retains the blocks and Litematica metadata that survive selection filtering in whole-export `ArrayList`s because later multipart resolution, face culling and export writers expect global spatial context.\n\n**Impact:** Multi-million-block retained selections can still create heavy heap usage and GC pressure. Sparse bubble/exact selections previously made this worse by first accumulating every decoded block and only filtering after all selected regions had been read.\n\n**Control implemented:** Bubble and exact-coordinate filters now run on each decoded region before its blocks, block entities, entities and scheduled ticks are appended to the whole-export lists. The 3D preview block-list path likewise removes bubble-excluded and air blocks region-by-region. Sparse selections therefore no longer pay whole-bounds peak heap merely to discard most objects later.\n\n**Remaining direction:** Replace the retained whole-export object lists with chunk/section batches plus compact spatial state needed by multipart resolution and face culling, so peak memory scales with the active working set instead of the final retained selection.\n''',
    "engine audit selection-memory control",
)
