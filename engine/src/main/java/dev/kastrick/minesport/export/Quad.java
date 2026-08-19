package dev.kastrick.minesport.export;

/**
 * A single textured quad ready for OBJ/glTF output.
 *
 * uv accepts either the legacy Minecraft rectangle [u1,v1,u2,v2] in 0..16
 * units, or eight normalized values [u0,v0,u1,v1,u2,v2,u3,v3].
 * The latter is used by the geometry builder so UVs remain attached to the
 * original model vertices after blockstate/model rotations.
 */
public record Quad(
    float[][] verts,
    float[] uv,
    String texturePath,
    float[] normal,
    String cullface,
    int tintindex
) {
    @Override
    public float[][] verts() {
        if (verts == null || verts.length < 4) return verts;
        // Keep the existing exporter winding correction. UVs are reversed in
        // vertexUVs() at the same time, so geometry and texture corners remain paired.
        return new float[][]{verts[0], verts[3], verts[2], verts[1]};
    }

    @Override
    public float[] normal() {
        float[][] p = verts();
        if (p == null || p.length < 3) return new float[]{0, 1, 0};
        float ux = p[1][0] - p[0][0], uy = p[1][1] - p[0][1], uz = p[1][2] - p[0][2];
        float vx = p[2][0] - p[0][0], vy = p[2][1] - p[0][1], vz = p[2][2] - p[0][2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-8f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    public float[][] vertexUVs() {
        if (uv == null || uv.length < 4) {
            return new float[][]{
                {0, 1}, {0, 0}, {1, 0}, {1, 1}
            };
        }

        if (uv.length >= 8) {
            // Explicit UVs are stored in the same order as the raw vertices.
            // Return them in the same corrected order as verts().
            return new float[][]{
                {uv[0], uv[1]},
                {uv[6], uv[7]},
                {uv[4], uv[5]},
                {uv[2], uv[3]}
            };
        }

        float u1 = uv[0] / 16f;
        float v1 = uv[1] / 16f;
        float u2 = uv[2] / 16f;
        float v2 = uv[3] / 16f;
        return new float[][]{
            {u1, v2}, {u1, v1}, {u2, v1}, {u2, v2}
        };
    }

    public float u1() {
        float[][] uvs = vertexUVs();
        float min = Float.MAX_VALUE;
        for (float[] p : uvs) min = Math.min(min, p[0]);
        return min;
    }

    public float v1() {
        float[][] uvs = vertexUVs();
        float min = Float.MAX_VALUE;
        for (float[] p : uvs) min = Math.min(min, p[1]);
        return min;
    }

    public float u2() {
        float[][] uvs = vertexUVs();
        float max = -Float.MAX_VALUE;
        for (float[] p : uvs) max = Math.max(max, p[0]);
        return max;
    }

    public float v2() {
        float[][] uvs = vertexUVs();
        float max = -Float.MAX_VALUE;
        for (float[] p : uvs) max = Math.max(max, p[1]);
        return max;
    }
}
