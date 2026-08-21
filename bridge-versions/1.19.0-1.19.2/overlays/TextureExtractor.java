package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Minecraft 1.19-1.19.2 sprite extractor. */
public class TextureExtractor {

    public static TextureEntry extractTexture(String textureId, Minecraft client) {
        try {
            ResourceLocation id = new ResourceLocation(textureId);
            TextureAtlas atlas = (TextureAtlas) client.getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (atlas == null) return null;

            TextureAtlasSprite sprite = atlas.getSprite(id);
            if (sprite == null) return null;

            int w = sprite.getWidth();
            int h = sprite.getHeight();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    int abgr = sprite.getPixelRGBA(0, px, py);
                    int a = (abgr >>> 24) & 0xFF;
                    int b = (abgr >>> 16) & 0xFF;
                    int g = (abgr >>> 8) & 0xFF;
                    int r = abgr & 0xFF;
                    img.setRGB(px, py, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", bytes);
            String b64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
            return new TextureEntry(textureId, w, h, b64);
        } catch (Exception e) {
            System.err.println("[MinesportBridge] Failed texture " + textureId + ": " + e.getMessage());
            return null;
        }
    }

    public static Set<String> collectTextureIds(List<BlockEntry> blocks) {
        var ids = new LinkedHashSet<String>();
        for (var entry : blocks)
            for (var variant : entry.variants())
                for (var quad : variant.quads())
                    if (quad.textureId() != null && !quad.textureId().equals("missing"))
                        ids.add(quad.textureId());
        return ids;
    }
}
