package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Resolves block assets from Quilt mod jars.
 *
 * Quilt mod jars are standard zip files containing:
 *   quilt.mod.json                ← native Quilt metadata (id, group, version)
 *   fabric.mod.json               ← present instead of/alongside quilt.mod.json
 *                                    for mods running in Fabric-compatibility mode
 *   assets/<modid>/blockstates/   ← blockstate JSONs (identical format to vanilla/Fabric)
 *   assets/<modid>/models/        ← model JSONs
 *   assets/<modid>/textures/      ← PNG textures
 *
 * Quilt's resource format is byte-for-byte the same as vanilla/Fabric —
 * the only loader-specific piece is which metadata file identifies the mod.
 * We check quilt.mod.json first (Quilt's native format), and fall back to
 * fabric.mod.json for mods that only ship Fabric-compat metadata but still
 * run under Quilt (this covers most cross-compatible mods on Quilt).
 *
 * Mirrors FabricResolver's structure deliberately — same lookup/caching/
 * inheritance behaviour, different metadata parsing.
 */
public class QuiltResolver implements AssetResolver {

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

    public record ModInfo(String modId, String name, String version, File jarFile, boolean fabricCompat) {}

    // ── Constructor + loading ─────────────────────────────────────────────────

    private QuiltResolver() {}

    /**
     * Scan a mods folder and load all Quilt mod jars.
     * A jar only registers with this resolver if it has quilt.mod.json OR
     * fabric.mod.json — plain library jars are skipped entirely (they're
     * picked up by whichever loader-specific resolver actually needs them,
     * or ignored if neither claims them).
     *
     * @param modsFolder  e.g. .minecraft/mods or an instance's mods folder
     * @param log         optional logger
     */
    public static QuiltResolver load(File modsFolder, java.util.function.Consumer<String> log) {
        QuiltResolver resolver = new QuiltResolver();

        if (!modsFolder.exists() || !modsFolder.isDirectory()) {
            if (log != null) log.accept("[QuiltResolver] Mods folder not found: " + modsFolder);
            return resolver;
        }

        File[] jars = modsFolder.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            if (log != null) log.accept("[QuiltResolver] No mod jars found in: " + modsFolder);
            return resolver;
        }

        if (log != null) log.accept("[QuiltResolver] Scanning " + jars.length + " mod jar(s)...");

        for (File jar : jars) {
            try {
                resolver.loadJar(jar, log);
            } catch (Exception e) {
                if (log != null) log.accept("[QuiltResolver] Failed to load " + jar.getName() + ": " + e.getMessage());
            }
        }

        if (log != null) {
            log.accept("[QuiltResolver] Loaded " + resolver.detectedMods.size() + " mod(s), "
                + resolver.namespaceJars.size() + " namespace(s).");
        }

        return resolver;
    }

    private void loadJar(File jarFile, java.util.function.Consumer<String> log) throws IOException {
        ZipFile zip = new ZipFile(jarFile);

        ZipEntry quiltMeta = zip.getEntry("quilt.mod.json");
        ZipEntry fabricMeta = zip.getEntry("fabric.mod.json");

        ModInfo info = null;
        if (quiltMeta != null) {
            info = parseQuiltMeta(zip.getInputStream(quiltMeta), jarFile);
        } else if (fabricMeta != null) {
            // Fabric-compat mod running under Quilt — reuse its fabric.mod.json
            info = parseFabricCompatMeta(zip.getInputStream(fabricMeta), jarFile);
        }

        if (info == null) {
            // Neither Quilt nor Fabric metadata present — not a mod this
            // resolver claims. Still worth scanning for stray assets in
            // case it's a resource-only jar, but we don't register it as a mod.
            zip.close();
            return;
        }

        detectedMods.put(info.modId(), info);
        if (log != null) {
            String tag = info.fabricCompat() ? " (Fabric-compat)" : "";
            log.accept("  [mod] " + info.modId() + " v" + info.version() + tag + " (" + jarFile.getName() + ")");
        }

        scanNamespaces(zip);
        openJars.add(zip);
    }

    /** Scan a jar for asset namespaces by looking at the assets/ directory entries. */
    private void scanNamespaces(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> foundNamespaces = new HashSet<>();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();

            if (name.startsWith("assets/") && name.length() > 7) {
                String rest = name.substring(7);
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    String ns = rest.substring(0, slash);
                    if (!ns.isEmpty() && foundNamespaces.add(ns)) {
                        namespaceJars.putIfAbsent(ns, zip);
                    }
                }
            }
        }
    }

    // ── quilt.mod.json parsing ────────────────────────────────────────────────

    /**
     * Parses the native Quilt metadata format:
     * {
     *   "schema_version": 1,
     *   "quilt_loader": {
     *     "id": "example_mod",
     *     "version": "1.0.0",
     *     "metadata": { "name": "Example Mod" }
     *   }
     * }
     */
    private ModInfo parseQuiltMeta(InputStream in, File jarFile) {
        try {
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String loaderObj = extractJsonObjectRegion(json, "quilt_loader");
            if (loaderObj == null) return null;

            String modId = extractJsonString(loaderObj, "id");
            String version = extractJsonString(loaderObj, "version");
            if (modId == null) return null;

            String name = modId;
            String metaObj = extractJsonObjectRegion(loaderObj, "metadata");
            if (metaObj != null) {
                String metaName = extractJsonString(metaObj, "name");
                if (metaName != null) name = metaName;
            }

            return new ModInfo(modId, name, version != null ? version : "?", jarFile, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses fabric.mod.json for a mod that's running under Quilt in Fabric-compat mode. */
    private ModInfo parseFabricCompatMeta(InputStream in, File jarFile) {
        try {
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String modId  = extractJsonString(json, "id");
            String name   = extractJsonString(json, "name");
            String version = extractJsonString(json, "version");
            if (modId == null) return null;
            return new ModInfo(modId, name != null ? name : modId, version != null ? version : "?", jarFile, true);
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

    /**
     * Finds the substring of a nested JSON object value for the given key,
     * e.g. extractJsonObjectRegion(json, "quilt_loader") returns everything
     * between (and including) the { } that follow "quilt_loader":.
     * Uses simple brace counting — good enough since we never need to parse
     * arbitrary/malformed JSON here, only the well-formed metadata Loom/Quilt
     * itself generates.
     */
    private static String extractJsonObjectRegion(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int braceStart = json.indexOf('{', colon);
        if (braceStart < 0) return null;

        int depth = 0;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(braceStart, i + 1);
            }
        }
        return null; // unbalanced — shouldn't happen with valid metadata
    }

    // ── AssetResolver impl ────────────────────────────────────────────────────

    @Override
    public boolean canResolve(String blockId) {
        if (!blockId.contains(":")) return false;
        String ns = blockId.split(":")[0];
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
                System.err.println("[QuiltResolver] BlockState parse failed for " + id + ": " + e.getMessage());
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
            System.err.println("[QuiltResolver] Model resolve failed for " + modelPath + ": " + e.getMessage());
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
                System.err.println("[QuiltResolver] Texture load failed for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public String name() {
        return "QuiltResolver(" + detectedMods.size() + " mods, " + namespaceJars.size() + " namespaces)";
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

    /** List all block model names available in the given namespace (used by fallback matching). */
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
                String modelName = name.substring(prefix.length(), name.length() - 5);
                if (!modelName.contains("/")) {
                    names.add(modelName);
                }
            }
        }
        return names;
    }
}
