from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} match(es), found {actual}")
    p.write_text(text.replace(old, new, count))


# ---------------------------------------------------------------------------
# WorldCopier: copy only region/entity files intersecting the selected X/Z box.
# ---------------------------------------------------------------------------
path = "engine/src/main/java/dev/kastrick/minesport/safety/WorldCopier.java"
replace(
    path,
    '''    public static File copyToTemp(File worldFolder, Consumer<String> statusCallback) throws IOException {
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        String tempName = "minesport_" + worldFolder.getName() + "_" + System.currentTimeMillis();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), tempName);
        if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
            throw new IOException("Could not create Minesport temp directory: " + tempDir);
        }

        if (statusCallback != null) {
            statusCallback.accept("Creating temp copy at: " + tempDir.getAbsolutePath());
        }

        try {
            copyLevelMetadata(worldFolder, tempDir, statusCallback);
            copyPreferredOverworldRegion(worldFolder, tempDir, statusCallback);
        } catch (IOException exception) {
            cleanupTemp(tempDir);
            throw exception;
        }

        if (statusCallback != null) {
            statusCallback.accept("World copy complete.");
        }
        return tempDir;
    }
''',
    '''    public static File copyToTemp(File worldFolder, Consumer<String> statusCallback) throws IOException {
        return copyToTemp(
            worldFolder,
            Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE,
            statusCallback
        );
    }

    /** Copy only the Overworld region files that can intersect selected X/Z bounds. */
    public static File copyToTemp(
        File worldFolder,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> statusCallback
    ) throws IOException {
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            throw new IOException("World folder does not exist: " + worldFolder);
        }

        String tempName = "minesport_" + worldFolder.getName() + "_" + System.currentTimeMillis();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), tempName);
        if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
            throw new IOException("Could not create Minesport temp directory: " + tempDir);
        }

        if (statusCallback != null) {
            statusCallback.accept("Preparing selected world data");
        }

        try {
            copyLevelMetadata(worldFolder, tempDir, statusCallback);
            copyPreferredOverworldRegion(
                worldFolder,
                tempDir,
                minX, minZ,
                maxX, maxZ,
                statusCallback
            );
        } catch (IOException exception) {
            cleanupTemp(tempDir);
            throw exception;
        }

        if (statusCallback != null) {
            statusCallback.accept("Selected world data ready");
        }
        return tempDir;
    }
''',
)

replace(
    path,
    '''    public static boolean copyOverworldEntitiesToTemp(
        File worldFolder,
        File tempDir,
        Consumer<String> log
    ) throws IOException {
        File source = null;
        for (String relativePath : OVERWORLD_ENTITY_PATHS) {
            File candidate = new File(worldFolder, relativePath);
            if (hasRegionFiles(candidate)) {
                source = candidate;
                break;
            }
        }
        if (source == null) return false;

        Path destination = tempDir.toPath().resolve("entities");
        Files.createDirectories(destination);
        File[] files = source.listFiles(file -> file.isFile() && isRegionFile(file.getName()));
        if (files == null || files.length == 0) return false;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "entities/" + file.getName()
            );
        }
        if (log != null) {
            log.accept("Using Overworld entity folder: " + source.getAbsolutePath());
        }
        return true;
    }
''',
    '''    public static boolean copyOverworldEntitiesToTemp(
        File worldFolder,
        File tempDir,
        Consumer<String> log
    ) throws IOException {
        return copyOverworldEntitiesToTemp(
            worldFolder,
            tempDir,
            Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE,
            log
        );
    }

    /** Copy only entity-region files that can intersect selected X/Z bounds. */
    public static boolean copyOverworldEntitiesToTemp(
        File worldFolder,
        File tempDir,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> log
    ) throws IOException {
        File source = null;
        for (String relativePath : OVERWORLD_ENTITY_PATHS) {
            File candidate = new File(worldFolder, relativePath);
            if (hasRegionFiles(candidate)) {
                source = candidate;
                break;
            }
        }
        if (source == null) return false;

        File[] files = selectedRegionFiles(source, minX, minZ, maxX, maxZ);
        if (files.length == 0) return false;

        Path destination = tempDir.toPath().resolve("entities");
        Files.createDirectories(destination);
        for (File file : files) {
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "entities/" + file.getName()
            );
        }
        if (log != null) {
            log.accept("Selected entity regions: " + files.length);
        }
        return true;
    }
''',
)

