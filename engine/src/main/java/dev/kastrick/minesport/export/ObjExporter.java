package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.io.*;
import java.util.*;

/** OBJ + MTL exporter using Minesport's explicit geometry/UV data. */
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

    private record Vec2Key(int x, int y) {
        static Vec2Key of(float[] value) {
            return new Vec2Key(bits(value[0]), bits(value[1]));
        }
    }

    private record Vec3Key(int x, int y, int z) {
        static Vec3Key of(float x, float y, float z) {
            return new Vec3Key(bits(x), bits(y), bits(z));
        }
    }

    private static int bits(float value) {
        return Float.floatToIntBits(value == 0f ? 0f : value);
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
        FlatterMetadataExporter.resetForExport(outputFile);

        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);
        FlatterOptimizer.Result flatter = FlatterSettings.enabled()
            ? FlatterOptimizer.compile(blocks, builder.getResolvers())
            : FlatterOptimizer.Result.empty();

        String exportName = safeObjectName(
            outputFile.getName().replaceFirst("(?i)\\.obj$", "")
        );

        Map<String,List<Quad>> objects = new LinkedHashMap<>();
        LinkedHashSet<MaterialKey> textures = new LinkedHashSet<>();

        int done = 0;
        int total = Math.max(blocks.size(), 1);
        int solid = 0;
        int quadCount = 0;

        for (BlockData block : blocks) {
            if (block.isAir()) {
                if (progress != null) progress.onProgress(++done, total);
                continue;
            }

            if (flatter.contains(block)) {
                solid++;
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
            for (Quad quad : quads) textures.add(MaterialKey.forQuad(quad));

            String shortName = BlockGrouper.shortName(block.blockId);
            String physicalName = BlockGrouper.physicalName(block);

            String logicalName = switch (mode) {
                case ALL_MERGED -> exportName;
                case GROUPED_BY_TYPE -> groupedIds.getOrDefault(block, shortName);
                case INDIVIDUAL -> compoundIds.getOrDefault(block, physicalName);
            };

            for (Quad quad : quads) {
                String objectName = quad.partName() == null
                    ? logicalName
                    : BlockGrouper.partName(block, quad.partName());
                objects.computeIfAbsent(objectName, ignored -> new ArrayList<>()).add(quad);
            }

            if (progress != null) progress.onProgress(++done, total);
        }

        for (FlatterOptimizer.FlatterObject object : flatter.objects()) {
            List<Quad> quads = new ArrayList<>(object.quads());
            objects.put(object.id(), quads);
            quadCount += quads.size();
            for (Quad quad : quads) textures.add(MaterialKey.forQuad(quad));
        }

        File mtl = new File(
            outputFile.getParent(),
            outputFile.getName().replaceFirst("(?i)\\.obj$", ".mtl")
        );

        int emittedVertices;
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            writer.println("# Minesport OBJ Export");
            writer.println("# Units: metres (1 Minecraft block grid cell = 1 metre)");
            writer.println("# Export root: " + exportName);
            writer.println("# Object mode: " + mode);
            writer.println("# Optimize requested: " + optimize);
            writer.println("# FLATTER: " + (!flatter.isEmpty()));
            writer.println("mtllib " + mtl.getName());
            writer.println();

            writer.println("g " + exportName);
            writer.println("s off");

            int vertexOffset = 1;
            int texcoordOffset = 1;
            int normalOffset = 1;
            Map<Vec3Key,Integer> weldedVertices = optimize ? new HashMap<>() : null;
            Map<Vec2Key,Integer> weldedTexcoords = optimize ? new HashMap<>() : null;
            Map<Vec3Key,Integer> weldedNormals = optimize ? new HashMap<>() : null;
            Set<String> usedObjectNames = new HashSet<>();

            for (var entry : objects.entrySet()) {
                String objectName;
                if (flatter.isFlatterObject(entry.getKey())) {
                    objectName = safeObjectName(entry.getKey());
                    usedObjectNames.add(objectName);
                } else {
                    objectName = mode == ExportMode.ALL_MERGED && entry.getKey().equals(exportName)
                        ? exportName
                        : uniqueName(safeObjectName(entry.getKey()), usedObjectNames);
                }

                writer.println();
                if (flatter.isFlatterObject(entry.getKey())) {
                    writer.println("# MINESPORT_TYPE FLATTER");
                    writer.println("# MINESPORT_FLATTER_ID " + entry.getKey());
                }
                writer.println("o " + objectName);

                Map<MaterialKey,List<Quad>> byTexture = new LinkedHashMap<>();
                for (Quad quad : entry.getValue()) {
                    byTexture.computeIfAbsent(
                        MaterialKey.forQuad(quad),
                        ignored -> new ArrayList<>()
                    ).add(quad);
                }

                for (var textureEntry : byTexture.entrySet()) {
                    writer.println("usemtl " + materialName(textureEntry.getKey()));

                    for (Quad quad : textureEntry.getValue()) {
                        float[][] vertices = quad.verts();
                        float[][] uvs = quad.vertexUVs();
                        float[] normal = quad.normal();
                        int[] vertexIndices = new int[4];
                        int[] texcoordIndices = new int[4];

                        for (int i = 0; i < vertices.length; i++) {
                            float x = vertices[i][0] - center[0];
                            float y = vertices[i][1] - center[1];
                            float z = vertices[i][2] - center[2];
                            Vec3Key key = Vec3Key.of(x, y, z);
                            Integer index = optimize ? weldedVertices.get(key) : null;
                            if (index == null) {
                                index = vertexOffset++;
                                writer.printf(Locale.ROOT, "v %.6f %.6f %.6f%n", x, y, z);
                                if (optimize) weldedVertices.put(key, index);
                            }
                            vertexIndices[i] = index;
                        }

                        for (int i = 0; i < uvs.length; i++) {
                            float[] flipped = new float[]{uvs[i][0], 1f - uvs[i][1]};
                            Vec2Key key = Vec2Key.of(flipped);
                            Integer index = optimize ? weldedTexcoords.get(key) : null;
                            if (index == null) {
                                index = texcoordOffset++;
                                writer.printf(Locale.ROOT, "vt %.6f %.6f%n", flipped[0], flipped[1]);
                                if (optimize) weldedTexcoords.put(key, index);
                            }
                            texcoordIndices[i] = index;
                        }

                        Vec3Key normalKey = Vec3Key.of(normal[0], normal[1], normal[2]);
                        Integer normalIndex = optimize ? weldedNormals.get(normalKey) : null;
                        if (normalIndex == null) {
                            normalIndex = normalOffset++;
                            writer.printf(
                                Locale.ROOT,
                                "vn %.6f %.6f %.6f%n",
                                normal[0], normal[1], normal[2]
                            );
                            if (optimize) weldedNormals.put(normalKey, normalIndex);
                        }

                        writer.printf(
                            Locale.ROOT,
                            "f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d%n",
                            vertexIndices[0], texcoordIndices[0], normalIndex,
                            vertexIndices[1], texcoordIndices[1], normalIndex,
                            vertexIndices[2], texcoordIndices[2], normalIndex,
                            vertexIndices[3], texcoordIndices[3], normalIndex
                        );
                    }
                }
            }
            emittedVertices = vertexOffset - 1;
        }

        MtlExporter.export(textures, mtl, builder.getResolvers());
        if (!flatter.isEmpty()) {
            FlatterMetadataExporter.write(outputFile, flatter, mode, "obj", center);
        }
        return new ExportStats(solid, quadCount, emittedVertices);
    }

    private static String materialName(MaterialKey value) {
        return value.materialName();
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
