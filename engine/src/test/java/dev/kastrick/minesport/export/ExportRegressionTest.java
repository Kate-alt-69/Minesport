package dev.kastrick.minesport.export;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.AssetResolver;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ExportRegressionTest {
    @TempDir Path temp;

    @Test
    void fullBlockOccupiesExactlyOneMetreCell() {
        GeometryBuilder builder = new GeometryBuilder(new ResolverChain());
        List<Quad> quads = builder.buildBlock(new BlockData(0, 0, 0, "test:missing", Map.of()));

        assertEquals(6, quads.size());
        float[] bounds = bounds(quads);
        assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, bounds, 1e-6f);
    }

    @Test
    void chestUsesSeparateAtlasMappedBaseAndLidParts() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FixtureResolver(false));
        GeometryBuilder builder = new GeometryBuilder(chain);
        BlockData chest = new BlockData(
            0, 0, 0,
            "minecraft:trapped_chest",
            Map.of("facing", "north", "type", "single", "waterlogged", "false")
        );

        List<Quad> quads = builder.buildBlock(chest);
        assertEquals(18, quads.size());
        assertEquals(6, quads.stream().filter(q -> "base".equals(q.partName())).count());
        assertEquals(12, quads.stream().filter(q -> "lid".equals(q.partName())).count());
        assertTrue(quads.stream().flatMap(q -> Arrays.stream(q.vertexUVs()))
            .allMatch(uv -> uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1));
        assertTrue(quads.stream().allMatch(q -> q.u2() - q.u1() < 1f));
        assertTrue(quads.stream().allMatch(q -> q.v2() - q.v1() < 1f));
    }

    @Test
    void gltfKeepsTopLeftUvsAndUsesCutoutMaterials() throws Exception {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FixtureResolver(true));
        GeometryBuilder builder = new GeometryBuilder(chain);
        File gltf = temp.resolve("fixture.gltf").toFile();

        new GltfExporter(chain).export(
            List.of(new BlockData(0, 0, 0, "test:fixture", Map.of())),
            builder,
            gltf,
            ObjExporter.ExportMode.INDIVIDUAL,
            false,
            null
        );

        JsonObject root;
        try (FileReader reader = new FileReader(gltf)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject material = root.getAsJsonArray("materials").get(0).getAsJsonObject();
        assertEquals("MASK", material.get("alphaMode").getAsString());
        assertTrue(material.get("doubleSided").getAsBoolean());

        JsonObject primitive = root.getAsJsonArray("meshes").get(0).getAsJsonObject()
            .getAsJsonArray("primitives").get(0).getAsJsonObject();
        int accessorIndex = primitive.getAsJsonObject("attributes").get("TEXCOORD_0").getAsInt();
        JsonObject accessor = root.getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
        JsonObject view = root.getAsJsonArray("bufferViews")
            .get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
        byte[] bin = Files.readAllBytes(temp.resolve("fixture.bin"));
        ByteBuffer buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(view.get("byteOffset").getAsInt());

        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < accessor.get("count").getAsInt(); i++) {
            buffer.getFloat();
            float v = buffer.getFloat();
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        assertEquals(0f, minV, 1e-6f);
        assertEquals(.5f, maxV, 1e-6f);
    }

    @Test
    void chestLidObjectAndBoneDescriptorUseTheSameStableName() throws Exception {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FixtureResolver(false));
        BlockData chest = new BlockData(
            4, 10, -3,
            "minecraft:chest",
            Map.of("facing", "east", "type", "single", "waterlogged", "false")
        );
        List<BlockData> blocks = List.of(chest);
        File obj = temp.resolve("chest.obj").toFile();

        ObjExporter.exportWithGeometry(
            blocks,
            new GeometryBuilder(chain),
            obj,
            ObjExporter.ExportMode.ALL_MERGED,
            false,
            null
        );
        File metadata = BlenderMetadataExporter.write(
            obj,
            blocks,
            ObjExporter.ExportMode.ALL_MERGED,
            "obj",
            "animate_export"
        );

        String lidName = BlockGrouper.partName(chest, "lid");
        String baseName = BlockGrouper.partName(chest, "base");
        String objText = Files.readString(obj.toPath());
        assertTrue(objText.contains("o " + lidName));
        assertTrue(objText.contains("o " + baseName));

        JsonObject sidecar;
        try (FileReader reader = new FileReader(metadata)) {
            sidecar = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject animation = sidecar.getAsJsonArray("animations").get(0).getAsJsonObject();
        assertEquals("rigid_bone", animation.get("kind").getAsString());
        assertEquals(lidName, animation.get("object").getAsString());
        assertEquals(baseName, animation.get("baseObject").getAsString());
        assertEquals("Y", animation.get("axis").getAsString());
        assertEquals(1.0, sidecar.get("metresPerBlock").getAsDouble(), 1e-9);
    }

    @Test
    void optimizedObjActuallyWeldsPositionRecords() throws Exception {
        ResolverChain chain = new ResolverChain();
        List<BlockData> blocks = List.of(
            new BlockData(0, 0, 0, "test:missing", Map.of()),
            new BlockData(1, 0, 0, "test:missing", Map.of())
        );
        File obj = temp.resolve("welded.obj").toFile();

        ObjExporter.ExportStats stats = ObjExporter.exportWithGeometry(
            blocks,
            new GeometryBuilder(chain),
            obj,
            ObjExporter.ExportMode.ALL_MERGED,
            true,
            null
        );

        long writtenVertices;
        try (var lines = Files.lines(obj.toPath())) {
            writtenVertices = lines.filter(line -> line.startsWith("v ")).count();
        }
        assertEquals(12, stats.quadCount());
        assertEquals(12, writtenVertices);
        assertEquals(writtenVertices, stats.vertexCount());
        assertTrue(stats.vertexCount() < stats.quadCount() * 4);
    }

    @Test
    void faceCullingRemovesTheCoveredFacesBetweenAdjacentBlocks() throws Exception {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FixtureResolver(true));
        List<BlockData> blocks = List.of(
            new BlockData(0, 0, 0, "test:full_cube", Map.of()),
            new BlockData(1, 0, 0, "test:full_cube", Map.of())
        );
        GeometryBuilder builder = new GeometryBuilder(chain);
        builder.enableFaceCulling(blocks);

        ObjExporter.ExportStats stats = ObjExporter.exportWithGeometry(
            blocks,
            builder,
            temp.resolve("culled.obj").toFile(),
            ObjExporter.ExportMode.ALL_MERGED,
            true,
            null
        );

        assertEquals(10, stats.quadCount());
    }

    @Test
    void airBlocksNeverBecomeFallbackGeometry() throws Exception {
        ObjExporter.ExportStats stats = ObjExporter.exportWithGeometry(
            List.of(
                new BlockData(0, 0, 0, "minecraft:air", Map.of()),
                new BlockData(1, 0, 0, "minecraft:cave_air", Map.of()),
                new BlockData(2, 0, 0, "minecraft:void_air", Map.of())
            ),
            new GeometryBuilder(new ResolverChain()),
            temp.resolve("air.obj").toFile(),
            ObjExporter.ExportMode.ALL_MERGED,
            true,
            null
        );

        assertEquals(0, stats.blockCount());
        assertEquals(0, stats.quadCount());
        assertEquals(0, stats.vertexCount());
    }

    @Test
    void objWritesARealFallbackPngWhenTextureResolutionFails() throws Exception {
        File mtl = temp.resolve("missing.mtl").toFile();
        MtlExporter.export(
            java.util.Set.of(new MaterialKey("test:block/not_found", -1)),
            mtl,
            new ResolverChain()
        );

        Path png = temp.resolve("textures/test_block_not_found.png");
        assertTrue(Files.isRegularFile(png));
        BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
        assertNotNull(image);
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertTrue(Files.readString(mtl.toPath()).contains("map_Kd textures/test_block_not_found.png"));
    }

    private static float[] bounds(List<Quad> quads) {
        float[] result = {
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
        };
        for (Quad quad : quads) {
            for (float[] vertex : quad.verts()) {
                result[0] = Math.min(result[0], vertex[0]);
                result[1] = Math.min(result[1], vertex[1]);
                result[2] = Math.min(result[2], vertex[2]);
                result[3] = Math.max(result[3], vertex[0]);
                result[4] = Math.max(result[4], vertex[1]);
                result[5] = Math.max(result[5], vertex[2]);
            }
        }
        return result;
    }

    private static final class FixtureResolver implements AssetResolver {
        private final boolean model;

        private FixtureResolver(boolean model) {
            this.model = model;
        }

        @Override public boolean canResolve(String blockId) { return true; }

        @Override public BlockState resolveBlockState(String blockId) {
            if (!model || (!blockId.equals("test:fixture") && !blockId.equals("test:full_cube"))) return null;
            BlockState state = new BlockState();
            state.format = BlockState.Format.VARIANTS;
            BlockState.ModelApplication application = new BlockState.ModelApplication();
            application.modelPath = blockId.equals("test:full_cube")
                ? "test:block/full_cube"
                : "test:block/fixture";
            state.variants.put("", List.of(application));
            return state;
        }

        @Override public BlockModel resolveModel(String modelPath) {
            if (!model) return null;
            BlockModel result = new BlockModel();
            result.textures.put("all", "test:block/fixture");
            BlockModel.Element element = new BlockModel.Element();
            if (modelPath.equals("test:block/full_cube")) {
                for (String direction : List.of("north", "south", "east", "west", "up", "down")) {
                    BlockModel.Face face = new BlockModel.Face();
                    face.texture = "#all";
                    face.cullface = direction;
                    element.faces.put(direction, face);
                }
                result.elements.add(element);
                return result;
            }
            if (!modelPath.equals("test:block/fixture")) return null;
            BlockModel.Face face = new BlockModel.Face();
            face.texture = "#all";
            face.uv = new float[]{0, 0, 16, 8};
            element.faces.put("north", face);
            result.elements.add(element);
            return result;
        }

        @Override public BufferedImage resolveTexture(String texturePath) {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xffffffff);
            image.setRGB(1, 0, 0xffffffff);
            image.setRGB(0, 1, 0x00ffffff);
            image.setRGB(1, 1, 0xffffffff);
            return image;
        }

        @Override public String name() { return "fixture"; }
    }
}
