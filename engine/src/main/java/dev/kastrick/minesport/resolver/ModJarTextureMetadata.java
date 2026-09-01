package dev.kastrick.minesport.resolver;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModJarTextureMetadata {
    private ModJarTextureMetadata() {}

    static String read(Object resolver, String texturePath) {
        if (resolver == null || texturePath == null || texturePath.isBlank()) return null;
        String normalized = texturePath.contains(":") ? texturePath : "minecraft:" + texturePath;
        String[] parts = normalized.split(":", 2);
        if (parts.length != 2) return null;
        String imagePath = "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
        String metadataPath = imagePath + ".mcmeta";
        try {
            Field field = resolver.getClass().getDeclaredField("namespaceJars");
            field.setAccessible(true);
            Object value = field.get(resolver);
            if (!(value instanceof Map<?, ?> map)) return null;
            Object sources = map.get(parts[0]);
            if (sources instanceof ZipFile zip) {
                return zip.getEntry(imagePath) == null ? null : readEntry(zip, metadataPath);
            }
            if (sources instanceof Iterable<?> layered) {
                for (Object source : layered) {
                    if (!(source instanceof ZipFile zip)) continue;
                    if (zip.getEntry(imagePath) == null) continue;
                    return readEntry(zip, metadataPath);
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readEntry(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
