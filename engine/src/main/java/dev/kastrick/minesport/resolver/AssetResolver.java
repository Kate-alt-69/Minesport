package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.*;

import java.awt.image.BufferedImage;

/**
 * Common interface for all asset resolvers.
 * VanillaResolver reads from minecraft.jar.
 * FabricResolver / ForgeResolver scan mods folders.
 */
public interface AssetResolver {

    /** Returns true if this resolver can handle the given namespaced block ID. */
    boolean canResolve(String blockId);

    /**
     * Load and parse the blockstate JSON for a block ID.
     * e.g. blockId = "minecraft:oak_fence"
     * → looks for assets/minecraft/blockstates/oak_fence.json
     */
    BlockState resolveBlockState(String blockId);

    /**
     * Load and parse a model JSON by its namespaced path.
     * e.g. modelPath = "minecraft:block/oak_fence_side"
     * → looks for assets/minecraft/models/block/oak_fence_side.json
     */
    BlockModel resolveModel(String modelPath);

    /**
     * Load a texture PNG by its namespaced path.
     * e.g. texturePath = "minecraft:block/oak_planks"
     * → looks for assets/minecraft/textures/block/oak_planks.png
     */
    BufferedImage resolveTexture(String texturePath);

    /** Human-readable name for logging. */
    String name();
}
