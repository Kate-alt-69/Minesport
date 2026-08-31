package dev.kastrick.minesport;

import com.google.gson.*;
import dev.kastrick.minesport.export.*;
import dev.kastrick.minesport.region.*;
import dev.kastrick.minesport.resolver.*;
import dev.kastrick.minesport.safety.WorldCopier;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** IPC mode for the Go wrapper. */
public class IpcMode {
    private static final Gson GSON = new Gson();
    private static final PrintWriter OUT = new PrintWriter(
        new BufferedWriter(new OutputStreamWriter(System.out)),
        true
    );
    private static final int MAX_CUSTOM_SELECTION = 5_000_000;

    public static void run() {
        send("info", json -> json.addProperty("version", "0.2.1"));
        log("Minesport engine ready (IPC mode)");

        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonObject request = GSON.fromJson(line, JsonObject.class);
                    String command = request.has("command")
                        ? request.get("command").getAsString()
                        : "";

                    switch (command) {
                        case "ping" -> send("pong", json -> json.addProperty("message", "pong"));
                        case "export" -> handleExport(request);
                        case "heightmap" -> handleHeightmap(request);
                        case "listBlocks" -> handleListBlocks(request);
                        case "quit" -> {
                            log("Engine shutting down.");
                            return;
                        }
                        default -> error("Unknown command: " + command);
                    }
                } catch (JsonSyntaxException exception) {
                    error("Invalid JSON: " + exception.getMessage());
                } catch (Exception exception) {
                    error("Command failed: " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            error("IPC stdin error: " + exception.getMessage());
        }
    }

