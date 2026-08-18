package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.io.*;
import java.util.*;

/**
 * OBJ+MTL exporter. Exports resolved block model geometry (from
 * GeometryBuilder) grouped by block type, individual block, or merged
 * into one mesh, with correct per-block textures via MtlExporter.
 *
 * Coordinate system: Minecraft's world (X=east, Y=up, Z=south) maps
 * directly to OBJ with no axis flip — both are right-handed Y-up, so no
 * conversion is needed.
 */
public class ObjExporter {

    /**
     * Summary of what an export actually produced, for the UI to display
     * (export-state panel, 3D-artist metadata HUD). vertexCount is the
     * unwelded upper bound (quadCount*4) — with Optimize Output on, the
     * real written count is lower (vertex welding dedupes shared corners),
     * but the exact post-weld number isn't threaded back up through the
     * writer methods; this is deliberately labeled as an upper bound on
     * the Go side rather than pretending to be exact.
     */
    public record ExportStats(int blockCount, int quadCount, int vertexCount) {
        public static ExportStats of(int blockCount, int quadCount) {
            return new ExportStats(blockCount, quadCount, quadCount * 4);
        }
    }

    public enum ExportMode {
        ALL_MERGED,       // Single object, everything in one mesh
        GROUPED_BY_TYPE,  // One group per block type (default)
        INDIVIDUAL        // One object per block
    }

    // ── Phase 2: proper geometry export ──────────────────────────────────────

    /**
     * Export using fully resolved geometry from GeometryBuilder.
     *
     * Fixes applied:
     *  - Names: strip namespace  ("polydecorations:oak_bench" → "oak_bench_x_y_z")
     *  - Center: bbox center subtracted so model lands at world origin in Blender
     *  - Flat shading: "s off" written per group — no Blender smooth interpolation
     *  - Grouping: touching same-type blocks share a group (g) but stay individual objects (o)
     */
    public static ExportStats exportWithGeometry(
            List<BlockData> blocks,
            GeometryBuilder builder,
            File outputFile,
            ExportMode mode,
            boolean optimize,
            ProgressCallback progress
    ) throws IOException {

        outputFile.getParentFile().mkdirs();

        // ── Step 1: compute center offset ────────────────────────────────────
        float[] center = BlockGrouper.boundingBoxCenter(blocks);

        // ── Step 2: compute connected component groups ────────────────────────
        Map<BlockData, String> blockGroups = BlockGrouper.computeGroups(blocks);

        // ── Step 3: build geometry per block, keyed by (groupName, blockName) ─
        // Structure: groupName → list of (objectName, quads)
        var groupMap = new java.util.LinkedHashMap<String, List<BlockObjectEntry>>();
        var allTextures = new java.util.LinkedHashSet<String>();

        int blocksDone = 0;
        int total = blocks.size();
        int solidBlockCount = 0;
        int quadCount = 0;

        for (BlockData b : blocks) {
            if (b.isAir()) continue;

            List<Quad> quads = builder.buildBlock(b);
            if (quads.isEmpty()) { if (progress != null) progress.onProgress(++blocksDone, total); continue; }

            solidBlockCount++;
            quadCount += quads.size();
            quads.forEach(q -> allTextures.add(q.texturePath()));

            String shortName = BlockGrouper.shortName(b.blockId);
            String objectName = shortName + BlockGrouper.stateSuffix(b.properties) + "_" + b.x + "_" + b.y + "_" + b.z;

            String groupName = switch (mode) {
                case ALL_MERGED      -> "__merged__";
                case INDIVIDUAL      -> objectName;
                case GROUPED_BY_TYPE -> blockGroups.getOrDefault(b, shortName);
            };

            groupMap.computeIfAbsent(groupName, k -> new java.util.ArrayList<>())
                    .add(new BlockObjectEntry(objectName, quads));

            if (progress != null) progress.onProgress(++blocksDone, total);
        }

        // ── Step 4: write MTL name ────────────────────────────────────────────
        String mtlName = outputFile.getName().replace(".obj", ".mtl");
        File mtlFile = new File(outputFile.getParent(), mtlName);

        // ── Step 5: write OBJ ────────────────────────────────────────────────
        try (var writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            writer.println("# Minesport OBJ Export");
            writer.println("# Generated: " + new java.util.Date());
            writer.println("# Blocks: " + blocks.size());
            writer.println("# Center offset: " + center[0] + ", " + center[1] + ", " + center[2]);
            if (optimize) writer.println("# Optimized: hidden faces culled, vertices welded (experimental)");
            writer.println();
            writer.println("mtllib " + mtlName);
            writer.println();

            if (optimize) {
                writeOptimized(writer, groupMap, center);
            } else {
                writeUnoptimized(writer, groupMap, center);
            }

            writer.println();
            writer.println("# End of export");
        }

        // ── Step 6: write MTL ─────────────────────────────────────────────────
        if (builder.getResolvers() != null) {
            MtlExporter.export(allTextures, mtlFile, builder.getResolvers());
        }

        return ExportStats.of(solidBlockCount, quadCount);
    }

