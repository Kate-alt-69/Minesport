package dev.kastrick.minesport.model;

import java.util.*;

/**
 * Parsed representation of a Minecraft block model JSON.
 *
 * Example JSON structure:
 * {
 *   "parent": "block/cube_all",
 *   "textures": { "all": "block/stone" },
 *   "elements": [
 *     {
 *       "from": [0,0,0], "to": [16,16,16],
 *       "rotation": { "origin":[8,8,8], "axis":"y", "angle":45 },
 *       "faces": {
 *         "north": { "uv":[0,0,16,16], "texture":"#all", "cullface":"north" }
 *       }
 *     }
 *   ]
 * }
 */
public class BlockModel {

    public String parentId;                          // e.g. "block/cube_all"
    public Map<String, String> textures = new HashMap<>(); // variable -> texture path
    public List<Element> elements = new ArrayList<>();
    public boolean ambientOcclusion = true;

    // ── Element ───────────────────────────────────────────────────────────────

    public static class Element {
        public float[] from = {0, 0, 0};   // in 1/16 units (0-16)
        public float[] to   = {16,16,16};
        public Rotation rotation = null;
        public Map<String, Face> faces = new LinkedHashMap<>(); // direction -> face
        public boolean shade = true;
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    public static class Rotation {
        public float[] origin = {8, 8, 8};
        public String axis   = "y";        // x, y, z
        public float angle   = 0;          // -45, -22.5, 0, 22.5, 45
        public boolean rescale = false;
    }

    // ── Face ──────────────────────────────────────────────────────────────────

    public static class Face {
        public float[] uv = null;          // [x1,y1,x2,y2] in 0-16 space; null = auto
        public String texture;             // e.g. "#all" or "block/stone"
        public String cullface = null;     // direction to cull against
        public int rotation = 0;          // UV rotation: 0, 90, 180, 270
        public int tintindex = -1;        // -1 = no tint

        /** Resolve texture variable → actual path using the model's texture map. */
        public String resolveTexture(Map<String, String> texMap) {
            String t = texture;
            // Follow chain of variable references: #all → #side → block/stone
            int depth = 0;
            while (t != null && t.startsWith("#") && depth < 10) {
                String key = t.substring(1);
                String resolved = texMap.get(key);
                if (resolved == null) return t; // unresolved
                t = resolved;
                depth++;
            }
            return t;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True if this model has no elements (likely a parent-only model). */
    public boolean isEmpty() {
        return elements == null || elements.isEmpty();
    }

    /** Merge parent textures into this model (parent provides fallback values). */
    public void mergeTextures(Map<String, String> parentTextures) {
        for (var entry : parentTextures.entrySet()) {
            textures.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }
}
