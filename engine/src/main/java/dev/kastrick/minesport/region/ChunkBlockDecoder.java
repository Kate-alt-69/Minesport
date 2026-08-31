package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Version-aware Minecraft chunk block decoder.
 *
 * It detects the save schema from the chunk NBT itself instead of trusting a
 * launcher label. That makes the engine resilient to copied/converted worlds
 * and gives Minesport a native fallback when a runtime bridge cannot be built.
 *
 * Supported storage eras:
 *   - 1.18+      sections[].block_states.palette/data
 *   - 1.13-1.17 Level.Sections[].Palette/BlockStates
 *   - 1.2-1.12  Level.Sections[].Blocks/Data/Add (numeric Anvil IDs)
 */
public final class ChunkBlockDecoder {
    private ChunkBlockDecoder() {}

    public enum Format {
        MODERN_PALETTE,
        FLATTENED_PALETTE,
        LEGACY_NUMERIC_ANVIL,
        UNKNOWN
    }

    public static Format detect(NbtCompound chunk) {
        if (chunk == null) return Format.UNKNOWN;
        if (chunk.has("sections")) return Format.MODERN_PALETTE;

        NbtCompound data = chunk;
        if (chunk.has("Level")) {
            try {
                data = chunk.getCompound("Level");
            } catch (Exception ignored) {
                return Format.UNKNOWN;
            }
        }
        if (!data.has("Sections")) return Format.UNKNOWN;

        try {
            for (Object sectionObject : data.getList("Sections")) {
                if (!(sectionObject instanceof NbtCompound section)) continue;
                if (section.has("Blocks")) return Format.LEGACY_NUMERIC_ANVIL;
                if (section.has("Palette") || section.has("BlockStates")) {
                    return Format.FLATTENED_PALETTE;
                }
            }
        } catch (Exception ignored) {}
        return Format.UNKNOWN;
    }

    public static List<BlockData> decode(
        NbtCompound chunk,
        int worldChunkX,
        int worldChunkZ,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
    ) {
        var result = new ArrayList<BlockData>();
        decodeInto(
            chunk, worldChunkX, worldChunkZ,
            minX, minY, minZ,
            maxX, maxY, maxZ,
            result
        );
        return result;
    }

    public static void decodeInto(
        NbtCompound chunk,
        int worldChunkX,
        int worldChunkZ,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<BlockData> out
    ) {
        if (chunk == null || out == null) return;

        Format format = detect(chunk);
        switch (format) {
            case MODERN_PALETTE -> decodeModern(
                chunk, worldChunkX, worldChunkZ,
                minX, minY, minZ, maxX, maxY, maxZ, out
            );
            case FLATTENED_PALETTE -> decodeFlattened(
                chunk, worldChunkX, worldChunkZ,
                minX, minY, minZ, maxX, maxY, maxZ, out
            );
            case LEGACY_NUMERIC_ANVIL -> decodeLegacy(
                chunk, worldChunkX, worldChunkZ,
                minX, minY, minZ, maxX, maxY, maxZ, out
            );
            case UNKNOWN -> {
                // Unknown chunk layouts are ignored rather than guessed. This
                // keeps corrupt or future chunks from poisoning an export.
            }
        }
    }

    private static void decodeModern(
        NbtCompound chunk,
        int worldChunkX,
        int worldChunkZ,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<BlockData> out
    ) {
        if (!chunk.has("sections")) return;
        for (Object sectionObject : chunk.getList("sections")) {
            if (!(sectionObject instanceof NbtCompound section)) continue;
            if (!section.has("block_states")) continue;
            NbtCompound blockStates;
            try {
                blockStates = section.getCompound("block_states");
            } catch (Exception ignored) {
                continue;
            }
            if (!blockStates.has("palette")) continue;
            List<Object> palette = blockStates.getList("palette");
            long[] packed = blockStates.has("data") ? blockStates.getLongArray("data") : new long[0];
            decodePaletteSection(
                section.getInt("Y", 0), palette, packed,
                worldChunkX, worldChunkZ,
                minX, minY, minZ, maxX, maxY, maxZ, out
            );
        }
    }

