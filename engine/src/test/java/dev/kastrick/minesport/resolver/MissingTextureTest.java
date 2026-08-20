package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MissingTextureTest {
    @Test
    void missingTextureIsClassicBlackMagentaChecker() {
        BufferedImage image = MissingTexture.image();
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());

        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }

        assertEquals(Set.of(0xffff00ff, 0xff000000), colors);
        assertNotEquals(image.getRGB(0, 0), image.getRGB(4, 0));
        assertEquals(image.getRGB(0, 0), image.getRGB(4, 4));
    }

    @Test
    void resolverChainUsesMissingTextureOnlyAfterEverythingMisses() {
        ResolverChain chain = new ResolverChain();
        BufferedImage image = chain.resolveTexture("minecraft:block/does_not_exist");
        assertSame(MissingTexture.image(), image);
    }
}
