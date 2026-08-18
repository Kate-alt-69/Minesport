package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Resolves block assets from Fabric mod jars.
 *
 * Fabric mod jars are standard zip files containing:
 *   fabric.mod.json              ← mod metadata (id, name, version)
 *   assets/<modid>/blockstates/  ← blockstate JSONs
 *   assets/<modid>/models/       ← model JSONs
 *   assets/<modid>/textures/     ← PNG textures
 *
 * Also handles mods that override vanilla assets by including
 * assets/minecraft/... entries inside their jar.
 *
 * A single FabricResolver instance manages ALL mod jars found
 * in the mods folder — it builds a namespace → jar map so
 * lookups go directly to the right jar.
 */
public class FabricResolver implements AssetResolver {

    // namespace → ZipFile that owns it
    private final Map<String, ZipFile> namespaceJars = new LinkedHashMap<>();

    // All open ZipFiles (for cleanup)
    private final List<ZipFile> openJars = new ArrayList<>();

    // Detected mods: modid → ModInfo
    private final Map<String, ModInfo> detectedMods = new LinkedHashMap<>();

    // Caches
    private final Map<String, BlockState>    stateCache = new ConcurrentHashMap<>();
    private final Map<String, BlockModel>    modelCache = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache   = new ConcurrentHashMap<>();

    // ── Mod metadata ──────────────────────────────────────────────────────────

    public record ModInfo(String modId, String name, String version, File jarFile) {}

    // ── Constructor + loading ─────────────────────────────────────────────────

    private FabricResolver() {}

    /**
     * Scan a mods folder and load all Fabric mod jars.
     * Returns a ready-to-use FabricResolver.
     *
     * @param modsFolder  e.g. .minecraft/mods or FreesmLauncher/instances/1.21.10/minecraft/mods
     * @param log         optional logger
     */
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
            try {
                resolver.loadJar(jar, log);
            } catch (Exception e) {
                if (log != null) log.accept("[FabricResolver] Failed to load " + jar.getName() + ": " + e.getMessage());
            }
        }

        if (log != null) {
            log.accept("[FabricResolver] Loaded " + resolver.detectedMods.size() + " mod(s), "
                + resolver.namespaceJars.size() + " namespace(s).");
        }

        return resolver;
    }

    private void loadJar(File jarFile, java.util.function.Consumer<String> log) throws IOException {
        ZipFile zip = new ZipFile(jarFile);

        // Read fabric.mod.json for mod metadata
        ZipEntry fabricMeta = zip.getEntry("fabric.mod.json");
        if (fabricMeta == null) {
            // Not a Fabric mod (could be a library jar) — still scan for assets
            scanNamespaces(zip);
            openJars.add(zip);
            return;
        }

        // Parse fabric.mod.json
        ModInfo info = parseFabricMeta(zip.getInputStream(fabricMeta), jarFile);
        if (info != null) {
            detectedMods.put(info.modId(), info);
            if (log != null) log.accept("  [mod] " + info.modId() + " v" + info.version() + " (" + jarFile.getName() + ")");
        }

        scanNamespaces(zip);
        openJars.add(zip);
    }

    /**
     * Scan a jar for asset namespaces by looking at the assets/ directory entries.
     * Each subdirectory of assets/ is a namespace.
     */
    private void scanNamespaces(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> foundNamespaces = new HashSet<>();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();

            // Look for assets/<namespace>/ pattern
            if (name.startsWith("assets/") && name.length() > 7) {
                String rest = name.substring(7);
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    String ns = rest.substring(0, slash);
                    if (!ns.isEmpty() && foundNamespaces.add(ns)) {
                        // First jar to claim a namespace wins
                        // (unless it's already claimed by another jar)
                        namespaceJars.putIfAbsent(ns, zip);
                    }
                }
            }
        }
    }

    private ModInfo parseFabricMeta(InputStream in, File jarFile) {
        try {
            // Simple JSON parse without full Gson to avoid circular deps
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String modId  = extractJsonString(json, "id");
            String name   = extractJsonString(json, "name");
            String version = extractJsonString(json, "version");
            if (modId == null) return null;
            return new ModInfo(
                modId,
                name != null ? name : modId,
                version != null ? version : "?",
                jarFile
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** Minimal JSON string extractor — avoids pulling in Gson for just metadata. */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    // ── AssetResolver impl ────────────────────────────────────────────────────

    @Override
    public boolean canResolve(String blockId) {
        if (!blockId.contains(":")) return false;
        String ns = blockId.split(":")[0];
        // Can resolve any namespace we found in mod jars
        // (including "minecraft" if a mod overrides vanilla assets)
        return namespaceJars.containsKey(ns);
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        return stateCache.computeIfAbsent(blockId, id -> {
            String[] parts = id.split(":", 2);
            String ns = parts[0], name = parts[1];
            String path = "assets/" + ns + "/blockstates/" + name + ".json";

            try (InputStream in = openEntry(ns, path)) {
                if (in == null) return null;
                return ModelParser.parseBlockState(in);
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
            String ns = parts[0], rest = parts[1];
            String jarPath = "assets/" + ns + "/textures/" + rest + ".png";

            try (InputStream in = openEntry(ns, jarPath)) {
                if (in == null) return null;
                return ImageIO.read(in);
            } catch (Exception e) {
                System.err.println("[FabricResolver] Texture load failed for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public String name() {
        return "FabricResolver(" + detectedMods.size() + " mods, " + namespaceJars.size() + " namespaces)";
    }

    // ── Model inheritance ─────────────────────────────────────────────────────

    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );

    private BlockModel loadModelWithParents(String modelPath, Set<String> visited) throws IOException {
        if (VIRTUAL_PARENTS.contains(modelPath)) return new BlockModel();
        if (!visited.add(modelPath)) return new BlockModel(); // cycle guard

        String[] parts = modelPath.split(":", 2);
        if (parts.length < 2) return new BlockModel();
        String ns = parts[0], rest = parts[1];
        String jarPath = "assets/" + ns + "/models/" + rest + ".json";

        InputStream in = openEntry(ns, jarPath);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private InputStream openEntry(String namespace, String path) throws IOException {
        ZipFile zip = namespaceJars.get(namespace);
        if (zip == null) return null;
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        return zip.getInputStream(entry);
    }

    private static String normalizePath(String path) {
        if (path.contains(":")) return path;
        return "minecraft:" + path;
    }

    public void close() {
        for (ZipFile z : openJars) {
            try { z.close(); } catch (IOException ignored) {}
        }
    }

    // ── Info accessors ────────────────────────────────────────────────────────

    public Collection<ModInfo> getDetectedMods() {
        return Collections.unmodifiableCollection(detectedMods.values());
    }

    public Set<String> getNamespaces() {
        return Collections.unmodifiableSet(namespaceJars.keySet());
    }

    /**
     * List all model names (without namespace prefix or path prefix) available
     * in the given namespace. E.g. returns {"oak_bench", "oak_table", ...}
     * for models at assets/polydecorations/models/block/*.json
     */
    public Set<String> listModels(String namespace) {
        ZipFile zip = namespaceJars.get(namespace);
        if (zip == null) return Collections.emptySet();

        String prefix = "assets/" + namespace + "/models/block/";
        Set<String> names = new LinkedHashSet<>();

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(prefix) && name.endsWith(".json")) {
                // Strip prefix and .json suffix → just the model name
                String modelName = name.substring(prefix.length(), name.length() - 5);
                // Skip subfolder models
                if (!modelName.contains("/")) {
                    names.add(modelName);
                }
            }
        }
        return names;
    }
}
