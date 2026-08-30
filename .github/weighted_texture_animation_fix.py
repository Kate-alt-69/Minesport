from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


path = Path("engine/src/main/java/dev/kastrick/minesport/export/TextureAnimationExporter.java")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''import com.google.gson.JsonParser;\nimport dev.kastrick.minesport.region.BlockData;''',
    '''import com.google.gson.JsonParser;\nimport dev.kastrick.minesport.model.BlockState;\nimport dev.kastrick.minesport.region.BlockData;''',
    "BlockState import",
)

text = replace_once(
    text,
    '''        Set<String> seenStates = new HashSet<>();
        Map<String, MaterialKey> materials = new LinkedHashMap<>();
        for (BlockData block : blocks) {
            if (block == null || block.isAir()) continue;
            String stateKey = block.blockId + "[" + BlockGrouper.stateKey(block.properties) + "]";
            if (!seenStates.add(stateKey)) continue;
            List<Quad> quads;''',
    '''        Set<String> seenVariants = new HashSet<>();
        Map<String, BlockState> resolvedStates = new LinkedHashMap<>();
        Set<String> unresolvedBlockStates = new HashSet<>();
        Map<String, MaterialKey> materials = new LinkedHashMap<>();
        ResolverChain resolvers = geometry.getResolvers();
        for (BlockData block : blocks) {
            if (block == null || block.isAir()) continue;
            String discoveryKey = materialDiscoveryKey(
                block,
                resolvers,
                resolvedStates,
                unresolvedBlockStates
            );
            if (!seenVariants.add(discoveryKey)) continue;
            List<Quad> quads;''',
    "coordinate-aware animation discovery",
)

text = replace_once(
    text,
    '''        return materials;
    }

    static JsonObject describeMaterial(MaterialKey material, ResolverChain resolvers) {''',
    '''        return materials;
    }

    /**
     * Deduplicate animation discovery by the model application that this exact
     * coordinate resolves to, not merely by logical block state. Minecraft's
     * weighted variants deliberately use position as part of their stable
     * selection, so two equal states can legitimately render different models
     * (and therefore different animated textures).
     */
    private static String materialDiscoveryKey(
        BlockData block,
        ResolverChain resolvers,
        Map<String, BlockState> resolvedStates,
        Set<String> unresolvedBlockStates
    ) {
        String stateKey = block.blockId + "[" + BlockGrouper.stateKey(block.properties) + "]";
        if (resolvers == null || unresolvedBlockStates.contains(block.blockId)) return stateKey;

        BlockState state = resolvedStates.get(block.blockId);
        if (state == null) {
            try {
                state = resolvers.resolveBlockState(block.blockId);
            } catch (Exception ignored) {
                state = null;
            }
            if (state == null) {
                unresolvedBlockStates.add(block.blockId);
                return stateKey;
            }
            resolvedStates.put(block.blockId, state);
        }

        List<BlockState.ModelApplication> applications;
        try {
            applications = state.resolve(
                block.properties,
                block.x,
                block.y,
                block.z
            );
        } catch (Exception ignored) {
            return stateKey;
        }
        if (applications == null || applications.isEmpty()) return stateKey;

        StringBuilder signature = new StringBuilder(stateKey).append("|models=");
        for (BlockState.ModelApplication application : applications) {
            if (application == null) continue;
            signature.append(application.modelPath == null ? "" : application.modelPath)
                .append(';');
        }
        return signature.toString();
    }

    static JsonObject describeMaterial(MaterialKey material, ResolverChain resolvers) {''',
    "animation model discovery key helper",
)

path.write_text(text, encoding="utf-8")


test = Path("engine/src/test/java/dev/kastrick/minesport/export/TextureAnimationWeightedVariantTest.java")
if test.exists():
    raise SystemExit("TextureAnimationWeightedVariantTest.java already exists")

test.write_text('''package dev.kastrick.minesport.export;

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
                ? "{\\\"animation\\\":{\\\"frametime\\\":1}}"
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
''', encoding="utf-8")

print("Made texture animation discovery weighted-variant aware")
