package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chains multiple asset resolvers together in priority order. */
public class ResolverChain implements AutoCloseable {
    private static final ThreadLocal<ResolverChain> CURRENT = new ThreadLocal<>();
    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );
    private static final Set<String> ENTITY_RENDERED_MODELS = Set.of(
        "minecraft:block/chest",
        "minecraft:block/trapped_chest",
        "minecraft:block/ender_chest"
    );
    public static final String CLASSIC_MISSING_SOURCE = "Classic Missing Texture";

    private final List<AssetResolver> resolvers = new ArrayList<>();
    private final Set<String> missingBlockStates = ConcurrentHashMap.newKeySet();
    private final Set<String> missingModels = ConcurrentHashMap.newKeySet();
    private final Set<String> missingTextures = ConcurrentHashMap.newKeySet();
    private final Map<String, String> blockStateSources = new ConcurrentHashMap<>();
    private final Map<String, String> modelSources = new ConcurrentHashMap<>();
    private final Map<String, String> textureSources = new ConcurrentHashMap<>();

    public ResolverChain() { CURRENT.set(this); }
    public static ResolverChain current() { return CURRENT.get(); }

    public void addResolver(AssetResolver resolver) { resolvers.add(resolver); }

    public BlockState resolveBlockState(String blockId) {
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(blockId)) continue;
            BlockState bs = r.resolveBlockState(blockId);
            if (bs != null) {
                blockStateSources.put(blockId, r.name());
                return bs;
            }
        }
        blockStateSources.put(blockId, "missing");
        if (missingBlockStates.add(blockId)) System.err.println("[ResolverChain] No blockstate found for: " + blockId);
        return null;
    }

    public BlockModel resolveModel(String modelPath) { return resolveModel(modelPath, new HashSet<>()); }

    private BlockModel resolveModel(String modelPath, Set<String> visited) {
        String normalized = normalizeModelPath(modelPath);
        if (!visited.add(normalized)) {
            System.err.println("[ResolverChain] Model inheritance cycle: " + normalized);
            return null;
        }
        String ns = normalized.contains(":") ? normalized.substring(0, normalized.indexOf(':')) : "minecraft";
        String dummyId = ns + ":__model__";
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(dummyId)) continue;
            BlockModel model = r.resolveModel(normalized);
            if (model == null) continue;
            if (model.isEmpty() && (model.parentId == null || model.parentId.isBlank())
                    && !VIRTUAL_PARENTS.contains(normalized)) continue;
            if (model.parentId != null && !model.parentId.isEmpty()) {
                BlockModel parent = resolveModel(model.parentId, visited);
                if (parent != null) {
                    if (model.isEmpty()) model.elements = parent.elements;
                    model.mergeTextures(parent.textures);
                }
            }
            modelSources.put(normalized, r.name());
            return model;
        }
        modelSources.put(normalized, "missing");
        if (missingModels.add(normalized) && !ENTITY_RENDERED_MODELS.contains(normalized)) {
            System.err.println("[ResolverChain] No model found for: " + normalized);
        }
        return null;
    }

    public BufferedImage resolveTexture(String texturePath) {
        String normalized = texturePath;
        String ns = normalized.contains(":") ? normalized.substring(0, normalized.indexOf(':')) : "minecraft";
        String dummyId = ns + ":__texture__";
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(dummyId)) continue;
            BufferedImage img = r.resolveTexture(normalized);
            if (img != null) {
                textureSources.put(texturePath, r.name());
                return img;
            }
        }
        textureSources.put(texturePath, CLASSIC_MISSING_SOURCE);
        if (missingTextures.add(texturePath)) {
            System.err.println("[ResolverChain] No texture found for: " + texturePath + " — using classic missing texture");
        }
        return MissingTexture.image();
    }

    /** Resolve .png.mcmeta from the exact resolver that wins the PNG lookup. */
    public String resolveTextureMetadata(String texturePath) {
        String normalized = texturePath;
        String ns = normalized.contains(":") ? normalized.substring(0, normalized.indexOf(':')) : "minecraft";
        String dummyId = ns + ":__texture__";
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(dummyId)) continue;
            BufferedImage img = r.resolveTexture(normalized);
            if (img == null) continue;
            textureSources.put(texturePath, r.name());

            String metadata = r.resolveTextureMetadata(normalized);
            if (metadata == null && r instanceof VanillaResolver vanilla) {
                metadata = AnimationAwareVanillaResolver.readFrom(vanilla, normalized);
            } else if (metadata == null && (r instanceof QuiltResolver || r instanceof ForgeResolver)) {
                metadata = ModJarTextureMetadata.read(r, normalized);
            }

            // The winning PNG owns the animation decision. Null means static;
            // never continue into a lower-priority resolver after this point.
            return metadata;
        }
        return null;
    }

    public String blockStateSource(String blockId) { return blockStateSources.getOrDefault(blockId, ""); }
    public String modelSource(String modelPath) { return modelSources.getOrDefault(normalizeModelPath(modelPath), ""); }
    public String textureSource(String texturePath) { return textureSources.getOrDefault(texturePath, ""); }
    public Map<String, String> textureSourcesSnapshot() { return Collections.unmodifiableMap(new TreeMap<>(textureSources)); }
    public Map<String, String> modelSourcesSnapshot() { return Collections.unmodifiableMap(new TreeMap<>(modelSources)); }
    public Map<String, String> blockStateSourcesSnapshot() { return Collections.unmodifiableMap(new TreeMap<>(blockStateSources)); }

    private static String normalizeModelPath(String path) {
        if (path == null || path.isBlank()) return "minecraft:";
        return path.contains(":") ? path : "minecraft:" + path;
    }

    public List<AssetResolver> getResolvers() { return Collections.unmodifiableList(resolvers); }
    public int size() { return resolvers.size(); }

    /**
     * Close every unique resolver exactly once and release the per-request
     * ThreadLocal. Resolver close failures are deliberately isolated so one bad
     * mod JAR cannot prevent the rest of the handles from being released.
     */
    @Override
    public void close() {
        Set<AssetResolver> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = resolvers.size() - 1; i >= 0; i--) {
            AssetResolver resolver = resolvers.get(i);
            if (resolver == null || !closed.add(resolver)) continue;
            try {
                resolver.close();
            } catch (Exception error) {
                System.err.println(
                    "[ResolverChain] Failed to close " + resolver.name() + ": " + error.getMessage()
                );
            }
        }
        resolvers.clear();
        missingBlockStates.clear();
        missingModels.clear();
        missingTextures.clear();
        blockStateSources.clear();
        modelSources.clear();
        textureSources.clear();
        if (CURRENT.get() == this) CURRENT.remove();
    }
}