    private static void decodeFlattened(
        NbtCompound chunk,
        int worldChunkX,
        int worldChunkZ,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<BlockData> out
    ) {
        NbtCompound level = chunk.has("Level") ? chunk.getCompound("Level") : chunk;
        if (!level.has("Sections")) return;

        for (Object sectionObject : level.getList("Sections")) {
            if (!(sectionObject instanceof NbtCompound section)) continue;
            if (!section.has("Palette")) continue;
            List<Object> palette = section.getList("Palette");
            long[] packed = section.has("BlockStates") ? section.getLongArray("BlockStates") : new long[0];
            decodePaletteSection(
                section.getInt("Y", 0), palette, packed,
                worldChunkX, worldChunkZ,
                minX, minY, minZ, maxX, maxY, maxZ, out
            );
        }
    }

    private static void decodePaletteSection(
        int sectionY,
        List<Object> palette,
        long[] packed,
        int worldChunkX,
        int worldChunkZ,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<BlockData> out
    ) {
        if (palette == null || palette.isEmpty()) return;

        int sectionWorldY = sectionY * 16;
        if (sectionWorldY + 15 < minY || sectionWorldY > maxY) return;

        String[] ids = new String[palette.size()];
        @SuppressWarnings("unchecked")
        Map<String, String>[] properties = new Map[palette.size()];
        for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
            if (!(palette.get(paletteIndex) instanceof NbtCompound entry)) continue;
            ids[paletteIndex] = entry.getString("Name", "minecraft:air");
            Map<String, String> stateProperties = new HashMap<>();
            if (entry.has("Properties")) {
                try {
                    NbtCompound props = entry.getCompound("Properties");
                    for (String key : props.keys()) {
                        stateProperties.put(key, props.getString(key));
                    }
                } catch (Exception ignored) {}
            }
            properties[paletteIndex] = stateProperties;
        }

