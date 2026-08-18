package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Reads vanilla Minecraft assets directly from the minecraft client jar.
 *
 * The jar lives at:
 *   %APPDATA%\.minecraft\versions\<version>\<version>.jar  (Windows)
 *   ~/.minecraft/versions/<version>/<version>.jar           (Linux/Mac)
 *
 * Inside the jar, assets are at:
 *   assets/minecraft/blockstates/<name>.json
 *   assets/minecraft/models/block/<name>.json
 *   assets/minecraft/textures/block/<name>.png
 *
 * Also handles model inheritance — follows "parent" chains and merges
 * textures + elements from parent models.
 */
public class VanillaResolver implements AssetResolver {

    private final File jarFile;
    private ZipFile zip;

    // Caches to avoid re-reading from zip on every block
    private final Map<String, BlockState> stateCache  = new ConcurrentHashMap<>();
    private final Map<String, BlockModel> modelCache  = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache = new ConcurrentHashMap<>();

    // Built-in parent models that define geometry but have no JSON in jar
    // (these are "virtual" models — their geometry is implicit)
    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public VanillaResolver(File jarFile) throws IOException {
        this.jarFile = jarFile;
        this.zip = new ZipFile(jarFile);
    }

    // ── AssetResolver impl ────────────────────────────────────────────────────

