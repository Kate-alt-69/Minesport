package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Compatibility extractor for Minecraft 1.21/1.21.1 NativeImage. */
public class TextureExtractor {

    public static TextureEntry extractTexture(String textureId, Minecraft client) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(textureId);
            if (id == null) return null;

            TextureAtlas atlas = (TextureAtlas) client.getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (atlas == null) return null;

            TextureAtlasSprite sprite = atlas.getSprite(id);
            if (sprite == null) return null;

            int x0 = sprite.getX();
            int y0 = sprite.getY();
            int w = sprite.contents().width();
            int h = sprite.contents().height();

            NativeImage atlasImg = getAtlasImage(atlas);
            if (atlasImg == null) return null;

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    int abgr = atlasImg.getPixelRGBA(x0 + px, y0 + py);
                    int a = (abgr >>> 24) & 0xFF;
                    int b = (abgr >>> 16) & 0xFF;
                    int g = (abgr >>> 8) & 0xFF;
                    int r = abgr & 0xFF;
                    img.setRGB(px, py, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
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

    private static NativeImage getAtlasImage(TextureAtlas atlas) {
        try {
            for (Class<?> cls = atlas.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (!NativeImage.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object value = f.get(atlas);
                    if (value instanceof NativeImage image) return image;
                }
            }
        } catch (Exception e) {
            System.err.println("[MinesportBridge] Atlas image access failed: " + e.getMessage());
        }
        return null;
    }
}
