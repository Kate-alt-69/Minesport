package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.region.BlockData;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BridgeStateRegistryTest {
    @Test
    void appliesOnlyMatchingEmittingState() throws Exception {
        File snapshot = File.createTempFile("minesport-bridge-registry-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 1,
              "minecraftVersion": "1.21.10",
              "blocks": {
                "example:lamp": [
                  {"properties": {"lit": "true"}, "lightLevel": 12}
                ]
              }
            }
            """);

        var blocks = new ArrayList<BlockData>();
        blocks.add(new BlockData(1, 2, 3, "example:lamp", Map.of("lit", "true")));
        blocks.add(new BlockData(4, 5, 6, "example:lamp", Map.of("lit", "false")));

        int applied = BridgeStateRegistry.apply(snapshot, "1.21.10", blocks, null);

        assertEquals(1, applied);
        assertEquals("12", blocks.get(0).prop("minesport_light_level"));
        assertEquals("", blocks.get(1).prop("minesport_light_level"));
    }

    @Test
    void preservesMultipartFlagsWhenEnriching() throws Exception {
        File snapshot = File.createTempFile("minesport-bridge-registry-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 1,
              "minecraftVersion": "1.21.10",
              "blocks": {
                "example:glowing_fence": [
                  {"properties": {"powered": "true"}, "lightLevel": 7}
                ]
              }
            }
            """);

        var block = new BlockData(
            0, 64, 0,
            "example:glowing_fence",
            new LinkedHashMap<>(Map.of("powered", "true"))
        );
        block.isMultipart = true;
        block.connectNorth = true;
        block.connectUp = true;
        var blocks = new ArrayList<>(List.of(block));

        BridgeStateRegistry.apply(snapshot, "1.21.10", blocks, null);

        BlockData enriched = blocks.get(0);
        assertEquals("7", enriched.prop("minesport_light_level"));
        assertTrue(enriched.isMultipart);
        assertTrue(enriched.connectNorth);
        assertTrue(enriched.connectUp);
    }

    @Test
    void rejectsSnapshotFromAnotherMinecraftVersion() throws Exception {
        File snapshot = File.createTempFile("minesport-bridge-registry-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 1,
              "minecraftVersion": "1.21.11",
              "blocks": {
                "example:lamp": [
                  {"properties": {}, "lightLevel": 15}
                ]
              }
            }
            """);

        var blocks = new ArrayList<BlockData>();
        blocks.add(new BlockData(0, 0, 0, "example:lamp", Map.of()));

        assertEquals(0, BridgeStateRegistry.apply(snapshot, "1.21.10", blocks, null));
        assertEquals("", blocks.get(0).prop("minesport_light_level"));
    }
}
