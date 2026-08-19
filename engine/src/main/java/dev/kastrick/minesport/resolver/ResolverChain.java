package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import java.awt.image.BufferedImage;
import java.util.*;

/** Chains multiple asset resolvers together in priority order. */
public class ResolverChain {
    private final List<AssetResolver> resolvers = new ArrayList<>();
    private final Set<String> missingBlockStates = ConcurrentHashMap.newKeySet();
    private final Set<String> missingModels = ConcurrentHashMap.newKeySet();
    private final Set<String> missingTextures = ConcurrentHashMap.newKeySet();

    public void addResolver(AssetResolver resolver) { resolvers.add(resolver); }

    public BlockState resolveBlockState(String blockId) {
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(blockId)) continue;
            BlockState bs = r.resolveBlockState(blockId);
            if (bs != null) return bs;
        }
        if (missingBlockStates.add(blockId)) System.err.println("[ResolverChain] No blockstate found for: " + blockId);
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

            // Models can inherit from a parent supplied by a lower-priority
            // resolver (e.g. a resource pack child model inheriting vanilla
            // block/cube_all). Resolve the parent through the WHOLE chain,
            // not just the current resolver.
            if (model.parentId != null && !model.parentId.isEmpty()) {
                BlockModel parent = resolveModel(model.parentId, visited);
                if (parent != null) {
                    if (model.isEmpty()) model.elements = parent.elements;
                    model.mergeTextures(parent.textures);
                }
            }
            return model;
        }

        if (missingModels.add(normalized)) System.err.println("[ResolverChain] No model found for: " + normalized);
        return null;
    }

    public BufferedImage resolveTexture(String texturePath) {
        String normalized = texturePath;
        String ns = normalized.contains(":") ? normalized.substring(0, normalized.indexOf(':')) : "minecraft";
        String dummyId = ns + ":__texture__";
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(dummyId)) continue;
            BufferedImage img = r.resolveTexture(normalized);
            if (img != null) return img;
        }
        if (missingTextures.add(texturePath)) System.err.println("[ResolverChain] No texture found for: " + texturePath);
        return null;
    }

    private static String normalizeModelPath(String path) {
        if (path == null || path.isBlank()) return "minecraft:";
        return path.contains(":") ? path : "minecraft:" + path;
    }

    public List<AssetResolver> getResolvers() { return Collections.unmodifiableList(resolvers); }
    public int size() { return resolvers.size(); }
}
