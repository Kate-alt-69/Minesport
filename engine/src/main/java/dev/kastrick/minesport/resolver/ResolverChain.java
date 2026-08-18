package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Chains multiple AssetResolvers together.
 * For each lookup, tries resolvers in order and returns the first hit.
 *
 * Priority order:
 *   1. VanillaResolver  (minecraft: namespace)
 *   2. FabricResolver   (mod namespaces, Fabric loader)
 *   3. ForgeResolver    (mod namespaces, Forge/NeoForge)
 *   ... etc
 *
 * This means mods can override vanilla assets (resource pack style)
 * if a mod jar contains assets/minecraft/... entries.
 */
public class ResolverChain {

    private final List<AssetResolver> resolvers = new ArrayList<>();

    public void addResolver(AssetResolver resolver) {
        resolvers.add(resolver);
    }

    // ── Lookup methods ────────────────────────────────────────────────────────

    public BlockState resolveBlockState(String blockId) {
        for (AssetResolver r : resolvers) {
            if (!r.canResolve(blockId)) continue;
            BlockState bs = r.resolveBlockState(blockId);
            if (bs != null) return bs;
        }
        System.err.println("[ResolverChain] No blockstate found for: " + blockId);
        return null;
    }

    public BlockModel resolveModel(String modelPath) {
        // Work out which namespace this model belongs to
        String ns = modelPath.contains(":") ? modelPath.split(":")[0] : "minecraft";
        String blockId = ns + ":__model__"; // dummy ID just for canResolve namespace check

        for (AssetResolver r : resolvers) {
            if (!r.canResolve(blockId)) continue;
            BlockModel model = r.resolveModel(modelPath);
            if (model != null) return model;
        }
        System.err.println("[ResolverChain] No model found for: " + modelPath);
        return null;
    }

    public BufferedImage resolveTexture(String texturePath) {
        String ns = texturePath.contains(":") ? texturePath.split(":")[0] : "minecraft";
        String blockId = ns + ":__texture__";

        for (AssetResolver r : resolvers) {
            if (!r.canResolve(blockId)) continue;
            BufferedImage img = r.resolveTexture(texturePath);
            if (img != null) return img;
        }
        System.err.println("[ResolverChain] No texture found for: " + texturePath);
        return null;
    }

    public List<AssetResolver> getResolvers() {
        return Collections.unmodifiableList(resolvers);
    }

    public int size() { return resolvers.size(); }
}
