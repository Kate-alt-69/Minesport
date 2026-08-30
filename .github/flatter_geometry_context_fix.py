from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# The live IPC request owns hidden-block culling. GeometryBuilder used to read
# settings.json in its constructor, so an old persisted `true` could silently
# override a current UI `false`. The IPC path already calls
# enableHiddenBlockCulling(allBlocks) when the current request enables it.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/GeometryBuilder.java",
    "import com.google.gson.JsonObject;\nimport com.google.gson.JsonParser;\n",
    "",
    "remove stale hidden-culling JSON imports",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/GeometryBuilder.java",
    "import dev.kastrick.minesport.export.FlatterSettings;\n",
    "",
    "remove stale FlatterSettings import",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/GeometryBuilder.java",
    "import java.nio.file.Files;\n",
    "",
    "remove stale Files import",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/GeometryBuilder.java",
    "        this.hiddenBlockCullingEnabled = readHiddenBlockCullingSetting();\n",
    "        // The current export request is authoritative. IpcMode explicitly\n"
    "        // enables this for the selected export when requested; never let a\n"
    "        // stale settings.json value turn culling on behind the UI's back.\n"
    "        this.hiddenBlockCullingEnabled = false;\n",
    "make hidden culling request-owned",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/GeometryBuilder.java",
    '''\n    private static boolean readHiddenBlockCullingSetting() {
        try {
            File settings = FlatterSettings.settingsFile();
            if (settings == null || !settings.isFile()) return false;
            JsonObject obj = JsonParser.parseString(Files.readString(settings.toPath())).getAsJsonObject();
            return obj.has("hiddenBlockCullingEnabled")
                && obj.get("hiddenBlockCullingEnabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
''',
    "\n",
    "remove legacy hidden-culling settings reader",
)

# FLATTER used to construct a second IPC GeometryBuilder. The primary builder
# had already consumed ExportWorldContext and had the current request's
# face/hidden-culling switches, so the second builder lost neighbour state and
# could render different geometry. Add an overload that accepts the exact
# already-configured export builder while retaining legacy/test overloads.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/FlatterOptimizer.java",
    '''    public static Result compile(
        List<BlockData> blocks,
        ResolverChain resolvers,
        ObjExporter.ProgressCallback progress
    ) {
        return compile(blocks, resolvers, FlatterSettings.cellSize(), progress);
    }
''',
    '''    public static Result compile(
        List<BlockData> blocks,
        ResolverChain resolvers,
        ObjExporter.ProgressCallback progress
    ) {
        return compile(blocks, resolvers, FlatterSettings.cellSize(), progress);
    }

    /**
     * Live-export overload: reuse the exact configured GeometryBuilder so
     * FLATTER sees the same runtime registry, neighbour map and culling flags
     * as conventional geometry.
     */
    public static Result compile(
        List<BlockData> blocks,
        GeometryBuilder geometry,
        ResolverChain resolvers,
        ObjExporter.ProgressCallback progress
    ) {
        return compile(blocks, geometry, resolvers, FlatterSettings.cellSize(), progress);
    }
''',
    "add live GeometryBuilder overload",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/FlatterOptimizer.java",
    '''    public static Result compile(
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
''',
    '''    public static Result compile(
        List<BlockData> blocks,
        ResolverChain resolvers,
        int requestedCellSize,
        ObjExporter.ProgressCallback progress
    ) {
        GeometryBuilder geometry = resolvers == null
            ? null
            : new dev.kastrick.minesport.GeometryBuilder(resolvers);
        return compile(blocks, geometry, resolvers, requestedCellSize, progress);
    }

    private static Result compile(
        List<BlockData> blocks,
        GeometryBuilder geometry,
        ResolverChain resolvers,
        int requestedCellSize,
        ObjExporter.ProgressCallback progress
    ) {
        if (blocks == null || blocks.isEmpty() || resolvers == null || geometry == null) {
            return Result.empty();
        }

        int cellSize = FlatterSettings.normalizeCellSize(requestedCellSize);
        Map<MaterialKey,Boolean> opaqueCache = new HashMap<>();
''',
    "reuse configured builder internally",
)

replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/ObjExporter.java",
    "            ? FlatterOptimizer.compile(blocks, builder.getResolvers(), (doneCount, total) -> {\n",
    "            ? FlatterOptimizer.compile(blocks, builder, builder.getResolvers(), (doneCount, total) -> {\n",
    "OBJ FLATTER authoritative builder",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/GltfExporter.java",
    "            ? FlatterOptimizer.compile(blocks, resolvers, (doneCount, total) -> {\n",
    "            ? FlatterOptimizer.compile(blocks, builder, resolvers, (doneCount, total) -> {\n",
    "glTF FLATTER authoritative builder",
)

print("Applied authoritative hidden-culling and FLATTER GeometryBuilder fixes")