replace(
    path,
    '''    private static boolean isRegionFile(String name) {
        return name.endsWith(".mca") || name.endsWith(".mcr");
    }
''',
    '''    private static boolean isRegionFile(String name) {
        return name.endsWith(".mca") || name.endsWith(".mcr");
    }

    private static File[] selectedRegionFiles(
        File directory,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        File[] files = directory.listFiles(file ->
            file.isFile()
                && isRegionFile(file.getName())
                && regionIntersects(file.getName(), minX, minZ, maxX, maxZ)
        );
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static boolean regionIntersects(
        String name,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        String[] parts = name.split("\\\\.");
        if (parts.length < 4 || !"r".equals(parts[0])) return false;
        try {
            long regionX = Long.parseLong(parts[1]);
            long regionZ = Long.parseLong(parts[2]);
            long regionMinX = regionX * 512L;
            long regionMinZ = regionZ * 512L;
            long regionMaxX = regionMinX + 511L;
            long regionMaxZ = regionMinZ + 511L;
            long selectionMinX = Math.min((long) minX, (long) maxX);
            long selectionMaxX = Math.max((long) minX, (long) maxX);
            long selectionMinZ = Math.min((long) minZ, (long) maxZ);
            long selectionMaxZ = Math.max((long) minZ, (long) maxZ);
            return regionMaxX >= selectionMinX && regionMinX <= selectionMaxX
                && regionMaxZ >= selectionMinZ && regionMinZ <= selectionMaxZ;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
''',
)

replace(
    path,
    '''    private static void copyPreferredOverworldRegion(
        File worldFolder,
        File tempDir,
        Consumer<String> log
    ) throws IOException {
        File sourceRegion = findOverworldRegionDir(worldFolder);
        Path destination = tempDir.toPath().resolve("region");
        Files.createDirectories(destination);

        File[] files = sourceRegion.listFiles(file -> file.isFile() && isRegionFile(file.getName()));
        if (files == null || files.length == 0) {
            throw new IOException("Selected Overworld region directory became empty: " + sourceRegion);
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "region/" + file.getName()
            );
        }

        if (log != null) {
            log.accept("Using Overworld region folder: " + sourceRegion.getAbsolutePath());
        }
    }
''',
    '''    private static void copyPreferredOverworldRegion(
        File worldFolder,
        File tempDir,
        int minX, int minZ,
        int maxX, int maxZ,
        Consumer<String> log
    ) throws IOException {
        File sourceRegion = findOverworldRegionDir(worldFolder);
        File[] files = selectedRegionFiles(sourceRegion, minX, minZ, maxX, maxZ);
        if (files.length == 0) {
            throw new IOException(
                "No Overworld region files intersect selected X/Z bounds: "
                    + minX + ".." + maxX + ", " + minZ + ".." + maxZ
            );
        }

        Path destination = tempDir.toPath().resolve("region");
        Files.createDirectories(destination);
        for (File file : files) {
            copyFile(
                file.toPath(),
                destination.resolve(file.getName()),
                log,
                "region/" + file.getName()
            );
        }

        if (log != null) {
            log.accept("Selected block regions: " + files.length);
        }
    }
''',
)


# ---------------------------------------------------------------------------
# RegionReader: expose real selected/present chunk counts and entity progress.
# ---------------------------------------------------------------------------
path = "engine/src/main/java/dev/kastrick/minesport/region/RegionReader.java"
replace(
    path,
    '''    public static List<EntityData> readEntityRegion(
            File regionFile,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ
    ) throws IOException {
        return readRegionInternal(
            regionFile,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            null,
            false,
            false,
            true
        ).entities();
    }
''',
    '''    public static List<EntityData> readEntityRegion(
            File regionFile,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ
    ) throws IOException {
        return readEntityRegion(
            regionFile,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            null
        );
    }

    public static List<EntityData> readEntityRegion(
            File regionFile,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            ProgressCallback progress
    ) throws IOException {
        return readRegionInternal(
            regionFile,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            progress,
            false,
            false,
            true
        ).entities();
    }
''',
)

replace(
    path,
    '''        int regionX = 0, regionZ = 0;
        try {
            String name = regionFile.getName();
            String[] parts = name.split("\\\\.");
            if (parts.length >= 4) {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}
''',
    '''        int[] coordinates = regionCoordinates(regionFile);
        int regionX = coordinates[0];
        int regionZ = coordinates[1];
''',
)

