package dev.kastrick.minesport.region;

import static org.junit.jupiter.api.Assertions.*;

import dev.kastrick.minesport.nbt.NbtCompound;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkBlockDecoderTest {

    @Test
    void decodesModernPaletteSection() {
        NbtCompound stone = compound("Name", "minecraft:stone");
        NbtCompound blockStates = compound("palette", List.of(stone));
        NbtCompound section = compound("Y", 0, "block_states", blockStates);
        NbtCompound chunk = compound("sections", List.of(section));

        assertEquals(ChunkBlockDecoder.Format.MODERN_PALETTE, ChunkBlockDecoder.detect(chunk));
        List<BlockData> blocks = ChunkBlockDecoder.decode(chunk, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(1, blocks.size());
        assertEquals("minecraft:stone", blocks.get(0).blockId);
    }

    @Test
    void decodesFlattened113To117PaletteSection() {
        NbtCompound dirt = compound("Name", "minecraft:dirt");
        NbtCompound section = compound("Y", (byte) 0, "Palette", List.of(dirt));
        NbtCompound level = compound("Sections", List.of(section));
        NbtCompound chunk = compound("Level", level);

        assertEquals(ChunkBlockDecoder.Format.FLATTENED_PALETTE, ChunkBlockDecoder.detect(chunk));
        List<BlockData> blocks = ChunkBlockDecoder.decode(chunk, 0, 0, 4, 7, 9, 4, 7, 9);
        assertEquals(1, blocks.size());
        assertEquals("minecraft:dirt", blocks.get(0).blockId);
        assertEquals(4, blocks.get(0).x);
        assertEquals(7, blocks.get(0).y);
        assertEquals(9, blocks.get(0).z);
    }

    @Test
    void decodesMinecraft15NumericAnvilSection() {
        byte[] numericIds = new byte[4096];
        byte[] metadata = new byte[2048];
        int index = blockIndex(1, 2, 3);
        numericIds[index] = 5; // wooden planks
        setNibble(metadata, index, 2); // birch variant

        NbtCompound section = compound(
            "Y", (byte) 0,
            "Blocks", numericIds,
            "Data", metadata
        );
        NbtCompound level = compound("Sections", List.of(section));
        NbtCompound chunk = compound("Level", level);

        assertEquals(ChunkBlockDecoder.Format.LEGACY_NUMERIC_ANVIL, ChunkBlockDecoder.detect(chunk));
        List<BlockData> blocks = ChunkBlockDecoder.decode(chunk, 0, 0, 1, 2, 3, 1, 2, 3);
        assertEquals(1, blocks.size());
        BlockData block = blocks.get(0);
        assertEquals("minecraft:birch_planks", block.blockId);
        assertEquals("5", block.properties.get("legacy_id"));
        assertEquals("2", block.properties.get("legacy_data"));
    }

    @Test
    void legacyAddArrayExtendsNumericIdsWithoutCorruptingCoordinates() {
        byte[] numericIds = new byte[4096];
        byte[] metadata = new byte[2048];
        byte[] add = new byte[2048];
        int index = blockIndex(15, 15, 15);
        numericIds[index] = 1;
        setNibble(add, index, 1); // 0x101 / 257, intentionally unknown to 1.5 table

        NbtCompound section = compound(
            "Y", (byte) 1,
            "Blocks", numericIds,
            "Data", metadata,
            "Add", add
        );
        NbtCompound chunk = compound("Level", compound("Sections", List.of(section)));

        List<BlockData> blocks = ChunkBlockDecoder.decode(chunk, 2, -1, 47, 31, -1, 47, 31, -1);
        assertEquals(1, blocks.size());
        BlockData block = blocks.get(0);
        assertEquals("minesport:legacy_block_257", block.blockId);
        assertEquals(47, block.x);
        assertEquals(31, block.y);
        assertEquals(-1, block.z);
    }

    private static int blockIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static void setNibble(byte[] values, int index, int value) {
        int byteIndex = index >> 1;
        int current = values[byteIndex] & 0xFF;
        if ((index & 1) == 0) {
            current = (current & 0xF0) | (value & 0xF);
        } else {
            current = (current & 0x0F) | ((value & 0xF) << 4);
        }
        values[byteIndex] = (byte) current;
    }

    private static NbtCompound compound(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], entries[i + 1]);
        }
        return new NbtCompound(values);
    }
}
