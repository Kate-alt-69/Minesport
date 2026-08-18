package dev.kastrick.minesport.export;

/**
 * A single textured quad (4 vertices) ready for OBJ/glTF output.
 *
 * verts[4][3] — four world-space XYZ positions
 * uv[4]       — [u1,v1,u2,v2] in 0-16 Minecraft UV space
 * texturePath — resolved namespaced texture path (e.g. "minecraft:block/oak_planks")
 * normal[3]   — face normal vector
 * cullface    — direction to cull against (may be null)
 * tintindex   — biome tint index (-1 = none)
 */
public record Quad(
    float[][] verts,
    float[] uv,
    String texturePath,
    float[] normal,
    String cullface,
    int tintindex
) {
    /** UV coords as 0-1 floats (Minecraft uses 0-16). */
    public float u1() { return uv[0] / 16f; }
    public float v1() { return uv[1] / 16f; }
    public float u2() { return uv[2] / 16f; }
    public float v2() { return uv[3] / 16f; }

    /** Per-vertex UVs for the quad corners: [BL, BR, TR, TL]. */
    public float[][] vertexUVs() {
        return new float[][]{
            {u1(), v2()},
            {u2(), v2()},
            {u2(), v1()},
            {u1(), v1()}
        };
    }
}
