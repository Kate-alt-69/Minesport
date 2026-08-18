package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Thread-safe cache for geometry compiled from a block's logical state.
 *
 * The cache key is block ID + canonicalized block-state properties, not world
 * coordinates. This is the important first step toward the new pipeline:
 * resolve/compile a state once, then place that immutable geometry everywhere
 * the state occurs.
 */
public final class GeometryTemplateCache {
    private final ConcurrentHashMap<Key, GeometryTemplate> templates = new ConcurrentHashMap<>();

    /** Return a cached template, creating it atomically on the first request. */
    public GeometryTemplate getOrCreate(BlockData block, Function<BlockData, GeometryTemplate> compiler) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(compiler, "compiler");

        Key key = Key.from(block);
        return templates.computeIfAbsent(key, ignored -> Objects.requireNonNull(
            compiler.apply(block), "geometry compiler returned null"));
    }

    public GeometryTemplate get(BlockData block) {
        return templates.get(Key.from(block));
    }

    public void clear() {
        templates.clear();
    }

    public int size() {
        return templates.size();
    }

    /** Number of cached logical block states currently represented. */
    public int stateCount() {
        return templates.size();
    }

    /**
     * Stable key for a block's logical state. Property ordering does not affect
     * the key, so maps produced by different NBT/resolver paths share a cache
     * entry when they describe the same state.
     */
    public record Key(String blockId, String stateKey) {
        public static Key from(BlockData block) {
            return new Key(block.blockId, canonicalState(block.properties));
        }

        private static String canonicalState(Map<String, String> properties) {
            if (properties == null || properties.isEmpty()) return "";

            List<String> keys = new ArrayList<>(properties.keySet());
            Collections.sort(keys);

            StringBuilder out = new StringBuilder();
            for (String key : keys) {
                if (out.length() > 0) out.append(',');
                out.append(key).append('=').append(properties.get(key));
            }
            return out.toString();
        }
    }
}
