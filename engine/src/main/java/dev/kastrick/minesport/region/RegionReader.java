package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Reads Minecraft .mca region files and extracts blocks as BlockData.
 */
public class RegionReader {

    private static final int SECTOR_SIZE = 4096;

    public static List<BlockData> readRegion(
            File regionFile,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            ProgressCallback progress
    ) throws IOException {

        int regionX = 0, regionZ = 0;
        try {
            String name = regionFile.getName();
            String[] parts = name.split("\\.");
            if (parts.length >= 4) {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}

        var blocks = new ArrayList<BlockData>();

        try (var raf = new RandomAccessFile(regionFile, "r")) {
            if (raf.length() < SECTOR_SIZE * 2L) return blocks;

            byte[] header = new byte[SECTOR_SIZE];
            raf.readFully(header);

            int chunksProcessed = 0;
            int totalChunks = 1024;

            for (int i = 0; i < totalChunks; i++) {
                int localCX = i % 32;
                int localCZ = i / 32;

                int worldChunkX = regionX * 32 + localCX;
                int worldChunkZ = regionZ * 32 + localCZ;

                if (worldChunkX * 16 + 15 < minX || worldChunkX * 16 > maxX) continue;
                if (worldChunkZ * 16 + 15 < minZ || worldChunkZ * 16 > maxZ) continue;

                int offset = ((header[i * 4]     & 0xFF) << 16)
                           | ((header[i * 4 + 1] & 0xFF) << 8)
                           |  (header[i * 4 + 2] & 0xFF);
                int sectorCount = header[i * 4 + 3] & 0xFF;

                if (offset == 0 || sectorCount == 0) continue;

                long seekPos = (long) offset * SECTOR_SIZE;
                long allocatedEnd = seekPos + (long) sectorCount * SECTOR_SIZE;
                if (seekPos < SECTOR_SIZE * 2L || seekPos + 5 > raf.length() || allocatedEnd > raf.length()) continue;

                try {
                    raf.seek(seekPos);
                    int dataLength = raf.readInt();

                    if (dataLength == 0 || dataLength == 1) continue;
                    if (dataLength < 1) continue;

                    int compressionType = raf.readByte() & 0xFF;
                    if (compressionType >= 128) continue;

                    int payloadLength = dataLength - 1;
                    // The chunk length is not allowed to spill into the next
                    // region allocation. The old reader only compared against
                    // the end of the whole file, so a corrupt length could
                    // consume bytes belonging to later chunks.
                    int maxPayload = sectorCount * SECTOR_SIZE - 5;
                    if (payloadLength <= 0 || payloadLength > maxPayload) continue;

                    long currentPos = raf.getFilePointer();
                    long remaining = raf.length() - currentPos;
                    if (payloadLength > remaining) continue;

                    byte[] compressed = new byte[payloadLength];
                    raf.readFully(compressed);

                    byte[] nbtBytes = decompress(compressed, compressionType);
                    if (nbtBytes == null || nbtBytes.length == 0) continue;

                    NbtCompound chunkNbt = NbtReader.readBytes(nbtBytes);
                    extractBlocks(chunkNbt, worldChunkX, worldChunkZ,
                            minX, minY, minZ, maxX, maxY, maxZ, blocks);

                } catch (java.io.EOFException e) {
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
            }
        }

        return blocks;
    }

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

        if (data == null || !data.has("sections")) return;

        int chunkWorldX = worldChunkX * 16;
        int chunkWorldZ = worldChunkZ * 16;

        List<Object> sections = data.getList("sections");

        for (Object sectionObj : sections) {
            if (!(sectionObj instanceof NbtCompound section)) continue;
            if (!section.has("block_states")) continue;

            int sectionY = section.getInt("Y", 0);
            int sectionWorldY = sectionY * 16;

            if (sectionWorldY + 15 < minY || sectionWorldY > maxY) continue;

            NbtCompound blockStates = section.getCompound("block_states");
            if (!blockStates.has("palette")) continue;

            List<Object> palette = blockStates.getList("palette");
            if (palette.isEmpty()) continue;

            if (palette.size() == 1) {
                if (!(palette.get(0) instanceof NbtCompound entry)) continue;
                String blockId = entry.getString("Name", "minecraft:air");
                if (isAir(blockId)) continue;

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

            long[] data64 = blockStates.has("data") ? blockStates.getLongArray("data") : new long[0];
            if (data64.length == 0) continue;

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

            int bitsPerBlock = Math.max(4, (int) Math.ceil(Math.log(palette.size()) / Math.log(2)));
            if (bitsPerBlock >= 64) continue;
            long mask = (1L << bitsPerBlock) - 1;
            int blocksPerLong = 64 / bitsPerBlock;
            if (blocksPerLong <= 0) continue;

            for (int blockIdx = 0; blockIdx < 4096; blockIdx++) {
                int longIdx = blockIdx / blocksPerLong;
                if (longIdx >= data64.length) break;
                int bitOffset = (blockIdx % blocksPerLong) * bitsPerBlock;
                int paletteIdx = (int) ((data64[longIdx] >>> bitOffset) & mask);

                if (paletteIdx >= blockIds.length) continue;
                String blockId = blockIds[paletteIdx];
                if (blockId == null || isAir(blockId)) continue;

                int lx = blockIdx & 0xF;
                int ly = (blockIdx >> 8) & 0xF;
                int lz = (blockIdx >> 4) & 0xF;

                int wx = chunkWorldX + lx;
                int wy = sectionWorldY + ly;
                int wz = chunkWorldZ + lz;

                if (wx < minX || wx > maxX || wy < minY || wy > maxY || wz < minZ || wz > maxZ) continue;
                out.add(new BlockData(wx, wy, wz, blockId, blockProps[paletteIdx]));
            }
        }
    }

    private static boolean isAir(String blockId) {
        return blockId.equals("minecraft:air")
            || blockId.equals("minecraft:cave_air")
            || blockId.equals("minecraft:void_air");
    }

    private static byte[] decompress(byte[] data, int type) {
        try {
            return switch (type) {
                case 1 -> {
                    var baos = new ByteArrayOutputStream();
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                        gzip.transferTo(baos);
                    }
                    yield baos.toByteArray();
                }
                case 2 -> {
                    var baos = new ByteArrayOutputStream();
                    try (var inf = new InflaterInputStream(new ByteArrayInputStream(data))) {
                        inf.transferTo(baos);
                    }
                    yield baos.toByteArray();
                }
                case 3 -> data;
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

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total, String message);
    }
}