        int bitsPerBlock = Math.max(4, ceilLog2(palette.size()));
        for (int blockIndex = 0; blockIndex < 4096; blockIndex++) {
            int paletteIndex = palette.size() == 1
                ? 0
                : readPaletteIndex(packed, blockIndex, bitsPerBlock);
            if (paletteIndex < 0 || paletteIndex >= ids.length) continue;

            String blockId = ids[paletteIndex];
            if (blockId == null || isAir(blockId)) continue;

            int localX = blockIndex & 0xF;
            int localY = (blockIndex >> 8) & 0xF;
            int localZ = (blockIndex >> 4) & 0xF;
            int worldX = worldChunkX * 16 + localX;
            int worldY = sectionWorldY + localY;
            int worldZ = worldChunkZ * 16 + localZ;

            if (!inside(worldX, worldY, worldZ, minX, minY, minZ, maxX, maxY, maxZ)) continue;
            Map<String, String> stateProperties = properties[paletteIndex];
            out.add(new BlockData(worldX, worldY, worldZ, blockId, stateProperties));
        }
    }

    private static void decodeLegacy(
        NbtCompound chunk,
        int worldChunkX,
        int worldChunkZ,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<BlockData> out
    ) {
        NbtCompound level = chunk.has("Level") ? chunk.getCompound("Level") : chunk;
        if (!level.has("Sections")) return;
        int legacyStart = out.size();

        for (Object sectionObject : level.getList("Sections")) {
            if (!(sectionObject instanceof NbtCompound section)) continue;
            if (!section.has("Blocks")) continue;

            byte[] blocks;
            try {
                blocks = section.getByteArray("Blocks");
            } catch (Exception ignored) {
                continue;
            }
            if (blocks.length < 4096) continue;

            byte[] metadata = section.has("Data") ? safeByteArray(section, "Data") : new byte[0];
            byte[] add = section.has("Add") ? safeByteArray(section, "Add") : new byte[0];
            int sectionY = section.getInt("Y", 0);
            int sectionWorldY = sectionY * 16;
            if (sectionWorldY + 15 < minY || sectionWorldY > maxY) continue;

            for (int blockIndex = 0; blockIndex < 4096; blockIndex++) {
                int numericId = blocks[blockIndex] & 0xFF;
                if (add.length > 0) numericId |= nibble(add, blockIndex) << 8;
                if (numericId == 0) continue;

                int localX = blockIndex & 0xF;
                int localY = (blockIndex >> 8) & 0xF;
                int localZ = (blockIndex >> 4) & 0xF;
                int worldX = worldChunkX * 16 + localX;
                int worldY = sectionWorldY + localY;
                int worldZ = worldChunkZ * 16 + localZ;
                if (!inside(worldX, worldY, worldZ, minX, minY, minZ, maxX, maxY, maxZ)) continue;

                int legacyData = metadata.length > 0 ? nibble(metadata, blockIndex) : 0;
                LegacyBlockIds.DecodedBlock decoded = LegacyBlockIds.decode(numericId, legacyData);
                if (isAir(decoded.blockId())) continue;
                out.add(new BlockData(
                    worldX, worldY, worldZ,
                    decoded.blockId(), decoded.properties()
                ));
            }
        }

        // Pre-flattening neighbour-derived state (currently two-block doors)
        // must be resolved after every section in this chunk has been decoded.
        // Door halves share X/Z, so they cannot cross a chunk boundary.
        LegacyStateResolver.resolve(out.subList(legacyStart, out.size()));
    }

    private static byte[] safeByteArray(NbtCompound compound, String key) {
        try {
            return compound.getByteArray(key);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private static int nibble(byte[] values, int index) {
        int byteIndex = index >> 1;
        if (byteIndex < 0 || byteIndex >= values.length) return 0;
        int value = values[byteIndex] & 0xFF;
        return (index & 1) == 0 ? value & 0xF : (value >>> 4) & 0xF;
    }

    /**
     * Palette packing changed during the flattened era. Older chunks packed
     * entries continuously across long boundaries; newer chunks pad each long
     * so an entry never crosses a boundary. The stored array length reliably
     * distinguishes the two representations for non-power-of-two bit widths.
     */
    private static int readPaletteIndex(long[] packed, int index, int bits) {
        if (packed == null || packed.length == 0 || bits <= 0 || bits >= 64) return 0;
        long mask = (1L << bits) - 1L;
        int valuesPerLong = 64 / bits;
        if (valuesPerLong <= 0) return 0;

        int paddedLength = (4096 + valuesPerLong - 1) / valuesPerLong;
        int compactLength = (int)(((long)4096 * bits + 63L) / 64L);
        boolean compact = compactLength != paddedLength
            && Math.abs(packed.length - compactLength) < Math.abs(packed.length - paddedLength);

        if (!compact) {
            int longIndex = index / valuesPerLong;
            if (longIndex < 0 || longIndex >= packed.length) return 0;
            int bitOffset = (index % valuesPerLong) * bits;
            return (int)((packed[longIndex] >>> bitOffset) & mask);
        }

        long bitIndex = (long) index * bits;
        int longIndex = (int)(bitIndex >>> 6);
        int bitOffset = (int)(bitIndex & 63L);
        if (longIndex < 0 || longIndex >= packed.length) return 0;

        long value = packed[longIndex] >>> bitOffset;
        int spill = bitOffset + bits - 64;
        if (spill > 0 && longIndex + 1 < packed.length) {
            value |= packed[longIndex + 1] << (64 - bitOffset);
        }
        return (int)(value & mask);
    }

    private static int ceilLog2(int value) {
        if (value <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private static boolean inside(
        int x, int y, int z,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
    ) {
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    private static boolean isAir(String blockId) {
        return "minecraft:air".equals(blockId)
            || "minecraft:cave_air".equals(blockId)
            || "minecraft:void_air".equals(blockId);
    }
}
