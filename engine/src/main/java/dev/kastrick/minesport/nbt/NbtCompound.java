package dev.kastrick.minesport.nbt;

import java.util.*;

/**
 * A TAG_Compound — basically a typed Map<String, Object>.
 * Provides typed getters so you don't have to cast everywhere.
 */
public class NbtCompound {

    private final Map<String, Object> tags;

    public NbtCompound(Map<String, Object> tags) {
        this.tags = tags;
    }

    public boolean has(String key) {
        return tags.containsKey(key);
    }

    public NbtCompound getCompound(String key) {
        Object v = tags.get(key);
        if (v instanceof NbtCompound c) return c;
        throw new NbtException("Expected compound at key: " + key + ", got: " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        Object v = tags.get(key);
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new NbtException("Expected list at key: " + key);
    }

    public String getString(String key) {
        Object v = tags.get(key);
        if (v instanceof String s) return s;
        throw new NbtException("Expected string at key: " + key);
    }

    public String getString(String key, String fallback) {
        if (!tags.containsKey(key)) return fallback;
        return getString(key);
    }

    public int getInt(String key) {
        Object v = tags.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        throw new NbtException("Expected int at key: " + key);
    }

    public int getInt(String key, int fallback) {
        if (!tags.containsKey(key)) return fallback;
        return getInt(key);
    }

    public long getLong(String key) {
        Object v = tags.get(key);
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        throw new NbtException("Expected long at key: " + key);
    }

    public byte getByte(String key) {
        Object v = tags.get(key);
        if (v instanceof Byte b) return b;
        if (v instanceof Number n) return n.byteValue();
        throw new NbtException("Expected byte at key: " + key);
    }

    public byte[] getByteArray(String key) {
        Object v = tags.get(key);
        if (v instanceof byte[] ba) return ba;
        throw new NbtException("Expected byte array at key: " + key);
    }

    public long[] getLongArray(String key) {
        Object v = tags.get(key);
        if (v instanceof long[] la) return la;
        throw new NbtException("Expected long array at key: " + key);
    }

    public int[] getIntArray(String key) {
        Object v = tags.get(key);
        if (v instanceof int[] ia) return ia;
        throw new NbtException("Expected int array at key: " + key);
    }

    public Set<String> keys() {
        return tags.keySet();
    }

    /** Read-only raw view used when losslessly re-emitting parsed NBT. */
    public Map<String, Object> asMapView() {
        return Collections.unmodifiableMap(tags);
    }

    @Override
    public String toString() {
        return "NbtCompound" + tags;
    }

    public static class NbtException extends RuntimeException {
        public NbtException(String msg) { super(msg); }
    }
}
