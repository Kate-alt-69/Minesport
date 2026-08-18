package dev.kastrick.minesport.datapack;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Reads block-related data from data packs.
 *
 * IMPORTANT: data packs do NOT contain block geometry, textures, or block
 * entity definitions — those live in resource packs (see ResourcePackResolver)
 * or mod code. A data pack can't make a new block exist or look like
 * anything; it can only reference blocks that already exist.
 *
 * What a data pack CAN meaningfully tell us about blocks:
 *   - Block tags: data/<namespace>/tags/block/<n>.json
 *     e.g. #minesport:decorative → [minecraft:oak_fence, mymod:ruby_block, ...]
 *     Useful for grouping/filtering an export by a tag a pack defines
 *     ("only export blocks tagged #myworld:buildable", etc.)
 *
 * Both the modern tag directory name ("tags/block/") and the pre-1.20.2
 * name ("tags/blocks/") are supported, since older packs still use the
 * plural form.
 *
 * A data pack can be a loose folder or a .zip — this reader accepts both,
 * and can also auto-discover the packs already sitting in a world's own
 * <world>/datapacks/ folder.
 */
public class DataPackBlockTagReader {

    // tagId ("namespace:path", no leading '#') → raw entries as written in the JSON
    // (block ids like "minecraft:stone" and/or tag refs like "#minecraft:logs")
    private final Map<String, List<String>> rawTags = new LinkedHashMap<>();

    private DataPackBlockTagReader() {}

    // ── Loading ────────────────────────────────────────────────────────────────

    /** Auto-discover data packs bundled inside a world save's datapacks/ folder. */
    public static List<File> discoverWorldDataPacks(File worldFolder) {
        List<File> found = new ArrayList<>();
        File dpDir = new File(worldFolder, "datapacks");
        File[] entries = dpDir.listFiles();
        if (entries == null) return found;
        for (File e : entries) {
            if (e.isDirectory() || e.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                found.add(e);
            }
        }
        return found;
    }

    /**
     * Load block tags from one or more data packs (folders or .zip files).
     * Later packs in the list can add to (or, if "replace":true, replace)
     * a tag already defined by an earlier pack, matching vanilla layering.
     */
    public static DataPackBlockTagReader load(List<File> dataPackPaths, java.util.function.Consumer<String> log) {
        DataPackBlockTagReader reader = new DataPackBlockTagReader();
        if (dataPackPaths == null) return reader;

        for (File pack : dataPackPaths) {
            try {
                if (pack.isDirectory()) {
                    reader.loadFolder(pack, log);
                } else if (pack.isFile()) {
                    reader.loadZip(pack, log);
                }
            } catch (Exception e) {
                if (log != null) log.accept("[DataPackBlockTagReader] Failed to load " + pack + ": " + e.getMessage());
            }
        }
        return reader;
    }

    private void loadFolder(File root, java.util.function.Consumer<String> log) throws IOException {
        File dataDir = new File(root, "data");
        File[] namespaceDirs = dataDir.listFiles(File::isDirectory);
        if (namespaceDirs == null) return;

        int count = 0;
        for (File nsDir : namespaceDirs) {
            String namespace = nsDir.getName();
            for (String tagsDirName : new String[]{"tags/block", "tags/blocks"}) {
                File tagsDir = new File(nsDir, tagsDirName);
                count += loadTagFilesFromFolder(tagsDir, namespace);
            }
        }
        if (log != null && count > 0) log.accept("[DataPackBlockTagReader] " + root.getName() + ": " + count + " block tag(s)");
    }

    private int loadTagFilesFromFolder(File tagsDir, String namespace) throws IOException {
        if (!tagsDir.isDirectory()) return 0;
        int count = 0;
        for (File f : listJsonRecursively(tagsDir)) {
            String relative = tagsDir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
            String tagPath = relative.substring(0, relative.length() - ".json".length());
            try (InputStream in = new FileInputStream(f)) {
                parseTagFile(namespace + ":" + tagPath, in);
                count++;
            }
        }
        return count;
    }

    private List<File> listJsonRecursively(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return out;
        for (File c : children) {
            if (c.isDirectory()) out.addAll(listJsonRecursively(c));
            else if (c.getName().endsWith(".json")) out.add(c);
        }
        return out;
    }

    private void loadZip(File zipFile, java.util.function.Consumer<String> log) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".json")) continue;
                if (!name.startsWith("data/")) continue;

                String rest = name.substring("data/".length());
                int nsSlash = rest.indexOf('/');
                if (nsSlash < 0) continue;
                String namespace = rest.substring(0, nsSlash);
                String afterNs = rest.substring(nsSlash + 1);

                String tagPath = null;
                for (String tagsDirName : new String[]{"tags/block/", "tags/blocks/"}) {
                    if (afterNs.startsWith(tagsDirName)) {
                        String p = afterNs.substring(tagsDirName.length());
                        tagPath = p.substring(0, p.length() - ".json".length());
                        break;
                    }
                }
                if (tagPath == null) continue;

                try (InputStream in = zip.getInputStream(entry)) {
                    parseTagFile(namespace + ":" + tagPath, in);
                    count++;
                }
            }
            if (log != null && count > 0) log.accept("[DataPackBlockTagReader] " + zipFile.getName() + ": " + count + " block tag(s)");
        }
    }

    private void parseTagFile(String tagId, InputStream in) {
        try {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            boolean replace = root.has("replace") && root.get("replace").getAsBoolean();

            List<String> values = new ArrayList<>();
            if (root.has("values")) {
                for (JsonElement el : root.getAsJsonArray("values")) {
                    // Entries can be plain strings, or objects like {"id": "...", "required": false}
                    if (el.isJsonPrimitive()) {
                        values.add(el.getAsString());
                    } else if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                        values.add(el.getAsJsonObject().get("id").getAsString());
                    }
                }
            }

            if (replace || !rawTags.containsKey(tagId)) {
                rawTags.put(tagId, values);
            } else {
                rawTags.get(tagId).addAll(values);
            }
        } catch (Exception e) {
            System.err.println("[DataPackBlockTagReader] Failed to parse tag " + tagId + ": " + e.getMessage());
        }
    }

    // ── Resolution ─────────────────────────────────────────────────────────────

    /**
     * Flattens a tag (and any nested tag references inside it) into a concrete
     * set of block IDs. Returns an empty set if the tag doesn't exist.
     * Cycle-safe.
     */
    public Set<String> resolveTag(String tagId) {
        Set<String> result = new LinkedHashSet<>();
        resolveTagInto(tagId, result, new HashSet<>());
        return result;
    }

    private void resolveTagInto(String tagId, Set<String> out, Set<String> visiting) {
        if (!visiting.add(tagId)) return; // cycle guard
        List<String> values = rawTags.get(tagId);
        if (values == null) return;

        for (String v : values) {
            if (v.startsWith("#")) {
                resolveTagInto(v.substring(1), out, visiting);
            } else {
                out.add(v);
            }
        }
    }

    /** All tag IDs this reader found (without the leading '#'). */
    public Set<String> getTagIds() {
        return Collections.unmodifiableSet(rawTags.keySet());
    }

    public boolean isEmpty() { return rawTags.isEmpty(); }
}