replace(
    path,
    '''            int chunksProcessed = 0;
            int totalChunks = 1024;

            for (int i = 0; i < totalChunks; i++) {
''',
    '''            int chunksProcessed = 0;
            int totalChunks = countSelectedChunks(
                header,
                regionX, regionZ,
                minX, minZ,
                maxX, maxZ
            );

            for (int i = 0; i < 1024; i++) {
''',
)

replace(
    path,
    '''                try {
                    raf.seek(seekPos);
''',
    '''                try {
                    raf.seek(seekPos);
''',
)

replace(
    path,
    '''                } catch (EOFException e) {
                    // Truncated chunk — skip without aborting the export.
                } catch (Exception e) {
                    if (e.getMessage() != null && !e.getMessage().contains("TAG_End")) {
                        System.err.println("[WARN] Skipping chunk " + worldChunkX + "," + worldChunkZ
                            + " in " + regionFile.getName() + ": " + e.getMessage());
                    }
                }

                chunksProcessed++;
                if (progress != null) {
                    progress.onProgress(chunksProcessed, totalChunks,
                            "Reading chunk " + worldChunkX + "," + worldChunkZ);
                }
''',
    '''                } catch (EOFException e) {
                    // Truncated chunk — skip without aborting the export.
                } catch (Exception e) {
                    if (e.getMessage() != null && !e.getMessage().contains("TAG_End")) {
                        System.err.println("[WARN] Skipping chunk " + worldChunkX + "," + worldChunkZ
                            + " in " + regionFile.getName() + ": " + e.getMessage());
                    }
                } finally {
                    chunksProcessed++;
                    if (progress != null) {
                        progress.onProgress(
                            chunksProcessed,
                            totalChunks,
                            "Reading chunk " + worldChunkX + "," + worldChunkZ
                        );
                    }
                }
''',
)

replace(
    path,
    '''    private static byte[] decompress(byte[] data, int type) {
''',
    '''    public static int countSelectedChunks(
        File regionFile,
        int minX, int minZ,
        int maxX, int maxZ
    ) throws IOException {
        try (var raf = new RandomAccessFile(regionFile, "r")) {
            if (raf.length() < SECTOR_SIZE) return 0;
            byte[] header = new byte[SECTOR_SIZE];
            raf.readFully(header);
            int[] coordinates = regionCoordinates(regionFile);
            return countSelectedChunks(
                header,
                coordinates[0], coordinates[1],
                minX, minZ,
                maxX, maxZ
            );
        }
    }

    private static int countSelectedChunks(
        byte[] header,
        int regionX, int regionZ,
        int minX, int minZ,
        int maxX, int maxZ
    ) {
        int selectionMinX = Math.min(minX, maxX);
        int selectionMaxX = Math.max(minX, maxX);
        int selectionMinZ = Math.min(minZ, maxZ);
        int selectionMaxZ = Math.max(minZ, maxZ);
        int count = 0;
        for (int i = 0; i < 1024; i++) {
            int localCX = i % 32;
            int localCZ = i / 32;
            int worldChunkX = regionX * 32 + localCX;
            int worldChunkZ = regionZ * 32 + localCZ;
            if (worldChunkX * 16 + 15 < selectionMinX || worldChunkX * 16 > selectionMaxX) continue;
            if (worldChunkZ * 16 + 15 < selectionMinZ || worldChunkZ * 16 > selectionMaxZ) continue;
            int offset = ((header[i * 4] & 0xFF) << 16)
                | ((header[i * 4 + 1] & 0xFF) << 8)
                | (header[i * 4 + 2] & 0xFF);
            int sectorCount = header[i * 4 + 3] & 0xFF;
            if (offset != 0 && sectorCount != 0) count++;
        }
        return count;
    }

    private static int[] regionCoordinates(File regionFile) {
        try {
            String[] parts = regionFile.getName().split("\\\\.");
            if (parts.length >= 4) {
                return new int[] {
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
                };
            }
        } catch (NumberFormatException ignored) {}
        return new int[] {0, 0};
    }

    private static byte[] decompress(byte[] data, int type) {
''',
)


