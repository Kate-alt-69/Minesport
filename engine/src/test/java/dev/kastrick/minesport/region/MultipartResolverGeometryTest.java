package dev.kastrick.minesport.region;

import dev.kastrick.minesport.GeometryBuilder;
import dev.kastrick.minesport.export.BlockGeometryClassifier;
import dev.kastrick.minesport.export.ExportWorldContext;
import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.resolver.AssetResolver;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartResolverGeometryTest {
    @AfterEach
    void clearWorldContext() {
        ExportWorldContext.clear();
    }

    @Test
    void exportBuilderConnectsOnlyVerifiedFullCubeNeighbours() {
        BlockData fence = block(0, 64, 0, "minecraft:oak_fence");
        BlockData stone = block(1, 64, 0, "test:full_block");
        BlockData stairs = block(-1, 64, 0, "test:stairs");
        BlockData unresolved = block(0, 64, -1, "test:unresolved");
        List<BlockData> blocks = List.of(fence, stone, stairs, unresolved);

        // Before assets exist, ordinary neighbours fail closed while the world
        // map is published for the real export builder.
        MultipartResolver.resolve(blocks);
        assertFalse(fence.connectEast);
        assertFalse(fence.connectWest);
        assertFalse(fence.connectNorth);

        try (ResolverChain chain = new ResolverChain()) {
            chain.addResolver(new GeometryResolver());
            new GeometryBuilder(chain);

            assertTrue(fence.connectEast, "resolved full cube should connect");
            assertFalse(fence.connectWest, "partial stair model must not connect as a solid cube");
            assertFalse(fence.connectNorth, "unresolved model must fail closed");
        }
    }

    @Test
    void multipartGroupsStillConnectBeforeAssetResolution() {
        BlockData first = block(0, 64, 0, "minecraft:oak_fence");
        BlockData second = block(1, 64, 0, "minecraft:spruce_fence");
        BlockData stairs = block(-1, 64, 0, "test:stairs");

        MultipartResolver.resolve(List.of(first, second, stairs));

        assertTrue(first.connectEast, "known fence topology does not need model resolution");
        assertFalse(first.connectWest, "ordinary blocks must not use the old blacklist heuristic");
    }

    @Test
    void directGeometryPassUsesCoordinateAwareClassifier() {
        BlockData fence = block(0, 64, 0, "minecraft:oak_fence");
        BlockData full = block(1, 64, 0, "test:full_block");
        Map<Long, BlockData> index = new HashMap<>();
        index.put(dev.kastrick.minesport.export.SpatialKey.of(fence.x, fence.y, fence.z), fence);
        index.put(dev.kastrick.minesport.export.SpatialKey.of(full.x, full.y, full.z), full);

        try (ResolverChain chain = new ResolverChain()) {
            chain.addResolver(new GeometryResolver());
            MultipartResolver.resolveIndex(index, new BlockGeometryClassifier(chain));
            assertTrue(fence.connectEast);
        }
    }

    private static BlockData block(int x, int y, int z, String id) {
        return new BlockData(x, y, z, id, Map.of());
    }

    private static final class GeometryResolver implements AssetResolver {
        @Override
        public boolean canResolve(String blockId) {
            return blockId != null && blockId.startsWith("test:");
        }

        @Override
        public BlockState resolveBlockState(String blockId) {
            if (blockId == null || blockId.endsWith("unresolved")) return null;
            BlockState state = new BlockState();
            state.format = BlockState.Format.VARIANTS;
            BlockState.ModelApplication application = new BlockState.ModelApplication();
            application.modelPath = blockId.endsWith("stairs") ? "test:partial" : "test:full";
            state.variants.put("", List.of(application));
            return state;
        }

        @Override
        public BlockModel resolveModel(String modelPath) {
            if (modelPath == null) return null;
            if (modelPath.endsWith("partial")) return cube(8f);
            if (modelPath.endsWith("full")) return cube(16f);
            return null;
        }

        private static BlockModel cube(float top) {
            BlockModel model = new BlockModel();
            BlockModel.Element element = new BlockModel.Element();
            element.from = new float[]{0f, 0f, 0f};
            element.to = new float[]{16f, top, 16f};
            for (String face : List.of("north", "south", "east", "west", "up", "down")) {
                element.faces.put(face, new BlockModel.Face());
            }
            model.elements.add(element);
            return model;
        }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            return null;
        }

        @Override
        public String name() {
            return "multipart-test";
        }
    }
}
