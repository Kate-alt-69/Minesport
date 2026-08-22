package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ResolverChainProvenanceTest {
    @Test
    void higherPriorityTextureResolverRecordsWinningSource() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new StubResolver("resource-pack", true));
        chain.addResolver(new StubResolver("vanilla", true));

        BufferedImage image = chain.resolveTexture("minecraft:block/stone");

        assertNotNull(image);
        assertEquals("resource-pack", chain.textureSource("minecraft:block/stone"));
        assertEquals(
            "resource-pack",
            chain.textureSourcesSnapshot().get("minecraft:block/stone")
        );
    }

    @Test
    void classicMissingTextureIsVisibleInProvenance() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new StubResolver("empty", false));

        BufferedImage image = chain.resolveTexture("minecraft:block/definitely_missing");

        assertNotNull(image);
        assertEquals(
            ResolverChain.CLASSIC_MISSING_SOURCE,
            chain.textureSource("minecraft:block/definitely_missing")
        );
    }

    private static final class StubResolver implements AssetResolver {
        private final String resolverName;
        private final boolean textureAvailable;

        private StubResolver(String resolverName, boolean textureAvailable) {
            this.resolverName = resolverName;
            this.textureAvailable = textureAvailable;
        }

        @Override
        public boolean canResolve(String blockId) {
            return blockId.startsWith("minecraft:");
        }

        @Override
        public BlockState resolveBlockState(String blockId) {
            return null;
        }

        @Override
        public BlockModel resolveModel(String modelPath) {
            return null;
        }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            return textureAvailable
                ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                : null;
        }

        @Override
        public String name() {
            return resolverName;
        }
    }
}