# ---------------------------------------------------------------------------
# IpcMode: selection-aware copy + live chunk/entity progress; remove fake 40/55.
# ---------------------------------------------------------------------------
path = "engine/src/main/java/dev/kastrick/minesport/IpcMode.java"
replace(
    path,
    '''        int maxX = getInt(request, "maxX", 256);
        int maxY = getInt(request, "maxY", 320);
        int maxZ = getInt(request, "maxZ", 256);
        String format = getString(request, "format", "gltf").toLowerCase();
''',
    '''        int maxX = getInt(request, "maxX", 256);
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
''',
)

replace(
    path,
    '''            log("Creating temp copy...");
            tempDir = WorldCopier.copyToTemp(worldFolder, IpcMode::log);
            boolean separateEntityRegions = format.equals("litematic")
                && WorldCopier.copyOverworldEntitiesToTemp(worldFolder, tempDir, IpcMode::log);
            progress(10, "World copy ready");
''',
    '''            progressIndeterminate("Preparing selected world data");
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
''',
)

replace(
    path,
    '''            log("Found " + mcaFiles.length + " region file(s)");
            var allBlocks = new ArrayList<BlockData>();
            var allBlockEntities = new ArrayList<BlockEntityData>();
            var allEntities = new ArrayList<EntityData>();
            for (int fileIndex = 0; fileIndex < mcaFiles.length; fileIndex++) {
                File mca = mcaFiles[fileIndex];
                log("Reading: " + mca.getName());
                if (format.equals("litematic")) {
                    RegionReader.RegionContents contents = RegionReader.readRegionContents(
                        mca,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        null
                    );
                    allBlocks.addAll(contents.blocks());
                    allBlockEntities.addAll(contents.blockEntities());
                    if (!separateEntityRegions) {
                        allEntities.addAll(contents.entities());
                    }
                } else {
                    allBlocks.addAll(RegionReader.readRegion(
                        mca,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        null
                    ));
                }
                int percent = 10 + (int)((fileIndex + 1.0) / mcaFiles.length * 30);
                progress(percent, "Read " + mca.getName());
            }
            if (format.equals("litematic") && separateEntityRegions) {
                File entityDir = new File(tempDir, "entities");
                File[] entityFiles = entityDir.listFiles((directory, name) -> name.endsWith(".mca") || name.endsWith(".mcr"));
                if (entityFiles != null) {
                    Arrays.sort(entityFiles, Comparator.comparing(File::getName));
                    for (File entityFile : entityFiles) {
                        allEntities.addAll(RegionReader.readEntityRegion(
                            entityFile,
                            minX, minY, minZ,
                            maxX, maxY, maxZ
                        ));
                    }
                }
            }
''',
    '''            Arrays.sort(mcaFiles, Comparator.comparing(File::getName));
            log("Found " + mcaFiles.length + " selected region file(s)");

            File[] entityFiles = new File[0];
            if (format.equals("litematic") && separateEntityRegions) {
                File entityDir = new File(tempDir, "entities");
                File[] listed = entityDir.listFiles(
                    (directory, name) -> name.endsWith(".mca") || name.endsWith(".mcr")
                );
                if (listed != null) {
                    Arrays.sort(listed, Comparator.comparing(File::getName));
                    entityFiles = listed;
                }
            }

            int[] blockChunkCounts = new int[mcaFiles.length];
            int[] entityChunkCounts = new int[entityFiles.length];
            int inputChunkTotal = 0;
            for (int i = 0; i < mcaFiles.length; i++) {
                blockChunkCounts[i] = RegionReader.countSelectedChunks(
                    mcaFiles[i], minX, minZ, maxX, maxZ
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
            int inputDoneBase = 0;

            for (int fileIndex = 0; fileIndex < mcaFiles.length; fileIndex++) {
                File mca = mcaFiles[fileIndex];
                final int progressBase = inputDoneBase;
                progressIndeterminate("Reading " + mca.getName());
                RegionReader.ProgressCallback chunkProgress = (doneCount, ignoredTotal, message) ->
                    reportChunkProgress(
                        progressBase + doneCount,
                        totalInputChunks,
                        message + " · " + mca.getName()
                    );

                if (format.equals("litematic")) {
                    RegionReader.RegionContents contents = RegionReader.readRegionContents(
                        mca,
                        minX, minY, minZ,
                        maxX, maxY, maxZ,
                        chunkProgress
                    );
                    allBlocks.addAll(contents.blocks());
                    allBlockEntities.addAll(contents.blockEntities());
                    if (!separateEntityRegions) {
                        allEntities.addAll(contents.entities());
                    }
                } else {
                    allBlocks.addAll(RegionReader.readRegion(
                        mca,
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
''',
)

