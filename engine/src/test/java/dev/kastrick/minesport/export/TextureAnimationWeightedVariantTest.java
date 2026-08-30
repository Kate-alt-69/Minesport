package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TextureAnimationWeightedVariantTest {
    @Test
    void equalStatesAtDifferentCoordinatesDiscoverBothWeightedAnimatedMaterials() {
        AnimationResolver resolvers = new AnimationResolver();
        try {
            BlockData first = null;
            BlockData second = null;
            String firstModel = null;

            // Find two deterministic coordinates that choose different weighted
            // entries. The production selector is stable but intentionally
            // position-dependent, so hard-coding a magic coordinate is brittle.
            for (int x = 0; x < 4096 && second == null; x++) {
                BlockData candidate = new BlockData(x, 64, 0, "test:weighted", Map.of());
                String model = resolvers.state.resolve(Map.of(), x, 64, 0).getFirst().modelPath;
                if (first == null) {
                    first = candidate;
                    firstModel = model;
                } else if (!firstModel.equals(model)) {
                    second = candidate;
                }
            }

            assertNotNull(first);
            assertNotNull(second, "test must exercise both weighted model choices");

            WeightedGeometryBuilder geometry = new WeightedGeometryBuilder(resolvers);
            JsonArray descriptors = TextureAnimationExporter.describe(
                List.of(first, second),
                geometry,
                true
            );

            Set<String> textures = new HashSet<>();
            descriptors.forEach(element ->
                textures.add(element.getAsJsonObject().get("texture").getAsString())
            );

            assertEquals(Set.of("test:animated_a", "test:animated_b"), textures);
        } finally {
            resolvers.close();
        }
    }

    private static final class AnimationResolver extends ResolverChain {
        final BlockState state = weightedState();

        @Override
        public BlockState resolveBlockState(String blockId) {
            return "test:weighted".equals(blockId) ? state : null;
        }

        @Override
        public String resolveTextureMetadata(String texturePath) {
            return texturePath != null && texturePath.startsWith("test:animated_")
                ? "{\"animation\":{\"frametime\":1}}"
                : null;
        }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            return texturePath != null && texturePath.startsWith("test:animated_")
                ? new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB)
                : null;
        }

        private static BlockState weightedState() {
            BlockState state = new BlockState();
            state.format = BlockState.Format.VARIANTS;

            BlockState.ModelApplication a = new BlockState.ModelApplication();
            a.modelPath = "test:block/variant_a";
            a.weight = 1;

            BlockState.ModelApplication b = new BlockState.ModelApplication();
            b.modelPath = "test:block/variant_b";
            b.weight = 1;

            state.variants.put("", List.of(a, b));
            return state;
        }
    }

    private static final class WeightedGeometryBuilder extends GeometryBuilder {
        private final AnimationResolver resolvers;

        WeightedGeometryBuilder(AnimationResolver resolvers) {
            super(resolvers);
            this.resolvers = resolvers;
        }

        @Override
        public List<Quad> buildBlock(BlockData block) {
            String model = resolvers.state.resolve(
                block.properties,
                block.x,
                block.y,
                block.z
            ).getFirst().modelPath;
            String texture = model.endsWith("variant_a")
                ? "test:animated_a"
                : "test:animated_b";
            return List.of(new Quad(
                new float[][]{
                    {0f, 0f, 0f},
                    {1f, 0f, 0f},
                    {1f, 1f, 0f},
                    {0f, 1f, 0f}
                },
                new float[]{0f, 0f, 16f, 16f},
                texture,
                new float[]{0f, 0f, 1f},
                null,
                -1
            ));
        }
    }
}
