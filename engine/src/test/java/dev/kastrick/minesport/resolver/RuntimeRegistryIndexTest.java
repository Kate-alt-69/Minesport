package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRegistryIndexTest {
    @Test
    void unusedPoisonedBlockDoesNotPreventLazyTargetRead() throws Exception {
        float[] poisonedVertices = RuntimeRegistryDataTestSupport.unitNorthQuad();
        poisonedVertices[0] = Float.NaN;

        Map<String, RuntimeRegistryDataTestSupport.Block> blocks = new LinkedHashMap<>();
        blocks.put(
            "unused:poisoned",
            new RuntimeRegistryDataTestSupport.Block(
                "",
                "fabric",
                List.of(new RuntimeRegistryDataTestSupport.Variant(
                    Map.of("mode", "unused"),
                    List.of(new RuntimeRegistryDataTestSupport.Quad(
                        poisonedVertices,
                        "unused:block/poisoned",
                        2,
                        true,
                        -1
                    ))
                )),
                List.of()
            )
        );
        blocks.put(
            "example:target",
            new RuntimeRegistryDataTestSupport.Block(
                "",
                "fabric",
                List.of(new RuntimeRegistryDataTestSupport.Variant(
                    Map.of("powered", "true"),
                    List.of(new RuntimeRegistryDataTestSupport.Quad(
                        RuntimeRegistryDataTestSupport.unitNorthQuad(),
                        "example:block/target",
                        2,
                        true,
                        -1
                    ))
                )),
                List.of()
            )
        );

        File snapshot = RuntimeRegistryDataTestSupport.write("1.21.10", "lazy-test", blocks);

        RuntimeRegistryIndex index = RuntimeRegistryIndex.open(snapshot);
        assertEquals(2, index.blockCount());
        assertTrue(index.hasBlock("unused:poisoned"));
        assertTrue(index.hasBlock("example:target"));
        assertFalse(index.hasBlock("example:missing"));

        RuntimeRegistryDataReader.DataBlock target = index.readBlock("example:target");
        assertNotNull(target);
        assertEquals(1, target.variants().size());
        assertEquals("example:block/target", target.variants().get(0).quads().get(0).textureId());

        // RuntimeModelRegistry must likewise avoid decoding the unrelated bad
        // record. The target can be resolved and built from a direct seek.
        RuntimeModelRegistry registry = RuntimeModelRegistry.load(snapshot, "1.21.10", null);
        assertNotNull(registry);
        BlockData block = new BlockData(4, 70, -2, "example:target", Map.of("powered", "true"));
        assertEquals(RuntimeModelRegistry.StateKind.BAKED, registry.stateKind(block));
        assertEquals(1, registry.build(block).size());
    }

    @Test
    void hash64IsStableButFullKeyRemainsAuthoritative() {
        long first = RuntimeRegistryIndex.hash64("minecraft:oak_stairs|facing=east,half=bottom");
        long again = RuntimeRegistryIndex.hash64("minecraft:oak_stairs|facing=east,half=bottom");
        long different = RuntimeRegistryIndex.hash64("minecraft:oak_stairs|facing=west,half=bottom");
        assertEquals(first, again);
        assertNotEquals(first, different);
    }
}
