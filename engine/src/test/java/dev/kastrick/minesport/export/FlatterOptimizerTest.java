package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.AssetResolver;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlatterOptimizerTest {
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
            BlockModel.Element cube = new BlockModel.Element();
            cube.from = new float[]{0f, 0f, 0f};
            cube.to = new float[]{16f, 16f, 16f};
            for (String direction : List.of("north", "south", "east", "west", "up", "down")) {
                BlockModel.Face face = new BlockModel.Face();
                face.texture = "minecraft:block/stone";
                face.cullface = direction;
                cube.faces.put(direction, face);
            }
            model.elements.add(cube);
            return model;
        }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, 0xffffffff);
                }
            }
            return image;
        }

        @Override public String name() { return "FLATTER test resolver"; }
    }
}
