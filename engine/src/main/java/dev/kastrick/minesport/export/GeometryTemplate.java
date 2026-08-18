package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable local-space geometry that can be reused at many world positions.
 *
 * A template deliberately contains no block coordinates. The expensive model
 * resolution/geometry construction work is performed once, then instantiate()
 * only translates the already-built vertices for each block occurrence.
 */
public final class GeometryTemplate {
    private final BlockGeometryKind kind;
    private final List<Quad> quads;

    public GeometryTemplate(BlockGeometryKind kind, List<Quad> localQuads) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.quads = List.copyOf(localQuads);
    }

    public BlockGeometryKind kind() {
        return kind;
    }

    public List<Quad> quads() {
        return quads;
    }

    /**
     * Place this template at a block's world coordinate.
     * The returned quads are independent of the template and safe for callers
     * to pass to exporters that retain their arrays.
     */
    public List<Quad> instantiate(BlockData block) {
        if (quads.isEmpty()) return List.of();

        List<Quad> placed = new ArrayList<>(quads.size());
        for (Quad q : quads) {
            float[][] source = q.verts();
            float[][] translated = new float[source.length][3];
            for (int i = 0; i < source.length; i++) {
                translated[i][0] = source[i][0] + block.x;
                translated[i][1] = source[i][1] + block.y;
                translated[i][2] = source[i][2] + block.z;
            }

            placed.add(new Quad(
                translated,
                q.uv().clone(),
                q.texturePath(),
                q.normal().clone(),
                q.cullface(),
                q.tintindex()
            ));
        }
        return placed;
    }

    @Override
    public String toString() {
        return "GeometryTemplate{" + kind + ", quads=" + quads.size() + '}';
    }
}
