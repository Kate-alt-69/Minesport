package dev.kastrick.minesport.export;

import java.awt.image.BufferedImage;
import java.util.Locale;

/** A texture plus the Minecraft tint that must be applied to its pixels. */
public record MaterialKey(String texturePath, int tintRgb) {
    public static MaterialKey forQuad(Quad quad) {
        return new MaterialKey(quad.texturePath(), tintFor(quad.texturePath(), quad.tintindex()));
    }

    public String materialName() {
        String base = texturePath == null || texturePath.isBlank()
            ? "Minesport_Material"
            : texturePath.replace(':', '_').replace('/', '_').replace('\\', '_');
        return tintRgb < 0 ? base : base + "__tint_" + String.format(Locale.ROOT, "%06x", tintRgb);
    }

    public BufferedImage apply(BufferedImage source) {
        if (source == null || tintRgb < 0) return source;
        int tr = (tintRgb >>> 16) & 0xff;
        int tg = (tintRgb >>> 8) & 0xff;
        int tb = tintRgb & 0xff;
        BufferedImage tinted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = ((argb >>> 16) & 0xff) * tr / 255;
                int g = ((argb >>> 8) & 0xff) * tg / 255;
                int b = (argb & 0xff) * tb / 255;
                tinted.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return tinted;
    }

    public static int tintFor(String texturePath, int tintIndex) {
        if (tintIndex < 0) return -1;
        String path = texturePath == null ? "" : texturePath.toLowerCase(Locale.ROOT);
        if (path.contains("water")) return 0x3f76e4;
        if (path.contains("spruce")) return 0x619961;
        if (path.contains("birch")) return 0x80a755;
        if (path.contains("foliage") || path.contains("leaves") || path.contains("vine")) return 0x77ab2f;
        if (path.contains("redstone")) return 0xff0000;
        // Plains is the neutral fallback when biome data is not yet available.
        // This fixes grass/fern/sugar-cane textures whose source PNG is grayscale
        // because Minecraft normally colors it at render time.
        return 0x91bd59;
    }
}
