package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GltfIndividualOriginTest {
    @TempDir Path temp;

    @Test
    void individualNodesUseTheirMinecraftBlockCentersAsOrigins() throws Exception {
        withFlatterDisabled(() -> {
            ResolverChain chain = new ResolverChain();
            List<BlockData> blocks = List.of(
                new BlockData(10, 0, 0, "test:missing", Map.of()),
                new BlockData(12, 0, 0, "test:missing", Map.of())
            );
            File gltf = temp.resolve("individual-origins.gltf").toFile();

            new GltfExporter(chain).export(
                blocks,
                new GeometryBuilder(chain),
                gltf,
                ObjExporter.ExportMode.INDIVIDUAL,
                false,
                null
            );

            JsonObject root = read(gltf);
            JsonObject left = node(root, BlockGrouper.physicalName(blocks.get(0)));
            JsonObject right = node(root, BlockGrouper.physicalName(blocks.get(1)));

            assertTranslation(left, -1f, 0f, 0f);
            assertTranslation(right, 1f, 0f, 0f);
            assertBlockCenterExtra(left, 10.5f, 0.5f, 0.5f);
            assertBlockCenterExtra(right, 12.5f, 0.5f, 0.5f);

            // The mesh itself is now local to its own one-block cell. Combining
            // these local coordinates with the node translation preserves the
            // exact same visible world-space geometry as the old exporter.
            assertPositionBounds(root, left, -.5f, .5f);
            assertPositionBounds(root, right, -.5f, .5f);
        });
    }

    @Test
    void individualChestPartsShareTheChestBlockCenter() throws Exception {
        withFlatterDisabled(() -> {
            ResolverChain chain = new ResolverChain();
            BlockData chest = new BlockData(
                4, 10, -3,
                "minecraft:trapped_chest",
                Map.of("facing", "north", "type", "single", "waterlogged", "false")
            );
            BlockData distant = new BlockData(8, 10, -3, "test:missing", Map.of());
            File gltf = temp.resolve("chest-parts.gltf").toFile();

            new GltfExporter(chain).export(
                List.of(chest, distant),
                new GeometryBuilder(chain),
                gltf,
                ObjExporter.ExportMode.INDIVIDUAL,
                false,
                null
            );

            JsonObject root = read(gltf);
            JsonObject base = node(root, BlockGrouper.partName(chest, "base"));
            JsonObject lid = node(root, BlockGrouper.partName(chest, "lid"));

            // Export bounds span X=4..9, so the export center is X=6.5. The
            // chest block center is X=4.5, therefore both chest part origins are
            // translated to X=-2 relative to the export root.
            assertTranslation(base, -2f, 0f, 0f);
            assertTranslation(lid, -2f, 0f, 0f);
            assertBlockCenterExtra(base, 4.5f, 10.5f, -2.5f);
            assertBlockCenterExtra(lid, 4.5f, 10.5f, -2.5f);
        });
    }

    private static JsonObject read(File gltf) throws Exception {
        try (FileReader reader = new FileReader(gltf)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject node(JsonObject root, String name) {
        for (var element : root.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (node.has("name") && name.equals(node.get("name").getAsString())) return node;
        }
        fail("Missing glTF node: " + name);
        return null;
    }

    private static void assertTranslation(JsonObject node, float x, float y, float z) {
        JsonArray translation = node.getAsJsonArray("translation");
        assertNotNull(translation, "individual node should carry a local origin translation");
        assertEquals(x, translation.get(0).getAsFloat(), 1e-6f);
        assertEquals(y, translation.get(1).getAsFloat(), 1e-6f);
        assertEquals(z, translation.get(2).getAsFloat(), 1e-6f);
    }

    private static void assertBlockCenterExtra(JsonObject node, float x, float y, float z) {
        JsonArray center = node.getAsJsonObject("extras").getAsJsonArray("minesportBlockCenter");
        assertNotNull(center);
        assertEquals(x, center.get(0).getAsFloat(), 1e-6f);
        assertEquals(y, center.get(1).getAsFloat(), 1e-6f);
        assertEquals(z, center.get(2).getAsFloat(), 1e-6f);
    }

    private static void assertPositionBounds(JsonObject root, JsonObject node, float expectedMin, float expectedMax) {
        JsonObject mesh = root.getAsJsonArray("meshes").get(node.get("mesh").getAsInt()).getAsJsonObject();
        JsonObject primitive = mesh.getAsJsonArray("primitives").get(0).getAsJsonObject();
        int accessorIndex = primitive.getAsJsonObject("attributes").get("POSITION").getAsInt();
        JsonObject accessor = root.getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
        JsonArray min = accessor.getAsJsonArray("min");
        JsonArray max = accessor.getAsJsonArray("max");
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(expectedMin, min.get(axis).getAsFloat(), 1e-6f);
            assertEquals(expectedMax, max.get(axis).getAsFloat(), 1e-6f);
        }
    }

    private static void withFlatterDisabled(ThrowingRunnable body) throws Exception {
        String previous = System.getProperty("minesport.flatter");
        System.setProperty("minesport.flatter", "false");
        try {
            body.run();
        } finally {
            if (previous == null) System.clearProperty("minesport.flatter");
            else System.setProperty("minesport.flatter", previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
