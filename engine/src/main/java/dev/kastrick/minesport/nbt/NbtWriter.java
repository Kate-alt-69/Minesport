package dev.kastrick.minesport.nbt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal big-endian NBT writer used by schematic exporters.
 *
 * Values are represented with normal Java types:
 * Byte/Short/Integer/Long/Float/Double, byte[]/int[]/long[], String,
 * List<?> and Map<String, ?>.
 */
public final class NbtWriter {
    private NbtWriter() {}

    public static void writeGzip(File file, Map<String, ?> root) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (
            var gzip = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
            var out = new DataOutputStream(gzip)
        ) {
            writeRoot(out, root);
        }
    }

    public static byte[] writeBytes(Map<String, ?> root) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            writeRoot(out, root);
        }
        return bytes.toByteArray();
    }

    private static void writeRoot(DataOutputStream out, Map<String, ?> root) throws IOException {
        out.writeByte(NbtReader.TAG_COMPOUND);
        writeString(out, "");
        writeCompound(out, root);
    }

    private static void writeNamed(DataOutputStream out, String name, Object value) throws IOException {
        byte type = typeOf(value);
        if (type == NbtReader.TAG_END) {
            throw new IOException("TAG_End cannot be written as a named NBT value: " + name);
        }
        out.writeByte(type);
        writeString(out, name);
        writePayload(out, type, value);
    }

    private static void writePayload(DataOutputStream out, byte type, Object value) throws IOException {
        switch (type) {
            case NbtReader.TAG_BYTE -> out.writeByte(((Number) value).byteValue());
            case NbtReader.TAG_SHORT -> out.writeShort(((Number) value).shortValue());
            case NbtReader.TAG_INT -> out.writeInt(((Number) value).intValue());
            case NbtReader.TAG_LONG -> out.writeLong(((Number) value).longValue());
            case NbtReader.TAG_FLOAT -> out.writeFloat(((Number) value).floatValue());
            case NbtReader.TAG_DOUBLE -> out.writeDouble(((Number) value).doubleValue());
            case NbtReader.TAG_BYTE_ARRAY -> {
                byte[] values = (byte[]) value;
                out.writeInt(values.length);
                out.write(values);
            }
            case NbtReader.TAG_STRING -> writeString(out, (String) value);
            case NbtReader.TAG_LIST -> writeList(out, (List<?>) value);
            case NbtReader.TAG_COMPOUND -> {
                @SuppressWarnings("unchecked")
                Map<String, ?> compound = (Map<String, ?>) value;
                writeCompound(out, compound);
            }
            case NbtReader.TAG_INT_ARRAY -> {
                int[] values = (int[]) value;
                out.writeInt(values.length);
                for (int item : values) out.writeInt(item);
            }
            case NbtReader.TAG_LONG_ARRAY -> {
                long[] values = (long[]) value;
                out.writeInt(values.length);
                for (long item : values) out.writeLong(item);
            }
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        }
    }

    private static void writeCompound(DataOutputStream out, Map<String, ?> compound) throws IOException {
        for (Map.Entry<String, ?> entry : compound.entrySet()) {
            if (entry.getValue() == null) continue;
            writeNamed(out, entry.getKey(), entry.getValue());
        }
        out.writeByte(NbtReader.TAG_END);
    }

    private static void writeList(DataOutputStream out, List<?> values) throws IOException {
        if (values.isEmpty()) {
            out.writeByte(NbtReader.TAG_END);
            out.writeInt(0);
            return;
        }

        byte type = typeOf(values.getFirst());
        if (type == NbtReader.TAG_END) {
            throw new IOException("NBT lists cannot contain TAG_End");
        }
        out.writeByte(type);
        out.writeInt(values.size());
        for (Object value : values) {
            if (typeOf(value) != type) {
                throw new IOException("NBT list contains mixed tag types");
            }
            writePayload(out, type, value);
        }
    }

    private static byte typeOf(Object value) throws IOException {
        if (value instanceof Byte) return NbtReader.TAG_BYTE;
        if (value instanceof Short) return NbtReader.TAG_SHORT;
        if (value instanceof Integer) return NbtReader.TAG_INT;
        if (value instanceof Long) return NbtReader.TAG_LONG;
        if (value instanceof Float) return NbtReader.TAG_FLOAT;
        if (value instanceof Double) return NbtReader.TAG_DOUBLE;
        if (value instanceof byte[]) return NbtReader.TAG_BYTE_ARRAY;
        if (value instanceof String) return NbtReader.TAG_STRING;
        if (value instanceof List<?>) return NbtReader.TAG_LIST;
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (!(key instanceof String)) {
                    throw new IOException("NBT compound keys must be strings");
                }
            }
            return NbtReader.TAG_COMPOUND;
        }
        if (value instanceof int[]) return NbtReader.TAG_INT_ARRAY;
        if (value instanceof long[]) return NbtReader.TAG_LONG_ARRAY;
        throw new IOException("Unsupported NBT value type: " + value.getClass().getName());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) {
            throw new IOException("NBT string is too long: " + bytes.length + " UTF-8 bytes");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