    /**
     * Original writer: every quad writes 4 fresh v/vt lines and 1 fresh vn
     * line, no sharing at all — simple, safe, always correct, but produces
     * far more vertices than actually exist (a cube writes 24 positions for
     * 8 real corners). This is the path used when "Optimize Output" is off.
     */
    private static void writeUnoptimized(
            PrintWriter writer,
            Map<String, List<BlockObjectEntry>> groupMap,
            float[] center
    ) {
        int vOff = 1, vtOff = 1, vnOff = 1;

        for (var groupEntry : groupMap.entrySet()) {
            String groupName = groupEntry.getKey();
            writer.println();
            writer.println("g " + groupName);
            writer.println("s off");   // ← FLAT SHADING — no smooth interpolation

            for (BlockObjectEntry boe : groupEntry.getValue()) {
                writer.println("o " + boe.objectName());

                var byTex = new java.util.LinkedHashMap<String, List<Quad>>();
                for (Quad q : boe.quads()) {
                    byTex.computeIfAbsent(q.texturePath(), t -> new java.util.ArrayList<>()).add(q);
                }

                for (var texEntry : byTex.entrySet()) {
                    String matName = texEntry.getKey().replace(':', '_').replace('/', '_');
                    writer.println("usemtl " + matName);

                    for (Quad q : texEntry.getValue()) {
                        // Apply center offset to all vertices
                        for (float[] v : q.verts()) {
                            writer.printf("v %.5f %.5f %.5f%n",
                                v[0] - center[0],
                                v[1] - center[1],
                                v[2] - center[2]);
                        }
                        float[][] uvs = q.vertexUVs();
                        for (float[] uv : uvs) {
                            writer.printf("vt %.5f %.5f%n", uv[0], 1f - uv[1]);
                        }
                        float[] n = q.normal();
                        writer.printf("vn %.3f %.3f %.3f%n", n[0], n[1], n[2]);

                        writer.printf("f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d%n",
                            vOff,   vtOff,   vnOff,
                            vOff+1, vtOff+1, vnOff,
                            vOff+2, vtOff+2, vnOff,
                            vOff+3, vtOff+3, vnOff);

                        vOff  += 4;
                        vtOff += 4;
                        vnOff += 1;
                    }
                }
            }
        }
    }

