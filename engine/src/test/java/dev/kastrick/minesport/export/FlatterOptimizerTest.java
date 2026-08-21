package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.AssetResolver;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlatterOptimizerTest {
    @TempDir Path temp;

    @Test
    void fourAdjacentFullBlocksBecomeOneSixQuadFlatterCell() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new CubeResolver());
        List<BlockData> blocks = List.of(
            new BlockData(0, 64, 0, "minecraft:stone", Map.of()),
            new BlockData(1, 64, 0, "minecraft:stone", Map.of()),
            new BlockData(2, 64, 0, "minecraft:stone", Map.of()),
            new BlockData(3, 64, 0, "minecraft:stone", Map.of())
        );

        FlatterOptimizer.Result result = FlatterOptimizer.compile(blocks, chain);

        assertFalse(result.isEmpty());
        assertEquals(4, result.blockCount());
        assertEquals(1, result.objects().size());
        FlatterOptimizer.FlatterObject object = result.objects().getFirst();
        assertEquals(4, object.blockCount());
        assertEquals(6, object.quads().size(), "a solid 4x1x1 prism should greedy-mesh to six faces");
        assertEquals(1, object.palette().size());
        assertEquals(1, object.runs().size());
        assertEquals(4, object.runs().getFirst().length());
        for (BlockData block : blocks) assertTrue(result.contains(block));
    }

    @Test
    void twoByTwoByTwoVolumeUsesAllThreeDimensionsAndSixGreedyPlanes() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new CubeResolver());
        List<BlockData> blocks = new ArrayList<>();
        for (int y = 64; y < 66; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    blocks.add(new BlockData(x, y, z, "minecraft:stone", Map.of()));
                }
            }
        }

        FlatterOptimizer.Result result = FlatterOptimizer.compile(blocks, chain);

        assertFalse(result.isEmpty());
        assertEquals(8, result.blockCount());
        assertEquals(1, result.objects().size());
        FlatterOptimizer.FlatterObject object = result.objects().getFirst();
        assertArrayEquals(new int[]{2, 2, 2}, object.size());
        assertEquals(8, object.blockCount());
        assertEquals(6, object.quads().size(),
            "a solid 2x2x2 FLATTER volume should collapse to its six exterior planes");
        assertEquals(1, object.runs().size());
        assertEquals(8, object.runs().getFirst().length());
        for (BlockData block : blocks) assertTrue(result.contains(block));
    }

    @Test
    void layeredFullCubeKeepsTransparentOverlayButStillFlattens() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new LayeredCubeResolver());
        List<BlockData> blocks = List.of(
            new BlockData(0, 64, 0, "minecraft:grass_block", Map.of("snowy", "false")),
            new BlockData(1, 64, 0, "minecraft:grass_block", Map.of("snowy", "false"))
        );

        FlatterOptimizer.Result result = FlatterOptimizer.compile(blocks, chain);

        assertFalse(result.isEmpty());
        assertEquals(2, result.blockCount());
        FlatterOptimizer.FlatterObject object = result.objects().getFirst();
        assertEquals(10, object.quads().size(),
            "2-block grass prism should keep 6 opaque surfaces + 4 exposed overlay surfaces");
        assertEquals(2, object.palette().getFirst().faces().get("north").size());
        for (BlockData block : blocks) assertTrue(result.contains(block));
    }

    @Test
    void flatterRunsBeforeEveryObjAndGltfGroupingMode() throws Exception {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new CubeResolver());
        List<BlockData> blocks = List.of(
            new BlockData(0, 64, 0, "minecraft:stone", Map.of()),
            new BlockData(1, 64, 0, "minecraft:stone", Map.of())
        );

        String previous = System.getProperty("minesport.flatter");
        System.setProperty("minesport.flatter", "true");
        try {
            for (ObjExporter.ExportMode mode : ObjExporter.ExportMode.values()) {
                String stem = mode.name().toLowerCase();

                File obj = temp.resolve(stem + ".obj").toFile();
                ObjExporter.exportWithGeometry(
                    blocks,
                    new GeometryBuilder(chain),
                    obj,
                    mode,
                    false,
                    null
                );
                String objText = Files.readString(obj.toPath());
                assertTrue(objText.contains("# MINESPORT_TYPE FLATTER"),
                    "OBJ " + mode + " must keep FLATTER active");
                assertTrue(objText.contains("minesport_v1.5_active_export"),
                    "OBJ " + mode + " must advertise the 0.1.5+ active-export contract");
                Path sidecar = temp.resolve(stem + ".minesport.json");
                assertTrue(Files.isRegularFile(sidecar));
                String sidecarText = Files.readString(sidecar);
                assertTrue(sidecarText.contains("\"flatterVersion\": \"0.1.0\""));
                assertTrue(sidecarText.contains("\"minesport_v1.5_active_export\": true"));

                File gltf = temp.resolve(stem + ".gltf").toFile();
                new GltfExporter(chain).export(
                    blocks,
                    new GeometryBuilder(chain),
                    gltf,
                    mode,
                    false,
                    null
                );
                String gltfText = Files.readString(gltf.toPath());
                assertTrue(gltfText.contains("FLATTER"),
                    "glTF " + mode + " must keep FLATTER active");
                assertTrue(gltfText.contains("minesport_v1.5_active_export"),
                    "glTF " + mode + " must advertise the 0.1.5+ active-export contract");
            }
        } finally {
            if (previous == null) System.clearProperty("minesport.flatter");
            else System.setProperty("minesport.flatter", previous);
        }
    }

    private static BlockModel.Element cube(String texture, boolean allFaces, boolean sidesOnly) {
        BlockModel.Element cube = new BlockModel.Element();
        cube.from = new float[]{0f, 0f, 0f};
        cube.to = new float[]{16f, 16f, 16f};
        for (String direction : List.of("north", "south", "east", "west", "up", "down")) {
            if (sidesOnly && (direction.equals("up") || direction.equals("down"))) continue;
            if (!allFaces && !sidesOnly) continue;
            BlockModel.Face face = new BlockModel.Face();
            face.texture = texture;
            face.cullface = direction;
            cube.faces.put(direction, face);
        }
        return cube;
    }

    private static final class CubeResolver implements AssetResolver {
        @Override public boolean canResolve(String blockId) { return blockId.startsWith("minecraft:"); }

        @Override
        public BlockState resolveBlockState(String blockId) {
            BlockState state = new BlockState();
            state.format = BlockState.Format.VARIANTS;
            BlockState.ModelApplication app = new BlockState.ModelApplication();
            app.modelPath = "minecraft:block/stone";
            state.variants.put("", List.of(app));
            return state;
        }

        @Override
        public BlockModel resolveModel(String modelPath) {
            BlockModel model = new BlockModel();
            model.elements.add(cube("minecraft:block/stone", true, false));
            return model;
        }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            return opaqueImage();
        }

        @Override public String name() { return "FLATTER test resolver"; }
    }

    private static final class LayeredCubeResolver implements AssetResolver {
        @Override public boolean canResolve(String blockId) {
            return blockId.startsWith("minecraft:") || blockId.startsWith("test:");
        }

        @Override
        public BlockState resolveBlockState(String blockId) {
            BlockState state = new BlockState();
            state.format = BlockState.Format.VARIANTS;
            BlockState.ModelApplication app = new BlockState.ModelApplication();
            app.modelPath = "minecraft:block/layered_grass";
            app.y = 90;
            state.variants.put("", List.of(app));
            return state;
        }

        @Override
        public BlockModel resolveModel(String modelPath) {
            BlockModel model = new BlockModel();
            model.elements.add(cube("test:block/base", true, false));
            BlockModel.Element overlay = cube("test:block/overlay", false, true);
            for (BlockModel.Face face : overlay.faces.values()) face.tintindex = 0;
            model.elements.add(overlay);
            return model;
        }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            BufferedImage image = opaqueImage();
            if (texturePath.contains("overlay")) image.setRGB(0, 0, 0x00ffffff);
            return image;
        }

        @Override public String name() { return "FLATTER layered test resolver"; }
    }

    private static BufferedImage opaqueImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, 0xffffffff);
        }
        return image;
    }
}