replace(path, '            progress(40, "Region scan complete");', '            progressIndeterminate("Preparing export data");')
replace(path, '                progress(55, "Packing Litematica block states");', '                progressIndeterminate("Writing Litematica file");')

replace(
    path,
    '''    private static void progress(int percent, String message) {
        send("progress", json -> {
            json.addProperty("percent", percent);
            json.addProperty("message", message);
        });
    }
''',
    '''    private static void progress(int percent, String message) {
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
            progressIndeterminate(message + " · " + done + "/" + total + " chunks");
        }
    }
''',
)

replace(
    path,
    '''            tempWorldCopy = WorldCopier.copyToTemp(worldFolder, IpcMode::log);
''',
    '''            tempWorldCopy = WorldCopier.copyToTemp(
                worldFolder,
                minX, minZ,
                maxX, maxZ,
                IpcMode::log
            );
''',
)


# ---------------------------------------------------------------------------
# Loader: 9-dot 120-degree arc -> sweep -> stagger collapse -> beam -> creeper.
# ---------------------------------------------------------------------------
path = "desktop/ui/minesport-loader.slint"
replace(path, '            root.cycle = mod(root.cycle + 0.0089, 1.0);', '            root.cycle = mod(root.cycle + 0.0054, 1.0);')

replace(
    path,
    '''    function working-radius() -> length {
        let p = root.cycle;
        if (p < 0.54) {
            return 21px;
        }
        if (p < 0.70) {
            let t = (p - 0.54) / 0.16;
            return 21px * (1.0 - t);
        }
        let t = (p - 0.70) / 0.30;
        return 21px * t;
    }

    function center-mass-size() -> length {
        if (root.finishing && root.finish-step == 1) {
            return 30px;
        }
        if (!root.active || root.cycle < 0.70) {
            return 0px;
        }
        let t = (root.cycle - 0.70) / 0.30;
        return 30px * (1.0 - t);
    }

    function dot-x(index: int) -> length {
        if (root.finishing) {
            return 29px;
        }
        let angle = index * 40deg + root.cycle * 600deg;
        return 29px + cos(angle) * root.working-radius();
    }

    function dot-y(index: int) -> length {
        if (root.finishing) {
            return 29px;
        }
        let angle = index * 40deg + root.cycle * 600deg;
        return 29px + sin(angle) * root.working-radius();
    }
''',
    '''    function smooth(t: float) -> float {
        let c = max(0.0, min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    function spawn-progress(index: int) -> float {
        if (root.finishing || root.cycle >= 0.22) { return 1.0; }
        let start = index * 0.018;
        let finish = start + 0.08;
        return root.smooth((root.cycle - start) / (finish - start));
    }

    function collapse-progress(index: int) -> float {
        if (root.finishing) { return 1.0; }
        if (root.cycle < 0.60) { return 0.0; }
        if (root.cycle >= 0.76) { return 1.0; }
        let start = 0.60 + index * 0.010;
        let finish = start + 0.08;
        return root.smooth((root.cycle - start) / (finish - start));
    }

    function dot-radius(index: int) -> length {
        if (root.finishing) { return 0px; }
        return 21px * (1.0 - root.collapse-progress(index));
    }

    function dot-opacity(index: int) -> float {
        if (root.finishing) { return root.finish-step <= 1 ? 1.0 : 0.0; }
        if (root.cycle >= 0.78) { return 0.0; }
        return root.spawn-progress(index) * (1.0 - root.collapse-progress(index));
    }

    function dot-size(index: int) -> length {
        return 6px * (0.62 + 0.38 * root.spawn-progress(index));
    }

    function center-mass-size() -> length {
        if (root.finishing && root.finish-step == 1) { return 30px; }
        if (!root.active || root.cycle < 0.64 || root.cycle >= 0.83) { return 0px; }
        if (root.cycle < 0.76) {
            return 4px + 16px * root.smooth((root.cycle - 0.64) / 0.12);
        }
        return 20px * (1.0 - root.smooth((root.cycle - 0.76) / 0.07));
    }

    function beam-opacity() -> float {
        if (!root.active || root.cycle < 0.75 || root.cycle >= 0.83) { return 0.0; }
        if (root.cycle < 0.79) { return root.smooth((root.cycle - 0.75) / 0.04); }
        return 1.0 - root.smooth((root.cycle - 0.79) / 0.04);
    }

    function active-face-progress() -> float {
        if (!root.active || root.cycle < 0.82 || root.cycle >= 0.98) { return 0.0; }
        if (root.cycle < 0.87) { return root.smooth((root.cycle - 0.82) / 0.05); }
        if (root.cycle < 0.93) { return 1.0; }
        return 1.0 - root.smooth((root.cycle - 0.93) / 0.05);
    }

    function dot-x(index: int) -> length {
        if (root.finishing) { return 32px; }
        let base = -53.333deg + index * 13.333deg;
        let sweep = root.cycle < 0.22 ? 0.0 : min(1.0, (root.cycle - 0.22) / 0.38);
        let angle = base + sweep * 540deg;
        return 32px + cos(angle) * root.dot-radius(index);
    }

    function dot-y(index: int) -> length {
        if (root.finishing) { return 32px; }
        let base = -53.333deg + index * 13.333deg;
        let sweep = root.cycle < 0.22 ? 0.0 : min(1.0, (root.cycle - 0.22) / 0.38);
        let angle = base + sweep * 540deg;
        return 32px + sin(angle) * root.dot-radius(index);
    }
''',
)

