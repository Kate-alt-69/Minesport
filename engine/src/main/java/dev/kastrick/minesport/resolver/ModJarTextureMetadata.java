package dev.kastrick.minesport.resolver;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only adapter for mod resolvers whose winning assets live in namespaceJars. */
final class ModJarTextureMetadata {
    private ModJarTextureMetadata() {}

    static String read(Object resolver, String texturePath) {
        if (resolver == null || texturePath == null || texturePath.isBlank()) return null;
        String normalized = texturePath.contains(":") ? texturePath : "minecraft:" + texturePath;
        String[] parts = normalized.split(":", 2);
        if (parts.length != 2) return null;
        try {
            Field field = resolver.getClass().getDeclaredField("namespaceJars");
            field.setAccessible(true);
            Object value = field.get(resolver);
            if (!(value instanceof Map<?, ?> map)) return null;
            Object zipValue = map.get(parts[0]);
            if (!(zipValue instanceof ZipFile zip)) return null;
            String path = "assets/" + parts[0] + "/textures/" + parts[1] + ".png.mcmeta";
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