    /**
     * Optimized writer: dedupes the v/vt/vn pools file-wide before writing
     * any faces. OBJ indexes position/UV/normal independently, so a corner
     * shared by several faces of the same block (or, at group/object
     * boundaries, even between touching blocks) only needs ONE v entry no
     * matter how many faces reference it — a cube goes from 24 positions to
     * its real 8. UVs and normals differ per-face by nature (different tiling,
     * different facing) so they dedupe less aggressively, but still collapse
     * wherever two faces genuinely share the same value.
     *
     * Two-pass by necessity: OBJ face lines can only reference indices
     * already declared earlier in the file, so every v/vt/vn has to be
     * collected and deduplicated BEFORE any "f" line is written — geometry
     * is already fully built in memory by this point (Step 3), so this is
     * just a second walk over the same data, not a second geometry pass.
     */
    private static void writeOptimized(
            PrintWriter writer,
            Map<String, List<BlockObjectEntry>> groupMap,
            float[] center
    ) {
        Map<String, Integer> vIndex  = new java.util.LinkedHashMap<>();
        Map<String, Integer> vtIndex = new java.util.LinkedHashMap<>();
        Map<String, Integer> vnIndex = new java.util.LinkedHashMap<>();
        List<String> vLines  = new java.util.ArrayList<>();
        List<String> vtLines = new java.util.ArrayList<>();
        List<String> vnLines = new java.util.ArrayList<>();

        // Per-quad resolved indices, filled in during the dedup pass and
        // read back during the write pass below. IdentityHashMap because we
        // only ever need "this exact Quad instance" lookups, not structural
        // equality (Quad's array fields wouldn't compare usefully anyway).
        Map<Quad, int[]> quadVIdx  = new IdentityHashMap<>();
        Map<Quad, int[]> quadVtIdx = new IdentityHashMap<>();
        Map<Quad, Integer> quadVnIdx = new IdentityHashMap<>();

        // ── Pass A: dedupe ────────────────────────────────────────────────
        for (List<BlockObjectEntry> entries : groupMap.values()) {
            for (BlockObjectEntry boe : entries) {
                for (Quad q : boe.quads()) {
                    int[] vIdxArr = new int[4];
                    float[][] verts = q.verts();
                    for (int i = 0; i < 4; i++) {
                        float vx = verts[i][0] - center[0];
                        float vy = verts[i][1] - center[1];
                        float vz = verts[i][2] - center[2];
                        String key = String.format("%.5f|%.5f|%.5f", vx, vy, vz);
                        vIdxArr[i] = vIndex.computeIfAbsent(key, k -> {
                            vLines.add(String.format("v %.5f %.5f %.5f", vx, vy, vz));
                            return vLines.size(); // 1-based OBJ index
                        });
                    }

                    int[] vtIdxArr = new int[4];
                    float[][] uvs = q.vertexUVs();
                    for (int i = 0; i < 4; i++) {
                        float u = uvs[i][0], v = 1f - uvs[i][1];
                        String key = String.format("%.5f|%.5f", u, v);
                        vtIdxArr[i] = vtIndex.computeIfAbsent(key, k -> {
                            vtLines.add(String.format("vt %.5f %.5f", u, v));
                            return vtLines.size();
                        });
                    }

                    float[] n = q.normal();
                    String nKey = String.format("%.3f|%.3f|%.3f", n[0], n[1], n[2]);
                    int vnIdx = vnIndex.computeIfAbsent(nKey, k -> {
                        vnLines.add(String.format("vn %.3f %.3f %.3f", n[0], n[1], n[2]));
                        return vnLines.size();
                    });

                    quadVIdx.put(q, vIdxArr);
                    quadVtIdx.put(q, vtIdxArr);
                    quadVnIdx.put(q, vnIdx);
                }
            }
        }

        for (String l : vLines)  writer.println(l);
        writer.println();
        for (String l : vtLines) writer.println(l);
        writer.println();
        for (String l : vnLines) writer.println(l);

        // ── Pass B: write faces using the precomputed indices ─────────────
        for (var groupEntry : groupMap.entrySet()) {
            writer.println();
            writer.println("g " + groupEntry.getKey());
            writer.println("s off");

            for (BlockObjectEntry boe : groupEntry.getValue()) {
                writer.println("o " + boe.objectName());

                var byTex = new java.util.LinkedHashMap<String, List<Quad>>();
                for (Quad q : boe.quads()) {
                    byTex.computeIfAbsent(q.texturePath(), t -> new java.util.ArrayList<>()).add(q);
                }

                for (var texEntry : byTex.entrySet()) {
                    writer.println("usemtl " + texEntry.getKey().replace(':', '_').replace('/', '_'));

                    for (Quad q : texEntry.getValue()) {
                        int[] vi  = quadVIdx.get(q);
                        int[] vti = quadVtIdx.get(q);
                        int   vni = quadVnIdx.get(q);
                        writer.printf("f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d%n",
                            vi[0], vti[0], vni, vi[1], vti[1], vni,
                            vi[2], vti[2], vni, vi[3], vti[3], vni);
                    }
                }
            }
        }
    }

    private record BlockObjectEntry(String objectName, List<Quad> quads) {}

    // ── Cube geometry (Phase 1 fallback) ──────────────────────────────────────

    /**
     * Write a single full-cube block to the OBJ stream.
     * Returns the updated vertex offset.
     *
     * Each Minecraft block is 1x1x1 in world units.
     * We scale: 1 block = 1 OBJ unit.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total);
    }
}
