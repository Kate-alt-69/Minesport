package dev.kastrick.minesport.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Reads vanilla Minecraft assets directly from the minecraft client jar.
 *
 * The jar lives at:
 *   %APPDATA%\.minecraft\versions\<version>\<version>.jar  (Windows)
 *   ~/.minecraft/versions/<version>/<version>.jar           (Linux/Mac)
 *
 * Inside modern jars, assets are at:
 *   assets/minecraft/blockstates/<name>.json
 *   assets/minecraft/models/block/<name>.json
 *   assets/minecraft/textures/block/<name>.png
 *
 * When a local vanilla jar exists but is incomplete/modded and a texture cannot
 * be resolved, this resolver lazily fetches Mojang's official client jar for the
 * same version through piston-meta / piston-data and tries the texture again.
 * The official jar is SHA-1 verified and cached locally.
 */
public class VanillaResolver implements AssetResolver {

    private static final String VERSION_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "((?:1\\.[0-9]+(?:\\.[0-9]+)?)|(?:2[0-9]\\.[0-9]+(?:\\.[0-9]+)?))"
    );

    private final File jarFile;
    private final boolean allowPistonFallback;
    private final String minecraftVersion;
    private ZipFile zip;

    // Caches to avoid re-reading from zip on every block
    private final Map<String, BlockState> stateCache  = new ConcurrentHashMap<>();
    private final Map<String, BlockModel> modelCache  = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache = new ConcurrentHashMap<>();
    private final Set<String> pistonTextureMisses = ConcurrentHashMap.newKeySet();

    private volatile VanillaResolver pistonFallback;
    private volatile boolean pistonFallbackAttempted;

    // Built-in parent models that define geometry but have no JSON in jar
    // (these are "virtual" models — their geometry is implicit)
    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public VanillaResolver(File jarFile) throws IOException {
        this(jarFile, null, true);
    }

    private VanillaResolver(File jarFile, String explicitVersion, boolean allowPistonFallback)
        throws IOException {
        this.jarFile = jarFile;
        this.zip = new ZipFile(jarFile);
        this.allowPistonFallback = allowPistonFallback;
        this.minecraftVersion = explicitVersion == null || explicitVersion.isBlank()
            ? detectMinecraftVersion(jarFile, zip)
            : explicitVersion;
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
        BufferedImage local = resolveTextureLocal(texturePath);
        if (local != null) return local;

        if (!allowPistonFallback || minecraftVersion == null || minecraftVersion.isBlank()) {
            return null;
        }

        VanillaResolver official = getPistonFallback();
        if (official == null) return null;

        BufferedImage recovered = official.resolveTextureLocal(texturePath);
        if (recovered != null) {
            texCache.put(texturePath, recovered);
            System.err.println(
                "[VanillaResolver] Recovered missing texture from official Piston client "
                + minecraftVersion + ": " + texturePath
            );
            return recovered;
        }

        if (pistonTextureMisses.add(texturePath)) {
            System.err.println(
                "[VanillaResolver] Official Piston client also lacks texture: " + texturePath
            );
        }
        return null;
    }

    @Override
    public String name() { return "VanillaResolver(" + jarFile.getName() + ")"; }

    private BufferedImage resolveTextureLocal(String texturePath) {
        if (texturePath == null || texturePath.isBlank() || texturePath.startsWith("#")) {
            return null;
        }
        BufferedImage cached = texCache.get(texturePath);
        if (cached != null) return cached;

        String normalized = normalizeTexturePath(texturePath);
        String jarPath = "assets/" + normalized.replace(":", "/textures/") + ".png";
        try (InputStream in = openEntry(jarPath)) {
            if (in == null) return null;
            BufferedImage image = ImageIO.read(in);
            if (image != null) texCache.put(texturePath, image);
            return image;
        } catch (Exception e) {
            System.err.println("[VanillaResolver] Failed to load texture " + texturePath + ": " + e.getMessage());
            return null;
        }
    }

    private VanillaResolver getPistonFallback() {
        VanillaResolver ready = pistonFallback;
        if (ready != null) return ready;
        if (pistonFallbackAttempted) return null;

        synchronized (this) {
            if (pistonFallback != null) return pistonFallback;
            if (pistonFallbackAttempted) return null;
            pistonFallbackAttempted = true;

            System.err.println(
                "[VanillaResolver] Local texture miss — checking official Piston data for Minecraft "
                + minecraftVersion
            );
            File officialJar = findOfficialMinecraftJar(minecraftVersion);
            if (officialJar == null || !officialJar.isFile()) {
                System.err.println(
                    "[VanillaResolver] Official Piston fallback unavailable for Minecraft "
                    + minecraftVersion
                );
                return null;
            }

            try {
                pistonFallback = new VanillaResolver(officialJar, minecraftVersion, false);
                return pistonFallback;
            } catch (IOException e) {
                System.err.println(
                    "[VanillaResolver] Could not open official Piston client jar: " + e.getMessage()
                );
                return null;
            }
        }
    }

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

        VanillaResolver fallback = pistonFallback;
        if (fallback != null) fallback.close();
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

        // Not found locally — use the same verified official Piston cache used
        // by the missing-texture fallback.
        return findOfficialMinecraftJar(version);
    }

    /**
     * Find or download Mojang's official client jar for a specific version.
     *
     * Metadata comes from piston-meta.mojang.com. The actual client URL is read
     * from the version metadata and normally points at piston-data.mojang.com.
     * The downloaded jar must match Mojang's SHA-1 before it is accepted.
     */
    public static File findOfficialMinecraftJar(String version) {
        if (version == null || version.isBlank()) return null;

        try {
            File cacheDir = new File(
                System.getProperty("java.io.tmpdir"),
                "minesport_jars/official/" + safeVersion(version)
            );
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw new IOException("could not create cache directory " + cacheDir);
            }

            File jarFile = new File(cacheDir, "client.jar");
            File shaFile = new File(cacheDir, "client.sha1");

            // Fast offline path: a previously verified cache can be reused
            // without contacting Mojang again.
            if (jarFile.isFile() && shaFile.isFile()) {
                String cachedSha = Files.readString(shaFile.toPath(), StandardCharsets.UTF_8).trim();
                if (isSha1(cachedSha) && sha1(jarFile).equalsIgnoreCase(cachedSha)) {
                    return jarFile;
                }
            }

            JsonObject versionMetadata = fetchVersionMetadata(version);
            if (versionMetadata == null) return null;
            JsonObject downloads = versionMetadata.getAsJsonObject("downloads");
            JsonObject client = downloads == null ? null : downloads.getAsJsonObject("client");
            if (client == null || !client.has("url") || !client.has("sha1")) {
                System.err.println("[VanillaResolver] Version metadata has no client download for " + version);
                return null;
            }

            String url = client.get("url").getAsString();
            String expectedSha = client.get("sha1").getAsString();
            if (!isSha1(expectedSha)) {
                System.err.println("[VanillaResolver] Invalid client SHA-1 in metadata for " + version);
                return null;
            }

            if (jarFile.isFile() && sha1(jarFile).equalsIgnoreCase(expectedSha)) {
                Files.writeString(shaFile.toPath(), expectedSha, StandardCharsets.UTF_8);
                return jarFile;
            }

            System.err.println(
                "[VanillaResolver] Downloading official Minecraft " + version
                + " client from Piston data..."
            );
            downloadVerified(URI.create(url), jarFile, expectedSha);
            Files.writeString(shaFile.toPath(), expectedSha, StandardCharsets.UTF_8);
            System.err.println("[VanillaResolver] Official client cached at " + jarFile.getAbsolutePath());
            return jarFile;

        } catch (Exception e) {
            System.err.println("[VanillaResolver] Piston client lookup failed: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject fetchVersionMetadata(String version) throws IOException {
        JsonObject manifest = JsonParser.parseString(
            readTextWithRetries(URI.create(VERSION_MANIFEST_URL))
        ).getAsJsonObject();
        JsonArray versions = manifest.getAsJsonArray("versions");
        if (versions == null) {
            throw new IOException("Piston version manifest contains no versions array");
        }

        String metadataUrl = null;
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("id") || !entry.has("url")) continue;
            if (version.equals(entry.get("id").getAsString())) {
                metadataUrl = entry.get("url").getAsString();
                break;
            }
        }
        if (metadataUrl == null) {
            System.err.println("[VanillaResolver] Version " + version + " not present in Piston manifest");
            return null;
        }

        return JsonParser.parseString(readTextWithRetries(URI.create(metadataUrl))).getAsJsonObject();
    }

    private static String readTextWithRetries(URI uri) throws IOException {
        Exception last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "Minesport/0.1")
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                last = new IOException("HTTP " + response.statusCode() + " from " + uri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + uri, e);
            } catch (Exception e) {
                last = e;
            }

            if (attempt < DOWNLOAD_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }
        throw new IOException(
            "download failed after " + DOWNLOAD_ATTEMPTS + " attempts: " + uri,
            last
        );
    }

    private static void downloadVerified(URI uri, File destination, String expectedSha) throws IOException {
        Exception last = null;
        File part = new File(destination.getParentFile(), destination.getName() + ".part");

        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(part.toPath());
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Minesport/0.1")
                    .GET()
                    .build();
                HttpResponse<InputStream> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try { response.body().close(); } catch (Exception ignored) {}
                    throw new IOException("HTTP " + response.statusCode() + " from " + uri);
                }

                try (InputStream in = response.body(); OutputStream out = new FileOutputStream(part)) {
                    in.transferTo(out);
                }

                String actualSha = sha1(part);
                if (!actualSha.equalsIgnoreCase(expectedSha)) {
                    throw new IOException(
                        "SHA-1 mismatch: expected " + expectedSha + ", got " + actualSha
                    );
                }

                try {
                    Files.move(
                        part.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (IOException atomicMoveFailed) {
                    Files.move(
                        part.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
                return;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + uri, e);
            } catch (Exception e) {
                last = e;
                try { Files.deleteIfExists(part.toPath()); } catch (IOException ignored) {}
            }

            if (attempt < DOWNLOAD_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }

        throw new IOException(
            "download failed after " + DOWNLOAD_ATTEMPTS + " attempts: " + uri,
            last
        );
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during retry backoff", e);
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isSha1(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{40}");
    }

    private static String safeVersion(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String detectMinecraftVersion(File jarFile, ZipFile zip) {
        // Modern vanilla jars contain version.json; prefer that because launcher
        // layouts and custom filenames are not always predictable.
        try {
            ZipEntry versionEntry = zip.getEntry("version.json");
            if (versionEntry != null) {
                try (InputStream in = zip.getInputStream(versionEntry)) {
                    JsonObject versionJson = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                    ).getAsJsonObject();
                    if (versionJson.has("id")) {
                        String id = versionJson.get("id").getAsString();
                        if (!id.isBlank()) return id;
                    }
                }
            }
        } catch (Exception ignored) {}

        String filename = jarFile.getName();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        if (filename.startsWith("minecraft-") && filename.endsWith("-client")) {
            filename = filename.substring("minecraft-".length(), filename.length() - "-client".length());
        }
        if (looksLikeVersion(filename)) return filename;

        File parent = jarFile.getParentFile();
        if (parent != null && looksLikeVersion(parent.getName())) return parent.getName();

        Matcher matcher = VERSION_PATTERN.matcher(jarFile.getAbsolutePath());
        String found = null;
        while (matcher.find()) found = matcher.group(1);
        return found;
    }

    private static boolean looksLikeVersion(String value) {
        return value != null && VERSION_PATTERN.matcher(value).matches();
    }
}
