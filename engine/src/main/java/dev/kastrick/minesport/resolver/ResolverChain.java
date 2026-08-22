package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chains multiple asset resolvers together in priority order. */
public class ResolverChain {
    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );
    public static final String CLASSIC_MISSING_SOURCE = "Classic Missing Texture";

    private final List<AssetResolver> resolvers = new ArrayList<>();
    private final Set<String> missingBlockStates = ConcurrentHashMap.newKeySet();
    private final Set<String> missingModels = ConcurrentHashMap.newKeySet();
    private final Set<String> missingTextures = ConcurrentHashMap.newKeySet();
    private final Map<String, String> blockStateSources = new ConcurrentHashMap<>();
    private final Map<String, String> modelSources = new ConcurrentHashMap<>();
    private final Map<String, String> textureSources = new ConcurrentHashMap<>();

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
        if (missingBlockStates.add(blockId)) {
            System.err.println("[ResolverChain] No blockstate found for: " + blockId);
        }
        return null;
    }

    public BlockModel resolveModel(String modelPath) {
        return resolveModel(modelPath, new HashSet<>());
    }

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

            // Resolver implementations historically returned an empty model
            // when an asset was absent. Treat that as a miss so a higher-priority
            // resource pack can override a child while a lower-priority vanilla
            // or mod resolver supplies the missing model. Virtual Minecraft
            // parents are intentionally allowed to be empty.
            if (model.isEmpty() && (model.parentId == null || model.parentId.isBlank())
                    && !VIRTUAL_PARENTS.contains(normalized)) {
                continue;
            }

            // Model inheritance can cross resolver boundaries. A resource-pack
            // model may inherit vanilla/mod geometry; resolve the parent through
            // the whole chain rather than only the current resolver.
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
        if (missingModels.add(normalized)) {
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
            System.err.println(
                "[ResolverChain] No texture found for: " + texturePath
                + " — using classic missing texture"
            );
        }
        return MissingTexture.image();
    }

    public String blockStateSource(String blockId) {
        return blockStateSources.getOrDefault(blockId, "");
    }

    public String modelSource(String modelPath) {
        return modelSources.getOrDefault(normalizeModelPath(modelPath), "");
    }

    public String textureSource(String texturePath) {
        return textureSources.getOrDefault(texturePath, "");
    }

    public Map<String, String> textureSourcesSnapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(textureSources));
    }

    public Map<String, String> modelSourcesSnapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(modelSources));
    }

    public Map<String, String> blockStateSourcesSnapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(blockStateSources));
    }

    private static String normalizeModelPath(String path) {
        if (path == null || path.isBlank()) return "minecraft:";
        return path.contains(":") ? path : "minecraft:" + path;
    }

    public List<AssetResolver> getResolvers() { return Collections.unmodifiableList(resolvers); }
    public int size() { return resolvers.size(); }
}
