package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Resolves block assets from one or more user-supplied resource packs
 * (a.k.a. "texture packs") — either a loose folder or a .zip, in the exact
 * layout Minecraft itself uses:
 *
 *   assets/<namespace>/blockstates/<n>.json
 *   assets/<namespace>/models/block/<n>.json
 *   assets/<namespace>/textures/block/<n>.png
 *
 * This is NOT the same thing as a data pack — data packs are server-side
 * (recipes, loot tables, tags, worldgen) and contain no geometry or texture
 * data at all. Custom block *appearance* — the thing "extract from a data
 * pack/texture pack" usually means in practice — lives in a resource pack,
 * whether that's a texture pack a player applied, or the bundled resources
 * inside a mod jar (already handled by FabricResolver/QuiltResolver/ForgeResolver).
 *
 * Resource packs are meant to OVERRIDE everything below them — vanilla assets,
 * mod-provided assets, all of it. So this resolver should be added to the
 * ResolverChain FIRST, ahead of Vanilla/Fabric/Quilt/Forge.
 *
 * Multiple packs can be layered, matching how Minecraft's own resource pack
 * list works: pack[0] is the highest priority (checked first), later packs
 * are progressively lower priority, and each individual asset lookup falls
 * through the stack independently — a pack can override just a texture and
 * still inherit the blockstate/model from a lower pack.
 */
public class ResourcePackResolver implements AssetResolver {

    /** Abstracts reading entries from either a loose folder or a zip file. */
    private interface PackSource extends Closeable {
        InputStream open(String path) throws IOException; // null if not present
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

        @Override
        public void close() {}
    }

    private static final class ZipPackSource implements PackSource {
        private final ZipFile zip;
        private Set<String> namespaces;

        ZipPackSource(File zipFile) throws IOException {
            this.zip = new ZipFile(zipFile);
        }

        @Override
        public InputStream open(String path) throws IOException {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) return null;
            return zip.getInputStream(entry);
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

        @Override
        public void close() throws IOException { zip.close(); }
    }

    // Priority-ordered: index 0 = highest priority
    private final List<PackSource> sources = new ArrayList<>();
    private final Set<String> allNamespaces = new LinkedHashSet<>();

    private final Map<String, BlockState>    stateCache = new ConcurrentHashMap<>();
    private final Map<String, BlockModel>    modelCache = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache   = new ConcurrentHashMap<>();

    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );

    private ResourcePackResolver() {}

    /**
     * Load one or more resource packs, highest priority first.
     * Each path may be a directory (loose resource pack) or a .zip file.
     * Missing/invalid paths are skipped with a log message rather than failing.
     */
    public static ResourcePackResolver load(List<File> packPaths, java.util.function.Consumer<String> log) {
        ResourcePackResolver resolver = new ResourcePackResolver();
        if (packPaths == null || packPaths.isEmpty()) return resolver;

        for (File pack : packPaths) {
            try {
                PackSource source;
                if (pack.isDirectory()) {
                    source = new FolderPackSource(pack);
                } else if (pack.isFile() && pack.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    source = new ZipPackSource(pack);
                } else {
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

    // ── AssetResolver impl ────────────────────────────────────────────────────

    @Override
    public boolean canResolve(String blockId) {
        if (!blockId.contains(":")) return false;
        String ns = blockId.split(":")[0];
        return allNamespaces.contains(ns);
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        return stateCache.computeIfAbsent(blockId, id -> {
            String[] parts = id.split(":", 2);
            String ns = parts[0], name = parts[1];
            String path = "assets/" + ns + "/blockstates/" + name + ".json";

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
            String norm = normalizePath(path);
            String[] parts = norm.split(":", 2);
            if (parts.length < 2) return null;
            String ns = parts[0], rest = parts[1];
            String jarPath = "assets/" + ns + "/textures/" + rest + ".png";

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
    public String name() { return "ResourcePackResolver(" + sources.size() + " pack(s))"; }

    // ── Model inheritance ─────────────────────────────────────────────────────

    private BlockModel loadModelWithParents(String modelPath, Set<String> visited) throws IOException {
        if (VIRTUAL_PARENTS.contains(modelPath)) return new BlockModel();
        if (!visited.add(modelPath)) return new BlockModel();

        String[] parts = modelPath.split(":", 2);
        if (parts.length < 2) return new BlockModel();
        String ns = parts[0], rest = parts[1];
        String jarPath = "assets/" + ns + "/models/" + rest + ".json";

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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
