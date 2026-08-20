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
            if (!model || !blockId.equals("test:fixture")) return null;
            BlockState state = new BlockState();
            state.format = BlockState.Format.VARIANTS;
            BlockState.ModelApplication application = new BlockState.ModelApplication();
            application.modelPath = "test:block/fixture";
            state.variants.put("", List.of(application));
            return state;
        }

        @Override public BlockModel resolveModel(String modelPath) {
            if (!model || !modelPath.equals("test:block/fixture")) return null;
            BlockModel result = new BlockModel();
            result.textures.put("all", "test:block/fixture");
            BlockModel.Element element = new BlockModel.Element();
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
