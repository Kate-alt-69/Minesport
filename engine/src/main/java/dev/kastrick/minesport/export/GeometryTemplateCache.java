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
 * Thread-safe cache for reusable local-space geometry templates.
 *
 * A logical block state alone is not a safe cache key: Minecraft weighted
 * variants are selected deterministically from world coordinates, so equal
 * properties can resolve to different models. Callers must therefore provide
 * the resolved render-variant signature (normally the selected model
 * application/model-set signature) in addition to the canonical state.
 *
 * This keeps templates reusable across positions that genuinely resolve to the
 * same geometry without allowing coordinate-dependent weighted variants to
 * alias each other.
 */
public final class GeometryTemplateCache {
    private final ConcurrentHashMap<Key, GeometryTemplate> templates = new ConcurrentHashMap<>();

    /** Return a cached template, creating it atomically on the first request. */
    public GeometryTemplate getOrCreate(
        BlockData block,
        String renderVariantKey,
        Function<BlockData, GeometryTemplate> compiler
    ) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(renderVariantKey, "renderVariantKey");
        Objects.requireNonNull(compiler, "compiler");

        Key key = Key.from(block, renderVariantKey);
        return templates.computeIfAbsent(key, ignored -> Objects.requireNonNull(
            compiler.apply(block), "geometry compiler returned null"));
    }

    public GeometryTemplate get(BlockData block, String renderVariantKey) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(renderVariantKey, "renderVariantKey");
        return templates.get(Key.from(block, renderVariantKey));
    }

    public void clear() {
        templates.clear();
    }

    public int size() {
        return templates.size();
    }

    /** Number of cached state+resolved-variant templates currently represented. */
    public int stateCount() {
        return templates.size();
    }

    /**
     * Stable key for a logical state plus the exact render variant selected for
     * that occurrence. Property ordering does not affect the state portion.
     */
    public record Key(String blockId, String stateKey, String renderVariantKey) {
        public Key {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(stateKey, "stateKey");
            Objects.requireNonNull(renderVariantKey, "renderVariantKey");
        }

        public static Key from(BlockData block, String renderVariantKey) {
            Objects.requireNonNull(block, "block");
            return new Key(
                block.blockId,
                canonicalState(block.properties),
                Objects.requireNonNull(renderVariantKey, "renderVariantKey")
            );
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
