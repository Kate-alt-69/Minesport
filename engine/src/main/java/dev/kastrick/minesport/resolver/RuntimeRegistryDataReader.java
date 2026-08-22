package dev.kastrick.minesport.resolver;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the compact schema-4 registry.data written by the Go bridge receiver. */
public final class RuntimeRegistryDataReader {
    public static final int SCHEMA = 4;
    private static final byte[] MAGIC = "MSREGD01".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_STRING_BYTES = 4 << 20;
    private static final int MAX_BLOCKS = 1_000_000;
    private static final int MAX_VARIANTS = 1_000_000;
    private static final int MAX_QUADS = 20_000_000;
    private static final int MAX_PROPERTIES = 4096;
    private static final int MAX_LOADED_MODS = 100_000;

    public record DataQuad(
        float[] vertices,
        String textureId,
        int face,
        boolean shade,
        int tintIndex
    ) {}

    public record DataVariant(Map<String, String> properties, List<DataQuad> quads) {}
    public record DataLight(Map<String, String> properties, int lightLevel) {}

    public record DataBlock(
        String vanillaMapping,
        String loaderType,
        List<DataVariant> variants,
        List<DataLight> lights
    ) {}

    public record DataSnapshot(
        int schema,
        String minecraftVersion,
        String loaderVersion,
        String modsFingerprint,
        String capturedAt,
        List<String> loadedMods,
        Map<String, DataBlock> blocks
    ) {}

    private RuntimeRegistryDataReader() {}

    public static DataSnapshot read(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("runtime registry file is unavailable");
        }
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(new FileInputStream(file), 256 * 1024)
        )) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (magic.length != MAGIC.length) {
                throw new EOFException("runtime registry ended before its header");
            }
            for (int index = 0; index < MAGIC.length; index++) {
                if (magic[index] != MAGIC[index]) {
                    throw new IOException("invalid runtime registry magic");
                }
            }

            int schema = input.readInt();
            String minecraftVersion = readString(input);
            String loaderVersion = readString(input);
            String modsFingerprint = readString(input);
            String capturedAt = readString(input);

            int modCount = readCount(input, MAX_LOADED_MODS, "loaded mods");
            List<String> loadedMods = new ArrayList<>(modCount);
            for (int index = 0; index < modCount; index++) {
                loadedMods.add(readString(input));
            }

            int blockCount = readCount(input, MAX_BLOCKS, "blocks");
            Map<String, DataBlock> blocks = new LinkedHashMap<>(Math.min(blockCount * 2, 1_000_000));
            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                String blockId = readString(input);
                String vanillaMapping = readString(input);
                String loaderType = readString(input);

                int variantCount = readCount(input, MAX_VARIANTS, "variants");
                List<DataVariant> variants = new ArrayList<>(variantCount);
                for (int variantIndex = 0; variantIndex < variantCount; variantIndex++) {
                    Map<String, String> properties = readStringMap(input);
                    int quadCount = readCount(input, MAX_QUADS, "quads");
                    List<DataQuad> quads = new ArrayList<>(quadCount);
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
                        quads.add(new DataQuad(vertices, textureId, face, shadeByte == 1, tintIndex));
                    }
                    variants.add(new DataVariant(Map.copyOf(properties), List.copyOf(quads)));
                }

                int lightCount = readCount(input, MAX_VARIANTS, "light states");
                List<DataLight> lights = new ArrayList<>(lightCount);
                for (int lightIndex = 0; lightIndex < lightCount; lightIndex++) {
                    Map<String, String> properties = readStringMap(input);
                    int level = input.readInt();
                    lights.add(new DataLight(Map.copyOf(properties), level));
                }

                blocks.put(blockId, new DataBlock(
                    vanillaMapping,
                    loaderType,
                    List.copyOf(variants),
                    List.copyOf(lights)
                ));
            }

            return new DataSnapshot(
                schema,
                minecraftVersion,
                loaderVersion,
                modsFingerprint,
                capturedAt,
                List.copyOf(loadedMods),
                Map.copyOf(blocks)
            );
        }
    }

    private static Map<String, String> readStringMap(DataInputStream input) throws IOException {
        int count = readCount(input, MAX_PROPERTIES, "properties");
        Map<String, String> result = new LinkedHashMap<>(count);
        for (int index = 0; index < count; index++) {
            result.put(readString(input), readString(input));
        }
        return result;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readCount(input, MAX_STRING_BYTES, "string bytes");
        if (length == 0) return "";
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("runtime registry ended inside a string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream input, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("runtime registry " + label + " count " + count
                + " exceeds limit " + maximum);
        }
        return count;
    }
}
