package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResolverChainLifecycleTest {
    @Test
    void closeReleasesUniqueResolversAndClearsCurrentThreadLocal() {
        CountingResolver resolver = new CountingResolver();
        ResolverChain chain = new ResolverChain();
        chain.addResolver(resolver);
        chain.addResolver(resolver);

        assertSame(chain, ResolverChain.current());
        chain.close();

        assertEquals(1, resolver.closeCount);
        assertEquals(0, chain.size());
        assertNull(ResolverChain.current());
    }

    private static final class CountingResolver implements AssetResolver {
        int closeCount;
        @Override public boolean canResolve(String blockId) { return false; }
        @Override public BlockState resolveBlockState(String blockId) { return null; }
        @Override public BlockModel resolveModel(String modelPath) { return null; }
        @Override public BufferedImage resolveTexture(String texturePath) { return null; }
        @Override public String name() { return "CountingResolver"; }
        @Override public void close() { closeCount++; }
    }
}
