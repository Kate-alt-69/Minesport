package dev.kastrick.minesport.export;

/**
 * A single textured quad (4 vertices) ready for OBJ/glTF output.
 *
 * verts[4][3] — four local/world-space XYZ positions
 * uv[4]       — [u1,v1,u2,v2] in 0-16 Minecraft UV space
 * texturePath — resolved namespaced texture path
 * normal[3]   — legacy/source normal; normal() now derives the actual world normal
 * cullface    — direction to cull against (may be null)
 * tintindex   — biome tint index (-1 = none)
 *
 * The original face definitions in GeometryBuilder use the opposite winding
 * from the outward Minecraft face normal. That was mostly invisible in the
 * custom preview because it deliberately disables face culling, but glTF/OBJ
 * consumers correctly treat those triangles as back-facing. In particular,
 * this made many front faces disappear and left the back side looking valid.
 *
 * This class normalizes the quad at the exporter boundary: verts() reverses
 * the winding, normal() derives the actual outward normal from that winding,
 * and vertexUVs() maps the Minecraft UV rectangle according to the face that
 * the geometry actually occupies after blockstate rotation.
 */
public record Quad(
    float[][] verts,
    float[] uv,
    String texturePath,
    float[] normal,
    String cullface,
    int tintindex
) {
    /** Return the outward-facing, consistently wound vertex order. */
    @Override
    public float[][] verts() {
        if (verts == null || verts.length < 4) return verts;
        return new float[][]{
            verts[0], verts[3], verts[2], verts[1]
        };
    }

    /** Derive the actual outward world-space normal from the corrected winding. */
    @Override
    public float[] normal() {
        float[][] p = verts();
        float ux = p[1][0] - p[0][0];
        float uy = p[1][1] - p[0][1];
        float uz = p[1][2] - p[0][2];
        float vx = p[2][0] - p[0][0];
        float vy = p[2][1] - p[0][1];
        float vz = p[2][2] - p[0][2];

        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-8f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    /** UV coords as 0-1 floats (Minecraft uses 0-16). */
    public float u1() { return uv[0] / 16f; }
    public float v1() { return uv[1] / 16f; }
    public float u2() { return uv[2] / 16f; }
    public float v2() { return uv[3] / 16f; }

    /**
     * Per-vertex UVs for the exporter-facing vertex order.
     * Minecraft's UV rectangle is face-local; north/west faces in particular
     * run through the block coordinates in the opposite direction from the
     * south/east faces. Mapping by the actual post-rotation face direction
     * fixes the inverted textures on directional blocks such as doors.
     */
    public float[][] vertexUVs() {
        float u1 = u1();
        float v1 = v1();
        float u2 = u2();
        float v2 = v2();
        String dir = dominantFaceDirection(verts());

        return switch (dir) {
            case "north" -> new float[][]{
                {u2, v2},
                {u2, v1},
                {u1, v1},
                {u1, v2}
            };
            case "south" -> new float[][]{
                {u1, v2},
                {u1, v1},
                {u2, v1},
                {u2, v2}
            };
            case "east" -> new float[][]{
                {u1, v2},
                {u1, v1},
                {u2, v1},
                {u2, v2}
            };
            case "west" -> new float[][]{
                {u2, v2},
                {u2, v1},
                {u1, v1},
                {u1, v2}
            };
            case "up" -> new float[][]{
                {u1, v2},
                {u1, v1},
                {u2, v1},
                {u2, v2}
            };
            case "down" -> new float[][]{
                {u1, v1},
                {u2, v1},
                {u2, v2},
                {u1, v2}
            };
            default -> new float[][]{
                {u1, v2},
                {u1, v1},
                {u2, v1},
                {u2, v2}
            };
        };
    }

    /** Pick the dominant axis of the actual outward quad normal. */
    private static String dominantFaceDirection(float[][] p) {
        if (p == null || p.length < 3) return "south";

        float ux = p[1][0] - p[0][0];
        float uy = p[1][1] - p[0][1];
        float uz = p[1][2] - p[0][2];
        float vx = p[2][0] - p[0][0];
        float vy = p[2][1] - p[0][1];
        float vz = p[2][2] - p[0][2];

        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;

        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        if (ay >= ax && ay >= az) return ny >= 0 ? "up" : "down";
        if (ax >= ay && ax >= az) return nx >= 0 ? "east" : "west";
        return nz >= 0 ? "south" : "north";
    }
}