    private static void handleExport(JsonObject request) {
        String worldPath = getString(request, "worldPath", "");
        int minX = getInt(request, "minX", -256);
        int minY = getInt(request, "minY", -64);
        int minZ = getInt(request, "minZ", -256);
        int maxX = getInt(request, "maxX", 256);
        int maxY = getInt(request, "maxY", 320);
        int maxZ = getInt(request, "maxZ", 256);

        int copyMinX = minX;
        int copyMinZ = minZ;
        int copyMaxX = maxX;
        int copyMaxZ = maxZ;
        Integer copyCenterX = getOptionalInt(request, "centerX");
        Integer copyCenterZ = getOptionalInt(request, "centerZ");
        Integer copyRadiusX = getOptionalInt(request, "radiusX");
        Integer copyRadiusZ = getOptionalInt(request, "radiusZ");
        if (copyCenterX != null && copyCenterZ != null && copyRadiusX != null && copyRadiusZ != null) {
            int rx = Math.max(copyRadiusX, 1);
            int rz = Math.max(copyRadiusZ, 1);
            copyMinX = copyCenterX - rx;
            copyMaxX = copyCenterX + rx;
            copyMinZ = copyCenterZ - rz;
            copyMaxZ = copyCenterZ + rz;
        }

        String format = getString(request, "format", "gltf").toLowerCase();
        String exportMode = getString(request, "exportMode", "grouped");
        String outputPath = getString(request, "outputPath", "");

        if (worldPath.isEmpty()) {
            error("worldPath is required");
            return;
        }

        File worldFolder = new File(worldPath);
        if (!worldFolder.exists() || !new File(worldFolder, "level.dat").exists()) {
            error("World not found or invalid: " + worldPath);
            return;
        }

        if (outputPath.isEmpty()) {
            String home = System.getProperty("user.home");
            String extension = switch (format) {
                case "gltf" -> ".gltf";
                case "litematic" -> ".litematic";
                default -> ".obj";
            };
            outputPath = home
                + File.separator + "Minesport_Exports"
                + File.separator + worldFolder.getName() + "_export" + extension;
        }

        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();

        File tempDir = null;
        File stagedOutput = null;
        ResolverChain chain = null;
        try {
            progressIndeterminate("Preparing selected world data");
            tempDir = WorldCopier.copyToTemp(
                worldFolder,
                copyMinX, copyMinZ,
                copyMaxX, copyMaxZ,
                IpcMode::log
            );
            boolean separateEntityRegions = format.equals("litematic")
                && WorldCopier.copyOverworldEntitiesToTemp(
                    worldFolder,
                    tempDir,
                    copyMinX, copyMinZ,
                    copyMaxX, copyMaxZ,
                    IpcMode::log
                );
            progressIndeterminate("Scanning selected regions");

            log("Scanning region files...");
            File regionDir = new File(tempDir, "region");
            if (!regionDir.exists()) {
                error("No region folder found in world");
                return;
            }

            File[] regionFiles = regionDir.listFiles((directory, name) -> isRegionFileName(name));
            if (regionFiles == null || regionFiles.length == 0) {
                error("No region files (.mca/.mcr) found");
                return;
            }

            Arrays.sort(regionFiles, Comparator.comparing(File::getName));
            log("Found " + regionFiles.length + " selected region file(s)");

            File[] entityFiles = new File[0];
            if (format.equals("litematic") && separateEntityRegions) {
                File entityDir = new File(tempDir, "entities");
                File[] listed = entityDir.listFiles(
                    (directory, name) -> isRegionFileName(name)
                );
                if (listed != null) {
                    Arrays.sort(listed, Comparator.comparing(File::getName));
                    entityFiles = listed;
                }
            }

            int[] blockChunkCounts = new int[regionFiles.length];
            int[] entityChunkCounts = new int[entityFiles.length];
            int inputChunkTotal = 0;
            for (int i = 0; i < regionFiles.length; i++) {
                blockChunkCounts[i] = RegionReader.countSelectedChunks(
                    regionFiles[i], minX, minZ, maxX, maxZ
                );
                inputChunkTotal += blockChunkCounts[i];
            }
            for (int i = 0; i < entityFiles.length; i++) {
                entityChunkCounts[i] = RegionReader.countSelectedChunks(
                    entityFiles[i], minX, minZ, maxX, maxZ
                );
                inputChunkTotal += entityChunkCounts[i];
            }
            final int totalInputChunks = inputChunkTotal;

            var allBlocks = new ArrayList<BlockData>();
            var allBlockEntities = new ArrayList<BlockEntityData>();
            var allEntities = new ArrayList<EntityData>();
            var allBlockTicks = new ArrayList<ScheduledTickData>();
            var allFluidTicks = new ArrayList<ScheduledTickData>();
            int inputDoneBase = 0;

            for (int fileIndex = 0; fileIndex < regionFiles.length; fileIndex++) {
                File regionFile = regionFiles[fileIndex];
                final int progressBase = inputDoneBase;
                progressIndeterminate("Reading " + regionFile.getName());
                RegionReader.ProgressCallback chunkProgress = (doneCount, ignoredTotal, message) ->
                    reportChunkProgress(
                        progressBase + doneCount,
                        totalInputChunks,
                        message + " · " + regionFile.getName()
                    );

                if (format.equals("litematic")) {
                    RegionReader.RegionContents contents = RegionReader.readRegionContents(
                        regionFile,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        chunkProgress
                    );
                    allBlocks.addAll(contents.blocks());
                    allBlockEntities.addAll(contents.blockEntities());
                    allBlockTicks.addAll(contents.blockTicks());
                    allFluidTicks.addAll(contents.fluidTicks());
                    if (!separateEntityRegions) {
                        allEntities.addAll(contents.entities());
                    }
                } else {
                    allBlocks.addAll(RegionReader.readRegion(
                        regionFile,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        chunkProgress
                    ));
                }
                inputDoneBase += blockChunkCounts[fileIndex];
            }

            if (format.equals("litematic") && separateEntityRegions) {
                for (int fileIndex = 0; fileIndex < entityFiles.length; fileIndex++) {
                    File entityFile = entityFiles[fileIndex];
                    final int progressBase = inputDoneBase;
                    progressIndeterminate("Reading entities " + entityFile.getName());
                    RegionReader.ProgressCallback chunkProgress = (doneCount, ignoredTotal, message) ->
                        reportChunkProgress(
                            progressBase + doneCount,
                            totalInputChunks,
                            "Reading entities " + entityFile.getName()
                        );
                    allEntities.addAll(RegionReader.readEntityRegion(
                        entityFile,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        chunkProgress
                    ));
                    inputDoneBase += entityChunkCounts[fileIndex];
                }
            }

            log(
                "Total blocks: " + allBlocks.size()
                + (format.equals("litematic")
                    ? " · block entities: " + allBlockEntities.size()
                        + " · entities: " + allEntities.size()
                        + " · block ticks: " + allBlockTicks.size()
                        + " · fluid ticks: " + allFluidTicks.size()
                    : "")
            );

            Integer centerX = getOptionalInt(request, "centerX");
            Integer centerY = getOptionalInt(request, "centerY");
            Integer centerZ = getOptionalInt(request, "centerZ");
            Integer radiusX = getOptionalInt(request, "radiusX");
            Integer radiusY = getOptionalInt(request, "radiusY");
            Integer radiusZ = getOptionalInt(request, "radiusZ");

            if (
                centerX != null && centerY != null && centerZ != null &&
                radiusX != null && radiusY != null && radiusZ != null
            ) {
                int before = allBlocks.size();
                allBlocks.removeIf(block -> !insideEllipsoid(
                    block,
                    centerX, centerY, centerZ,
                    Math.max(radiusX, 1),
                    Math.max(radiusY, 1),
                    Math.max(radiusZ, 1)
                ));
                allBlockEntities.removeIf(entity -> !insideEllipsoid(
                    entity.x(), entity.y(), entity.z(),
                    centerX, centerY, centerZ,
                    Math.max(radiusX, 1),
                    Math.max(radiusY, 1),
                    Math.max(radiusZ, 1)
                ));
                allEntities.removeIf(entity -> !insideEllipsoidPoint(
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
                ));
                log(
                    "Bubble selection: " + allBlocks.size() + " / " + before
                    + " blocks kept (center " + centerX + "," + centerY + "," + centerZ
                    + " · radius " + radiusX + "," + radiusY + "," + radiusZ + ")"
                );
            }

            String customSelectionFile = getStringOption(request, "customSelectionFile", null);
            if (customSelectionFile != null && !customSelectionFile.isBlank()) {
                Set<Long> exact = loadCustomSelection(new File(customSelectionFile));
                int before = allBlocks.size();
                allBlocks.removeIf(block -> !exact.contains(SpatialKey.of(block.x, block.y, block.z)));
                allBlockEntities.removeIf(entity ->
                    !exact.contains(SpatialKey.of(entity.x(), entity.y(), entity.z()))
                );
                allEntities.removeIf(entity ->
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
                );
                log(
                    "Custom selection: " + allBlocks.size() + " / " + before
                    + " blocks kept (" + exact.size() + " coordinate(s) requested)"
                );
            }
            progressIndeterminate("Preparing export data");

            if (format.equals("litematic")) {
                String mcVersion = readMcVersion(tempDir);
                int dataVersion = readMinecraftDataVersion(tempDir);
                if (dataVersion <= 0) {
                    log("[WARN] World has no Minecraft DataVersion; using the Litematica compatibility fallback");
                }
                progressIndeterminate("Writing Litematica file");
                String schematicName = outFile.getName().replaceFirst("(?i)\\.litematic$", "");
                Path outputParent = outFile.toPath().toAbsolutePath().getParent();
                if (outputParent == null) outputParent = Path.of(".").toAbsolutePath();
                stagedOutput = Files.createTempFile(
                    outputParent,
                    "." + outFile.getName() + ".",
                    ".part"
                ).toFile();
                LitematicExporter.ExportStats schematicStats = LitematicExporter.export(
                    allBlocks,
                    allBlockEntities,
                    allEntities,
                    allBlockTicks,
                    allFluidTicks,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    schematicName,
                    "Minesport",
                    "Exported by Minesport from Minecraft " + mcVersion,
                    dataVersion,
                    stagedOutput,
                    IpcMode::reportLitematicWriteProgress
                );
                progressIndeterminate("Cleaning temporary world data");
                WorldCopier.cleanupTemp(tempDir);
                tempDir = null;
                log(
                    "Litematica export: " + schematicStats.blockCount() + " blocks, "
                    + schematicStats.blockEntityCount() + " block entities, "
                    + schematicStats.entityCount() + " entities, "
                    + schematicStats.blockTickCount() + " block ticks, "
                    + schematicStats.fluidTickCount() + " fluid ticks, "
                    + schematicStats.paletteSize() + " palette states, "
                    + schematicStats.volume() + " volume"
                );
                progress(98, "Publishing Litematica file");
                commitStagedOutput(stagedOutput, outFile);
                stagedOutput = null;
                // The final path now represents a complete export. Make the terminal
                // event the very next IPC message so the desktop cannot remain in an
                // earlier "Reading..." state while a finished file is visible.
                done(
                    outFile.getAbsolutePath(),
                    new ObjExporter.ExportStats(schematicStats.blockCount(), 0, 0)
                );
                return;
            }

            log("Resolving multipart connections...");
            MultipartResolver.resolve(allBlocks);
            progress(45, "Multipart resolved");

            log("Setting up resolvers...");
            chain = new ResolverChain();

            List<File> resourcePackPaths = getPathList(request, "resourcePacks");
            if (!resourcePackPaths.isEmpty()) {
                ResourcePackResolver resourcePacks = ResourcePackResolver.load(resourcePackPaths, IpcMode::log);
                if (!resourcePacks.isEmpty()) {
                    chain.addResolver(resourcePacks);
                    log("Resource pack override active (" + resourcePackPaths.size() + " pack(s))");
                }
            }

            String mcVersion = readMcVersion(tempDir);
            log("MC version: " + mcVersion);
            String bridgeRegistry = getStringOption(request, "bridgeRegistry", null);
            if (bridgeRegistry != null && !bridgeRegistry.isBlank()) {
                BridgeStateRegistry.apply(new File(bridgeRegistry), mcVersion, allBlocks, IpcMode::log);
            }
            File mcJar = VanillaResolver.findMinecraftJar(mcVersion);
            if (mcJar != null && mcJar.exists()) {
                log("Vanilla resolver: " + mcJar.getName());
                chain.addResolver(new VanillaResolver(mcJar));
            } else {
                log("[WARN] minecraft.jar not found — vanilla blocks use fallback geometry");
            }

            String requestedModsPath = getString(request, "modsPath", "").trim();
            String requestedLoader = getString(request, "modLoader", "").trim();
            if (!requestedLoader.isEmpty()) {
                log("Requested mod loader: " + requestedLoader);
            }
            File modsFolder = requestedModsPath.isEmpty() ? null : new File(requestedModsPath);
            if (modsFolder != null && modsFolder.isDirectory()) {
                log("Using selected instance mods folder: " + modsFolder.getAbsolutePath());
            } else {
                if (modsFolder != null) {
                    log("[WARN] Selected instance mods folder not found: " + modsFolder.getAbsolutePath());
                }
                ModsLocator.LocatedMods located = ModsLocator.locate(worldFolder);
                modsFolder = located != null ? located.modsFolder() : null;
            }
            if (modsFolder == null) {
                for (File candidate : ModsLocator.candidatePaths(mcVersion)) {
                    if (candidate.exists()) {
                        modsFolder = candidate;
                        break;
                    }
                }
            }

            addModResolvers(chain, modsFolder, requestedLoader);

            List<File> dataPackPaths = getPathList(request, "dataPacks");
            if (dataPackPaths.isEmpty()) {
                dataPackPaths = dev.kastrick.minesport.datapack.DataPackBlockTagReader
                    .discoverWorldDataPacks(tempDir);
            }
            if (!dataPackPaths.isEmpty()) {
                var tagReader = dev.kastrick.minesport.datapack.DataPackBlockTagReader.load(
                    dataPackPaths,
                    IpcMode::log
                );
                if (!tagReader.isEmpty()) {
                    log("Data pack block tags found: " + tagReader.getTagIds());
                }
            }
            progress(50, "Resolvers ready");

            boolean optimize = getBoolOption(request, "optimize", false);
            boolean faceCulling = getBoolOption(request, "faceCulling", false);
            boolean hiddenBlockCulling = getBoolOption(request, "hiddenBlockCulling", false);

            // The desktop export request is authoritative for FLATTER. The old
            // path ignored these IPC options and let FlatterSettings fall back
            // to unrelated JVM/env/legacy settings, so the UI could say ON or
            // 64x64 while the Java exporter actually ran OFF or 16x16.
            boolean flatterOptimization = getBoolOption(
                request,
                "flatterOptimization",
                FlatterSettings.enabled()
            );
            int flatterCellSize = FlatterSettings.cellSize();
            String requestedFlatterCellSize = getStringOption(request, "flatterCellSize", null);
            if (requestedFlatterCellSize != null && !requestedFlatterCellSize.isBlank()) {
                try {
                    flatterCellSize = Integer.parseInt(requestedFlatterCellSize.trim());
                } catch (NumberFormatException ignored) {
                    log("[WARN] Invalid FLATTER cell size in export request: " + requestedFlatterCellSize);
                }
            }
            flatterCellSize = FlatterSettings.normalizeCellSize(flatterCellSize);
            System.setProperty("minesport.flatter", Boolean.toString(flatterOptimization));
            System.setProperty("minesport.flatterCellSize", Integer.toString(flatterCellSize));
            log(
                "FLATTER " + (flatterOptimization ? "enabled" : "disabled")
                    + " · cell " + flatterCellSize
            );

            boolean blenderExport = getBoolOption(request, "blenderExport", false);
            String blenderAnimationMode = getStringOption(
                request,
                "blenderAnimationMode",
                "animate_export"
            );

            var geometryBuilder = new GeometryBuilder(chain);
            if (optimize) {
                log("Vertex welding enabled");
            }
            if (faceCulling) {
                log("Face culling enabled");
                geometryBuilder.enableFaceCulling(allBlocks);
            }
            if (hiddenBlockCulling) {
                log("Hidden block culling enabled (experimental)");
                geometryBuilder.enableHiddenBlockCulling(allBlocks);
            }

            ObjExporter.ExportMode mode = switch (exportMode) {
                case "merged" -> ObjExporter.ExportMode.ALL_MERGED;
                case "individual" -> ObjExporter.ExportMode.INDIVIDUAL;
                default -> ObjExporter.ExportMode.GROUPED_BY_TYPE;
            };

            log("Exporting as " + format.toUpperCase() + "...");
            ObjExporter.ExportStats stats;
            if (format.equals("gltf")) {
                stats = new GltfExporter(chain).export(
                    allBlocks,
                    geometryBuilder,
                    outFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {
                        int percent = 50 + (int)((doneCount / (double)total) * 45);
                        progress(percent, "Building geometry " + doneCount + "/" + total);
                    }
                );
                GltfPostProcessor.fixSamplers(outFile);
                log("glTF sampler normalization complete");
            } else {
                stats = ObjExporter.exportWithGeometry(
                    allBlocks,
                    geometryBuilder,
                    outFile,
                    mode,
                    optimize,
                    (doneCount, total) -> {
                        int percent = 50 + (int)((doneCount / (double)total) * 45);
                        progress(percent, "Building geometry " + doneCount + "/" + total);
                    }
                );
            }

            if (blenderExport) {
                File metadata = BlenderMetadataExporter.write(
                    outFile,
                    allBlocks,
                    mode,
                    format,
                    blenderAnimationMode
                );
                log("Blender translation metadata: " + metadata.getName());
            }

            progress(100, "Done");
            log(
                "Export stats: " + stats.blockCount() + " blocks, "
                + stats.quadCount() + " faces, " + stats.vertexCount() + " vertices"
            );
            done(outFile.getAbsolutePath(), stats);
        } catch (Exception exception) {
            StringWriter stack = new StringWriter();
            exception.printStackTrace(new PrintWriter(stack));
            error("Export failed: " + exception.getMessage() + "\n" + stack);
        } finally {
            if (chain != null) {
                chain.close();
                chain = null;
            }
            if (stagedOutput != null) {
                try {
                    Files.deleteIfExists(stagedOutput.toPath());
                } catch (IOException ignored) {}
            }
            if (tempDir != null) WorldCopier.cleanupTemp(tempDir);
        }
    }

