package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-export spatial context shared by geometry builders created later in the
 * same IPC request (including FLATTER's runtime-aware builder).
 *
 * The engine processes one export command on one request thread. Multipart
 * resolution already receives the complete selected block list before geometry
 * construction, so it is the natural place to seed this context. Keeping the
 * map thread-local avoids global cross-export state while making liquid slopes,
 * waterlogging and transparent-neighbour rules independent of optimization
 * toggles such as face culling.
 */
public final class ExportWorldContext {
    private static final ThreadLocal<Map<Long, BlockData>> CURRENT =
        ThreadLocal.withInitial(Map::of);

    private ExportWorldContext() {}

    public static void set(List<BlockData> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            CURRENT.set(Map.of());
            return;
        }

        Map<Long, BlockData> index = new HashMap<>(Math.max(16, blocks.size() * 2));
        for (BlockData block : blocks) {
            if (block == null || block.isAir()) continue;
            index.put(SpatialKey.of(block.x, block.y, block.z), block);
        }
        CURRENT.set(Collections.unmodifiableMap(index));
    }

    public static Map<Long, BlockData> currentIndex() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
