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

    /**
     * Per-vertex UVs for the quad corners.
     *
     * Minecraft model UVs are face-local: the same [u1,v1,u2,v2] rectangle
     * cannot simply be assigned in the same vertex order for all six faces.
     * The old implementation did exactly that, which mirrors/inverts the
     * texture on the north/west/east faces and becomes especially obvious on
     * doors, trapdoors, signs and other rotated models.
     *
     * Derive the face orientation from the actual world-space geometry so
     * blockstate rotations are handled automatically. This also means a
     * rotated door uses the texture orientation of the face it actually
     * occupies instead of the original model-space face.
     */
    public float[][] vertexUVs() {
        String dir = dominantFaceDirection(verts);

        float u1 = u1();
        float v1 = v1();
        float u2 = u2();
        float v2 = v2();

        return switch (dir) {
            case "north" -> new float[][]{
                {u2, v2},
                {u1, v2},
                {u1, v1},
                {u2, v1}
            };
            case "south" -> new float[][]{
                {u1, v2},
                {u2, v2},
                {u2, v1},
                {u1, v1}
            };
            case "west" -> new float[][]{
                {u2, v2},
                {u1, v2},
                {u1, v1},
                {u2, v1}
            };
            case "east" -> new float[][]{
                {u1, v2},
                {u2, v2},
                {u2, v1},
                {u1, v1}
            };
            case "up" -> new float[][]{
                {u1, v1},
                {u2, v1},
                {u2, v2},
                {u1, v2}
            };
            case "down" -> new float[][]{
                {u1, v2},
                {u1, v1},
                {u2, v1},
                {u2, v2}
            };
            default -> new float[][]{
                {u1, v2},
                {u2, v2},
                {u2, v1},
                {u1, v1}
            };
        };
    }

    /** Pick the dominant axis of the quad's actual world-space normal. */
    private static String dominantFaceDirection(float[][] p) {
        if (p == null || p.length < 3) return "south";

        float ax = p[0][0], ay = p[0][1], az = p[0][2];
        float bx = p[1][0], by = p[1][1], bz = p[1][2];
        float cx = p[2][0], cy = p[2][1], cz = p[2][2];

        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;

        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;

        float absX = Math.abs(nx);
        float absY = Math.abs(ny);
        float absZ = Math.abs(nz);

        if (absY >= absX && absY >= absZ) return ny >= 0 ? "up" : "down";
        if (absX >= absY && absX >= absZ) return nx >= 0 ? "east" : "west";
        return nz >= 0 ? "south" : "north";
    }
}
