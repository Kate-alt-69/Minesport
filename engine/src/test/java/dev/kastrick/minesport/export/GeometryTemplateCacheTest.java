package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GeometryTemplateCacheTest {
    @Test
    void propertyOrderingStillSharesTheSameResolvedVariantTemplate() {
        GeometryTemplateCache cache = new GeometryTemplateCache();
        AtomicInteger compiled = new AtomicInteger();
        BlockData first = new BlockData(
            1, 64, 1,
            "minecraft:test",
            Map.of("facing", "north", "powered", "false")
        );
        BlockData second = new BlockData(
            40, 80, -7,
            "minecraft:test",
            Map.of("powered", "false", "facing", "north")
        );

        GeometryTemplate one = cache.getOrCreate(first, "model=test_a;x=0;y=0", block -> {
            compiled.incrementAndGet();
            return new GeometryTemplate(BlockGeometryKind.PARTIAL_BLOCK, List.of());
        });
        GeometryTemplate two = cache.getOrCreate(second, "model=test_a;x=0;y=0", block -> {
            compiled.incrementAndGet();
            return new GeometryTemplate(BlockGeometryKind.PARTIAL_BLOCK, List.of());
        });

        assertSame(one, two);
        assertEquals(1, compiled.get());
        assertEquals(1, cache.size());
    }

    @Test
    void equalLogicalStatesDoNotAliasDifferentWeightedModelSelections() {
        GeometryTemplateCache cache = new GeometryTemplateCache();
        BlockData first = new BlockData(0, 64, 0, "minecraft:test", Map.of("age", "0"));
        BlockData second = new BlockData(1, 64, 0, "minecraft:test", Map.of("age", "0"));

        GeometryTemplate modelA = cache.getOrCreate(
            first,
            "model=weighted_a;x=0;y=0",
            block -> new GeometryTemplate(BlockGeometryKind.PARTIAL_BLOCK, List.of())
        );
        GeometryTemplate modelB = cache.getOrCreate(
            second,
            "model=weighted_b;x=0;y=0",
            block -> new GeometryTemplate(BlockGeometryKind.FULL_BLOCK, List.of())
        );

        assertNotEquals(
            GeometryTemplateCache.Key.from(first, "model=weighted_a;x=0;y=0"),
            GeometryTemplateCache.Key.from(second, "model=weighted_b;x=0;y=0")
        );
        assertEquals(2, cache.size());
        assertSame(modelA, cache.get(first, "model=weighted_a;x=0;y=0"));
        assertSame(modelB, cache.get(second, "model=weighted_b;x=0;y=0"));
    }
}
