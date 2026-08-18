package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Reads Minecraft .mca region files and extracts blocks as BlockData.
 *
 * Region file format:
 *   - 4096 bytes: chunk location table (1024 entries × 4 bytes)
 *   - 4096 bytes: chunk timestamp table (unused by us)
 *   - Variable: chunk data sectors
 *
 * Each chunk location entry:
 *   - 3 bytes: sector offset from file start (in 4096-byte sectors)
 *   - 1 byte:  sector count
 *
 * Chunk data:
 *   - 4 bytes: data length
 *   - 1 byte:  compression type (1=GZIP, 2=Zlib, 3=none, 4=LZ4)
 *   - N bytes: compressed NBT
 */
public class RegionReader {

    private static final int SECTOR_SIZE = 4096;

    /**
     * Read all blocks from a region file within the given world coordinate bounds.
     * Pass Integer.MIN/MAX to read everything in the region.
     */
    /**
     * Read all blocks from a region file within the given world coordinate bounds.
     * regionX/regionZ are the region coords encoded in the filename (r.X.Z.mca).
     */
    public static List<BlockData> readRegion(
            File regionFile,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            ProgressCallback progress
    ) throws IOException {

        // Parse region coords from filename: r.X.Z.mca
        int regionX = 0, regionZ = 0;
        try {
            String name = regionFile.getName(); // e.g. r.-1.0.mca
            String[] parts = name.split("\\.");
            if (parts.length >= 4) {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}

        var blocks = new ArrayList<BlockData>();

        try (var raf = new RandomAccessFile(regionFile, "r")) {
            // File must be at least 8192 bytes (two sector header) to be valid
            if (raf.length() < SECTOR_SIZE * 2) return blocks;

            byte[] header = new byte[SECTOR_SIZE];
            raf.readFully(header);

            int chunksProcessed = 0;
            int totalChunks = 1024;

            for (int i = 0; i < totalChunks; i++) {
                int localCX = i % 32;
                int localCZ = i / 32;

                // World chunk coords = region offset + local
                int worldChunkX = regionX * 32 + localCX;
                int worldChunkZ = regionZ * 32 + localCZ;

                // Quick bounds pre-check at chunk level (each chunk = 16 blocks wide)
                if (worldChunkX * 16 + 15 < minX || worldChunkX * 16 > maxX) continue;
                if (worldChunkZ * 16 + 15 < minZ || worldChunkZ * 16 > maxZ) continue;

                int offset = ((header[i * 4]     & 0xFF) << 16)
                           | ((header[i * 4 + 1] & 0xFF) << 8)
                           |  (header[i * 4 + 2] & 0xFF);
                int sectorCount = header[i * 4 + 3] & 0xFF;

                if (offset == 0 || sectorCount == 0) continue; // empty slot

                // Check seek target is within file before attempting
                long seekPos = (long) offset * SECTOR_SIZE;
                if (seekPos + 5 > raf.length()) continue; // truncated file

                try {
                    raf.seek(seekPos);
                    int dataLength = raf.readInt();

                    // dataLength == 0: chunk slot allocated but never written — skip silently
                    if (dataLength == 0) continue;

                    int compressionType = raf.readByte() & 0xFF;

                    // External storage (type >= 128): chunk data stored outside region file
                    // Rare edge case, skip silently
                    if (compressionType >= 128) continue;

                    // dataLength == 1: header only, no payload — empty but valid, skip silently
                    if (dataLength == 1) continue;

                    int payloadLength = dataLength - 1;

                    // Sanity check: payload shouldn't exceed remaining file size
                    long currentPos = (long) offset * SECTOR_SIZE + 5;
                    long remaining  = raf.length() - currentPos;
                    if (payloadLength > remaining || payloadLength <= 0) continue;

                    byte[] compressed = new byte[payloadLength];
                    raf.readFully(compressed);

                    byte[] nbtBytes = decompress(compressed, compressionType);
                    // null means unknown compression type — skip silently
                    if (nbtBytes == null) continue;
                    // Empty decompressed result — skip silently
                    if (nbtBytes.length == 0) continue;

                    NbtCompound chunkNbt = NbtReader.readBytes(nbtBytes);
                    extractBlocks(chunkNbt, worldChunkX, worldChunkZ,
                            minX, minY, minZ, maxX, maxY, maxZ, blocks);

                } catch (java.io.EOFException e) {
                    // Truncated chunk — common in creative worlds where chunks are
                    // partially written or saved mid-game. Skip silently.
                } catch (Exception e) {
                    // Any other parse error — skip silently, don't crash the export
                    // Only log if it seems like real data (not just empty chunk noise)
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
            }
        }

        return blocks;
    }

    // ── Chunk NBT extraction ──────────────────────────────────────────────────

    private static void extractBlocks(
            NbtCompound chunk,
            int worldChunkX, int worldChunkZ,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            List<BlockData> out
    ) {
        NbtCompound data = chunk.has("sections") ? chunk
                         : chunk.has("Level") ? chunk.getCompound("Level")
                         : null;

        if (data == null) return;
        if (!data.has("sections")) return;

        int chunkWorldX = worldChunkX * 16;
        int chunkWorldZ = worldChunkZ * 16;

        List<Object> sections = data.getList("sections");

        for (Object sectionObj : sections) {
            if (!(sectionObj instanceof NbtCompound section)) continue;
            if (!section.has("block_states")) continue;

            int sectionY = section.getInt("Y", 0);
            int sectionWorldY = sectionY * 16;

            // Y bounds pre-check at section level
            if (sectionWorldY + 15 < minY || sectionWorldY > maxY) continue;

            NbtCompound blockStates = section.getCompound("block_states");
            if (!blockStates.has("palette")) continue;

            List<Object> palette = blockStates.getList("palette");

            // Empty palette — nothing to do
            if (palette.isEmpty()) continue;

            // Single-entry palette: entire section is one block type (very common
            // in empty worlds — all air, or all bedrock at y=-64)
            if (palette.size() == 1) {
                if (!(palette.get(0) instanceof NbtCompound entry)) continue;
                String blockId = entry.getString("Name", "minecraft:air");
                // All air/cave_air/void_air — skip the whole section fast
                if (blockId.equals("minecraft:air")
                        || blockId.equals("minecraft:cave_air")
                        || blockId.equals("minecraft:void_air")) continue;

                // All same non-air block — add all 4096 of them
                Map<String, String> props = new HashMap<>();
                if (entry.has("Properties")) {
                    NbtCompound p = entry.getCompound("Properties");
                    for (String key : p.keys()) props.put(key, p.getString(key));
                }
                for (int ly = 0; ly < 16; ly++) {
                    int wy = sectionWorldY + ly;
                    if (wy < minY || wy > maxY) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        int wz = chunkWorldZ + lz;
                        if (wz < minZ || wz > maxZ) continue;
                        for (int lx = 0; lx < 16; lx++) {
                            int wx = chunkWorldX + lx;
                            if (wx < minX || wx > maxX) continue;
                            out.add(new BlockData(wx, wy, wz, blockId, props));
                        }
                    }
                }
                continue;
            }

            // Multi-entry palette — need to read the data array
            long[] data64 = blockStates.has("data") ? blockStates.getLongArray("data") : new long[0];

            // If no data array despite multi-palette, treat as all-index-0
            // (shouldn't happen, but empty creative chunks can be weird)
            if (data64.length == 0) continue;

            // Parse palette entries
            String[] blockIds = new String[palette.size()];
            Map<String, String>[] blockProps = new Map[palette.size()];

            for (int pi = 0; pi < palette.size(); pi++) {
                if (!(palette.get(pi) instanceof NbtCompound entry)) continue;
                blockIds[pi] = entry.getString("Name", "minecraft:air");
                blockProps[pi] = new HashMap<>();
                if (entry.has("Properties")) {
                    NbtCompound props = entry.getCompound("Properties");
                    for (String key : props.keys()) {
                        blockProps[pi].put(key, props.getString(key));
                    }
                }
            }

            // Bits per entry — minimum 4, log2 of palette size
            int bitsPerBlock = Math.max(4, (int) Math.ceil(Math.log(palette.size()) / Math.log(2)));
            long mask = (1L << bitsPerBlock) - 1;
            int blocksPerLong = 64 / bitsPerBlock;

            // Extract each of the 4096 blocks in this section
            for (int blockIdx = 0; blockIdx < 4096; blockIdx++) {
                int paletteIdx;
                if (data64.length == 0) {
                    paletteIdx = 0; // single-value palette
                } else {
                    int longIdx = blockIdx / blocksPerLong;
                    int bitOffset = (blockIdx % blocksPerLong) * bitsPerBlock;
                    paletteIdx = (int) ((data64[longIdx] >> bitOffset) & mask);
                }

                if (paletteIdx >= blockIds.length) continue;
                String blockId = blockIds[paletteIdx];
                if (blockId == null || blockId.equals("minecraft:air")
                        || blockId.equals("minecraft:cave_air")
                        || blockId.equals("minecraft:void_air")) continue;

                // Local block coords within section
                int lx = blockIdx & 0xF;
                int ly = (blockIdx >> 8) & 0xF;
                int lz = (blockIdx >> 4) & 0xF;

                int wx = chunkWorldX + lx;
                int wy = sectionWorldY + ly;
                int wz = chunkWorldZ + lz;

                // Bounds check
                if (wx < minX || wx > maxX) continue;
                if (wy < minY || wy > maxY) continue;
                if (wz < minZ || wz > maxZ) continue;

                out.add(new BlockData(wx, wy, wz, blockId, blockProps[paletteIdx]));
            }
        }
    }

    // ── Decompression ─────────────────────────────────────────────────────────

    private static byte[] decompress(byte[] data, int type) {
        try {
            return switch (type) {
                case 1 -> { // GZIP
                    var baos = new ByteArrayOutputStream();
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                        gzip.transferTo(baos);
                    }
                    yield baos.toByteArray();
                }
                case 2 -> { // Zlib (most common)
                    var baos = new ByteArrayOutputStream();
                    try (var inf = new InflaterInputStream(new ByteArrayInputStream(data))) {
                        inf.transferTo(baos);
                    }
                    yield baos.toByteArray();
                }
                case 3 -> data; // Uncompressed
                default -> {
                    System.err.println("[WARN] Unknown compression type: " + type);
                    yield null;
                }
            };
        } catch (IOException e) {
            System.err.println("[WARN] Decompression failed: " + e.getMessage());
            return null;
        }
    }

    // ── Progress callback ─────────────────────────────────────────────────────

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total, String message);
    }
}
