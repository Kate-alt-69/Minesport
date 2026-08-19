package dev.kastrick.minesport.model;

import java.util.*;

/** Parsed Minecraft blockstate JSON. */
public class BlockState {

    public enum Format { VARIANTS, MULTIPART }
    public Format format;
    public Map<String, List<ModelApplication>> variants = new LinkedHashMap<>();
    public List<MultipartPart> multiparts = new ArrayList<>();

    public static class ModelApplication {
        public String modelPath;
        public int x = 0;
        public int y = 0;
        public boolean uvlock = false;
        public float weight = 1;
    }

    public static class MultipartPart {
        public Map<String, String> when = null;
        public List<Map<String, String>> whenOr = null;
        public List<ModelApplication> apply = new ArrayList<>();
    }

    /**
     * Resolve a blockstate. For weighted variant arrays, Minecraft selects
     * one model; returning every weighted entry would duplicate geometry on
     * the exported block. This overload uses a stable state-only seed and is
     * retained for callers that do not have world coordinates available.
     */
    public List<ModelApplication> resolve(Map<String, String> props) {
        return resolve(props, 0, 0, 0);
    }

    /** Resolve with world coordinates so repeated blocks can receive stable variation. */
    public List<ModelApplication> resolve(Map<String, String> props, int x, int y, int z) {
        if (format == Format.VARIANTS) return resolveVariants(props, stableSeed(x, y, z));
        return resolveMultipart(props);
    }

    private List<ModelApplication> resolveVariants(Map<String, String> props, long seed) {
        if (variants.containsKey("")) return chooseWeighted(variants.get(""), seed);

        for (var entry : variants.entrySet()) {
            if (matchesVariantKey(entry.getKey(), props)) {
                return chooseWeighted(entry.getValue(), seed);
            }
        }

        if (!variants.isEmpty()) {
            return chooseWeighted(variants.values().iterator().next(), seed);
        }
        return List.of();
    }

    private List<ModelApplication> chooseWeighted(List<ModelApplication> candidates, long seed) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() == 1) return List.of(candidates.getFirst());

        double total = 0.0;
        for (ModelApplication app : candidates) total += Math.max(0.0, app.weight);
        if (total <= 0.0) return List.of(candidates.getFirst());

        double position = unsignedFraction(seed) * total;
        for (ModelApplication app : candidates) {
            double weight = Math.max(0.0, app.weight);
            if (position < weight) return List.of(app);
            position -= weight;
        }
        return List.of(candidates.getLast());
    }

    private static double unsignedFraction(long seed) {
        long mixed = mix64(seed);
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static long stableSeed(int x, int y, int z) {
        long h = 0x9E3779B97F4A7C15L;
        h ^= x * 0x632BE59BD9B4E019L;
        h = Long.rotateLeft(h, 21);
        h ^= y * 0x9E3779B185EBCA87L;
        h = Long.rotateLeft(h, 17);
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        return mix64(h);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private boolean matchesVariantKey(String key, Map<String, String> props) {
        if (key.isEmpty()) return true;
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
            if (partMatches(part, props)) result.addAll(part.apply);
        }
        return result;
    }

    private boolean partMatches(MultipartPart part, Map<String, String> props) {
        if (part.when == null && part.whenOr == null) return true;
        if (part.whenOr != null) {
            for (var cond : part.whenOr) if (conditionMatches(cond, props)) return true;
            return false;
        }
        return conditionMatches(part.when, props);
    }

    private boolean conditionMatches(Map<String, String> cond, Map<String, String> props) {
        if (cond == null) return false;
        for (var entry : cond.entrySet()) {
            String actual = props.getOrDefault(entry.getKey(), "");
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
