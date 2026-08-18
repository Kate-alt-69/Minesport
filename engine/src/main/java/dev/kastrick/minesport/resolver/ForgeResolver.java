package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Resolves block assets from Forge and NeoForge mod jars.
 *
 * Forge/NeoForge mod jars are standard zip files containing:
 *   META-INF/mods.toml            ← mod metadata (modId, version, displayName)
 *   assets/<modid>/blockstates/   ← blockstate JSONs (identical format to vanilla/Fabric)
 *   assets/<modid>/models/        ← model JSONs
 *   assets/<modid>/textures/      ← PNG textures
 *
 * Forge and NeoForge both use the vanilla ResourceLocation resource-pack
 * layout for client assets — the same assets/<namespace>/... structure
 * VanillaResolver and FabricResolver already read. The only loader-specific
 * piece is the mod metadata file (mods.toml, TOML format, vs. Fabric's
 * fabric.mod.json). NeoForge kept mods.toml's schema after forking from
 * Forge, so one parser covers both.
 *
 * Only the modern TOML-based mods.toml format (Forge 1.13+) is supported.
 * The legacy pre-1.13 mcmod.info (JSON) format is not — those mod versions
 * predate the block model/blockstate JSON system this tool relies on anyway.
 *
 * Mirrors FabricResolver/QuiltResolver's structure deliberately — same
 * lookup/caching/inheritance behaviour, different metadata parsing.
 */
public class ForgeResolver implements AssetResolver {

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

    private ForgeResolver() {}

    /**
     * Scan a mods folder and load all Forge/NeoForge mod jars.
     * A jar only registers with this resolver if it has a valid
     * META-INF/mods.toml with at least one [[mods]] entry.
     *
     * @param modsFolder  e.g. .minecraft/mods or an instance's mods folder
     * @param log         optional logger
     */
    public static ForgeResolver load(File modsFolder, java.util.function.Consumer<String> log) {
        ForgeResolver resolver = new ForgeResolver();

        if (!modsFolder.exists() || !modsFolder.isDirectory()) {
            if (log != null) log.accept("[ForgeResolver] Mods folder not found: " + modsFolder);
            return resolver;
        }

        File[] jars = modsFolder.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            if (log != null) log.accept("[ForgeResolver] No mod jars found in: " + modsFolder);
            return resolver;
        }

        if (log != null) log.accept("[ForgeResolver] Scanning " + jars.length + " mod jar(s)...");

        for (File jar : jars) {
            try {
                resolver.loadJar(jar, log);
            } catch (Exception e) {
                if (log != null) log.accept("[ForgeResolver] Failed to load " + jar.getName() + ": " + e.getMessage());
            }
        }

        if (log != null) {
            log.accept("[ForgeResolver] Loaded " + resolver.detectedMods.size() + " mod(s), "
                + resolver.namespaceJars.size() + " namespace(s).");
        }

        return resolver;
    }

    private void loadJar(File jarFile, java.util.function.Consumer<String> log) throws IOException {
        ZipFile zip = new ZipFile(jarFile);

        ZipEntry modsToml = zip.getEntry("META-INF/mods.toml");
        if (modsToml == null) {
            // Not a Forge/NeoForge mod — skip (don't claim namespaces from
            // jars we can't positively identify, to avoid stealing a
            // namespace that a Fabric/Quilt mod jar with the same modid
            // might legitimately own in a mixed-mods folder scenario).
            zip.close();
            return;
        }

        String toml = new String(zip.getInputStream(modsToml).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<ModInfo> mods = parseModsToml(toml, jarFile);

        if (mods.isEmpty()) {
            if (log != null) log.accept("[ForgeResolver] mods.toml present but no [[mods]] entries in " + jarFile.getName());
            zip.close();
            return;
        }

        for (ModInfo info : mods) {
            detectedMods.put(info.modId(), info);
            if (log != null) log.accept("  [mod] " + info.modId() + " v" + info.version() + " (" + jarFile.getName() + ")");
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

    // ── mods.toml parsing ─────────────────────────────────────────────────────

    /**
     * Parses the [[mods]] array-of-tables from a mods.toml file:
     *
     *   modLoader="javafml"
     *   loaderVersion="[47,)"
     *
     *   [[mods]]
     *   modId="examplemod"
     *   version="1.0.0"
     *   displayName="Example Mod"
     *
     *   [[mods]]
     *   modId="examplemod2"
     *   ...
     *
     * A mod jar can declare more than one mod, so this returns a list.
     * This is a small hand-rolled TOML subset — just enough to read the
     * fields Minesport needs — not a general-purpose TOML parser.
     */
    private static List<ModInfo> parseModsToml(String toml, File jarFile) {
        List<ModInfo> result = new ArrayList<>();

        // Split into [[mods]] blocks. Each block runs until the next
        // top-level table header ([...] or [[...]]) or end of file.
        String marker = "[[mods]]";
        int searchFrom = 0;
        while (true) {
            int start = toml.indexOf(marker, searchFrom);
            if (start < 0) break;
            int blockStart = start + marker.length();

            int nextHeader = findNextTableHeader(toml, blockStart);
            String block = nextHeader < 0 ? toml.substring(blockStart) : toml.substring(blockStart, nextHeader);

            String modId = extractTomlString(block, "modId");
            if (modId != null && !modId.isBlank()) {
                String version = extractTomlString(block, "version");
                String displayName = extractTomlString(block, "displayName");
                result.add(new ModInfo(
                    modId,
                    displayName != null ? displayName : modId,
                    version != null ? version : "?",
                    jarFile
                ));
            }

            searchFrom = blockStart;
        }

        return result;
    }

    /** Finds the index of the next "[" that starts a new table header on its own line, or -1. */
    private static int findNextTableHeader(String toml, int from) {
        int idx = toml.indexOf('[', from);
        while (idx >= 0) {
            // Confirm this '[' starts a line (ignoring leading whitespace) —
            // avoids false positives from '[' appearing inside array values.
            int lineStart = toml.lastIndexOf('\n', idx) + 1;
            String prefix = toml.substring(lineStart, idx).trim();
            if (prefix.isEmpty()) return idx;
            idx = toml.indexOf('[', idx + 1);
        }
        return -1;
    }

    /** Extracts a `key="value"` (or key = "value") string from a TOML block. */
    private static String extractTomlString(String toml, String key) {
        // Match key at the start of a line (allowing leading whitespace),
        // to avoid accidentally matching a substring inside another value.
        String[] lines = toml.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue; // comment line
            if (!trimmed.startsWith(key)) continue;

            String rest = trimmed.substring(key.length()).trim();
            if (rest.isEmpty() || rest.charAt(0) != '=') continue;
            rest = rest.substring(1).trim();

            if (rest.startsWith("\"")) {
                int end = rest.indexOf('"', 1);
                if (end > 0) return rest.substring(1, end);
            }
        }
        return null;
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
                System.err.println("[ForgeResolver] BlockState parse failed for " + id + ": " + e.getMessage());
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
            System.err.println("[ForgeResolver] Model resolve failed for " + modelPath + ": " + e.getMessage());
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
                System.err.println("[ForgeResolver] Texture load failed for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public String name() {
        return "ForgeResolver(" + detectedMods.size() + " mods, " + namespaceJars.size() + " namespaces)";
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
