from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


ipc = "engine/src/main/java/dev/kastrick/minesport/IpcMode.java"

replace_once(
    ipc,
    '''            File[] mcaFiles = regionDir.listFiles((directory, name) -> name.endsWith(".mca"));
            if (mcaFiles == null || mcaFiles.length == 0) {
                error("No .mca region files found");
                return;
            }

            Arrays.sort(mcaFiles, Comparator.comparing(File::getName));
            log("Found " + mcaFiles.length + " selected region file(s)");''',
    '''            File[] regionFiles = regionDir.listFiles((directory, name) -> isRegionFileName(name));
            if (regionFiles == null || regionFiles.length == 0) {
                error("No region files (.mca/.mcr) found");
                return;
            }

            Arrays.sort(regionFiles, Comparator.comparing(File::getName));
            log("Found " + regionFiles.length + " selected region file(s)");''',
    "block region enumeration",
)

replace_once(
    ipc,
    '''                File[] listed = entityDir.listFiles(
                    (directory, name) -> name.endsWith(".mca") || name.endsWith(".mcr")
                );''',
    '''                File[] listed = entityDir.listFiles(
                    (directory, name) -> isRegionFileName(name)
                );''',
    "entity region enumeration",
)

replace_once(
    ipc,
    '''            int[] blockChunkCounts = new int[mcaFiles.length];
            int[] entityChunkCounts = new int[entityFiles.length];
            int inputChunkTotal = 0;
            for (int i = 0; i < mcaFiles.length; i++) {
                blockChunkCounts[i] = RegionReader.countSelectedChunks(
                    mcaFiles[i], minX, minZ, maxX, maxZ
                );''',
    '''            int[] blockChunkCounts = new int[regionFiles.length];
            int[] entityChunkCounts = new int[entityFiles.length];
            int inputChunkTotal = 0;
            for (int i = 0; i < regionFiles.length; i++) {
                blockChunkCounts[i] = RegionReader.countSelectedChunks(
                    regionFiles[i], minX, minZ, maxX, maxZ
                );''',
    "block region chunk counts",
)

replace_once(
    ipc,
    '''            for (int fileIndex = 0; fileIndex < mcaFiles.length; fileIndex++) {
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
                        mca,''',
    '''            for (int fileIndex = 0; fileIndex < regionFiles.length; fileIndex++) {
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
                        regionFile,''',
    "block region read loop",
)

replace_once(
    ipc,
    '''                    allBlocks.addAll(RegionReader.readRegion(
                        mca,
                        minX, minY, minZ,''',
    '''                    allBlocks.addAll(RegionReader.readRegion(
                        regionFile,
                        minX, minY, minZ,''',
    "ordinary region read",
)

replace_once(
    ipc,
    '''    private static void commitStagedOutput(File staged, File output) throws IOException {''',
    '''    /**
     * Minecraft has used both McRegion (.mcr) and Anvil (.mca) containers.
     * Keep the extension check in one place so export/entity enumeration cannot
     * silently diverge again. RegionReader handles the actual format details.
     */
    static boolean isRegionFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mca") || lower.endsWith(".mcr");
    }

    private static void commitStagedOutput(File staged, File output) throws IOException {''',
    "shared region extension helper",
)

test = Path("engine/src/test/java/dev/kastrick/minesport/IpcModeRegionFileTest.java")
if test.exists():
    raise SystemExit("IpcModeRegionFileTest.java already exists")
test.write_text('''package dev.kastrick.minesport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcModeRegionFileTest {
    @Test
    void acceptsAnvilAndLegacyMcRegionContainers() {
        assertTrue(IpcMode.isRegionFileName("r.0.0.mca"));
        assertTrue(IpcMode.isRegionFileName("r.-2.7.mcr"));
        assertTrue(IpcMode.isRegionFileName("R.1.1.MCR"));
    }

    @Test
    void rejectsUnrelatedFiles() {
        assertFalse(IpcMode.isRegionFileName("level.dat"));
        assertFalse(IpcMode.isRegionFileName("r.0.0.mca.part"));
        assertFalse(IpcMode.isRegionFileName(null));
    }
}
''', encoding="utf-8")

print("Applied legacy McRegion export enumeration fix")
