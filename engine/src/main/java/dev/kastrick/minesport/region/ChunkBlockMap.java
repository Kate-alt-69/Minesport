package dev.kastrick.minesport.region;

import dev.kastrick.minesport.export.BlockGeometryKind;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Compact first-pass representation of one Minecraft chunk.
 *
 * This deliberately separates "where blocks are" from geometry generation.
 * Each occupied position stores only a state ID and a coarse geometry kind;
 * the actual mesh is produced later from reusable geometry templates.
 *
 * The Y range is supplied by the caller because selections can be narrower
 * than the world's full build height (for example a 60-block-tall export).
 */
public final class ChunkBlockMap {
    public static final int CHUNK_SIZE = 16;

    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int height;
    private final int[] stateIds;
    private final byte[] kinds;

    public ChunkBlockMap(int chunkX, int chunkZ, int minY, int height) {
        if (height <= 0) throw new IllegalArgumentException("height must be > 0");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        int volume = CHUNK_SIZE * height * CHUNK_SIZE;
        this.stateIds = new int[volume];
        this.kinds = new byte[volume];
        java.util.Arrays.fill(stateIds, -1);
        java.util.Arrays.fill(kinds, (byte) BlockGeometryKind.AIR.ordinal());
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int minY() { return minY; }
    public int height() { return height; }

    public void put(BlockData block, int stateId, BlockGeometryKind kind) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(kind, "kind");

        int localX = Math.floorMod(block.x, CHUNK_SIZE);
        int localZ = Math.floorMod(block.z, CHUNK_SIZE);
        int localY = block.y - minY;
        if (localY < 0 || localY >= height) return;

        int index = index(localX, localY, localZ);
        stateIds[index] = stateId;
        kinds[index] = (byte) kind.ordinal();
    }

    /**
     * Map a block list into compact storage. The classifier is intentionally
     * injectable so the asset/resolver layer can decide FULL/PARTIAL/CUSTOM
     * without coupling this data structure to model parsing.
     */
    public static ChunkBlockMap fromBlocks(
            int chunkX,
            int chunkZ,
            int minY,
            int height,
            List<BlockData> blocks,
            BlockStateRegistry states,
            Function<BlockData, BlockGeometryKind> classifier
    ) {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(classifier, "classifier");

        ChunkBlockMap map = new ChunkBlockMap(chunkX, chunkZ, minY, height);
        for (BlockData block : blocks) {
            if (block.isAir()) continue;
            map.put(block, states.intern(block), classifier.apply(block));
        }
        return map;
    }

    public int stateIdAt(int localX, int localY, int localZ) {
        return stateIds[index(localX, localY, localZ)];
    }

    public BlockGeometryKind kindAt(int localX, int localY, int localZ) {
        int ordinal = Byte.toUnsignedInt(kinds[index(localX, localY, localZ)]);
        return BlockGeometryKind.values()[ordinal];
    }

    public int occupiedCount() {
        int count = 0;
        for (int stateId : stateIds) {
            if (stateId >= 0) count++;
        }
        return count;
    }

    private int index(int localX, int localY, int localZ) {
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) {
            throw new IndexOutOfBoundsException("chunk coordinates: " + localX + ", " + localZ);
        }
        if (localY < 0 || localY >= height) {
            throw new IndexOutOfBoundsException("local Y: " + localY);
        }
        return (localY * CHUNK_SIZE + localZ) * CHUNK_SIZE + localX;
    }
}
