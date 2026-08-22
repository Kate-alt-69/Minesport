package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Resolves block assets from one or more user-supplied resource packs
 * (a.k.a. "texture packs") — either a loose folder or a .zip, in the exact
 * layout Minecraft itself uses.
 *
 * Resource packs are priority-ordered: pack[0] is highest priority. Texture
 * animation metadata is intentionally read from the same PackSource that owns
 * the winning PNG so an override cannot inherit stale timing from a lower pack.
 */
public class ResourcePackResolver implements AssetResolver {

    private interface PackSource extends Closeable {
        InputStream open(String path) throws IOException;
        Set<String> namespaces();
    }

    private static final class FolderPackSource implements PackSource {
        private final File root;
        private Set<String> namespaces;

        FolderPackSource(File root) { this.root = root; }

        @Override
        public InputStream open(String path) throws IOException {
            File f = new File(root, path);
            if (!f.exists() || !f.isFile()) return null;
            return new FileInputStream(f);
        }

        @Override
        public Set<String> namespaces() {
            if (namespaces != null) return namespaces;
            Set<String> found = new LinkedHashSet<>();
            File assetsDir = new File(root, "assets");
            File[] children = assetsDir.listFiles(File::isDirectory);
            if (children != null) {
                for (File c : children) found.add(c.getName());
            }
            namespaces = found;
            return found;
        }

        @Override public void close() {}
    }

    private static final class ZipPackSource implements PackSource {
        private final ZipFile zip;
        private Set<String> namespaces;

        ZipPackSource(File zipFile) throws IOException { this.zip = new ZipFile(zipFile); }

        @Override
        public InputStream open(String path) throws IOException {
            ZipEntry entry = zip.getEntry(path);
            return entry == null ? null : zip.getInputStream(entry);
        }

