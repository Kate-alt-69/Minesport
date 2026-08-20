package dev.kastrick.minesport.resolver;

import java.awt.image.BufferedImage;

/**
 * Minecraft-style missing texture used only after every real resolver failed.
 *
 * Keep this centralized so OBJ, glTF and future exporters all degrade the same
 * way instead of inventing format-specific placeholder colors.
 */
public final class MissingTexture {
    private static final int SIZE = 16;
    private static final int TILE = 4;
    private static final int MAGENTA = 0xffff00ff;
    private static final int BLACK = 0xff000000;
    private static final BufferedImage IMAGE = build();

    private MissingTexture() {}

    public static BufferedImage image() {
        return IMAGE;
    }

    public static boolean is(BufferedImage image) {
        return image == IMAGE;
    }

    private static BufferedImage build() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean magenta = ((x / TILE) + (y / TILE)) % 2 == 0;
                image.setRGB(x, y, magenta ? MAGENTA : BLACK);
            }
        }
        return image;
    }
}
