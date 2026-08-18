package dev.kastrick.minesport;

import com.google.gson.*;
import dev.kastrick.minesport.export.*;
import dev.kastrick.minesport.region.*;
import dev.kastrick.minesport.resolver.*;
import dev.kastrick.minesport.safety.WorldCopier;

import java.io.*;
import java.util.*;

/**
 * IPC mode — called when Java engine is launched with --ipc flag by Go wrapper.
 *
 * Protocol: newline-delimited JSON over stdin/stdout.
 *
 * Go → Java (stdin):
 *   {"command":"ping"}
 *   {"command":"export","worldPath":"...","minX":-64,...,"format":"gltf","exportMode":"grouped"}
 *   {"command":"quit"}
 *
 * Java → Go (stdout):
 *   {"type":"info","version":"0.1.0"}
 *   {"type":"log","message":"..."}
 *   {"type":"progress","percent":42,"message":"..."}
 *   {"type":"done","output":"/path/to/file.gltf"}
 *   {"type":"error","message":"..."}
 */
public class IpcMode {

    private static final Gson GSON = new Gson();
    private static final PrintWriter OUT = new PrintWriter(
        new BufferedWriter(new OutputStreamWriter(System.out)), true
    );

    public static void run() {
        send("info", j -> j.addProperty("version", "0.1.0"));
        log("Minesport engine ready (IPC mode)");

        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonObject req = GSON.fromJson(line, JsonObject.class);
                    String command = req.has("command") ? req.get("command").getAsString() : "";

                    switch (command) {
                        case "ping"      -> send("pong", j -> j.addProperty("message", "pong"));
                        case "export"    -> handleExport(req);
                        case "heightmap" -> handleHeightmap(req);
                        case "listBlocks" -> handleListBlocks(req);
                        case "quit"      -> {
                            log("Engine shutting down.");
                            return;
                        }
                        default -> error("Unknown command: " + command);
                    }
                } catch (JsonSyntaxException e) {
                    error("Invalid JSON: " + e.getMessage());
                } catch (Exception e) {
                    error("Command failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            error("IPC stdin error: " + e.getMessage());
        }
    }

    // ── Export handler ────────────────────────────────────────────────────────

