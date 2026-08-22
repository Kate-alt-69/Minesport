package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeModelRegistryTest {
    private static final String QUAD = """
        {
          "vertices": [
            0,0,0, 0,0,-1, 0,0,
            1,0,0, 0,0,-1, 1,0,
            1,1,0, 0,0,-1, 1,1,
            0,1,0, 0,0,-1, 0,1
          ],
          "textureId": "minecraft:block/grass_block_side",
          "face": 2,
          "shade": true,
          "tintIndex": -1
        }
        """;

    @Test
    void runtimeRegistryCanDriveVanillaAndModdedBlocks() throws Exception {
        File snapshot = File.createTempFile("minesport-runtime-models-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 3,
              "minecraftVersion": "1.21.10",
              "modsFingerprint": "test",
              "blocks": {
                "minecraft:grass_block": {
                  "loaderType": "vanilla",
                  "variants": [
                    {"properties": {"snowy": "false"}, "quads": [%s]}
                  ]
                },
                "example:block": {
                  "loaderType": "fabric",
                  "variants": [
                    {"properties": {}, "quads": [%s]}
                  ]
                }
              }
            }
            """.formatted(QUAD, QUAD));

        RuntimeModelRegistry registry = RuntimeModelRegistry.load(
            snapshot,
            "1.21.10",
            null
        );
        assertNotNull(registry);

        BlockData vanilla = new BlockData(
            10, 64, -3,
            "minecraft:grass_block",
            Map.of("snowy", "false")
        );
        BlockData modded = new BlockData(
            0, 0, 0,
            "example:block",
            Map.of()
        );

        assertTrue(registry.shouldOverride(vanilla));
        assertTrue(registry.shouldOverride(modded));
        assertEquals(RuntimeModelRegistry.StateKind.BAKED, registry.stateKind(vanilla));
        assertEquals(RuntimeModelRegistry.StateKind.BAKED, registry.stateKind(modded));
        assertFalse(registry.shouldOverride(new BlockData(
            0, 0, 0,
            "minecraft:grass_block",
            Map.of("snowy", "true")
        )));

        var quads = registry.build(vanilla);
        assertNotNull(quads);
        assertEquals(1, quads.size());
        assertNull(quads.get(0).partName(), "runtime provenance must not create fake model-part objects");
        assertEquals("minecraft:block/grass_block_side", quads.get(0).texturePath());

        float[][] vertices = quads.get(0).verts();
        assertEquals(10f, vertices[0][0], 1e-6f);
        assertEquals(64f, vertices[0][1], 1e-6f);
        assertEquals(-3f, vertices[0][2], 1e-6f);
    }

    @Test
    void knownEmptyRuntimeStateDoesNotBecomeStaticFallbackCube() throws Exception {
        File snapshot = File.createTempFile("minesport-runtime-empty-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 3,
              "minecraftVersion": "1.21.10",
              "modsFingerprint": "test",
              "blocks": {
                "example:dynamic_block": {
                  "loaderType": "fabric",
                  "variants": [
                    {"properties": {"powered": "false"}, "quads": []}
                  ]
                }
              }
            }
            """);

        RuntimeModelRegistry registry = RuntimeModelRegistry.load(snapshot, "1.21.10", null);
        assertNotNull(registry);

        BlockData knownEmpty = new BlockData(
            0, 0, 0,
            "example:dynamic_block",
            Map.of("powered", "false")
        );
        assertEquals(RuntimeModelRegistry.StateKind.EMPTY_BAKED_MODEL, registry.stateKind(knownEmpty));
        assertTrue(registry.shouldOverride(knownEmpty));
        assertNotNull(registry.build(knownEmpty));
        assertTrue(registry.build(knownEmpty).isEmpty());

        knownEmpty.runtimeRegistryPath = snapshot.getAbsolutePath();
        var geometry = new dev.kastrick.minesport.GeometryBuilder(new ResolverChain());
        assertTrue(
            geometry.buildBlock(knownEmpty).isEmpty(),
            "known empty runtime state must not fall through to static/fallback geometry"
        );

        BlockData unknownState = new BlockData(
            0, 0, 0,
            "example:dynamic_block",
            Map.of("powered", "true")
        );
        assertEquals(RuntimeModelRegistry.StateKind.UNKNOWN, registry.stateKind(unknownState));
        assertFalse(registry.shouldOverride(unknownState));
    }

    @Test
    void rejectsPreLocalUvSchemaTwoSnapshot() throws Exception {
        File snapshot = File.createTempFile("minesport-runtime-models-old-uv-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 2,
              "minecraftVersion": "1.21.10",
              "modsFingerprint": "test",
              "blocks": {
                "minecraft:grass_block": {
                  "loaderType": "vanilla",
                  "variants": [{"properties": {"snowy": "false"}, "quads": [%s]}]
                }
              }
            }
            """.formatted(QUAD));

        assertNull(RuntimeModelRegistry.load(snapshot, "1.21.10", null),
            "schema 2 stored atlas-space UVs and must never be reused");
    }

    @Test
    void rejectsRuntimeSnapshotFromWrongMinecraftVersion() throws Exception {
        File snapshot = File.createTempFile("minesport-runtime-models-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 3,
              "minecraftVersion": "1.21.11",
              "modsFingerprint": "test",
              "blocks": {
                "minecraft:stone": {
                  "loaderType": "vanilla",
                  "variants": [{"properties": {}, "quads": [%s]}]
                }
              }
            }
            """.formatted(QUAD));

        assertNull(RuntimeModelRegistry.load(snapshot, "1.21.10", null));
    }
}