    /**
     * Minecraft has used both McRegion (.mcr) and Anvil (.mca) containers.
     * Keep the extension check in one place so export/entity enumeration cannot
     * silently diverge again. RegionReader handles the actual format details.
     */
    static boolean isRegionFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mca") || lower.endsWith(".mcr");
    }

    private static void commitStagedOutput(File staged, File output) throws IOException {
        try {
            Files.move(
                staged.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                staged.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static int readMinecraftDataVersion(File tempDir) {
        try {
            var levelDat = new File(tempDir, "level.dat");
            var root = dev.kastrick.minesport.nbt.NbtReader.readGzip(levelDat);
            if (root.has("Data")) {
                var data = root.getCompound("Data");
                if (data.has("DataVersion")) {
                    return data.getInt("DataVersion", 0);
                }
                if (data.has("Version")) {
                    return data.getCompound("Version").getInt("Id", 0);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String readMcVersion(File tempDir) {
        try {
            var levelDat = new File(tempDir, "level.dat");
            var root = dev.kastrick.minesport.nbt.NbtReader.readGzip(levelDat);
            if (root.has("Data")) {
                try {
                    return root.getCompound("Data")
                        .getCompound("Version")
                        .getString("Name", "1.21.10");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return "1.21.10";
    }

    private static void send(String type, java.util.function.Consumer<JsonObject> builder) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        builder.accept(object);
        OUT.println(GSON.toJson(object));
    }

    private static void log(String message) {
        send("log", json -> json.addProperty("message", message));
    }

    private static void progress(int percent, String message) {
        send("progress", json -> {
            json.addProperty("percent", percent);
            json.addProperty("message", message);
        });
    }

    private static void progressIndeterminate(String message) {
        progress(0, message);
    }

    private static void reportChunkProgress(int done, int total, String message) {
        if (total <= 0) {
            progressIndeterminate(message);
            return;
        }
        int interval = Math.max(1, total / 100);
        if (done <= 1 || done >= total || done % interval == 0) {
            int percent = 5 + (int)Math.round(done * 35.0 / total);
            progress(
                Math.max(5, Math.min(40, percent)),
                message + " · " + done + "/" + total + " chunks"
            );
        }
    }

    private static void reportLitematicWriteProgress(long done, long total) {
        if (total <= 0L) {
            progressIndeterminate("Writing Litematica file");
            return;
        }
        long interval = Math.max(1L, total / 100L);
        if (done <= 1L || done >= total || done % interval == 0L) {
            int percent = 45 + (int)Math.round(done * 50.0 / total);
            progress(
                Math.max(45, Math.min(95, percent)),
                "Writing Litematica · " + done + "/" + total + " state words"
            );
        }
    }

    private static void done(String outputPath, ObjExporter.ExportStats stats) {
        send("done", json -> {
            json.addProperty("output", outputPath);
            if (stats != null) {
                json.addProperty("blockCount", stats.blockCount());
                json.addProperty("quadCount", stats.quadCount());
                json.addProperty("vertexCount", stats.vertexCount());
            }
        });
    }

    private static void error(String message) {
        send("error", json -> json.addProperty("message", message));
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static Set<Long> loadCustomSelection(File file) throws IOException {
        Set<Long> result = new HashSet<>();
        if (!file.exists()) return result;

        try (var reader = new com.google.gson.stream.JsonReader(
            new BufferedReader(new FileReader(file))
        )) {
            reader.beginArray();
            while (reader.hasNext()) {
                if (result.size() >= MAX_CUSTOM_SELECTION) {
                    throw new IOException(
                        "Custom selection exceeds " + MAX_CUSTOM_SELECTION + " blocks"
                    );
                }

                reader.beginObject();
                int x = 0, y = 0, z = 0;
                boolean hasX = false, hasY = false, hasZ = false;
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "x" -> {
                            x = reader.nextInt();
                            hasX = true;
                        }
                        case "y" -> {
                            y = reader.nextInt();
                            hasY = true;
                        }
                        case "z" -> {
                            z = reader.nextInt();
                            hasZ = true;
                        }
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();

                if (!hasX || !hasY || !hasZ) {
                    throw new IOException("Custom selection entry is missing x, y, or z");
                }
                result.add(SpatialKey.of(x, y, z));
            }
            reader.endArray();
        }
        return result;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static Integer getOptionalInt(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
            ? object.get(key).getAsInt()
            : null;
    }

    private static boolean insideEllipsoid(
        BlockData block,
        int centerX, int centerY, int centerZ,
        int radiusX, int radiusY, int radiusZ
    ) {
        return insideEllipsoid(
            block.x, block.y, block.z,
            centerX, centerY, centerZ,
            radiusX, radiusY, radiusZ
        );
    }

    private static boolean insideEllipsoid(
        int x, int y, int z,
        int centerX, int centerY, int centerZ,
        int radiusX, int radiusY, int radiusZ
    ) {
        double dx = (x + 0.5 - centerX) / (double)radiusX;
        double dy = (y + 0.5 - centerY) / (double)radiusY;
        double dz = (z + 0.5 - centerZ) / (double)radiusZ;
        return dx * dx + dy * dy + dz * dz <= 1.0;
    }

    private static boolean insideEllipsoidPoint(
        double x, double y, double z,
        int centerX, int centerY, int centerZ,
        int radiusX, int radiusY, int radiusZ
    ) {
        double dx = (x - centerX) / radiusX;
        double dy = (y - centerY) / radiusY;
        double dz = (z - centerZ) / radiusZ;
        return dx * dx + dy * dy + dz * dz <= 1.0;
    }

    private static boolean getBoolOption(JsonObject request, String key, boolean fallback) {
        String value = getStringOption(request, key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static String getStringOption(JsonObject request, String key, String fallback) {
        if (!request.has("options") || !request.get("options").isJsonObject()) {
            return fallback;
        }
        JsonObject options = request.getAsJsonObject("options");
        if (!options.has(key) || options.get(key).isJsonNull()) {
            return fallback;
        }
        return options.get(key).getAsString();
    }

    private static List<File> getPathList(JsonObject request, String key) {
        List<File> result = new ArrayList<>();
        String raw = getStringOption(request, key, null);
        if (raw == null || raw.isBlank()) return result;

        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            File file = new File(trimmed);
            if (file.exists()) {
                result.add(file);
            } else {
                log("[WARN] Path not found, skipping: " + trimmed);
            }
        }
        return result;
    }

    private static void handleHeightmap(JsonObject request) {
        String worldPath = getString(request, "worldPath", "");
        int scale = getInt(request, "scale", 4);
        if (worldPath.isEmpty()) {
            error("worldPath required");
            return;
        }

        File regionDir = new File(worldPath, "region");
        if (!regionDir.exists()) {
            error("No region folder: " + worldPath);
            return;
        }

        try {
            log("Generating heightmap (scale=" + scale + ")...");
            String base64 = dev.kastrick.minesport.region.HeightmapGenerator
                .generateBase64Png(regionDir, scale);
            if (base64 == null) {
                error("No region files found");
                return;
            }

            File[] mcaFiles = regionDir.listFiles((directory, name) -> name.endsWith(".mca"));
            int minRegionX = Integer.MAX_VALUE;
            int minRegionZ = Integer.MAX_VALUE;
            int maxRegionX = Integer.MIN_VALUE;
            int maxRegionZ = Integer.MIN_VALUE;

            if (mcaFiles != null) {
                for (File file : mcaFiles) {
                    String[] parts = file.getName().split("\\.");
                    if (parts.length < 4) continue;
                    try {
                        int regionX = Integer.parseInt(parts[1]);
                        int regionZ = Integer.parseInt(parts[2]);
                        minRegionX = Math.min(minRegionX, regionX);
                        minRegionZ = Math.min(minRegionZ, regionZ);
                        maxRegionX = Math.max(maxRegionX, regionX);
                        maxRegionZ = Math.max(maxRegionZ, regionZ);
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (minRegionX == Integer.MAX_VALUE) {
                error("No valid region coordinates found");
                return;
            }

            final int minX = minRegionX * 512;
            final int minZ = minRegionZ * 512;
            final int maxX = (maxRegionX + 1) * 512;
            final int maxZ = (maxRegionZ + 1) * 512;
            final String imageData = base64;

            send("heightmap", json -> {
                json.addProperty("image", imageData);
                json.addProperty("minX", minX);
                json.addProperty("minZ", minZ);
                json.addProperty("maxX", maxX);
                json.addProperty("maxZ", maxZ);
                json.addProperty("scale", scale);
            });
        } catch (Exception exception) {
            error(failureDetails("Heightmap failed", exception));
        }
    }

    private static String failureDetails(String context, Throwable failure) {
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getName();
        return context + ": " + message + "\n" + stack;
    }

    static boolean listBlocksNeedsPreviewAssets(String purpose) {
        return purpose == null || !purpose.trim().equalsIgnoreCase("preflight");
    }

    static int writePreflightBlockIds(
        com.google.gson.stream.JsonWriter writer,
        Iterable<BlockData> blocks,
        Integer centerX, Integer centerY, Integer centerZ,
        Integer radiusX, Integer radiusY, Integer radiusZ
    ) throws IOException {
        int count = 0;
        for (BlockData block : blocks) {
            if (block == null || block.isAir()) continue;
            if (!blockMatchesOptionalEllipsoid(
                block,
                centerX, centerY, centerZ,
                radiusX, radiusY, radiusZ
            )) continue;
            writer.beginObject();
            writer.name("id").value(block.blockId);
            writer.endObject();
            count++;
        }
        return count;
    }

    private static boolean blockMatchesOptionalEllipsoid(
        BlockData block,
        Integer centerX, Integer centerY, Integer centerZ,
        Integer radiusX, Integer radiusY, Integer radiusZ
    ) {
        if (
            centerX == null || centerY == null || centerZ == null ||
            radiusX == null || radiusY == null || radiusZ == null
        ) return true;
        return insideEllipsoid(
            block,
            centerX, centerY, centerZ,
            Math.max(radiusX, 1),
            Math.max(radiusY, 1),
            Math.max(radiusZ, 1)
        );
    }

    private static void handleListBlocks(JsonObject request) {
        String worldPath = getString(request, "worldPath", "");
        int minX = getInt(request, "minX", -256);
        int minY = getInt(request, "minY", -64);
        int minZ = getInt(request, "minZ", -256);
        int maxX = getInt(request, "maxX", 256);
        int maxY = getInt(request, "maxY", 320);
        int maxZ = getInt(request, "maxZ", 256);

        if (worldPath.isEmpty()) {
            error("worldPath required");
            return;
        }

        File worldFolder = new File(worldPath);
        if (!worldFolder.exists() || !new File(worldFolder, "level.dat").exists()) {
            error("World not found or invalid: " + worldPath);
            return;
        }

        String purpose = getString(request, "clientPurpose", "preview");
        boolean includePreviewAssets = listBlocksNeedsPreviewAssets(purpose);

        File tempWorldCopy = null;
        try {
            log(includePreviewAssets
                ? "Preparing block list for 3D preview..."
                : "Preparing block list for preflight...");
            tempWorldCopy = WorldCopier.copyToTemp(
                worldFolder,
                minX, minZ,
                maxX, maxZ,
                IpcMode::log
            );
            File regionDir = new File(tempWorldCopy, "region");
            if (!regionDir.exists()) {
                error("No region folder found in world");
                return;
            }

            File[] regionFiles = regionDir.listFiles((directory, name) -> isRegionFileName(name));
            if (regionFiles == null || regionFiles.length == 0) {
                error("No region files (.mca/.mcr) found");
                return;
            }
            Arrays.sort(regionFiles, Comparator.comparing(File::getName));

            Integer centerX = getOptionalInt(request, "centerX");
            Integer centerY = getOptionalInt(request, "centerY");
            Integer centerZ = getOptionalInt(request, "centerZ");
            Integer radiusX = getOptionalInt(request, "radiusX");
            Integer radiusY = getOptionalInt(request, "radiusY");
            Integer radiusZ = getOptionalInt(request, "radiusZ");

            if (!includePreviewAssets) {
                File outFile = File.createTempFile("minesport_blocks_", ".json");
                outFile.deleteOnExit();
                int count = 0;
                try (var writer = new com.google.gson.stream.JsonWriter(
                    new BufferedWriter(new FileWriter(outFile))
                )) {
                    writer.beginArray();
                    for (File regionFile : regionFiles) {
                        count += writePreflightBlockIds(
                            writer,
                            RegionReader.readRegion(
                                regionFile,
                                minX, minY, minZ,
                                maxX, maxY, maxZ,
                                null
                            ),
                            centerX, centerY, centerZ,
                            radiusX, radiusY, radiusZ
                        );
                    }
                    writer.endArray();
                }
                final int preflightCount = count;
                log("Preflight block list: " + preflightCount + " solid block(s) · region-at-a-time");
                send("blocksReady", json -> {
                    json.addProperty("file", outFile.getAbsolutePath());
                    json.addProperty("count", preflightCount);
                });
                return;
            }

            var allBlocks = new ArrayList<BlockData>();
            for (File regionFile : regionFiles) {
                allBlocks.addAll(RegionReader.readRegion(
                    regionFile,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    null
                ));
            }

            if (
                centerX != null && centerY != null && centerZ != null &&
                radiusX != null && radiusY != null && radiusZ != null
            ) {
                int cx = centerX;
                int cy = centerY;
                int cz = centerZ;
                int rx = Math.max(radiusX, 1);
                int ry = Math.max(radiusY, 1);
                int rz = Math.max(radiusZ, 1);
                allBlocks.removeIf(block -> !insideEllipsoid(block, cx, cy, cz, rx, ry, rz));
            }

            allBlocks.removeIf(BlockData::isAir);
            log("Block list: " + allBlocks.size() + " solid block(s)");

            Map<String, PreviewTextures> previewTextures = Collections.emptyMap();
            if (includePreviewAssets) {
                try (ResolverChain previewResolvers = buildPreviewResolverChain(request, worldFolder, tempWorldCopy)) {
                    File previewTextureDir = Files.createTempDirectory("minesport_preview_textures_").toFile();
                    previewTextureDir.deleteOnExit();
                    previewTextures = writePreviewTextures(allBlocks, previewResolvers, previewTextureDir);
                }
                log("3D preview textures: " + previewTextures.size() + " block type(s) resolved");
            } else {
                log("Preflight block list: preview asset resolution skipped");
            }

            File outFile = File.createTempFile("minesport_blocks_", ".json");
            outFile.deleteOnExit();
            try (var writer = new com.google.gson.stream.JsonWriter(
                new BufferedWriter(new FileWriter(outFile))
            )) {
                writer.beginArray();
                for (BlockData block : allBlocks) {
                    writer.beginObject();
                    if (includePreviewAssets) {
                        writer.name("x").value(block.x);
                        writer.name("y").value(block.y);
                        writer.name("z").value(block.z);
                    }
                    writer.name("id").value(block.blockId);
                    if (includePreviewAssets) {
                        PreviewTextures blockTextures = previewTextures.get(blockTextureKey(block));
                        if (blockTextures != null) {
                            if (blockTextures.top() != null) writer.name("textureTop").value(blockTextures.top());
                            if (blockTextures.side() != null) writer.name("textureSide").value(blockTextures.side());
                            if (blockTextures.bottom() != null) writer.name("textureBottom").value(blockTextures.bottom());
                        }
                        int[] color = dev.kastrick.minesport.region.HeightmapGenerator
                            .colorForBlock(block.blockId);
                        writer.name("r").value(color[0]);
                        writer.name("g").value(color[1]);
                        writer.name("b").value(color[2]);
                    }
                    writer.endObject();
                }
                writer.endArray();
            }

            final int count = allBlocks.size();
            send("blocksReady", json -> {
                json.addProperty("file", outFile.getAbsolutePath());
                json.addProperty("count", count);
            });
        } catch (Exception exception) {
            error(failureDetails("List blocks failed", exception));
        } finally {
            if (tempWorldCopy != null) WorldCopier.cleanupTemp(tempWorldCopy);
        }
    }

    private static ResolverChain buildPreviewResolverChain(JsonObject request, File worldFolder, File copiedWorld) throws IOException {
        var chain = new ResolverChain();
        String mcVersion = readMcVersion(copiedWorld);
        File mcJar = VanillaResolver.findMinecraftJar(mcVersion);
        if (mcJar != null && mcJar.exists()) chain.addResolver(new VanillaResolver(mcJar));

        String requestedModsPath = getString(request, "modsPath", "").trim();
        String requestedLoader = getString(request, "modLoader", "").trim();
        File modsFolder = requestedModsPath.isEmpty() ? null : new File(requestedModsPath);
        if (modsFolder == null || !modsFolder.isDirectory()) {
            ModsLocator.LocatedMods located = ModsLocator.locate(worldFolder);
            modsFolder = located == null ? null : located.modsFolder();
        }
        addModResolvers(chain, modsFolder, requestedLoader);
        return chain;
    }

    private static void addModResolvers(ResolverChain chain, File modsFolder, String requestedLoader) throws IOException {
        if (modsFolder == null || !modsFolder.isDirectory()) return;

        String loader = requestedLoader == null
            ? ""
            : requestedLoader.trim().toLowerCase(Locale.ROOT).replace('_', '-');

        switch (loader) {
            case "fabric" -> {
                log("Resolver loader filter: Fabric");
                addFabricResolvers(chain, modsFolder);
            }
            case "quilt" -> {
                log("Resolver loader filter: Quilt");
                addQuiltResolver(chain, modsFolder);
            }
            case "forge" -> {
                log("Resolver loader filter: Forge");
                addForgeResolver(chain, modsFolder);
            }
            case "neoforge", "neo-forge", "neo forge" -> {
                log("Resolver loader filter: NeoForge");
                addForgeResolver(chain, modsFolder);
            }
            default -> {
                // Older callers and manually supplied worlds may not know the
                // loader. Preserve broad compatibility only for that unknown case.
                log("Resolver loader is unknown; probing Fabric, Quilt and Forge/NeoForge");
                addFabricResolvers(chain, modsFolder);
                addQuiltResolver(chain, modsFolder);
                addForgeResolver(chain, modsFolder);
            }
        }
    }

    private static void addFabricResolvers(ResolverChain chain, File modsFolder) throws IOException {
        FabricResolver fabric = FabricResolver.load(modsFolder, IpcMode::log);
        if (fabric.getNamespaces().isEmpty()) return;
        chain.addResolver(fabric);
        log("Fabric mod namespaces: " + fabric.getNamespaces());
        chain.addResolver(new PolymerResolver(fabric));
    }

    private static void addQuiltResolver(ResolverChain chain, File modsFolder) throws IOException {
        QuiltResolver quilt = QuiltResolver.load(modsFolder, IpcMode::log);
        if (quilt.getNamespaces().isEmpty()) return;
        chain.addResolver(quilt);
        log("Quilt mod namespaces: " + quilt.getNamespaces());
    }

    private static void addForgeResolver(ResolverChain chain, File modsFolder) throws IOException {
        ForgeResolver forge = ForgeResolver.load(modsFolder, IpcMode::log);
        if (forge.getNamespaces().isEmpty()) return;
        chain.addResolver(forge);
        log("Forge/NeoForge mod namespaces: " + forge.getNamespaces());
    }

    private record PreviewTexture(String path, int tintIndex) {}
    private record PreviewTextures(String top, String side, String bottom) {}

    private static Map<String, PreviewTextures> writePreviewTextures(List<BlockData> blocks, ResolverChain chain, File outputDir) {
        Map<String, PreviewTextures> paths = new HashMap<>();
        Map<MaterialKey, String> written = new HashMap<>();
        for (BlockData block : blocks) {
            String key = blockTextureKey(block);
            if (paths.containsKey(key)) continue;
            try {
                var state = chain.resolveBlockState(block.blockId);
                if (state == null) continue;
                var applications = state.resolve(block.properties, block.x, block.y, block.z);
                if (applications.isEmpty()) continue;
                var model = chain.resolveModel(applications.getFirst().modelPath);
                if (model == null) continue;
                PreviewTexture top = representativeTexture(model, "up");
                PreviewTexture side = representativeTexture(model, "north", "south", "east", "west");
                PreviewTexture bottom = representativeTexture(model, "down");
                if (top == null) top = side != null ? side : bottom;
                if (side == null) side = top != null ? top : bottom;
                if (bottom == null) bottom = side != null ? side : top;
                String topPath = writePreviewTexture(top, chain, outputDir, written);
                String sidePath = writePreviewTexture(side, chain, outputDir, written);
                String bottomPath = writePreviewTexture(bottom, chain, outputDir, written);
                if (topPath != null || sidePath != null || bottomPath != null) {
                    paths.put(key, new PreviewTextures(topPath, sidePath, bottomPath));
                }
            } catch (Exception exception) {
                log("[WARN] Preview texture failed for " + block.blockId + ": " + exception.getMessage());
            }
        }
        return paths;
    }

    private static PreviewTexture representativeTexture(dev.kastrick.minesport.model.BlockModel model, String... directions) {
        for (var element : model.elements) {
            for (String direction : directions) {
                var face = element.faces.get(direction);
                if (face == null) continue;
                String path = face.resolveTexture(model.textures);
                if (path != null && !path.startsWith("#")) return new PreviewTexture(path, face.tintindex);
            }
        }
        for (String key : List.of("all", "top", "side", "bottom", "particle")) {
            String value = model.textures.get(key);
            if (value != null && !value.startsWith("#")) return new PreviewTexture(value, -1);
        }
        return null;
    }

    private static String writePreviewTexture(PreviewTexture texture, ResolverChain chain, File outputDir, Map<MaterialKey, String> written) throws IOException {
        if (texture == null) return null;
        MaterialKey material = new MaterialKey(texture.path(), MaterialKey.tintFor(texture.path(), texture.tintIndex()));
        String existing = written.get(material);
        if (existing != null) return existing;
        var image = material.apply(chain.resolveTexture(texture.path()));
        if (image == null) return null;
        String name = Integer.toUnsignedString(material.hashCode(), 16) + ".png";
        File file = new File(outputDir, name);
        if (!ImageIO.write(image, "png", file)) return null;
        file.deleteOnExit();
        written.put(material, file.getAbsolutePath());
        return file.getAbsolutePath();
    }

    private static String blockTextureKey(BlockData block) {
        return block.blockId + "[" + BlockGrouper.stateKey(block.properties) + "]";
    }
}
