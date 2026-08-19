package dev.kastrick.minesport.nbt;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Reads Minecraft's Named Binary Tag (NBT) format.
 * Supports both raw and GZIP-compressed NBT.
 */
public class NbtReader {

    public static final byte TAG_END        = 0;
    public static final byte TAG_BYTE       = 1;
    public static final byte TAG_SHORT      = 2;
    public static final byte TAG_INT        = 3;
    public static final byte TAG_LONG       = 4;
    public static final byte TAG_FLOAT      = 5;
    public static final byte TAG_DOUBLE     = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING     = 8;
    public static final byte TAG_LIST       = 9;
    public static final byte TAG_COMPOUND   = 10;
    public static final byte TAG_INT_ARRAY  = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    // Corrupt region files should fail the individual chunk, not take down the
    // whole process with an attacker/corruption-induced huge allocation.
    private static final int MAX_LIST_ENTRIES = 1_000_000;
    private static final int MAX_ARRAY_ENTRIES = 16_000_000;

    public static NbtCompound readGzip(File file) throws IOException {
        try (var gzip = new GZIPInputStream(new FileInputStream(file));
             var data = new DataInputStream(new BufferedInputStream(gzip))) {
            return readRoot(data);
        }
    }

    public static NbtCompound readBytes(byte[] bytes) throws IOException {
        try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return readRoot(data);
        }
    }

    private static NbtCompound readRoot(DataInputStream in) throws IOException {
        byte type = in.readByte();
        if (type != TAG_COMPOUND) {
            throw new IOException("Expected root TAG_Compound, got: " + type);
        }
        readString(in);
        return readCompound(in);
    }

    private static Object readPayload(DataInputStream in, byte type) throws IOException {
        return switch (type) {
            case TAG_BYTE       -> in.readByte();
            case TAG_SHORT      -> in.readShort();
            case TAG_INT        -> in.readInt();
            case TAG_LONG       -> in.readLong();
            case TAG_FLOAT      -> in.readFloat();
            case TAG_DOUBLE     -> in.readDouble();
            case TAG_BYTE_ARRAY -> readByteArray(in);
            case TAG_STRING     -> readString(in);
            case TAG_LIST       -> readList(in);
            case TAG_COMPOUND   -> readCompound(in);
            case TAG_INT_ARRAY  -> readIntArray(in);
            case TAG_LONG_ARRAY -> readLongArray(in);
            default -> throw new IOException("Unknown NBT tag type: " + type);
        };
    }

    private static NbtCompound readCompound(DataInputStream in) throws IOException {
        var map = new LinkedHashMap<String, Object>();
        byte type;
        while ((type = in.readByte()) != TAG_END) {
            String name = readString(in);
            Object value = readPayload(in, type);
            map.put(name, value);
        }
        return new NbtCompound(map);
    }

    private static List<Object> readList(DataInputStream in) throws IOException {
        byte elementType = in.readByte();
        int size = in.readInt();
        if (size < 0 || size > MAX_LIST_ENTRIES) {
            throw new IOException("Invalid NBT list length: " + size);
        }
        var list = new ArrayList<Object>(size);
        for (int i = 0; i < size; i++) {
            list.add(readPayload(in, elementType));
        }
        return list;
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readByteArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_ARRAY_ENTRIES) throw new IOException("Invalid NBT byte-array length: " + len);
        byte[] arr = new byte[len];
        in.readFully(arr);
        return arr;
    }

    private static int[] readIntArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_ARRAY_ENTRIES) throw new IOException("Invalid NBT int-array length: " + len);
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) arr[i] = in.readInt();
        return arr;
    }

    private static long[] readLongArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_ARRAY_ENTRIES) throw new IOException("Invalid NBT long-array length: " + len);
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) arr[i] = in.readLong();
        return arr;
    }
}