    @Override
    public boolean canResolve(String blockId) {
        // Vanilla handles minecraft: namespace only
        return blockId.startsWith("minecraft:");
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        return stateCache.computeIfAbsent(blockId, id -> {
            String name = id.contains(":") ? id.split(":")[1] : id;
            String path = "assets/minecraft/blockstates/" + name + ".json";
            try (InputStream in = openEntry(path)) {
                if (in == null) return null;
                return ModelParser.parseBlockState(in);
            } catch (Exception e) {
                System.err.println("[VanillaResolver] Failed to parse blockstate for " + id + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public BlockModel resolveModel(String modelPath) {
        String normalized = normalizeModelPath(modelPath);
        // Check cache first without computeIfAbsent to avoid recursive update
        BlockModel cached = modelCache.get(normalized);
        if (cached != null) return cached;

        try {
            BlockModel model = loadModelWithParents(normalized, new HashSet<>());
            if (model != null) modelCache.put(normalized, model);
            return model;
        } catch (Exception e) {
            System.err.println("[VanillaResolver] Failed to resolve model " + normalized + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public BufferedImage resolveTexture(String texturePath) {
        return texCache.computeIfAbsent(texturePath, path -> {
            String normalized = normalizeTexturePath(path);
            String jarPath = "assets/" + normalized.replace(":", "/textures/") + ".png";
            try (InputStream in = openEntry(jarPath)) {
                if (in == null) return null;
                return ImageIO.read(in);
            } catch (Exception e) {
                System.err.println("[VanillaResolver] Failed to load texture " + path + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public String name() { return "VanillaResolver(" + jarFile.getName() + ")"; }

    // ── Model inheritance resolution ──────────────────────────────────────────

    /**
     * Load a model JSON and recursively merge its parent chain.
     * Result is a fully-resolved model with all elements + merged textures.
     */
    private BlockModel loadModelWithParents(String modelPath) throws IOException {
        return loadModelWithParents(modelPath, new HashSet<>());
    }

    private BlockModel loadModelWithParents(String modelPath, Set<String> visited) throws IOException {
        if (VIRTUAL_PARENTS.contains(modelPath)) return new BlockModel();
        if (!visited.add(modelPath)) return new BlockModel(); // cycle detected

        String jarPath = "assets/" + modelPath.replace(":", "/models/") + ".json";
        InputStream in = openEntry(jarPath);
        if (in == null) return new BlockModel();

        BlockModel model;
        try { model = ModelParser.parseBlockModel(in); }
        finally { in.close(); }

        if (model.parentId != null && !model.parentId.isEmpty()) {
            String parentPath = normalizeModelPath(model.parentId);
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

    private InputStream openEntry(String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        return zip.getInputStream(entry);
    }

    /**
     * Normalize a model path to namespaced form.
     * "block/stone"            → "minecraft:block/stone"
     * "minecraft:block/stone"  → "minecraft:block/stone"
     */
    private static String normalizeModelPath(String path) {
        if (path.contains(":")) return path;
        return "minecraft:" + path;
    }

    /**
     * Normalize a texture path for jar lookup.
     * "minecraft:block/stone"  → "minecraft:block/stone"
     * "block/stone"            → "minecraft:block/stone"
     */
    private static String normalizeTexturePath(String path) {
        if (path.startsWith("#")) return path; // unresolved variable, caller's problem
        if (path.contains(":")) return path;
        return "minecraft:" + path;
    }

    /** Close zip when done. */
    public void close() {
        try { if (zip != null) zip.close(); }
        catch (IOException ignored) {}
    }

    // ── Factory — find minecraft.jar automatically ────────────────────────────

    /**
     * Try to locate the minecraft.jar for a given version.
     * Checks standard .minecraft locations on Windows/Linux/Mac.
     */
    public static File findMinecraftJar(String version) {
        List<File> candidates = new ArrayList<>();

        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            candidates.add(new File(appdata, ".minecraft/versions/" + version + "/" + version + ".jar"));
            candidates.add(new File(appdata,
                "FreesmLauncher/libraries/com/mojang/minecraft/" + version + "/minecraft-" + version + "-client.jar"));
        }
        candidates.add(new File(System.getProperty("user.home"),
            ".minecraft/versions/" + version + "/" + version + ".jar"));
        candidates.add(new File(System.getProperty("user.home"),
            "Library/Application Support/minecraft/versions/" + version + "/" + version + ".jar"));

        for (File f : candidates) {
            if (f.exists()) return f;
        }

        // Not found locally — try to download from Mojang
        return downloadMinecraftJar(version);
    }

    /**
     * Downloads the minecraft client jar for the given version from Mojang's servers.
     * Stores it in a temp location and returns the file.
     */
    private static File downloadMinecraftJar(String version) {
        try {
            System.err.println("[VanillaResolver] minecraft.jar not found locally, attempting download for " + version);

            // 1. Fetch version manifest
            java.net.URL manifestUrl = new java.net.URI(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
            ).toURL();
            String manifest = readUrl(manifestUrl);
            if (manifest == null) return null;

            // Find version entry URL
            String searchKey = "\"id\": \"" + version + "\"";
            int idx = manifest.indexOf(searchKey);
            if (idx < 0) {
                // Try without space
                searchKey = "\"id\":\"" + version + "\"";
                idx = manifest.indexOf(searchKey);
            }
            if (idx < 0) { System.err.println("[VanillaResolver] Version " + version + " not in manifest"); return null; }

            // Find the url field after the id
            int urlIdx = manifest.indexOf("\"url\":", idx);
            if (urlIdx < 0) return null;
            int q1 = manifest.indexOf('"', urlIdx + 7);
            int q2 = manifest.indexOf('"', q1 + 1);
            String versionJsonUrl = manifest.substring(q1 + 1, q2);

            // 2. Fetch version JSON
            String versionJson = readUrl(new java.net.URI(versionJsonUrl).toURL());
            if (versionJson == null) return null;

            // Find client jar download URL
            int clientIdx = versionJson.indexOf("\"client\"");
            if (clientIdx < 0) return null;
            int clientUrlIdx = versionJson.indexOf("\"url\":", clientIdx);
            if (clientUrlIdx < 0) return null;
            int cq1 = versionJson.indexOf('"', clientUrlIdx + 7);
            int cq2 = versionJson.indexOf('"', cq1 + 1);
            String clientJarUrl = versionJson.substring(cq1 + 1, cq2);

            // 3. Download jar
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "minesport_jars");
            tempDir.mkdirs();
            File jarFile = new File(tempDir, "minecraft-" + version + "-client.jar");

            if (jarFile.exists()) return jarFile; // already cached from previous download

            System.err.println("[VanillaResolver] Downloading minecraft.jar from Mojang...");
            try (java.io.InputStream in = new java.net.URI(clientJarUrl).toURL().openStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(jarFile)) {
                in.transferTo(out);
            }
            System.err.println("[VanillaResolver] Downloaded to " + jarFile.getAbsolutePath());
            return jarFile;

        } catch (Exception e) {
            System.err.println("[VanillaResolver] Auto-download failed: " + e.getMessage());
            return null;
        }
    }

    private static String readUrl(java.net.URL url) {
        try (java.io.InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