replace(
    path,
    '''    function pixel-x(index: int) -> length {
        if (root.finish-step <= 1) { return 30px; }
        return 6px + root.pixel-column(index) * 6px;
    }

    function pixel-y(index: int) -> length {
        if (root.finish-step <= 1) { return 30px; }
        return 6px + root.pixel-row(index) * 6px;
    }
''',
    '''    function pixel-x(index: int) -> length {
        if (root.finishing) {
            if (root.finish-step <= 1) { return 30px; }
            return 6px + root.pixel-column(index) * 6px;
        }
        let target = 6px + root.pixel-column(index) * 6px;
        return 30px + (target - 30px) * root.active-face-progress();
    }

    function pixel-y(index: int) -> length {
        if (root.finishing) {
            if (root.finish-step <= 1) { return 30px; }
            return 6px + root.pixel-row(index) * 6px;
        }
        let target = 6px + root.pixel-row(index) * 6px;
        return 30px + (target - 30px) * root.active-face-progress();
    }
''',
)

replace(
    path,
    '''            center-mass := Rectangle {
''',
    '''            horizontal-beam := Rectangle {
                width: 44px;
                height: 2px;
                x: 10px;
                y: 31px;
                border-radius: 1px;
                background: #f2f4f3;
                opacity: root.beam-opacity();
            }

            vertical-beam := Rectangle {
                width: 2px;
                height: 44px;
                x: 31px;
                y: 10px;
                border-radius: 1px;
                background: #f2f4f3;
                opacity: root.beam-opacity() * 0.72;
            }

            center-mass := Rectangle {
''',
)

replace(
    path,
    '''            for dot[index] in 9: Rectangle {
                x: root.dot-x(index);
                y: root.dot-y(index);
                width: 6px;
                height: 6px;
                border-radius: 3px;
                background: #f2f4f3;
                visible: !root.finishing || root.finish-step <= 1;
''',
    '''            for dot[index] in 9: Rectangle {
                width: root.dot-size(index);
                height: self.width;
                x: root.dot-x(index) - self.width / 2;
                y: root.dot-y(index) - self.height / 2;
                border-radius: self.width / 2;
                background: #f2f4f3;
                opacity: root.dot-opacity(index);
                visible: self.opacity > 0.001;
''',
)

replace(
    path,
    '''                visible: root.finishing && root.finish-step >= 2 && root.finish-step < 5;
''',
    '''                visible: (root.finishing && root.finish-step >= 2 && root.finish-step < 5)
                    || (root.active && root.active-face-progress() > 0.001);
                opacity: root.finishing ? 1.0 : root.active-face-progress();
''',
)

# Unknown export phases use progress=0; hide the numeric bar instead of lying.
replace(path, '            if root.determinate: HorizontalLayout {', '            if root.determinate && root.progress > 0.02: HorizontalLayout {')

print("Export progress/performance/loader patch applied.")
