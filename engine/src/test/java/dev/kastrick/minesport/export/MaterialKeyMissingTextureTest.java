package dev.kastrick.minesport.export;

import dev.kastrick.minesport.resolver.MissingTexture;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertSame;

class MaterialKeyMissingTextureTest {
    @Test
    void biomeTintDoesNotRecolorMissingTexture() {
        MaterialKey tintedGrass = new MaterialKey("minecraft:block/grass_block_side_overlay", 0x91bd59);
        BufferedImage missing = MissingTexture.image();
        assertSame(missing, tintedGrass.apply(missing));
    }
}
