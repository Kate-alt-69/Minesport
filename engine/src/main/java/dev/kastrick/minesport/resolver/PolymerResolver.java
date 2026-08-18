package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

/**
 * Resolves Polymer-based mod blocks.
 *
 * The problem: Polymer mods store geometry as standard JSON model files
 * inside their jar (assets/modid/models/block/*.json), but they have NO
 * blockstates/ folder. Instead of blockstate JSONs, Polymer uses Java API
 * calls like PolymerBlockModel.of("modid:block/foo") at runtime.
 *
 * The solution: When a blockstate lookup fails for a mod block, we apply
 * the Polymer naming convention directly:
 *
 *   polydecorations:oak_bench
 *     → try assets/polydecorations/models/block/oak_bench.json
 *     → if found, build a synthetic single-variant blockstate pointing to it
 *
 * This lets us resolve the geometry without needing:
 *   - The generated polymer-rp.zip
 *   - The noteblock → model mapping
 *   - Any runtime Polymer code
 *
 * Texture reuse works automatically — if oak_bench.json references
 * minecraft:block/oak_planks, our existing VanillaResolver handles it.
 *
 * This resolver is used as a FALLBACK — it only activates when
 * FabricResolver.resolveBlockState() returns null (i.e. for Polymer blocks
 * with no blockstates/ JSON in their jar).
 */
public class PolymerResolver implements AssetResolver {

    // The underlying FabricResolver we delegate model/texture lookups to
    private final FabricResolver fabricResolver;

    // Cache of synthetic blockstates we've built
    private final Map<String, BlockState> stateCache = new ConcurrentHashMap<>();

    // Track which block IDs we've confirmed have models (to avoid repeat scans)
    private final Map<String, Boolean> modelExistsCache = new ConcurrentHashMap<>();

    public PolymerResolver(FabricResolver fabricResolver) {
        this.fabricResolver = fabricResolver;
    }

    // ── AssetResolver impl ────────────────────────────────────────────────────

    @Override
    public boolean canResolve(String blockId) {
        if (!blockId.contains(":")) return false;
        String[] parts = blockId.split(":", 2);
        String ns = parts[0];
        // Only handle mod namespaces, not minecraft itself
        return !ns.equals("minecraft") && fabricResolver.getNamespaces().contains(ns);
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        return stateCache.computeIfAbsent(blockId, id -> buildSyntheticBlockState(id));
    }

    @Override
    public BlockModel resolveModel(String modelPath) {
        // Delegate to FabricResolver — it has the jar contents
        return fabricResolver.resolveModel(modelPath);
    }

    @Override
    public BufferedImage resolveTexture(String texturePath) {
        return fabricResolver.resolveTexture(texturePath);
    }

    @Override
    public String name() {
        return "PolymerResolver";
    }

    // ── Synthetic blockstate builder ──────────────────────────────────────────

    /**
     * Builds a synthetic BlockState that points to the most likely model
     * path for this Polymer block, with real keyed facing variants so the
     * normal BlockState.resolve(properties) mechanism — the same one every
     * other resolver relies on — can pick the correct rotation.
     *
     * For a block like "polydecorations:oak_bench" we try:
     *   1. polydecorations:block/oak_bench   (exact name match)
     *   2. a name-suffixed match, e.g. oak_bench_north / _south / _east / _west
     *   3. closest-prefix scan of the jar's models
     *
     * Returns null if no model can be found in the mod jar.
     */
    private BlockState buildSyntheticBlockState(String blockId) {
        String baseId = blockId.contains("[") ? blockId.substring(0, blockId.indexOf('[')) : blockId;
        String[] parts = baseId.split(":", 2);
        String ns = parts[0];
        String name = parts[1];

        // Strategy 1: exact name match
        String exactPath = ns + ":block/" + name;
        if (modelExists(exactPath)) {
            return makeRotatedState(exactPath);
        }

        // Strategy 2: some mods ship the base name with a direction suffix
        // already stripped off elsewhere (e.g. "oak_bench_north" as the
        // actual file) — try that shape directly.
        for (String suffix : new String[]{"_north", "_south", "_east", "_west"}) {
            if (name.endsWith(suffix)) {
                String stripped = name.substring(0, name.length() - suffix.length());
                String strippedPath = ns + ":block/" + stripped;
                if (modelExists(strippedPath)) {
                    return makeRotatedState(strippedPath);
                }
            }
        }

        // Strategy 3: scan the jar for any model whose name starts with the block name
        // Handles cases like "oak_bench" → finds "oak_bench_left", "oak_bench_right" etc.
        String bestMatch = findClosestModel(ns, name);
        if (bestMatch != null) {
            return makeRotatedState(ns + ":block/" + bestMatch);
        }

        // No model found — return null and let GeometryBuilder use fallback cube
        return null;
    }

    /**
     * Builds a BlockState with keyed facing variants — "facing=north",
     * "facing=south", "facing=east", "facing=west" — all pointing at the
     * same resolved model with the correct Y rotation for each direction,
     * matching how a real vanilla blockstate JSON would define them.
     *
     * This used to bake ONE hardcoded rotation into a single always-match
     * ("") variant, guessed from a property lookup that could never
     * succeed (see buildSyntheticBlockState's blockId note) — every
     * Polymer block ended up rotation-locked to north regardless of its
     * actual facing. Building real keyed variants here instead lets
     * BlockState.resolve(block.properties) — called downstream in
     * GeometryBuilder exactly like it is for every other resolver — pick
     * the correct one from the block's real stored properties.
     *
     * Blocks with no "facing" property at all (non-directional decor)
     * simply fall through BlockState's own "no key matched" fallback to
     * the first variant (north / no extra rotation), which is the correct,
     * neutral result for those.
     */
    private BlockState makeRotatedState(String modelPath) {
        var bs = new BlockState();
        bs.format = BlockState.Format.VARIANTS;

        for (var entry : FACING_ROTATIONS.entrySet()) {
            var app = new BlockState.ModelApplication();
            app.modelPath = modelPath;
            app.y = entry.getValue();
            bs.variants.put("facing=" + entry.getKey(), List.of(app));
        }

        return bs;
    }

    private static final Map<String, Integer> FACING_ROTATIONS = Map.of(
        "north", 0,
        "east",  90,
        "south", 180,
        "west",  270
    );

    // ── Model existence check ─────────────────────────────────────────────────

    private boolean modelExists(String modelPath) {
        return modelExistsCache.computeIfAbsent(modelPath, path -> {
            BlockModel m = fabricResolver.resolveModel(path);
            return m != null && !m.isEmpty();
        });
    }

    /**
     * Scan the namespace's jar for model files whose name starts with blockName.
     * Returns the best match (exact or closest prefix).
     */
    private String findClosestModel(String namespace, String blockName) {
        // Ask FabricResolver to list available models in this namespace
        Set<String> available = fabricResolver.listModels(namespace);
        if (available.isEmpty()) return null;

        // Exact match
        if (available.contains(blockName)) return blockName;

        // Prefix match — find shortest model name that starts with blockName
        String best = null;
        for (String model : available) {
            if (model.startsWith(blockName)) {
                if (best == null || model.length() < best.length()) {
                    best = model;
                }
            }
        }
        return best;
    }

}