    private static void handleExport(JsonObject req) {
        String worldPath  = getString(req, "worldPath", "");
        int minX = getInt(req, "minX", -256);
        int minY = getInt(req, "minY", -64);
        int minZ = getInt(req, "minZ", -256);
        int maxX = getInt(req, "maxX", 256);
        int maxY = getInt(req, "maxY", 320);
        int maxZ = getInt(req, "maxZ", 256);
        String format     = getString(req, "format", "gltf").toLowerCase();
        String exportMode = getString(req, "exportMode", "grouped");
        String outputPath = getString(req, "outputPath", "");

        if (worldPath.isEmpty()) {
            error("worldPath is required");
            return;
        }

        File worldFolder = new File(worldPath);
        if (!worldFolder.exists() || !new File(worldFolder, "level.dat").exists()) {
            error("World not found or invalid: " + worldPath);
            return;
        }

        // Auto-generate output path if not provided
        if (outputPath.isEmpty()) {
            String home = System.getProperty("user.home");
            String ext  = format.equals("gltf") ? ".gltf" : ".obj";
            outputPath = home + File.separator + "Minesport_Exports"
                       + File.separator + worldFolder.getName() + "_export" + ext;
        }

        File outFile = new File(outputPath);
        outFile.getParentFile().mkdirs();

        // Run export in this thread (Go already dispatches on a goroutine)
        File tempDir = null;
        try {
            // Step 1: Safe world copy
            log("Creating temp copy...");
            tempDir = WorldCopier.copyToTemp(worldFolder, msg -> log(msg));
            progress(10, "World copy ready");

            // Step 2: Read region files
            log("Scanning region files...");
            File regionDir = new File(tempDir, "region");
            if (!regionDir.exists()) {
                error("No region folder found in world");
                return;
            }

            File[] mcaFiles = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
            if (mcaFiles == null || mcaFiles.length == 0) {
                error("No .mca region files found");
                return;
            }

            log("Found " + mcaFiles.length + " region file(s)");
            var allBlocks = new ArrayList<BlockData>();

            for (int fi = 0; fi < mcaFiles.length; fi++) {
                File mca = mcaFiles[fi];
                log("Reading: " + mca.getName());
                List<BlockData> regionBlocks = RegionReader.readRegion(
                    mca, minX, minY, minZ, maxX, maxY, maxZ, null
                );
                allBlocks.addAll(regionBlocks);

                int pct = 10 + (int)((fi + 1.0) / mcaFiles.length * 30);
                progress(pct, "Read " + mca.getName());
            }

            log("Total blocks: " + allBlocks.size());

            // Bubble/radius selection: MinX..MaxZ above is always a box (the
            // engine has to scan region files by bounding box regardless),
            // but if the request also carries a center + radius, narrow that
            // box down to an ellipsoid — this is what the Go UI's "bubble"
            // selection mode actually sends. Plain box exports simply won't
            // have these fields set, so this is a no-op for them.
            Integer cx = getOptionalInt(req, "centerX");
            Integer cy = getOptionalInt(req, "centerY");
            Integer cz = getOptionalInt(req, "centerZ");
            Integer rx = getOptionalInt(req, "radiusX");
            Integer ry = getOptionalInt(req, "radiusY");
            Integer rz = getOptionalInt(req, "radiusZ");
            if (cx != null && cy != null && cz != null && rx != null && ry != null && rz != null) {
                int before = allBlocks.size();
                allBlocks.removeIf(b -> !insideEllipsoid(b, cx, cy, cz,
                    Math.max(rx, 1), Math.max(ry, 1), Math.max(rz, 1)));
                log("Bubble selection: " + allBlocks.size() + " / " + before + " blocks kept "
                    + "(center " + cx + "," + cy + "," + cz + " · radius " + rx + "," + ry + "," + rz + ")");
            }

            // Exact selection (e.g. a "Joined Blocks" flood-fill from the 3D
            // viewer) — not representable as a box or ellipsoid, so it comes
            // in as a file of explicit [x,y,z] coordinates instead. Narrows
            // whatever the box/ellipsoid above already selected down to
            // exactly this set.
            String customSelectionFile = req.has("options") && req.getAsJsonObject("options").has("customSelectionFile")
                ? req.getAsJsonObject("options").get("customSelectionFile").getAsString() : null;
            if (customSelectionFile != null && !customSelectionFile.isBlank()) {
                Set<Long> exact = loadCustomSelection(new File(customSelectionFile));
                int before = allBlocks.size();
                allBlocks.removeIf(b -> !exact.contains(packKey(b.x, b.y, b.z)));
                log("Custom selection: " + allBlocks.size() + " / " + before + " blocks kept ("
                    + exact.size() + " coordinate(s) requested)");
            }
            progress(40, "Region scan complete");

            // Step 3: Multipart resolution
            log("Resolving multipart connections...");
            MultipartResolver.resolve(allBlocks);
            progress(45, "Multipart resolved");

            // Step 4: Build resolver chain
            log("Setting up resolvers...");
            var chain = new ResolverChain();

            // Resource packs go FIRST — they're meant to override everything
            // below them (vanilla, mods, all of it), same as in real Minecraft.
            List<File> resourcePackPaths = getPathList(req, "resourcePacks");
            if (!resourcePackPaths.isEmpty()) {
                ResourcePackResolver rp = ResourcePackResolver.load(resourcePackPaths, msg -> log(msg));
                if (!rp.isEmpty()) {
                    chain.addResolver(rp);
                    log("Resource pack override active (" + resourcePackPaths.size() + " pack(s))");
                }
            }

            String mcVersion = readMcVersion(tempDir);
            log("MC version: " + mcVersion);

            File mcJar = VanillaResolver.findMinecraftJar(mcVersion);
            if (mcJar != null && mcJar.exists()) {
                log("Vanilla resolver: " + mcJar.getName());
                chain.addResolver(new VanillaResolver(mcJar));
            } else {
                log("[WARN] minecraft.jar not found — vanilla blocks use fallback geometry");
            }

            ModsLocator.LocatedMods located = ModsLocator.locate(worldFolder);
            File modsFolder = located != null ? located.modsFolder() : null;
            if (modsFolder == null) {
                for (File c : ModsLocator.candidatePaths(mcVersion)) {
                    if (c.exists()) { modsFolder = c; break; }
                }
            }

            if (modsFolder != null) {
                // Try every loader's resolver against the same mods folder. Each one
                // only claims jars it can positively identify via that loader's own
                // metadata file (fabric.mod.json / quilt.mod.json / mods.toml), so
                // it's safe to run all three even though a real instance normally
                // only has jars for one loader — nothing gets double-claimed, and
                // this sidesteps needing 100%-reliable loader auto-detection.
                FabricResolver fab = FabricResolver.load(modsFolder, msg -> log(msg));
                if (!fab.getNamespaces().isEmpty()) {
                    chain.addResolver(fab);
                    log("Fabric mod namespaces: " + fab.getNamespaces());
                    // Polymer fallback for mods with no blockstates/ folder
                    chain.addResolver(new PolymerResolver(fab));
                }

                QuiltResolver quilt = QuiltResolver.load(modsFolder, msg -> log(msg));
                if (!quilt.getNamespaces().isEmpty()) {
                    chain.addResolver(quilt);
                    log("Quilt mod namespaces: " + quilt.getNamespaces());
                }

                ForgeResolver forge = ForgeResolver.load(modsFolder, msg -> log(msg));
                if (!forge.getNamespaces().isEmpty()) {
                    chain.addResolver(forge);
                    log("Forge/NeoForge mod namespaces: " + forge.getNamespaces());
                }
            }

            // Data packs — block tags only (see DataPackBlockTagReader for why).
            // Defaults to auto-discovering the world's own bundled datapacks/
            // folder unless the request explicitly overrides with its own list.
            List<File> dataPackPaths = getPathList(req, "dataPacks");
            if (dataPackPaths.isEmpty()) {
                dataPackPaths = dev.kastrick.minesport.datapack.DataPackBlockTagReader.discoverWorldDataPacks(tempDir);
            }
            if (!dataPackPaths.isEmpty()) {
                var tagReader = dev.kastrick.minesport.datapack.DataPackBlockTagReader.load(dataPackPaths, msg -> log(msg));
                if (!tagReader.isEmpty()) {
                    log("Data pack block tags found: " + tagReader.getTagIds());
                }
            }

            progress(50, "Resolvers ready");

            // Step 5: Export
            boolean optimize = getBoolOption(req, "optimize", false);
            var geoBuilder = new GeometryBuilder(chain);
            if (optimize) {
                log("Optimize Output enabled (experimental): culling hidden faces, welding vertices...");
                geoBuilder.enableFaceCulling(allBlocks);
            }
            ObjExporter.ExportMode mode = switch (exportMode) {
                case "merged"     -> ObjExporter.ExportMode.ALL_MERGED;
                case "individual" -> ObjExporter.ExportMode.INDIVIDUAL;
                default           -> ObjExporter.ExportMode.GROUPED_BY_TYPE;
            };

            log("Exporting as " + format.toUpperCase() + "...");

            ObjExporter.ExportStats stats;
            if (format.equals("gltf")) {
                stats = new GltfExporter(chain).export(allBlocks, geoBuilder, outFile, mode, optimize,
                    (doneCount, total) -> {
                        int pct = 50 + (int)((doneCount / (double) total) * 45);
                        progress(pct, "Building geometry " + doneCount + "/" + total);
                    });
            } else {
                stats = ObjExporter.exportWithGeometry(allBlocks, geoBuilder, outFile, mode, optimize,
                    (doneCount, total) -> {
                        int pct = 50 + (int)((doneCount / (double) total) * 45);
                        progress(pct, "Building geometry " + doneCount + "/" + total);
                    });
            }

            progress(100, "Done");
            log("Export stats: " + stats.blockCount() + " blocks, " + stats.quadCount() + " faces, "
                + "≤" + stats.vertexCount() + " vertices");
            done(outFile.getAbsolutePath(), stats);

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            error("Export failed: " + e.getMessage() + "\n" + sw);
        } finally {
            if (tempDir != null) {
                WorldCopier.cleanupTemp(tempDir);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String readMcVersion(File tempDir) {
        try {
            var levelDat = new File(tempDir, "level.dat");
            var root = dev.kastrick.minesport.nbt.NbtReader.readGzip(levelDat);
            if (root.has("Data")) {
                try {
                    return root.getCompound("Data").getCompound("Version").getString("Name", "1.21.10");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return "1.21.10";
    }

    // ── JSON output helpers ───────────────────────────────────────────────────

    private static void send(String type, java.util.function.Consumer<JsonObject> builder) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        builder.accept(obj);
        OUT.println(GSON.toJson(obj));
    }

    private static void log(String msg) {
        send("log", j -> j.addProperty("message", msg));
    }

    private static void progress(int pct, String msg) {
        send("progress", j -> {
            j.addProperty("percent", pct);
            j.addProperty("message", msg);
        });
    }

    private static void done(String outputPath, ObjExporter.ExportStats stats) {
        send("done", j -> {
            j.addProperty("output", outputPath);
            if (stats != null) {
                j.addProperty("blockCount", stats.blockCount());
                j.addProperty("quadCount", stats.quadCount());
                j.addProperty("vertexCount", stats.vertexCount());
            }
        });
    }

    private static void error(String msg) {
        send("error", j -> j.addProperty("message", msg));
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }

    /**
     * Reads a JSON array of {"x":..,"y":..,"z":..} coordinates (as written
     * by the 3D viewer's flood-fill selection) into a packed-key set for
     * fast membership testing.
     */
    private static Set<Long> loadCustomSelection(File file) throws IOException {
        Set<Long> result = new HashSet<>();
        if (!file.exists()) return result;

        try (var reader = new com.google.gson.stream.JsonReader(new BufferedReader(new FileReader(file)))) {
            reader.beginArray();
            while (reader.hasNext()) {
                reader.beginObject();
                int x = 0, y = 0, z = 0;
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "x" -> x = reader.nextInt();
                        case "y" -> y = reader.nextInt();
                        case "z" -> z = reader.nextInt();
                        default  -> reader.skipValue();
                    }
                }
                reader.endObject();
                result.add(packKey(x, y, z));
            }
            reader.endArray();
        }
        return result;
    }

    /** Pack x,y,z into a single long — same scheme as MultipartResolver/BlockGrouper/GeometryBuilder. */
    private static long packKey(int x, int y, int z) {
        return ((long) (x + 1048576) << 42)
             | ((long) (y + 1048576) << 21)
             |  (long) (z + 1048576);
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) ? obj.get(key).getAsInt() : fallback;
    }

    /** Like getInt, but returns null instead of a fallback when the field is absent. */
    private static Integer getOptionalInt(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsInt() : null;
    }

    /**
     * True if a block's center falls within the ellipsoid defined by
     * (cx,cy,cz) and per-axis radii (rx,ry,rz):
     *   ((x-cx)/rx)^2 + ((y-cy)/ry)^2 + ((z-cz)/rz)^2 <= 1
     * A block's own center is used (position + 0.5 on each axis) rather than
     * its corner, so a block sitting exactly on the boundary is judged by
     * where most of its volume actually is.
     */
    private static boolean insideEllipsoid(dev.kastrick.minesport.region.BlockData b,
                                            int cx, int cy, int cz, int rx, int ry, int rz) {
        double dx = (b.x + 0.5 - cx) / (double) rx;
        double dy = (b.y + 0.5 - cy) / (double) ry;
        double dz = (b.z + 0.5 - cz) / (double) rz;
        return dx * dx + dy * dy + dz * dz <= 1.0;
    }

    /**
     * Reads a semicolon-separated list of filesystem paths from req.options.<key>.
     * (Semicolon rather than comma — Windows paths occasionally contain commas
     * but essentially never semicolons.) Missing/blank entries are skipped.
     * Returns an empty list if "options" or the key isn't present.
     */
    /** Reads a boolean flag out of req.options.<key> (as the string "true"/"false"). */
    private static boolean getBoolOption(JsonObject req, String key, boolean fallback) {
        if (!req.has("options") || !req.get("options").isJsonObject()) return fallback;
        JsonObject options = req.getAsJsonObject("options");
        if (!options.has(key)) return fallback;
        return Boolean.parseBoolean(options.get(key).getAsString());
    }

    private static List<File> getPathList(JsonObject req, String key) {
        List<File> result = new ArrayList<>();
        if (!req.has("options") || !req.get("options").isJsonObject()) return result;
        JsonObject options = req.getAsJsonObject("options");
        if (!options.has(key)) return result;

        String raw = options.get(key).getAsString();
        if (raw == null || raw.isBlank()) return result;

        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            File f = new File(trimmed);
            if (f.exists()) {
                result.add(f);
            } else {
                log("[WARN] Path not found, skipping: " + trimmed);
            }
        }
        return result;
    }

    private static void handleHeightmap(JsonObject req) {
        String worldPath = getString(req, "worldPath", "");
        int scale        = getInt(req, "scale", 4); // blocks per pixel

        if (worldPath.isEmpty()) { error("worldPath required"); return; }

        File regionDir = new File(worldPath, "region");
        if (!regionDir.exists()) { error("No region folder: " + worldPath); return; }

        try {
            log("Generating heightmap (scale=" + scale + ")...");
            String b64 = dev.kastrick.minesport.region.HeightmapGenerator.generateBase64Png(regionDir, scale);
            if (b64 == null) { error("No region files found"); return; }

            // Emit bounds alongside image
            File[] mcaFiles = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
            int minRX = Integer.MAX_VALUE, minRZ = Integer.MAX_VALUE;
            int maxRX = Integer.MIN_VALUE, maxRZ = Integer.MIN_VALUE;
            if (mcaFiles != null) {
                for (File f : mcaFiles) {
                    String[] p = f.getName().split("\\.");
                    if (p.length < 4) continue;
                    try {
                        int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
                        minRX = Math.min(minRX, rx); minRZ = Math.min(minRZ, rz);
                        maxRX = Math.max(maxRX, rx); maxRZ = Math.max(maxRZ, rz);
                    } catch (NumberFormatException ignored) {}
                }
            }
            final int minX = minRX * 512, minZ = minRZ * 512;
            final int maxX = (maxRX + 1) * 512, maxZ = (maxRZ + 1) * 512;
            final String imgData = b64;

            send("heightmap", j -> {
                j.addProperty("image", imgData);
                j.addProperty("minX", minX);
                j.addProperty("minZ", minZ);
                j.addProperty("maxX", maxX);
                j.addProperty("maxZ", maxZ);
                j.addProperty("scale", scale);
            });
        } catch (Exception e) {
            error("Heightmap failed: " + e.getMessage());
        }
    }

    // ── Block list handler (3D preview viewer) ───────────────────────────────

    /**
     * Lightweight sibling to handleExport: reads block positions + IDs within
     * the given bounds (box, optionally narrowed to an ellipsoid — same
     * fields as export) but does NOT run the resolver chain, model
     * resolution, or geometry building at all. The 3D viewer only needs
     * "where are the solid blocks and roughly what color" to render voxel
     * cubes and do picking, not full per-model geometry — so this skips the
     * expensive parts of the pipeline entirely and stays fast enough to
     * re-run whenever the viewer's selection changes.
     *
     * Results are written to a temp JSON file rather than sent inline —
     * a real selection can easily be 100k+ blocks, and the IPC transport
     * reads one line per message (see ipc.Engine.readLoop on the Go side),
     * which has a bounded scan-token size. A file has no such ceiling.
     */
    private static void handleListBlocks(JsonObject req) {
        String worldPath = getString(req, "worldPath", "");
        int minX = getInt(req, "minX", -256);
        int minY = getInt(req, "minY", -64);
        int minZ = getInt(req, "minZ", -256);
        int maxX = getInt(req, "maxX", 256);
        int maxY = getInt(req, "maxY", 320);
        int maxZ = getInt(req, "maxZ", 256);

        if (worldPath.isEmpty()) { error("worldPath required"); return; }

        File worldFolder = new File(worldPath);
        if (!worldFolder.exists() || !new File(worldFolder, "level.dat").exists()) {
            error("World not found or invalid: " + worldPath);
            return;
        }

        File tempWorldCopy = null;
        try {
            log("Preparing block list for 3D preview...");
            tempWorldCopy = WorldCopier.copyToTemp(worldFolder, msg -> log(msg));

            File regionDir = new File(tempWorldCopy, "region");
            if (!regionDir.exists()) { error("No region folder found in world"); return; }

            File[] mcaFiles = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
            if (mcaFiles == null || mcaFiles.length == 0) { error("No .mca region files found"); return; }

            var allBlocks = new ArrayList<BlockData>();
            for (File mca : mcaFiles) {
                allBlocks.addAll(RegionReader.readRegion(mca, minX, minY, minZ, maxX, maxY, maxZ, null));
            }

            // Same optional ellipsoid narrowing as export (bubble selection).
            Integer cx = getOptionalInt(req, "centerX");
            Integer cy = getOptionalInt(req, "centerY");
            Integer cz = getOptionalInt(req, "centerZ");
            Integer rx = getOptionalInt(req, "radiusX");
            Integer ry = getOptionalInt(req, "radiusY");
            Integer rz = getOptionalInt(req, "radiusZ");
            if (cx != null && cy != null && cz != null && rx != null && ry != null && rz != null) {
                int fcx = cx, fcy = cy, fcz = cz;
                int frx = Math.max(rx, 1), fry = Math.max(ry, 1), frz = Math.max(rz, 1);
                allBlocks.removeIf(b -> !insideEllipsoid(b, fcx, fcy, fcz, frx, fry, frz));
            }

            allBlocks.removeIf(BlockData::isAir);
            log("Block list: " + allBlocks.size() + " solid block(s)");

            File outFile = File.createTempFile("minesport_blocks_", ".json");
            outFile.deleteOnExit();

            try (var writer = new com.google.gson.stream.JsonWriter(
                    new BufferedWriter(new FileWriter(outFile)))) {
                writer.beginArray();
                for (BlockData b : allBlocks) {
                    writer.beginObject();
                    writer.name("x").value(b.x);
                    writer.name("y").value(b.y);
                    writer.name("z").value(b.z);
                    writer.name("id").value(b.blockId);
                    int[] color = dev.kastrick.minesport.region.HeightmapGenerator.colorForBlock(b.blockId);
                    writer.name("r").value(color[0]);
                    writer.name("g").value(color[1]);
                    writer.name("b").value(color[2]);
                    writer.endObject();
                }
                writer.endArray();
            }

            send("blocksReady", j -> {
                j.addProperty("file", outFile.getAbsolutePath());
                j.addProperty("count", allBlocks.size());
            });

        } catch (Exception e) {
            error("List blocks failed: " + e.getMessage());
        } finally {
            if (tempWorldCopy != null) {
                WorldCopier.cleanupTemp(tempWorldCopy);
            }
        }
    }

    // ── end of IpcMode ────────────────────────────────────────────────────────
}
