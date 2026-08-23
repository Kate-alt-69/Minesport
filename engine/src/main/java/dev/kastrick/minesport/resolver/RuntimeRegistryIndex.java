package dev.kastrick.minesport.resolver;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight seek index for schema-4 registry.data.
 *
 * The registry stays as one complete on-disk cache. Opening it scans only the
 * record structure and remembers block-record byte offsets; baked quad arrays
 * are decoded later, only for block IDs the current world/export actually uses.
 *
 * Lookup buckets use the first 64 bits of SHA-256 for compact/fast lookup, but
 * the full namespaced block ID is always verified before a record is accepted.
 * A truncated hash is therefore never treated as identity and collisions are
 * harmless.
 */
public final class RuntimeRegistryIndex {
    private static final byte[] MAGIC = "MSREGD01".getBytes(StandardCharsets.US_ASCII);
    private static final int SCHEMA = RuntimeRegistryDataReader.SCHEMA;
    private static final int MAX_STRING_BYTES = 4 << 20;
    private static final int MAX_BLOCKS = 1_000_000;
    private static final int MAX_VARIANTS = 1_000_000;
    private static final int MAX_QUADS = 20_000_000;
    private static final int MAX_PROPERTIES = 4096;
    private static final int MAX_LOADED_MODS = 100_000;
    private static final int PACKED_VERTEX_BYTES = 32 * Float.BYTES;
    private static final int QUAD_TRAILER_BYTES = Integer.BYTES + 1 + Integer.BYTES;

    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    });

    public record Header(
        int schema,
        String minecraftVersion,
        String loaderVersion,
        String modsFingerprint,
        String capturedAt,
        int blockCount
    ) {}

    /** Data needed by BridgeStateRegistry without touching baked quad payloads. */
    public record BlockMetadata(boolean hasVariants, List<RuntimeRegistryDataReader.DataLight> lights) {}

    private record BlockLocation(String blockId, long offset) {}

    private final File file;
    private final Header header;
    private final Map<Long, List<BlockLocation>> buckets;

    private RuntimeRegistryIndex(File file, Header header, Map<Long, List<BlockLocation>> buckets) {
        this.file = file;
        this.header = header;
        this.buckets = Map.copyOf(buckets);
    }

    public static RuntimeRegistryIndex open(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("runtime registry file is unavailable");
        }

        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            validateMagic(input);
            int schema = input.readInt();
            if (schema != SCHEMA) {
                throw new IOException("unsupported runtime registry schema " + schema + "; expected " + SCHEMA);
            }

            String minecraftVersion = readString(input);
            String loaderVersion = readString(input);
            String modsFingerprint = readString(input);
            String capturedAt = readString(input);

            int loadedModCount = readCount(input, MAX_LOADED_MODS, "loaded mods");
            for (int index = 0; index < loadedModCount; index++) {
                skipString(input);
            }

            int blockCount = readCount(input, MAX_BLOCKS, "blocks");
            Map<Long, List<BlockLocation>> buckets = new HashMap<>(Math.max(16, Math.min(blockCount * 2, 1_000_000)));
            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                long offset = input.getFilePointer();
                String blockId = readString(input);
                if (blockId.isBlank()) {
                    throw new IOException("runtime registry contains an empty block ID at index " + blockIndex);
                }
                buckets.computeIfAbsent(hash64(blockId), ignored -> new ArrayList<>(1))
                    .add(new BlockLocation(blockId, offset));
                skipBlockPayload(input);
            }

            Header header = new Header(
                schema,
                minecraftVersion,
                loaderVersion,
                modsFingerprint,
                capturedAt,
                blockCount
            );
            Map<Long, List<BlockLocation>> frozen = new HashMap<>(buckets.size());
            for (var entry : buckets.entrySet()) {
                frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new RuntimeRegistryIndex(file, header, frozen);
        }
    }

    public Header header() {
        return header;
    }

    public int blockCount() {
        return header.blockCount();
    }

    public boolean hasBlock(String blockId) {
        return find(blockId) != null;
    }

    /** Decode exactly one block record from disk. No other baked geometry is materialized. */
    public RuntimeRegistryDataReader.DataBlock readBlock(String blockId) throws IOException {
        BlockLocation location = find(blockId);
        if (location == null) return null;

        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(location.offset());
            verifyStoredId(input, blockId);

            String vanillaMapping = readString(input);
            String loaderType = readString(input);
            int variantCount = readCount(input, MAX_VARIANTS, "variants");
            List<RuntimeRegistryDataReader.DataVariant> variants = new ArrayList<>(variantCount);
            for (int variantIndex = 0; variantIndex < variantCount; variantIndex++) {
                Map<String, String> properties = readStringMap(input);
                int quadCount = readCount(input, MAX_QUADS, "quads");
                List<RuntimeRegistryDataReader.DataQuad> quads = new ArrayList<>(quadCount);
                for (int quadIndex = 0; quadIndex < quadCount; quadIndex++) {
                    float[] vertices = new float[32];
                    for (int vertexIndex = 0; vertexIndex < vertices.length; vertexIndex++) {
                        float value = input.readFloat();
                        if (!Float.isFinite(value)) {
                            throw new IOException("runtime registry contains non-finite vertex data");
                        }
                        vertices[vertexIndex] = value;
                    }
                    String textureId = readString(input);
                    int face = input.readInt();
                    int shadeByte = input.readUnsignedByte();
                    if (shadeByte > 1) {
                        throw new IOException("invalid runtime quad shade byte " + shadeByte);
                    }
                    int tintIndex = input.readInt();
                    quads.add(new RuntimeRegistryDataReader.DataQuad(
                        vertices,
                        textureId,
                        face,
                        shadeByte == 1,
                        tintIndex
                    ));
                }
                variants.add(new RuntimeRegistryDataReader.DataVariant(
                    Map.copyOf(properties),
                    List.copyOf(quads)
                ));
            }

            List<RuntimeRegistryDataReader.DataLight> lights = readLights(input);
            return new RuntimeRegistryDataReader.DataBlock(
                vanillaMapping,
                loaderType,
                List.copyOf(variants),
                lights
            );
        }
    }

    /**
     * Read only state metadata needed for world enrichment. Quad payloads are
     * seek-skipped, so this path allocates no baked vertex/UV arrays at all.
     */
    public BlockMetadata readMetadata(String blockId) throws IOException {
        BlockLocation location = find(blockId);
        if (location == null) return null;

        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(location.offset());
            verifyStoredId(input, blockId);
            skipString(input); // vanilla mapping
            skipString(input); // loader type

            int variantCount = readCount(input, MAX_VARIANTS, "variants");
            for (int variantIndex = 0; variantIndex < variantCount; variantIndex++) {
                skipStringMap(input);
                skipQuadList(input);
            }
            return new BlockMetadata(variantCount > 0, readLights(input));
        }
    }

    /** First 64 bits of SHA-256. The caller must still verify the canonical key. */
    public static long hash64(String canonicalKey) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        byte[] bytes = digest.digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << 8) | (bytes[index] & 0xffL);
        }
        return value;
    }

    private BlockLocation find(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;
        List<BlockLocation> candidates = buckets.get(hash64(blockId));
        if (candidates == null) return null;
        for (BlockLocation candidate : candidates) {
            if (candidate.blockId().equals(blockId)) return candidate;
        }
        return null;
    }

    private static void verifyStoredId(RandomAccessFile input, String expected) throws IOException {
        String storedId = readString(input);
        if (!storedId.equals(expected)) {
            throw new IOException("runtime registry hash index mismatch for " + expected);
        }
    }

    private static void validateMagic(RandomAccessFile input) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        for (int index = 0; index < MAGIC.length; index++) {
            if (magic[index] != MAGIC[index]) {
                throw new IOException("invalid runtime registry magic");
            }
        }
    }

    private static void skipBlockPayload(RandomAccessFile input) throws IOException {
        skipString(input); // vanilla mapping
        skipString(input); // loader type
        int variantCount = readCount(input, MAX_VARIANTS, "variants");
        for (int variantIndex = 0; variantIndex < variantCount; variantIndex++) {
            skipStringMap(input);
            skipQuadList(input);
        }

        int lightCount = readCount(input, MAX_VARIANTS, "light states");
        for (int lightIndex = 0; lightIndex < lightCount; lightIndex++) {
            skipStringMap(input);
            skipBytes(input, Integer.BYTES, "light level");
        }
    }

    private static void skipQuadList(RandomAccessFile input) throws IOException {
        int quadCount = readCount(input, MAX_QUADS, "quads");
        for (int quadIndex = 0; quadIndex < quadCount; quadIndex++) {
            skipBytes(input, PACKED_VERTEX_BYTES, "quad vertices");
            skipString(input);
            skipBytes(input, QUAD_TRAILER_BYTES, "quad metadata");
        }
    }

    private static List<RuntimeRegistryDataReader.DataLight> readLights(RandomAccessFile input) throws IOException {
        int lightCount = readCount(input, MAX_VARIANTS, "light states");
        List<RuntimeRegistryDataReader.DataLight> lights = new ArrayList<>(lightCount);
        for (int lightIndex = 0; lightIndex < lightCount; lightIndex++) {
            Map<String, String> properties = readStringMap(input);
            int level = input.readInt();
            if (level < 0 || level > 15) {
                throw new IOException("invalid runtime light level " + level);
            }
            lights.add(new RuntimeRegistryDataReader.DataLight(Map.copyOf(properties), level));
        }
        return List.copyOf(lights);
    }

    private static Map<String, String> readStringMap(RandomAccessFile input) throws IOException {
        int count = readCount(input, MAX_PROPERTIES, "properties");
        Map<String, String> result = new LinkedHashMap<>(count);
        for (int index = 0; index < count; index++) {
            result.put(readString(input), readString(input));
        }
        return result;
    }

    private static void skipStringMap(RandomAccessFile input) throws IOException {
        int count = readCount(input, MAX_PROPERTIES, "properties");
        for (int index = 0; index < count; index++) {
            skipString(input);
            skipString(input);
        }
    }

    private static String readString(RandomAccessFile input) throws IOException {
        int length = readCount(input, MAX_STRING_BYTES, "string bytes");
        if (length == 0) return "";
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skipString(RandomAccessFile input) throws IOException {
        int length = readCount(input, MAX_STRING_BYTES, "string bytes");
        skipBytes(input, length, "string");
    }

    private static int readCount(RandomAccessFile input, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("runtime registry " + label + " count " + count
                + " exceeds limit " + maximum);
        }
        return count;
    }

    private static void skipBytes(RandomAccessFile input, long count, String label) throws IOException {
        if (count < 0) throw new IOException("negative runtime registry " + label + " length");
        long current = input.getFilePointer();
        long target;
        try {
            target = Math.addExact(current, count);
        } catch (ArithmeticException exception) {
            throw new IOException("runtime registry " + label + " offset overflow", exception);
        }
        if (target > input.length()) {
            throw new EOFException("runtime registry ended inside " + label);
        }
        input.seek(target);
    }
}