        @Override
        public Set<String> namespaces() {
            if (namespaces != null) return namespaces;
            Set<String> found = new LinkedHashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("assets/") && name.length() > 7) {
                    String rest = name.substring(7);
                    int slash = rest.indexOf('/');
                    if (slash > 0) found.add(rest.substring(0, slash));
                }
            }
            namespaces = found;
            return found;
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    private final List<PackSource> sources = new ArrayList<>();
    private final Set<String> allNamespaces = new LinkedHashSet<>();
    private final Map<String, BlockState> stateCache = new ConcurrentHashMap<>();
    private final Map<String, BlockModel> modelCache = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache = new ConcurrentHashMap<>();

    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );

    private ResourcePackResolver() {}

    public static ResourcePackResolver load(List<File> packPaths, java.util.function.Consumer<String> log) {
        ResourcePackResolver resolver = new ResourcePackResolver();
        if (packPaths == null || packPaths.isEmpty()) return resolver;

        for (File pack : packPaths) {
            try {
                PackSource source;
                if (pack.isDirectory()) source = new FolderPackSource(pack);
                else if (pack.isFile() && pack.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) source = new ZipPackSource(pack);
                else {
                    if (log != null) log.accept("[ResourcePackResolver] Not a folder or .zip, skipping: " + pack);
                    continue;
                }
                resolver.sources.add(source);
                resolver.allNamespaces.addAll(source.namespaces());
                if (log != null) log.accept("[ResourcePackResolver] Loaded pack: " + pack.getName()
                    + " (namespaces: " + source.namespaces() + ")");
            } catch (Exception e) {
                if (log != null) log.accept("[ResourcePackResolver] Failed to load " + pack + ": " + e.getMessage());
            }
        }
        return resolver;
    }

    public static ResourcePackResolver empty() { return new ResourcePackResolver(); }
    public boolean isEmpty() { return sources.isEmpty(); }

    @Override
    public boolean canResolve(String blockId) {
        if (!blockId.contains(":")) return false;
        return allNamespaces.contains(blockId.split(":")[0]);
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        return stateCache.computeIfAbsent(blockId, id -> {
            String[] parts = id.split(":", 2);
            String path = "assets/" + parts[0] + "/blockstates/" + parts[1] + ".json";
            for (PackSource src : sources) {
                try (InputStream in = src.open(path)) {
                    if (in == null) continue;
                    return ModelParser.parseBlockState(in);
                } catch (Exception e) {
                    System.err.println("[ResourcePackResolver] BlockState parse failed for " + id + ": " + e.getMessage());
                }
            }
            return null;
        });
    }

    @Override
    public BlockModel resolveModel(String modelPath) {
        String normalized = normalizePath(modelPath);
        BlockModel cached = modelCache.get(normalized);
        if (cached != null) return cached;
        try {
            BlockModel model = loadModelWithParents(normalized, new HashSet<>());
            if (model != null) modelCache.put(normalized, model);
            return model;
        } catch (Exception e) {
            System.err.println("[ResourcePackResolver] Model resolve failed for " + modelPath + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public BufferedImage resolveTexture(String texturePath) {
        return texCache.computeIfAbsent(texturePath, path -> {
            String jarPath = textureAssetPath(path);
            if (jarPath == null) return null;
            for (PackSource src : sources) {
                try (InputStream in = src.open(jarPath)) {
                    if (in == null) continue;
                    return ImageIO.read(in);
                } catch (Exception e) {
                    System.err.println("[ResourcePackResolver] Texture load failed for " + path + ": " + e.getMessage());
                }
            }
            return null;
        });
    }

    @Override
    public String resolveTextureMetadata(String texturePath) {
        String jarPath = textureAssetPath(texturePath);
        if (jarPath == null) return null;
        for (PackSource src : sources) {
            // First locate the winning PNG. If this source owns it, metadata is
            // either its own .mcmeta or intentionally absent/static. Do not fall
            // through to a lower-priority pack after that point.
            try (InputStream image = src.open(jarPath)) {
                if (image == null) continue;
            } catch (Exception ignored) {
                continue;
            }
            try (InputStream meta = src.open(jarPath + ".mcmeta")) {
                if (meta == null) return null;
                return new String(meta.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("[ResourcePackResolver] Texture metadata load failed for " + texturePath + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    @Override public String name() { return "ResourcePackResolver(" + sources.size() + " pack(s))"; }

    private BlockModel loadModelWithParents(String modelPath, Set<String> visited) throws IOException {
        if (VIRTUAL_PARENTS.contains(modelPath)) return new BlockModel();
        if (!visited.add(modelPath)) return new BlockModel();

        String[] parts = modelPath.split(":", 2);
        if (parts.length < 2) return new BlockModel();
        String jarPath = "assets/" + parts[0] + "/models/" + parts[1] + ".json";

        BlockModel model = null;
        for (PackSource src : sources) {
            InputStream in = src.open(jarPath);
            if (in == null) continue;
            try { model = ModelParser.parseBlockModel(in); }
            finally { in.close(); }
            break;
        }
        if (model == null) return new BlockModel();

        if (model.parentId != null && !model.parentId.isEmpty()) {
            String parentPath = normalizePath(model.parentId);
            if (!VIRTUAL_PARENTS.contains(parentPath) && !visited.contains(parentPath)) {
                BlockModel parent = modelCache.get(parentPath);
                if (parent == null) {
                    parent = loadModelWithParents(parentPath, visited);
                    if (parent != null) modelCache.put(parentPath, parent);
                }
                if (parent != null) {
                    if (model.isEmpty()) model.elements = parent.elements;
                    model.mergeTextures(parent.textures);
                }
            }
        }
        return model;
    }

    private static String textureAssetPath(String texturePath) {
        String norm = normalizePath(texturePath);
        String[] parts = norm.split(":", 2);
        if (parts.length < 2) return null;
        return "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
    }

    private static String normalizePath(String path) {
        if (path.contains(":")) return path;
        return "minecraft:" + path;
    }

    public void close() {
        for (PackSource s : sources) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
