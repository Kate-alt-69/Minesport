package dev.kastrick.minesport.nbt;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NbtWriterTest {
    @Test
    void writesValuesThatTheExistingReaderCanReadBack() throws Exception {
        LinkedHashMap<String, Object> child = new LinkedHashMap<>();
        child.put("Name", "minecraft:oak_stairs");
        child.put("Facing", "north");

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("Byte", (byte) 7);
        root.put("Short", (short) 300);
        root.put("Int", 123456);
        root.put("Long", 9_876_543_210L);
        root.put("Text", "Minesport ✓");
        root.put("Bytes", new byte[] {1, 2, 3});
        root.put("Ints", new int[] {4, 5, 6});
        root.put("Longs", new long[] {7L, -1L, Long.MIN_VALUE});
        root.put("Compound", child);
        root.put("List", List.of("a", "b", "c"));
        root.put("Empty", List.of());

        NbtCompound decoded = NbtReader.readBytes(NbtWriter.writeBytes(root));

        assertEquals(7, decoded.getByte("Byte"));
        assertEquals(300, decoded.getInt("Short"));
        assertEquals(123456, decoded.getInt("Int"));
        assertEquals(9_876_543_210L, decoded.getLong("Long"));
        assertEquals("Minesport ✓", decoded.getString("Text"));
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.getByteArray("Bytes"));
        assertArrayEquals(new int[] {4, 5, 6}, decoded.getIntArray("Ints"));
        assertArrayEquals(new long[] {7L, -1L, Long.MIN_VALUE}, decoded.getLongArray("Longs"));
        assertEquals("minecraft:oak_stairs", decoded.getCompound("Compound").getString("Name"));
        assertEquals(List.of("a", "b", "c"), decoded.getList("List"));
        assertTrue(decoded.getList("Empty").isEmpty());
    }
}
