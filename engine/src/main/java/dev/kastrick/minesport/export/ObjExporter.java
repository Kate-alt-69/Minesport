package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.io.*;
import java.util.*;

/**
 * OBJ + MTL exporter using Minesport's explicit geometry/UV data.
 *
 * OBJ has no real nested collection format, so the export filename is emitted
 * as a common `g` group. `o` records still follow the selected object mode:
 * one object for Merged, one per logical block type for Grouped, and one per
 * physical/compound structure for Individual.
 */
public class ObjExporter {
    public record ExportStats(int blockCount, int quadCount, int vertexCount) {
        public static ExportStats of(int blocks, int quads) {
            return new ExportStats(blocks, quads, quads * 4);
        }
    }

    public enum ExportMode {
        ALL_MERGED,
        GROUPED_BY_TYPE,
        INDIVIDUAL
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total);
    }

    public static ExportStats exportWithGeometry(
        List<BlockData> blocks,
        GeometryBuilder builder,
        File outputFile,
        ExportMode mode,
        boolean optimize,
        ProgressCallback progress
    ) throws IOException {
        if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();

        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);

        String exportName = safeObjectName(
            outputFile.getName().replaceFirst("(?i)\\.obj$", "")
        );

        Map<String,List<Quad>> objects = new LinkedHashMap<>();
        LinkedHashSet<String> textures = new LinkedHashSet<>();

        int done = 0;
        int total = Math.max(blocks.size(), 1);
        int solid = 0;
        int quadCount = 0;

        for (BlockData block : blocks) {
            if (block.isAir()) {
                if (progress != null) progress.onProgress(++done, total);
                continue;
            }

            List<Quad> quads = builder.buildBlock(block);
            if (quads.isEmpty()) {
                if (progress != null) progress.onProgress(++done, total);
                continue;
            }

            solid++;
            quadCount += quads.size();
            for (Quad quad : quads) textures.add(quad.texturePath());

            String shortName = BlockGrouper.shortName(block.blockId);
            String physicalName = shortName
                + BlockGrouper.stateSuffix(block.properties)
                + "_" + block.x + "_" + block.y + "_" + block.z;

            String logicalName = switch (mode) {
                case ALL_MERGED -> exportName;
                case GROUPED_BY_TYPE -> groupedIds.getOrDefault(block, shortName);
                case INDIVIDUAL -> compoundIds.getOrDefault(block, physicalName);
            };

            objects.computeIfAbsent(logicalName, ignored -> new ArrayList<>()).addAll(quads);

            if (progress != null) progress.onProgress(++done, total);
        }

        File mtl = new File(
            outputFile.getParent(),
            outputFile.getName().replaceFirst("(?i)\\.obj$", ".mtl")
        );

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            writer.println("# Minesport OBJ Export");
            writer.println("# Export root: " + exportName);
            writer.println("# Object mode: " + mode);
            writer.println("# Optimize requested: " + optimize);
            writer.println("mtllib " + mtl.getName());
            writer.println();

            // Common root/group name. DCCs that support OBJ groups can keep the
            // export together, while `o` records retain real selectable objects.
            writer.println("g " + exportName);
            writer.println("s off");

            int vertexOffset = 1;
            int texcoordOffset = 1;
            int normalOffset = 1;
            Set<String> usedObjectNames = new HashSet<>();

            for (var entry : objects.entrySet()) {
                String objectName = mode == ExportMode.ALL_MERGED
                    ? exportName
                    : uniqueName(safeObjectName(entry.getKey()), usedObjectNames);

                writer.println();
                writer.println("o " + objectName);

                Map<String,List<Quad>> byTexture = new LinkedHashMap<>();
                for (Quad quad : entry.getValue()) {
                    byTexture.computeIfAbsent(
                        quad.texturePath(),
                        ignored -> new ArrayList<>()
                    ).add(quad);
                }

                for (var textureEntry : byTexture.entrySet()) {
                    writer.println("usemtl " + materialName(textureEntry.getKey()));

                    for (Quad quad : textureEntry.getValue()) {
                        float[][] vertices = quad.verts();
                        float[][] uvs = quad.vertexUVs();
                        float[] normal = quad.normal();

                        for (float[] vertex : vertices) {
                            writer.printf(
                                Locale.ROOT,
                                "v %.6f %.6f %.6f%n",
                                vertex[0] - center[0],
                                vertex[1] - center[1],
                                vertex[2] - center[2]
                            );
                        }

                        for (float[] uv : uvs) {
                            writer.printf(
                                Locale.ROOT,
                                "vt %.6f %.6f%n",
                                uv[0],
                                1f - uv[1]
                            );
                        }

                        writer.printf(
                            Locale.ROOT,
                            "vn %.6f %.6f %.6f%n",
                            normal[0], normal[1], normal[2]
                        );

                        writer.printf(
                            Locale.ROOT,
                            "f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d%n",
                            vertexOffset,     texcoordOffset,     normalOffset,
                            vertexOffset + 1, texcoordOffset + 1, normalOffset,
                            vertexOffset + 2, texcoordOffset + 2, normalOffset,
                            vertexOffset + 3, texcoordOffset + 3, normalOffset
                        );

                        vertexOffset += 4;
                        texcoordOffset += 4;
                        normalOffset++;
                    }
                }
            }
        }

        MtlExporter.export(textures, mtl, builder.getResolvers());
        return ExportStats.of(solid, quadCount);
    }

    private static String materialName(String value) {
        if (value == null || value.isBlank()) return "Minesport_Material";
        return value.replace(':', '_').replace('/', '_').replace('\\', '_');
    }

    private static String uniqueName(String base, Set<String> used) {
        if (base == null || base.isBlank()) base = "Minesport_Object";
        String candidate = base;
        int suffix = 1;
        while (!used.add(candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }

    private static String safeObjectName(String value) {
        if (value == null || value.isBlank()) return "Minesport_Export";
        return value
            .replace(':', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .trim();
    }
}
