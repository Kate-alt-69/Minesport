from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: str, start_marker: str, end_marker: str, replacement: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{label}: start marker missing in {path}")
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise SystemExit(f"{label}: end marker missing in {path}")
    file.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


# Desktop: percent=0 means phase announcement/indeterminate. It must not reset
# a live export's last real percentage back to zero.
replace_once(
    "desktop/src/app.rs",
    '''        "progress" => {
            ui.set_task_active(true);
            let progress = (response.percent.clamp(0, 100) as f32) / 100.0;
            ui.set_task_progress(if ui.get_task_title() == "EXPORT" { progress.min(0.99) } else { progress });
            ui.set_task_detail(response.message.into());
        }
''',
    '''        "progress" => {
            ui.set_task_active(true);
            // Percent 0 is the engine's indeterminate sentinel. Keep the
            // last real value so phase announcements cannot rewind the bar.
            if response.percent > 0 {
                let progress = (response.percent.clamp(0, 100) as f32) / 100.0;
                ui.set_task_progress(if ui.get_task_title() == "EXPORT" { progress.min(0.99) } else { progress });
            }
            ui.set_task_detail(response.message.into());
        }
''',
    "desktop indeterminate progress handling",
)

# Engine: region reading owns 5..40%, not 1..100%. The old mapping reached 100
# and then jumped backward to 45/50 for later stages.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''        int interval = Math.max(1, total / 100);
        if (done <= 1 || done >= total || done % interval == 0) {
            int percent = (int)Math.round(done * 100.0 / total);
            progress(
                Math.max(1, Math.min(100, percent)),
                message + " · " + done + "/" + total + " chunks"
            );
        }
''',
    '''        int interval = Math.max(1, total / 100);
        if (done <= 1 || done >= total || done % interval == 0) {
            int percent = 5 + (int)Math.round(done * 35.0 / total);
            progress(
                Math.max(5, Math.min(40, percent)),
                message + " · " + done + "/" + total + " chunks"
            );
        }
''',
    "monotonic selected-region progress",
)

# Litematica had the same backwards-progress problem after region reading.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''            int percent = (int)Math.round(done * 99.0 / total);
            progress(
                Math.max(1, Math.min(99, percent)),
                "Writing Litematica · " + done + "/" + total + " state words"
            );
''',
    '''            int percent = 45 + (int)Math.round(done * 50.0 / total);
            progress(
                Math.max(45, Math.min(95, percent)),
                "Writing Litematica · " + done + "/" + total + " state words"
            );
''',
    "monotonic Litematica write progress",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '            progressIndeterminate("Publishing Litematica file");',
    '            progress(98, "Publishing Litematica file");',
    "Litematica publish progress",
)

# FLATTER used to run a complete model/geometry analysis pass without any
# callback, which made large exports appear frozen after the .mca stage.
replace_between(
    "engine/src/main/java/dev/kastrick/minesport/export/FlatterOptimizer.java",
    "    public static Result compile(List<BlockData> blocks, ResolverChain resolvers) {",
    "    private static Candidate analyze(",
    '''    public static Result compile(List<BlockData> blocks, ResolverChain resolvers) {
        return compile(blocks, resolvers, FlatterSettings.cellSize(), null);
    }

    public static Result compile(
        List<BlockData> blocks,
        ResolverChain resolvers,
        ObjExporter.ProgressCallback progress
    ) {
        return compile(blocks, resolvers, FlatterSettings.cellSize(), progress);
    }

    /** Explicit cell-size overload used by tests/tools without changing global settings. */
    public static Result compile(List<BlockData> blocks, ResolverChain resolvers, int requestedCellSize) {
        return compile(blocks, resolvers, requestedCellSize, null);
    }

    public static Result compile(
        List<BlockData> blocks,
        ResolverChain resolvers,
        int requestedCellSize,
        ObjExporter.ProgressCallback progress
    ) {
        if (blocks == null || blocks.isEmpty() || resolvers == null) return Result.empty();

        int cellSize = FlatterSettings.normalizeCellSize(requestedCellSize);
        // Use the IPC/export GeometryBuilder so FLATTER and conventional output
        // consume the same captured runtime baked-model source.
        GeometryBuilder geometry = new dev.kastrick.minesport.GeometryBuilder(resolvers);
        Map<MaterialKey,Boolean> opaqueCache = new HashMap<>();
        Map<ObjectKey,List<Candidate>> cells = new LinkedHashMap<>();

        int analyzed = 0;
        int total = Math.max(blocks.size(), 1);
        int interval = Math.max(1, total / 200);
        for (BlockData block : blocks) {
            if (block != null && !block.isAir()) {
                Candidate candidate = analyze(block, geometry, resolvers, opaqueCache);
                if (candidate != null) {
                    CellKey cell = cellFor(block, cellSize);
                    String shapeKey = candidate.kind() == CandidateKind.SHAPE
                        ? candidate.paletteKey()
                        : "";
                    ObjectKey key = new ObjectKey(cell, candidate.kind(), shapeKey);
                    cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
                }
            }
            analyzed++;
            if (progress != null && (analyzed == 1 || analyzed >= total || analyzed % interval == 0)) {
                progress.onProgress(analyzed, total);
            }
        }

        List<FlatterObject> objects = new ArrayList<>();
        Set<BlockData> included = Collections.newSetFromMap(new IdentityHashMap<>());

        for (var entry : cells.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            FlatterObject object = buildObject(entry.getKey(), entry.getValue());
            if (object == null || object.blockCount() < 2) continue;
            objects.add(object);
            for (Candidate candidate : entry.getValue()) included.add(candidate.block());
        }

        return objects.isEmpty() ? Result.empty() : new Result(objects, included);
    }

''',
    "FLATTER live progress section",
)

