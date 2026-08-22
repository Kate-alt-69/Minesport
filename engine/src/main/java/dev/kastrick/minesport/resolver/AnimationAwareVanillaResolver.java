package dev.kastrick.minesport.resolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only access to the adjacent .png.mcmeta used by Minecraft texture
 * animations. This stays isolated from the mature VanillaResolver model path.
 */
public final class AnimationAwareVanillaResolver extends VanillaResolver {
    private final File sourceJar;

    public AnimationAwareVanillaResolver(File jarFile) throws IOException {
        super(jarFile);
        this.sourceJar = jarFile;
    }

    @Override
    public String resolveTextureMetadata(String texturePath) {
        return readMetadata(sourceJar, texturePath);
    }

    /**
     * Compatibility path for the existing IPC code, which constructs a plain
     * VanillaResolver. Reflection is intentionally confined to this helper and
     * fails closed; normal vanilla texture/model resolution is never affected.
     */
    public static String readFrom(VanillaResolver resolver, String texturePath) {
        if (resolver == null) return null;
        if (resolver instanceof AnimationAwareVanillaResolver aware) {
            return aware.resolveTextureMetadata(texturePath);
        }
        try {
            Field field = VanillaResolver.class.getDeclaredField("jarFile");
            field.setAccessible(true);
            Object value = field.get(resolver);
            return value instanceof File file ? readMetadata(file, texturePath) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readMetadata(File sourceJar, String texturePath) {
        if (sourceJar == null || texturePath == null || texturePath.isBlank() || texturePath.startsWith("#")) {
            return null;
        }
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
