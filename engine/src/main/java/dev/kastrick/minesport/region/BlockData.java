package dev.kastrick.minesport.region;

import java.util.Map;
import java.util.Collections;

/**
 * A single block in the world.
 * Stores position, block ID (namespaced like "minecraft:stone"),
 * and its block state properties (e.g. facing=north, waterlogged=false).
 */
public class BlockData {

    public final int x, y, z;
    public final String blockId;           // e.g. "minecraft:oak_fence"
    public final Map<String, String> properties; // e.g. {north=true, south=false}

    // Multipart connection flags (resolved in Pass 2)
    public boolean connectNorth = false;
    public boolean connectSouth = false;
    public boolean connectEast  = false;
    public boolean connectWest  = false;
    public boolean connectUp    = false;

    public boolean isMultipart  = false;   // flagged in Pass 1

    /**
     * Internal, export-only hint pointing at a verified runtime model registry.
     * This is deliberately not a Minecraft state property, so local cache paths
     * never leak into exported block metadata or affect block-state matching.
     */
    public transient String runtimeRegistryPath = "";

    public BlockData(int x, int y, int z, String blockId, Map<String, String> properties) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
        this.properties = properties != null ? properties : Collections.emptyMap();
    }

    /** Convenience — check a property value */
    public String prop(String key) {
        return properties.getOrDefault(key, "");
    }

    /**
     * Blocks that occupy no visible export geometry.
     *
     * minecraft:light intentionally behaves like air for mesh generation so an
     * invisible level-6 Light block never becomes a resolver fallback cube. The
     * BlockData record itself is still retained in the world scan, allowing the
     * Minesport light exporter to preserve its level and create a real DCC light.
     */
    public boolean isAir() {
        return blockId.equals("minecraft:air")
            || blockId.equals("minecraft:cave_air")
            || blockId.equals("minecraft:void_air")
            || blockId.equals("minecraft:light");
    }

    @Override
    public String toString() {
        return blockId + properties + " @ (" + x + "," + y + "," + z + ")";
    }
}
