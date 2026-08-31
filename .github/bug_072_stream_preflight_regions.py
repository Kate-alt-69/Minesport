from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


ipc_path = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
ipc = ipc_path.read_text(encoding="utf-8")

ipc = replace_once(
    ipc,
    '''    static boolean listBlocksNeedsPreviewAssets(String purpose) {
        return purpose == null || !purpose.trim().equalsIgnoreCase("preflight");
    }

    private static void handleListBlocks(JsonObject request) {''',
    '''    static boolean listBlocksNeedsPreviewAssets(String purpose) {
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

    private static void handleListBlocks(JsonObject request) {''',
    "streaming preflight helpers",
)

ipc = replace_once(
    ipc,
    '''            var allBlocks = new ArrayList<BlockData>();
            for (File mca : mcaFiles) {
                allBlocks.addAll(RegionReader.readRegion(
                    mca,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    null
                ));
            }

            Integer centerX = getOptionalInt(request, "centerX");
            Integer centerY = getOptionalInt(request, "centerY");
            Integer centerZ = getOptionalInt(request, "centerZ");
            Integer radiusX = getOptionalInt(request, "radiusX");
            Integer radiusY = getOptionalInt(request, "radiusY");
            Integer radiusZ = getOptionalInt(request, "radiusZ");''',
    '''            Integer centerX = getOptionalInt(request, "centerX");
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
                    for (File mca : mcaFiles) {
                        count += writePreflightBlockIds(
                            writer,
                            RegionReader.readRegion(
                                mca,
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
            for (File mca : mcaFiles) {
                allBlocks.addAll(RegionReader.readRegion(
                    mca,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    null
                ));
            }''',
    "region-at-a-time preflight path",
)

ipc_path.write_text(ipc, encoding="utf-8")


test_path = Path("engine/src/test/java/dev/kastrick/minesport/IpcModeBlockListPurposeTest.java")
test = test_path.read_text(encoding="utf-8")
test = replace_once(
    test,
    '''import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;''',
    '''import com.google.gson.stream.JsonWriter;
import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;''',
    "preflight test imports",
)

test = replace_once(
    test,
    '''    @Test
    void previewAndLegacyRequestsKeepPreviewAssets() {''',
    '''    @Test
    void preflightWriterStreamsIdOnlyAndPreservesSelectionFiltering() throws Exception {
        StringWriter output = new StringWriter();
        int count;
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.beginArray();
            count = IpcMode.writePreflightBlockIds(
                writer,
                List.of(
                    new BlockData(0, 64, 0, "minecraft:stone", Map.of()),
                    new BlockData(1, 64, 0, "minecraft:light", Map.of()),
                    new BlockData(20, 64, 0, "minecraft:dirt", Map.of())
                ),
                0, 64, 0,
                2, 2, 2
            );
            writer.endArray();
        }
        assertEquals(1, count);
        assertEquals("[{\\"id\\":\\"minecraft:stone\\"}]", output.toString());
    }

    @Test
    void previewAndLegacyRequestsKeepPreviewAssets() {''',
    "streaming preflight regression test",
)

test_path.write_text(test, encoding="utf-8")
print("BUG-072: preflight now releases each region block list before reading the next")
