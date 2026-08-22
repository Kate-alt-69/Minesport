package dev.kastrick.minesport.resolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * VanillaResolver plus read-only access to the adjacent .png.mcmeta file used
 * by Minecraft texture animations. Keeping this as a small wrapper avoids
 * coupling the mature vanilla model resolver to Blender/DCC animation logic.
 */
public final class AnimationAwareVanillaResolver extends VanillaResolver {
    private final File sourceJar;

    public AnimationAwareVanillaResolver(File jarFile) throws IOException {
        super(jarFile);
        this.sourceJar = jarFile;
    }

    @Override
    public String resolveTextureMetadata(String texturePath) {
        if (texturePath == null || texturePath.isBlank() || texturePath.startsWith("#")) return null;
        String normalized = texturePath.contains(":") ? texturePath : "minecraft:" + texturePath;
        int colon = normalized.indexOf(':');
        String namespace = colon >= 0 ? normalized.substring(0, colon) : "minecraft";
        String relative = colon >= 0 ? normalized.substring(colon + 1) : normalized;
        String path = "assets/" + namespace + "/textures/" + relative + ".png.mcmeta";

        try (ZipFile zip = new ZipFile(sourceJar)) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[VanillaResolver] Texture metadata load failed for " + texturePath + ": " + e.getMessage());
            return null;
        }
    }
}
