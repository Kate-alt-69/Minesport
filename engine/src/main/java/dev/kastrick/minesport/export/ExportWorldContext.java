package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-export spatial context handed from multipart resolution to the primary
 * export GeometryBuilder.
 *
 * The engine processes one export command on one request thread. Multipart
 * resolution receives the complete selected block list before geometry
 * construction, so it is the natural place to seed neighbour-aware geometry.
 * The primary builder consumes this map once and keeps the strong reference for
 * the duration of the export; the ThreadLocal is removed immediately so a large
 * completed world cannot remain pinned between IPC commands.
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

    /** Read without consuming; retained for focused tests/tools. */
    public static Map<Long, BlockData> currentIndex() {
        return CURRENT.get();
    }

    /**
     * Transfer ownership of the current map to the primary export builder and
     * immediately clear the ThreadLocal reference.
     */
    public static Map<Long, BlockData> takeIndex() {
        Map<Long, BlockData> index = CURRENT.get();
        CURRENT.remove();
        return index;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
