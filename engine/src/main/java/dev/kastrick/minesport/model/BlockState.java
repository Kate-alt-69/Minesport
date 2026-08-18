package dev.kastrick.minesport.model;

import java.util.*;

/**
 * Parsed Minecraft blockstate JSON.
 *
 * Two formats exist:
 *
 * 1. Variants — each key is a property combo:
 *    { "variants": { "facing=north": { "model": "block/furnace" } } }
 *
 * 2. Multipart — list of condition/apply pairs:
 *    { "multipart": [ { "when": {"north":"true"}, "apply": {"model":"..."} } ] }
 */
public class BlockState {

    public enum Format { VARIANTS, MULTIPART }

    public Format format;

    // VARIANTS mode: property combo string → list of model applications (rotations etc)
    public Map<String, List<ModelApplication>> variants = new LinkedHashMap<>();

    // MULTIPART mode: list of conditional parts
    public List<MultipartPart> multiparts = new ArrayList<>();

    // ── Model application ─────────────────────────────────────────────────────

    public static class ModelApplication {
        public String modelPath;    // e.g. "block/oak_fence_side"
        public int x = 0;           // blockstate rotation around X axis (0,90,180,270)
        public int y = 0;           // blockstate rotation around Y axis
        public boolean uvlock = false;
        public float weight = 1;    // for random variant selection
    }

    // ── Multipart ─────────────────────────────────────────────────────────────

    public static class MultipartPart {
        /** null = always apply; otherwise a property condition */
        public Map<String, String> when = null;
        /** OR conditions: list of property maps — any match = apply */
        public List<Map<String, String>> whenOr = null;
        public List<ModelApplication> apply = new ArrayList<>();
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Resolve which model applications to use for a given set of block properties.
     * Returns all matching ModelApplications (there can be multiple for multipart).
     */
    public List<ModelApplication> resolve(Map<String, String> props) {
        if (format == Format.VARIANTS) {
            return resolveVariants(props);
        } else {
            return resolveMultipart(props);
        }
    }

    private List<ModelApplication> resolveVariants(Map<String, String> props) {
        // Build property string from block properties
        // Try exact match first, then partial matches
        if (variants.containsKey("")) {
            return variants.get(""); // single-variant block
        }

        // Build all possible key combos that match the props
        for (var entry : variants.entrySet()) {
            if (matchesVariantKey(entry.getKey(), props)) {
                return entry.getValue();
            }
        }

        // Fallback: first variant
        if (!variants.isEmpty()) {
            return variants.values().iterator().next();
        }
        return List.of();
    }

    private boolean matchesVariantKey(String key, Map<String, String> props) {
        if (key.isEmpty()) return true;
        // Key format: "facing=north,powered=false"
        String[] conditions = key.split(",");
        for (String cond : conditions) {
            String[] kv = cond.split("=", 2);
            if (kv.length != 2) continue;
            String actual = props.getOrDefault(kv[0].trim(), "");
            if (!actual.equals(kv[1].trim())) return false;
        }
        return true;
    }

    private List<ModelApplication> resolveMultipart(Map<String, String> props) {
        var result = new ArrayList<ModelApplication>();
        for (MultipartPart part : multiparts) {
            if (partMatches(part, props)) {
                result.addAll(part.apply);
            }
        }
        return result;
    }

    private boolean partMatches(MultipartPart part, Map<String, String> props) {
        if (part.when == null && part.whenOr == null) return true; // always applies

        if (part.whenOr != null) {
            // OR condition — any map that fully matches
            for (var cond : part.whenOr) {
                if (conditionMatches(cond, props)) return true;
            }
            return false;
        }

        return conditionMatches(part.when, props);
    }

    private boolean conditionMatches(Map<String, String> cond, Map<String, String> props) {
        for (var entry : cond.entrySet()) {
            String actual = props.getOrDefault(entry.getKey(), "");
            // Condition value may be pipe-separated: "true|false"
            String[] options = entry.getValue().split("\\|");
            boolean anyMatch = false;
            for (String opt : options) {
                if (actual.equals(opt.trim())) { anyMatch = true; break; }
            }
            if (!anyMatch) return false;
        }
        return true;
    }
}
