package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;
import dev.kastrick.minesport.nbt.NbtReader;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Reads Minecraft Anvil .mca region files.
 *
 * Region framing is stable across a huge span of Minecraft versions. Chunk NBT
 * differences are delegated to ChunkBlockDecoder so this reader can handle
 * modern palettes, flattened palettes, and legacy numeric Anvil sections.
 */
public class RegionReader {

    private static final int SECTOR_SIZE = 4096;

    public record RegionContents(
        List<BlockData> blocks,
        List<BlockEntityData> blockEntities,
        List<EntityData> entities
    ) {}

    public static List<BlockData> readRegion(
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
            true,
            false,
            false
        ).blocks();
    }

    public static RegionContents readRegionContents(
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
            true,
            true,
            true
        );
    }

    public static List<EntityData> readEntityRegion(
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

    private static RegionContents readRegionInternal(
            File regionFile,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            ProgressCallback progress,
            boolean decodeBlocks,
            boolean includeBlockEntities,
            boolean includeEntities
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
        var blockEntities = new ArrayList<BlockEntityData>();
        var entities = new ArrayList<EntityData>();

        try (var raf = new RandomAccessFile(regionFile, "r")) {
            if (raf.length() < SECTOR_SIZE * 2L) {
                return new RegionContents(blocks, blockEntities, entities);
            }

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

                    if (dataLength <= 1) continue;

                    int compressionType = raf.readByte() & 0xFF;
                    // 128+ means external chunk storage in newer Anvil. Minesport
                    // does not read external .mcc payloads yet, so skip safely.
                    if (compressionType >= 128) continue;

                    int payloadLength = dataLength - 1;
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
                    if (decodeBlocks) {
                        ChunkBlockDecoder.decodeInto(
                            chunkNbt,
                            worldChunkX,
                            worldChunkZ,
                            minX, minY, minZ,
                            maxX, maxY, maxZ,
                            blocks
                        );
                    }
                    if (includeBlockEntities) {
                        extractBlockEntities(
                            chunkNbt,
                            minX, minY, minZ,
                            maxX, maxY, maxZ,
                            blockEntities
                        );
                    }
                    if (includeEntities) {
                        extractEntities(
                            chunkNbt,
                            minX, minY, minZ,
                            maxX, maxY, maxZ,
                            entities
                        );
                    }

                } catch (EOFException e) {
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

        return new RegionContents(blocks, blockEntities, entities);
    }

    static void extractBlockEntities(
        NbtCompound chunk,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<BlockEntityData> out
    ) {
        if (chunk == null || out == null) return;

        NbtCompound level = chunk;
        if (chunk.has("Level")) {
            try {
                level = chunk.getCompound("Level");
            } catch (Exception ignored) {}
        }

        List<Object> entries = null;
        try {
            if (chunk.has("block_entities")) {
                entries = chunk.getList("block_entities");
            } else if (level.has("TileEntities")) {
                entries = level.getList("TileEntities");
            } else if (chunk.has("TileEntities")) {
                entries = chunk.getList("TileEntities");
            }
        } catch (Exception ignored) {
            return;
        }
        if (entries == null) return;

        for (Object entry : entries) {
            if (!(entry instanceof NbtCompound entity)) continue;
            try {
                int x = entity.getInt("x");
                int y = entity.getInt("y");
                int z = entity.getInt("z");
                if (
                    x < minX || x > maxX ||
                    y < minY || y > maxY ||
                    z < minZ || z > maxZ
                ) continue;
                out.add(new BlockEntityData(x, y, z, entity));
            } catch (Exception ignored) {
                // Malformed block entity: preserve the surrounding chunk.
            }
        }
    }

    static void extractEntities(
        NbtCompound chunk,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        List<EntityData> out
    ) {
        if (chunk == null || out == null) return;

        NbtCompound level = chunk;
        if (chunk.has("Level")) {
            try {
                level = chunk.getCompound("Level");
            } catch (Exception ignored) {}
        }

        List<Object> entries = null;
        try {
            if (chunk.has("Entities")) {
                entries = chunk.getList("Entities");
            } else if (chunk.has("entities")) {
                entries = chunk.getList("entities");
            } else if (level.has("Entities")) {
                entries = level.getList("Entities");
            }
        } catch (Exception ignored) {
            return;
        }
        if (entries == null) return;

        for (Object entry : entries) {
            if (!(entry instanceof NbtCompound entity) || !entity.has("Pos")) continue;
            try {
                List<Object> pos = entity.getList("Pos");
                if (pos.size() < 3) continue;
                if (
                    !(pos.get(0) instanceof Number xValue) ||
                    !(pos.get(1) instanceof Number yValue) ||
                    !(pos.get(2) instanceof Number zValue)
                ) continue;
                double x = xValue.doubleValue();
                double y = yValue.doubleValue();
                double z = zValue.doubleValue();
                if (
                    x < minX || x >= (double) maxX + 1.0 ||
                    y < minY || y >= (double) maxY + 1.0 ||
                    z < minZ || z >= (double) maxZ + 1.0
                ) continue;
                out.add(new EntityData(x, y, z, entity));
            } catch (Exception ignored) {
                // Malformed entity: preserve the surrounding chunk.
            }
        }
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