# Exporters combine the FLATTER pre-pass and regular geometry pass into one
# continuous callback range. IpcMode maps it to 50..95%.
replace_between(
    "engine/src/main/java/dev/kastrick/minesport/export/ObjExporter.java",
    "        float[] center = BlockGrouper.boundingBoxCenter(blocks);",
    "        String exportName = safeObjectName(",
    '''        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);
        int progressBlocks = Math.max(blocks.size(), 1);
        boolean flatterEnabled = FlatterSettings.enabled();
        FlatterOptimizer.Result flatter = flatterEnabled
            ? FlatterOptimizer.compile(blocks, builder.getResolvers(), (doneCount, total) -> {
                if (progress != null) progress.onProgress(doneCount, Math.max(1, total * 2));
            })
            : FlatterOptimizer.Result.empty();

''',
    "OBJ FLATTER progress hookup",
)

replace_between(
    "engine/src/main/java/dev/kastrick/minesport/export/GltfExporter.java",
    "        float[] center = BlockGrouper.boundingBoxCenter(blocks);",
    "        Map<String,List<Quad>> groups = new LinkedHashMap<>();",
    '''        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);
        int progressBlocks = Math.max(blocks.size(), 1);
        boolean flatterEnabled = FlatterSettings.enabled();
        FlatterOptimizer.Result flatter = flatterEnabled
            ? FlatterOptimizer.compile(blocks, resolvers, (doneCount, total) -> {
                if (progress != null) progress.onProgress(doneCount, Math.max(1, total * 2));
            })
            : FlatterOptimizer.Result.empty();
''',
    "glTF FLATTER progress hookup",
)

for path in (
    "engine/src/main/java/dev/kastrick/minesport/export/ObjExporter.java",
    "engine/src/main/java/dev/kastrick/minesport/export/GltfExporter.java",
):
    replace_once(
        path,
        '''        int done = 0;
        int total = Math.max(blocks.size(), 1);
''',
        '''        int done = 0;
        int total = progressBlocks;
        int progressBase = flatterEnabled ? total : 0;
        int progressTotal = flatterEnabled ? total * 2 : total;
''',
        f"{path} combined progress range",
    )
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count("progress.onProgress(++done, total)")
    if count < 1:
        raise SystemExit(f"normal progress callback missing in {path}")
    file.write_text(
        text.replace(
            "progress.onProgress(++done, total)",
            "progress.onProgress(progressBase + ++done, progressTotal)",
        ),
        encoding="utf-8",
    )

# Chests are block-entity rendered. Missing ordinary model JSON for these names
# is expected, and GeometryBuilder already provides the chest fallback geometry.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/resolver/ResolverChain.java",
    '''    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );
''',
    '''    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );
    private static final Set<String> ENTITY_RENDERED_MODELS = Set.of(
        "minecraft:block/chest",
        "minecraft:block/trapped_chest",
        "minecraft:block/ender_chest"
    );
''',
    "entity-rendered model set",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/resolver/ResolverChain.java",
    '        if (missingModels.add(normalized)) System.err.println("[ResolverChain] No model found for: " + normalized);',
    '''        if (missingModels.add(normalized) && !ENTITY_RENDERED_MODELS.contains(normalized)) {
            System.err.println("[ResolverChain] No model found for: " + normalized);
        }''',
    "expected chest model warning suppression",
)

# Diagnostics deduped progress solely by percent. Distinct phases sharing the
# same percentage vanished from logs, making a live worker look stalled.
replace_once(
    "desktop/src/ipc.rs",
    "        let mut last_progress: Option<(String, String, i32)> = None;",
    "        let mut last_progress: Option<(String, String, i32, String)> = None;",
    "IPC progress dedupe tuple",
)
replace_once(
    "desktop/src/ipc.rs",
    '''                            let progress_key = (
                                response.operation_id.clone(), response.trace_id.clone(), percent,
                            );
''',
    '''                            let progress_key = (
                                response.operation_id.clone(),
                                response.trace_id.clone(),
                                percent,
                                response.message.clone(),
                            );
''',
    "IPC progress dedupe key",
)

print("Applied live export progress, FLATTER visibility, and resolver diagnostics fixes")
