package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRegistryDataIntegrationTest {
    @Test
    void binaryRegistryDrivesGeometryAndKnownEmptyStates() throws Exception {
        File snapshot = RuntimeRegistryDataTestSupport.write(
            "1.21.10",
            "test-fingerprint",
            Map.of(
                "minecraft:grass_block",
                new RuntimeRegistryDataTestSupport.Block(
                    "minecraft:grass_block",
                    "vanilla",
                    List.of(new RuntimeRegistryDataTestSupport.Variant(
                        Map.of("snowy", "false"),
                        List.of(new RuntimeRegistryDataTestSupport.Quad(
                            RuntimeRegistryDataTestSupport.unitNorthQuad(),
                            "minecraft:block/grass_block_side",
                            2,
                            true,
                            -1
                        ))
                    )),
                    List.of()
                ),
                "example:dynamic_block",
                new RuntimeRegistryDataTestSupport.Block(
                    "",
                    "fabric",
                    List.of(new RuntimeRegistryDataTestSupport.Variant(
                        Map.of("powered", "false"),
                        List.of()
                    )),
                    List.of()
                )
            )
        );

        RuntimeModelRegistry registry = RuntimeModelRegistry.load(snapshot, "1.21.10", null);
        assertNotNull(registry);
        assertEquals(2, registry.blockTypeCount());
        assertEquals("test-fingerprint", registry.modsFingerprint());

        BlockData grass = new BlockData(10, 64, -3, "minecraft:grass_block", Map.of("snowy", "false"));
        assertEquals(RuntimeModelRegistry.StateKind.BAKED, registry.stateKind(grass));
        var quads = registry.build(grass);
        assertNotNull(quads);
        assertEquals(1, quads.size());
        assertEquals("minecraft:block/grass_block_side", quads.get(0).texturePath());
        assertEquals(10f, quads.get(0).verts()[0][0], 1e-6f);
        assertEquals(64f, quads.get(0).verts()[0][1], 1e-6f);
        assertEquals(-3f, quads.get(0).verts()[0][2], 1e-6f);

        BlockData empty = new BlockData(0, 0, 0, "example:dynamic_block", Map.of("powered", "false"));
        assertEquals(RuntimeModelRegistry.StateKind.EMPTY_BAKED_MODEL, registry.stateKind(empty));
        assertTrue(registry.shouldOverride(empty));
        assertNotNull(registry.build(empty));
        assertTrue(registry.build(empty).isEmpty());
    }

    @Test
    void binaryRegistryAppliesLightsAndTagsRuntimeGeometry() throws Exception {
        File snapshot = RuntimeRegistryDataTestSupport.write(
            "1.21.10",
            "test-fingerprint",
            Map.of(
                "example:lamp",
                new RuntimeRegistryDataTestSupport.Block(
                    "",
                    "fabric",
                    List.of(new RuntimeRegistryDataTestSupport.Variant(
                        Map.of("lit", "true"),
                        List.of(new RuntimeRegistryDataTestSupport.Quad(
                            RuntimeRegistryDataTestSupport.unitNorthQuad(),
                            "example:block/lamp",
                            2,
                            true,
                            -1
                        ))
                    )),
                    List.of(new RuntimeRegistryDataTestSupport.Light(Map.of("lit", "true"), 12))
                )
            )
        );

        var blocks = new ArrayList<BlockData>();
        blocks.add(new BlockData(1, 2, 3, "example:lamp", Map.of("lit", "true")));
        blocks.add(new BlockData(4, 5, 6, "example:lamp", Map.of("lit", "false")));

        int applied = BridgeStateRegistry.apply(snapshot, "1.21.10", blocks, null);
        assertEquals(1, applied);
        assertEquals("12", blocks.get(0).prop("minesport_light_level"));
        assertEquals("", blocks.get(1).prop("minesport_light_level"));
        assertEquals(snapshot.getAbsolutePath(), blocks.get(0).runtimeRegistryPath);
        assertEquals(snapshot.getAbsolutePath(), blocks.get(1).runtimeRegistryPath);
    }
}
