package dev.kastrick.minesport.export;

import dev.kastrick.minesport.nbt.NbtWriter;
import dev.kastrick.minesport.region.BlockData;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Writes a single-region Litematica schematic directly from decoded world blocks.
 *
 * This deliberately bypasses GeometryBuilder and every texture/model resolver:
 * a litematic stores Minecraft block states, not rendered geometry.
 */
public final class LitematicExporter {
    private static final int LITEMATIC_VERSION = 6;
    private static final int LITEMATIC_SUBVERSION = 1;
    private static final int LEGACY_DATA_VERSION_FALLBACK = 2975; // 1.18.2
    private static final String AIR = "minecraft:air";

    private LitematicExporter() {}

    public record ExportStats(int blockCount, int paletteSize, int volume) {}

    private record StateKey(String blockId, SortedMap<String, String> properties) {
        static StateKey of(BlockData block) {
            return new StateKey(
                block.blockId,
                Collections.unmodifiableSortedMap(new TreeMap<>(block.properties))
            );
        }

        static StateKey air() {
            return new StateKey(AIR, Collections.unmodifiableSortedMap(new TreeMap<>()));
        }
    }

    public static ExportStats export(
        List<BlockData> blocks,
        int firstX, int firstY, int firstZ,
        int secondX, int secondY, int secondZ,
        String name,
        String author,
        String description,
        int minecraftDataVersion,
        File output
    ) throws IOException {
        int minX = Math.min(firstX, secondX);
        int minY = Math.min(firstY, secondY);
        int minZ = Math.min(firstZ, secondZ);
        int maxX = Math.max(firstX, secondX);
        int maxY = Math.max(firstY, secondY);
        int maxZ = Math.max(firstZ, secondZ);

        long widthLong = (long) maxX - minX + 1L;
        long heightLong = (long) maxY - minY + 1L;
        long lengthLong = (long) maxZ - minZ + 1L;
        long volumeLong = Math.multiplyExact(Math.multiplyExact(widthLong, heightLong), lengthLong);
        if (widthLong > Integer.MAX_VALUE || heightLong > Integer.MAX_VALUE || lengthLong > Integer.MAX_VALUE) {
            throw new IOException("Litematica selection axis exceeds supported size");
        }
        if (volumeLong <= 0 || volumeLong > Integer.MAX_VALUE) {
            throw new IOException("Litematica selection volume exceeds " + Integer.MAX_VALUE + " blocks");
        }

        int width = (int) widthLong;
        int height = (int) heightLong;
        int length = (int) lengthLong;
        int volume = (int) volumeLong;

        LinkedHashMap<StateKey, Integer> palette = new LinkedHashMap<>();
        palette.put(StateKey.air(), 0);
        for (BlockData block : blocks) {
            if (!inside(block, minX, minY, minZ, maxX, maxY, maxZ)) continue;
            if (isTrueAir(block.blockId)) continue;
            palette.computeIfAbsent(StateKey.of(block), ignored -> palette.size());
        }

        int bitsPerBlock = Math.max(2, ceilLog2(palette.size()));
        long packedLength = ((long) volume * bitsPerBlock + 63L) / 64L;
        if (packedLength > Integer.MAX_VALUE) {
            throw new IOException("Litematica block-state array is too large");
        }
        long[] packed = new long[(int) packedLength];

        int nonAir = 0;
        for (BlockData block : blocks) {
            if (!inside(block, minX, minY, minZ, maxX, maxY, maxZ)) continue;
            if (isTrueAir(block.blockId)) continue;
            Integer paletteIndex = palette.get(StateKey.of(block));
            if (paletteIndex == null) continue;

            int x = block.x - minX;
            int y = block.y - minY;
            int z = block.z - minZ;
            long linearIndex = (long) y * width * length + (long) z * width + x;
            setPacked(packed, linearIndex, paletteIndex, bitsPerBlock);
            nonAir++;
        }

        List<Object> paletteTag = new ArrayList<>(palette.size());
        for (StateKey state : palette.keySet()) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", state.blockId());
            if (!state.properties().isEmpty()) {
                LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
                for (Map.Entry<String, String> property : state.properties().entrySet()) {
                    properties.put(property.getKey(), property.getValue());
                }
                entry.put("Properties", properties);
            }
            paletteTag.add(entry);
        }

        LinkedHashMap<String, Object> region = new LinkedHashMap<>();
        region.put("Position", xyz(0, 0, 0));
        region.put("Size", xyz(width, height, length));
        region.put("BlockStatePalette", paletteTag);
        region.put("BlockStates", packed);
        region.put("TileEntities", List.of());
        region.put("Entities", List.of());
        region.put("PendingBlockTicks", List.of());
        region.put("PendingFluidTicks", List.of());

        int dataVersion = minecraftDataVersion > 0
            ? minecraftDataVersion
            : LEGACY_DATA_VERSION_FALLBACK;
        region.put("DataVersion", dataVersion);

        String displayName = cleanName(name);
        long now = System.currentTimeMillis();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Name", displayName);
        metadata.put("Author", author == null ? "" : author);
        metadata.put("Description", description == null ? "" : description);
        metadata.put("Software", "Minesport 0.2.0");
        metadata.put("RegionCount", 1);
        metadata.put("TimeCreated", now);
        metadata.put("TimeModified", now);
        metadata.put("TotalBlocks", nonAir);
        metadata.put("TotalVolume", volume);
        metadata.put("EnclosingSize", xyz(width, height, length));

        LinkedHashMap<String, Object> regions = new LinkedHashMap<>();
        regions.put(displayName, region);

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("Version", LITEMATIC_VERSION);
        root.put("SubVersion", LITEMATIC_SUBVERSION);
        root.put("MinecraftDataVersion", dataVersion);
        root.put("Metadata", metadata);
        root.put("Regions", regions);

        NbtWriter.writeGzip(output, root);
        return new ExportStats(nonAir, palette.size(), volume);
    }

    private static LinkedHashMap<String, Object> xyz(int x, int y, int z) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("x", x);
        value.put("y", y);
        value.put("z", z);
        return value;
    }

    private static boolean inside(
        BlockData block,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
    ) {
        return block.x >= minX && block.x <= maxX
            && block.y >= minY && block.y <= maxY
            && block.z >= minZ && block.z <= maxZ;
    }

    private static boolean isTrueAir(String blockId) {
        return AIR.equals(blockId)
            || "minecraft:cave_air".equals(blockId)
            || "minecraft:void_air".equals(blockId);
    }

    private static int ceilLog2(int value) {
        if (value <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    /** Dense Litematica packing: entries may cross a 64-bit word boundary. */
    private static void setPacked(long[] words, long index, int value, int bits) {
        long bitIndex = index * bits;
        int wordIndex = (int) (bitIndex >>> 6);
        int bitOffset = (int) (bitIndex & 63L);
        long mask = (1L << bits) - 1L;
        long encoded = value & mask;

        words[wordIndex] |= encoded << bitOffset;
        if (bitOffset + bits > 64) {
            words[wordIndex + 1] |= encoded >>> (64 - bitOffset);
        }
    }

    private static String cleanName(String value) {
        if (value == null || value.isBlank()) return "Minesport Export";
        return value.strip();
    }
}
