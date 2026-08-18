package dev.kastrick.minesport.export;

/**
 * Coarse geometry classification used by the world-mapping stage.
 *
 * The important distinction is FULL_BLOCK: these blocks can eventually be
 * represented by a canonical reusable cube/template instead of rebuilding
 * their geometry for every world position.
 */
public enum BlockGeometryKind {
    AIR,
    FULL_BLOCK,
    PARTIAL_BLOCK,
    MULTIPART,
    FLUID,
    BLOCK_ENTITY,
    CUSTOM,
    UNKNOWN
}
