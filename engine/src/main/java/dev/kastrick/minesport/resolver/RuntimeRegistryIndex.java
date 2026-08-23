package dev.kastrick.minesport.resolver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight seek index for schema-4 registry.data.
 *
 * The registry stays as one complete on-disk cache. Opening it remembers
 * block-record byte offsets; baked quad arrays are decoded later, only for
 * block IDs the current world/export actually uses.
 *
 * Lookup buckets use the first 64 bits of SHA-256 for compact/fast lookup, but
 * the full namespaced block ID is always verified before a record is accepted.
 * A truncated hash is therefore never treated as identity and collisions are
 * harmless.
 *
 * The expensive structural scan is persisted in a small sibling .idx file.
 * Existing schema-4 data remains unchanged. A missing/stale/corrupt index is an
 * optimization miss only: Minesport rebuilds it safely from registry.data.
 */
public final class RuntimeRegistryIndex {
    private static final byte[] MAGIC = "MSREGD01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INDEX_MAGIC = "MSRIDX01".getBytes(StandardCharsets.US_ASCII);
    private static final int INDEX_SCHEMA = 1;
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
            Header header = readHeader(input);
            Map<Long, List<BlockLocation>> persisted = readPersistentIndex(file, header);
            if (persisted != null) {
                return new RuntimeRegistryIndex(file, header, persisted);
            }

            Map<Long, List<BlockLocation>> buckets = new HashMap<>(
                Math.max(16, Math.min(header.blockCount() * 2, 1_000_000))
            );
            for (int blockIndex = 0; blockIndex < header.blockCount(); blockIndex++) {
                long offset = input.getFilePointer();
                String blockId = readString(input);
                if (blockId.isBlank()) {
                    throw new IOException("runtime registry contains an empty block ID at index " + blockIndex);
                }
                buckets.computeIfAbsent(hash64(blockId), ignored -> new ArrayList<>(1))
                    .add(new BlockLocation(blockId, offset));
                skipBlockPayload(input);
            }

            Map<Long, List<BlockLocation>> frozen = freezeBuckets(buckets);
            writePersistentIndex(file, header, frozen);
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

    private static Header readHeader(RandomAccessFile input) throws IOException {
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
        return new Header(
            schema,
            minecraftVersion,
            loaderVersion,
            modsFingerprint,
            capturedAt,
            blockCount
        );
    }

    private static File indexFile(File dataFile) {
        return new File(dataFile.getAbsolutePath() + ".idx");
    }

    private static Map<Long, List<BlockLocation>> readPersistentIndex(File dataFile, Header header) {
        File indexFile = indexFile(dataFile);
        if (!indexFile.isFile()) return null;

        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(new FileInputStream(indexFile), 64 * 1024)
        )) {
            byte[] magic = new byte[INDEX_MAGIC.length];
            input.readFully(magic);
            for (int index = 0; index < INDEX_MAGIC.length; index++) {
                if (magic[index] != INDEX_MAGIC[index]) return null;
            }
            if (input.readInt() != INDEX_SCHEMA) return null;
            if (input.readLong() != dataFile.length()) return null;
            if (input.readLong() != dataFile.lastModified()) return null;
            if (input.readInt() != header.schema()) return null;
            if (!readIndexString(input).equals(header.minecraftVersion())) return null;
            if (!readIndexString(input).equals(header.loaderVersion())) return null;
            if (!readIndexString(input).equals(header.modsFingerprint())) return null;
            if (!readIndexString(input).equals(header.capturedAt())) return null;

            int entryCount = input.readInt();
            if (entryCount != header.blockCount() || entryCount < 0 || entryCount > MAX_BLOCKS) return null;
            Map<Long, List<BlockLocation>> buckets = new HashMap<>(
                Math.max(16, Math.min(entryCount * 2, 1_000_000))
            );
            long dataLength = dataFile.length();
            for (int index = 0; index < entryCount; index++) {
                long hash = input.readLong();
                long offset = input.readLong();
                String blockId = readIndexString(input);
                if (blockId.isBlank() || hash != hash64(blockId) || offset < 0 || offset >= dataLength) {
                    return null;
                }
                buckets.computeIfAbsent(hash, ignored -> new ArrayList<>(1))
                    .add(new BlockLocation(blockId, offset));
            }
            return freezeBuckets(buckets);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writePersistentIndex(
        File dataFile,
        Header header,
        Map<Long, List<BlockLocation>> buckets
    ) {
        File destination = indexFile(dataFile);
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;

        File temporary = new File(
            parent,
            destination.getName() + ".tmp-" + ProcessHandle.current().pid() + "-" + Thread.currentThread().getId()
        );
        List<BlockLocation> locations = buckets.values().stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparingLong(BlockLocation::offset))
            .toList();
        if (locations.size() != header.blockCount()) return;

        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(temporary), 64 * 1024)
        )) {
            output.write(INDEX_MAGIC);
            output.writeInt(INDEX_SCHEMA);
            output.writeLong(dataFile.length());
            output.writeLong(dataFile.lastModified());
            output.writeInt(header.schema());
            writeIndexString(output, header.minecraftVersion());
            writeIndexString(output, header.loaderVersion());
            writeIndexString(output, header.modsFingerprint());
            writeIndexString(output, header.capturedAt());
            output.writeInt(locations.size());
            for (BlockLocation location : locations) {
                output.writeLong(hash64(location.blockId()));
                output.writeLong(location.offset());
                writeIndexString(output, location.blockId());
            }
        } catch (IOException ignored) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignoredDelete) {}
            return;
        }

        try {
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException ignored) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignoredDelete) {}
        }
    }

    private static Map<Long, List<BlockLocation>> freezeBuckets(Map<Long, List<BlockLocation>> buckets) {
        Map<Long, List<BlockLocation>> frozen = new HashMap<>(buckets.size());
        for (var entry : buckets.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static String readIndexString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("invalid runtime registry index string length " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeIndexString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("runtime registry index string exceeds limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
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
