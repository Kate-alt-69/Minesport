package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/** Resolves block assets from Fabric mod jars. */
public class FabricResolver implements AssetResolver {
    private final Map<String, ZipFile> namespaceJars = new LinkedHashMap<>();
    private final List<ZipFile> openJars = new ArrayList<>();
    private final Map<String, ModInfo> detectedMods = new LinkedHashMap<>();
    private final Map<String, BlockState> stateCache = new ConcurrentHashMap<>();
    private final Map<String, BlockModel> modelCache = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache = new ConcurrentHashMap<>();

    public record ModInfo(String modId, String name, String version, File jarFile) {}

    private FabricResolver() {}

    public static FabricResolver load(File modsFolder, java.util.function.Consumer<String> log) {
        FabricResolver resolver = new FabricResolver();
        if (!modsFolder.exists() || !modsFolder.isDirectory()) {
            if (log != null) log.accept("[FabricResolver] Mods folder not found: " + modsFolder);
            return resolver;
        }
        File[] jars = modsFolder.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            if (log != null) log.accept("[FabricResolver] No mod jars found in: " + modsFolder);
            return resolver;
        }
        if (log != null) log.accept("[FabricResolver] Scanning " + jars.length + " mod jar(s)...");
        for (File jar : jars) {
            try { resolver.loadJar(jar, log); }
            catch (Exception e) {
                if (log != null) log.accept("[FabricResolver] Failed to load " + jar.getName() + ": " + e.getMessage());
            }
        }
        if (log != null) log.accept("[FabricResolver] Loaded " + resolver.detectedMods.size() + " mod(s), "
            + resolver.namespaceJars.size() + " namespace(s).");
        return resolver;
    }

    private void loadJar(File jarFile, java.util.function.Consumer<String> log) throws IOException {
        ZipFile zip = new ZipFile(jarFile);
        ZipEntry fabricMeta = zip.getEntry("fabric.mod.json");
        if (fabricMeta == null) {
            scanNamespaces(zip);
            openJars.add(zip);
            return;
        }
        ModInfo info = parseFabricMeta(zip.getInputStream(fabricMeta), jarFile);
        if (info != null) {
            detectedMods.put(info.modId(), info);
            if (log != null) log.accept("  [mod] " + info.modId() + " v" + info.version() + " (" + jarFile.getName() + ")");
        }
        scanNamespaces(zip);
        openJars.add(zip);
    }

    private void scanNamespaces(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> foundNamespaces = new HashSet<>();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith("assets/") || name.length() <= 7) continue;
            String rest = name.substring(7);
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String ns = rest.substring(0, slash);
                if (!ns.isEmpty() && foundNamespaces.add(ns)) namespaceJars.putIfAbsent(ns, zip);
            }
        }
    }

    private ModInfo parseFabricMeta(InputStream in, File jarFile) {
        try {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String modId = extractJsonString(json, "id");
            String name = extractJsonString(json, "name");
            String version = extractJsonString(json, "version");
            if (modId == null) return null;
            return new ModInfo(modId, name != null ? name : modId, version != null ? version : "?", jarFile);
        } catch (Exception e) { return null; }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        return q2 < 0 ? null : json.substring(q1 + 1, q2);
    }

    @Override
    public boolean canResolve(String blockId) {
        if (!blockId.contains(":")) return false;
        return namespaceJars.containsKey(blockId.split(":")[0]);
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        return stateCache.computeIfAbsent(blockId, id -> {
            String[] parts = id.split(":", 2);
            String path = "assets/" + parts[0] + "/blockstates/" + parts[1] + ".json";
            try (InputStream in = openEntry(parts[0], path)) {
                return in == null ? null : ModelParser.parseBlockState(in);
            } catch (Exception e) {
                System.err.println("[FabricResolver] BlockState parse failed for " + id + ": " + e.getMessage());
                return null;
            }
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
            System.err.println("[FabricResolver] Model resolve failed for " + modelPath + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public BufferedImage resolveTexture(String texturePath) {
        return texCache.computeIfAbsent(texturePath, path -> {
            String norm = normalizePath(path);
            String[] parts = norm.split(":", 2);
            if (parts.length < 2) return null;
            String jarPath = "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
            try (InputStream in = openEntry(parts[0], jarPath)) {
                return in == null ? null : ImageIO.read(in);
            } catch (Exception e) {
                System.err.println("[FabricResolver] Texture load failed for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public String resolveTextureMetadata(String texturePath) {
        String norm = normalizePath(texturePath);
        String[] parts = norm.split(":", 2);
        if (parts.length < 2) return null;
        String path = "assets/" + parts[0] + "/textures/" + parts[1] + ".png.mcmeta";
        try (InputStream in = openEntry(parts[0], path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[FabricResolver] Texture metadata load failed for " + texturePath + ": " + e.getMessage());
            return null;
        }
    }

    @Override public String name() {
        return "FabricResolver(" + detectedMods.size() + " mods, " + namespaceJars.size() + " namespaces)";
    }

    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block", "minecraft:builtin/generated", "minecraft:builtin/entity"
    );

    private BlockModel loadModelWithParents(String modelPath, Set<String> visited) throws IOException {
        if (VIRTUAL_PARENTS.contains(modelPath)) return new BlockModel();
        if (!visited.add(modelPath)) return new BlockModel();
        String[] parts = modelPath.split(":", 2);
        if (parts.length < 2) return new BlockModel();
        String path = "assets/" + parts[0] + "/models/" + parts[1] + ".json";
        InputStream in = openEntry(parts[0], path);
        if (in == null) return new BlockModel();
        BlockModel model;
        try { model = ModelParser.parseBlockModel(in); }
        finally { in.close(); }
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

    private InputStream openEntry(String namespace, String path) throws IOException {
        ZipFile zip = namespaceJars.get(namespace);
        if (zip == null) return null;
        ZipEntry entry = zip.getEntry(path);
        return entry == null ? null : zip.getInputStream(entry);
    }

    private static String normalizePath(String path) {
        return path.contains(":") ? path : "minecraft:" + path;
    }

    public void close() {
        for (ZipFile z : openJars) try { z.close(); } catch (IOException ignored) {}
    }

    public Collection<ModInfo> getDetectedMods() { return Collections.unmodifiableCollection(detectedMods.values()); }
    public Set<String> getNamespaces() { return Collections.unmodifiableSet(namespaceJars.keySet()); }

    public Set<String> listModels(String namespace) {
        ZipFile zip = namespaceJars.get(namespace);
        if (zip == null) return Collections.emptySet();
        String prefix = "assets/" + namespace + "/models/block/";
        Set<String> names = new LinkedHashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix) && name.endsWith(".json")) {
                String modelName = name.substring(prefix.length(), name.length() - 5);
                if (!modelName.contains("/")) names.add(modelName);
            }
        }
        return names;
    }
}
