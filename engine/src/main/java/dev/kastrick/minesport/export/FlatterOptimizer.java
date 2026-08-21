package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.MissingTexture;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Lossless chunk-local geometry compiler for FLATTER.
 *
 * Minecraft blocks remain the source of truth. Eligibility is based on the
 * final resolved geometry, not the export grouping mode or the number of JSON
 * model elements. A block may therefore contain layered full-cube faces (for
 * example grass base + tinted overlay) as long as every side has at least one
 * fully opaque full-cell layer. All face layers are retained in metadata.
 */
public final class FlatterOptimizer {
    public static final int CELL_SIZE = 16;
    private static final List<String> DIRECTIONS =
        List.of("north", "south", "east", "west", "up", "down");

    private FlatterOptimizer() {}

    public record FaceInfo(
        String material,
        String texturePath,
        int tintRgb,
        float[] uv,
        float[] vertices
    ) {
        public FaceInfo {
            uv = uv == null ? new float[0] : uv.clone();
            vertices = vertices == null ? new float[0] : vertices.clone();
        }

        @Override public float[] uv() { return uv.clone(); }
        @Override public float[] vertices() { return vertices.clone(); }
    }

    public record PaletteEntry(
        String blockId,
        Map<String,String> properties,
        Map<String,List<FaceInfo>> faces
    ) {
        public PaletteEntry {
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            Map<String,List<FaceInfo>> copied = new LinkedHashMap<>();
            for (var entry : faces.entrySet()) {
                copied.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            faces = Collections.unmodifiableMap(copied);
        }
    }

    public record Run(int start, int length, int palette) {}

    public record FlatterObject(
        String id,
        int[] origin,
        int[] size,
        List<PaletteEntry> palette,
        List<Run> runs,
        List<Quad> quads,
        int blockCount
    ) {
        public FlatterObject {
            origin = origin.clone();
            size = size.clone();
            palette = List.copyOf(palette);
            runs = List.copyOf(runs);
            quads = List.copyOf(quads);
        }

        @Override public int[] origin() { return origin.clone(); }
        @Override public int[] size() { return size.clone(); }
    }

    public static final class Result {
        private static final Result EMPTY = new Result(List.of(), Set.of());

        private final List<FlatterObject> objects;
        private final Set<BlockData> included;
        private final Set<String> objectIds;
        private final int blockCount;

        private Result(List<FlatterObject> objects, Set<BlockData> included) {
            this.objects = List.copyOf(objects);
            Set<BlockData> identity = Collections.newSetFromMap(new IdentityHashMap<>());
            identity.addAll(included);
            this.included = Collections.unmodifiableSet(identity);
            Set<String> ids = new HashSet<>();
            int count = 0;
            for (FlatterObject object : objects) {
                ids.add(object.id());
                count += object.blockCount();
            }
            this.objectIds = Collections.unmodifiableSet(ids);
            this.blockCount = count;
        }

        public static Result empty() { return EMPTY; }
        public List<FlatterObject> objects() { return objects; }
        public int blockCount() { return blockCount; }
        public boolean isEmpty() { return objects.isEmpty(); }
        public boolean contains(BlockData block) { return included.contains(block); }
        public boolean isFlatterObject(String id) { return objectIds.contains(id); }
    }

    private record CellKey(int x, int y, int z) {}
    private record Candidate(
        BlockData block,
        Map<String,List<Quad>> faces,
        Map<String,List<FaceInfo>> faceInfo,
        String paletteKey
    ) {}
    private record AxisEdge(int axis, int sign) {}
    private record FaceCell(Candidate candidate, Quad quad, int a, int b) {}
    private record MergeKey(
        String direction,
        MaterialKey material,
        String uvSignature,
        int plane,
        AxisEdge edge1,
        AxisEdge edge3
    ) {}

    public static Result compile(List<BlockData> blocks, ResolverChain resolvers) {
        if (blocks == null || blocks.isEmpty() || resolvers == null) return Result.empty();

        GeometryBuilder geometry = new GeometryBuilder(resolvers);
        Map<MaterialKey,Boolean> opaqueCache = new HashMap<>();
        Map<CellKey,List<Candidate>> cells = new LinkedHashMap<>();

        for (BlockData block : blocks) {
            if (block == null || block.isAir()) continue;
            Candidate candidate = analyze(block, geometry, resolvers, opaqueCache);
            if (candidate == null) continue;
            cells.computeIfAbsent(cellFor(block), ignored -> new ArrayList<>()).add(candidate);
        }

        List<FlatterObject> objects = new ArrayList<>();
        Set<BlockData> included = Collections.newSetFromMap(new IdentityHashMap<>());

        for (var entry : cells.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            FlatterObject object = buildObject(entry.getKey(), entry.getValue());
            if (object == null || object.blockCount() < 2) continue;
            objects.add(object);
            for (Candidate candidate : entry.getValue()) included.add(candidate.block());
        }

        return objects.isEmpty() ? Result.empty() : new Result(objects, included);
    }

    private static Candidate analyze(
        BlockData block,
        GeometryBuilder geometry,
        ResolverChain resolvers,
        Map<MaterialKey,Boolean> opaqueCache
    ) {
        List<Quad> quads = geometry.buildBlock(block);
        if (quads.size() < 6) return null;

        Map<String,List<Quad>> faces = new LinkedHashMap<>();
        Map<String,List<FaceInfo>> faceInfo = new LinkedHashMap<>();
        Set<String> opaqueDirections = new HashSet<>();

        for (Quad quad : quads) {
            if (quad.partName() != null) return null;
            String direction = directionOf(quad);
            if (direction == null || !isUnitAxisAlignedFace(block, quad)) return null;

            MaterialKey material = MaterialKey.forQuad(quad);
            boolean opaque = opaqueCache.computeIfAbsent(
                material,
                key -> isOpaqueTexture(key, resolvers)
            );
            if (opaque) opaqueDirections.add(direction);

            faces.computeIfAbsent(direction, ignored -> new ArrayList<>()).add(quad);
            faceInfo.computeIfAbsent(direction, ignored -> new ArrayList<>())
                .add(faceInfo(block, quad, material));
        }

        if (!faces.keySet().containsAll(DIRECTIONS)) return null;
        if (!opaqueDirections.containsAll(DIRECTIONS)) return null;

        Map<String,List<Quad>> immutableFaces = new LinkedHashMap<>();
        Map<String,List<FaceInfo>> immutableInfo = new LinkedHashMap<>();
        for (String direction : DIRECTIONS) {
            immutableFaces.put(direction, List.copyOf(faces.getOrDefault(direction, List.of())));
            immutableInfo.put(direction, List.copyOf(faceInfo.getOrDefault(direction, List.of())));
        }

        return new Candidate(
            block,
            Collections.unmodifiableMap(immutableFaces),
            Collections.unmodifiableMap(immutableInfo),
            paletteKey(block, immutableInfo)
        );
    }

    private static boolean isOpaqueTexture(MaterialKey material, ResolverChain resolvers) {
        BufferedImage image;
        try {
            image = material.apply(resolvers.resolveTexture(material.texturePath()));
        } catch (Exception ignored) {
            return false;
        }
        if (image == null || MissingTexture.is(image)) return false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 255) return false;
            }
        }
        return true;
    }

    private static FaceInfo faceInfo(BlockData block, Quad quad, MaterialKey material) {
        float[][] vertices = quad.verts();
        float[] flat = new float[vertices.length * 3];
        for (int i = 0; i < vertices.length; i++) {
            flat[i * 3] = vertices[i][0] - block.x;
            flat[i * 3 + 1] = vertices[i][1] - block.y;
            flat[i * 3 + 2] = vertices[i][2] - block.z;
        }

        float[][] uvs = quad.vertexUVs();
        float[] uv = new float[uvs.length * 2];
        for (int i = 0; i < uvs.length; i++) {
            uv[i * 2] = uvs[i][0];
            uv[i * 2 + 1] = uvs[i][1];
        }

        return new FaceInfo(
            material.materialName(),
            material.texturePath(),
            material.tintRgb(),
            uv,
            flat
        );
    }

    private static FlatterObject buildObject(CellKey cell, List<Candidate> candidates) {
        Map<Long,Candidate> local = new HashMap<>(Math.max(16, candidates.size() * 2));
        for (Candidate candidate : candidates) {
            BlockData block = candidate.block();
            local.put(SpatialKey.of(block.x, block.y, block.z), candidate);
        }

        Map<MergeKey,List<FaceCell>> faceGroups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            BlockData block = candidate.block();
            for (String direction : DIRECTIONS) {
                int[] delta = delta(direction);
                Candidate neighbour = local.get(SpatialKey.of(
                    block.x + delta[0],
                    block.y + delta[1],
                    block.z + delta[2]
                ));
                if (neighbour != null) continue;

                for (Quad quad : candidate.faces().getOrDefault(direction, List.of())) {
                    AxisEdge edge1 = edgeOf(quad, 1);
                    AxisEdge edge3 = edgeOf(quad, 3);
                    if (edge1 == null || edge3 == null || edge1.axis() == edge3.axis()) {
                        edge1 = fallbackEdge1(direction);
                        edge3 = fallbackEdge3(direction);
                    }

                    MergeKey key = new MergeKey(
                        direction,
                        MaterialKey.forQuad(quad),
                        uvSignature(quad),
                        plane(block, direction),
                        edge1,
                        edge3
                    );
                    int a = axisCoordinate(block, edge1);
                    int b = axisCoordinate(block, edge3);
                    faceGroups.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new FaceCell(candidate, quad, a, b));
                }
            }
        }

        List<Quad> merged = new ArrayList<>();
        for (var entry : faceGroups.entrySet()) {
            merged.addAll(greedy(entry.getKey(), entry.getValue()));
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Candidate candidate : candidates) {
            BlockData b = candidate.block();
            minX = Math.min(minX, b.x); maxX = Math.max(maxX, b.x);
            minY = Math.min(minY, b.y); maxY = Math.max(maxY, b.y);
            minZ = Math.min(minZ, b.z); maxZ = Math.max(maxZ, b.z);
        }
        int[] origin = {minX, minY, minZ};
        int[] size = {maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};

        Map<String,Integer> paletteIndex = new LinkedHashMap<>();
        List<PaletteEntry> palette = new ArrayList<>();
        Map<Integer,Integer> occupied = new TreeMap<>();

        for (Candidate candidate : candidates) {
            Integer p = paletteIndex.get(candidate.paletteKey());
            if (p == null) {
                p = palette.size();
                paletteIndex.put(candidate.paletteKey(), p);
                palette.add(new PaletteEntry(
                    candidate.block().blockId,
                    candidate.block().properties,
                    candidate.faceInfo()
                ));
            }
            BlockData b = candidate.block();
            int index = linearIndex(
                b.x - origin[0],
                b.y - origin[1],
                b.z - origin[2],
                size
            );
            occupied.put(index, p);
        }

        List<Run> runs = encodeRuns(occupied);
        String id = "FLATTER_" + cell.x() + "_" + cell.y() + "_" + cell.z();
        return new FlatterObject(
            id,
            origin,
            size,
            palette,
            runs,
            merged,
            candidates.size()
        );
    }

    private static List<Run> encodeRuns(Map<Integer,Integer> occupied) {
        List<Run> runs = new ArrayList<>();
        int start = -1;
        int previous = -2;
        int palette = -1;
        int length = 0;

        for (var entry : occupied.entrySet()) {
            int index = entry.getKey();
            int value = entry.getValue();
            if (length > 0 && index == previous + 1 && value == palette) {
                length++;
                previous = index;
                continue;
            }
            if (length > 0) runs.add(new Run(start, length, palette));
            start = index;
            previous = index;
            palette = value;
            length = 1;
        }
        if (length > 0) runs.add(new Run(start, length, palette));
        return runs;
    }

    private static int linearIndex(int x, int y, int z, int[] size) {
        return (y * size[2] + z) * size[0] + x;
    }

    private static List<Quad> greedy(MergeKey key, List<FaceCell> cells) {
        Map<Long,FaceCell> grid = new HashMap<>();
        List<FaceCell> ordered = new ArrayList<>(cells);
        ordered.sort(
            Comparator.comparingInt(FaceCell::b).thenComparingInt(FaceCell::a)
        );
        for (FaceCell cell : ordered) grid.put(pair(cell.a(), cell.b()), cell);

        Set<Long> used = new HashSet<>();
        List<Quad> result = new ArrayList<>();

        for (FaceCell base : ordered) {
            long baseKey = pair(base.a(), base.b());
            if (used.contains(baseKey)) continue;

            int width = 1;
            while (true) {
                long next = pair(base.a() + width, base.b());
                if (used.contains(next) || !grid.containsKey(next)) break;
                width++;
            }

            int height = 1;
            outer:
            while (true) {
                int b = base.b() + height;
                for (int a = 0; a < width; a++) {
                    long next = pair(base.a() + a, b);
                    if (used.contains(next) || !grid.containsKey(next)) break outer;
                }
                height++;
            }

            for (int db = 0; db < height; db++) {
                for (int da = 0; da < width; da++) {
                    used.add(pair(base.a() + da, base.b() + db));
                }
            }

            result.add(expandQuad(base.quad(), width, height));
        }
        return result;
    }

    private static Quad expandQuad(Quad source, int width, int height) {
        if (width == 1 && height == 1) return source;

        float[][] v = source.verts();
        float[][] uv = source.vertexUVs();
        if (v == null || v.length < 4 || uv == null || uv.length < 4) return source;

        float[] e1 = subtract(v[1], v[0]);
        float[] e3 = subtract(v[3], v[0]);
        if (!isUnitAxis(e1) || !isUnitAxis(e3)) return source;

        float[][] out = new float[4][3];
        out[0] = v[0].clone();
        out[1] = add(v[0], scale(e1, width));
        out[3] = add(v[0], scale(e3, height));
        out[2] = add(out[1], scale(e3, height));

        float[] du1 = {uv[1][0] - uv[0][0], uv[1][1] - uv[0][1]};
        float[] du3 = {uv[3][0] - uv[0][0], uv[3][1] - uv[0][1]};
        float[][] outUv = new float[4][2];
        outUv[0] = uv[0].clone();
        outUv[1] = new float[]{uv[0][0] + du1[0] * width, uv[0][1] + du1[1] * width};
        outUv[3] = new float[]{uv[0][0] + du3[0] * height, uv[0][1] + du3[1] * height};
        outUv[2] = new float[]{
            outUv[1][0] + du3[0] * height,
            outUv[1][1] + du3[1] * height
        };

        float[][] raw = {out[0], out[3], out[2], out[1]};
        float[] rawUv = {
            outUv[0][0], outUv[0][1],
            outUv[3][0], outUv[3][1],
            outUv[2][0], outUv[2][1],
            outUv[1][0], outUv[1][1]
        };

        return new Quad(
            raw,
            rawUv,
            source.texturePath(),
            source.normal(),
            source.cullface(),
            source.tintindex(),
            source.partName()
        );
    }

    private static CellKey cellFor(BlockData block) {
        return new CellKey(
            Math.floorDiv(block.x, CELL_SIZE),
            Math.floorDiv(block.y, CELL_SIZE),
            Math.floorDiv(block.z, CELL_SIZE)
        );
    }

    private static String paletteKey(BlockData block, Map<String,List<FaceInfo>> faceInfo) {
        StringBuilder value = new StringBuilder();
        value.append(block.blockId)
            .append('[').append(BlockGrouper.stateKey(block.properties)).append(']');
        for (String direction : DIRECTIONS) {
            value.append('|').append(direction);
            for (FaceInfo info : faceInfo.getOrDefault(direction, List.of())) {
                value.append('{')
                    .append(info.material()).append(';')
                    .append(info.texturePath()).append(';')
                    .append(info.tintRgb()).append(';');
                appendFloatBits(value, info.uv());
                value.append(';');
                appendFloatBits(value, info.vertices());
                value.append('}');
            }
        }
        return value.toString();
    }

    private static void appendFloatBits(StringBuilder value, float[] values) {
        for (float f : values) value.append(Float.floatToIntBits(f)).append(',');
    }

    private static String directionOf(Quad quad) {
        float[] normal = quad.normal();
        if (normal != null && normal.length >= 3) {
            float ax = Math.abs(normal[0]), ay = Math.abs(normal[1]), az = Math.abs(normal[2]);
            if (ax >= ay && ax >= az && ax > .9f) return normal[0] > 0 ? "east" : "west";
            if (ay >= ax && ay >= az && ay > .9f) return normal[1] > 0 ? "up" : "down";
            if (az > .9f) return normal[2] > 0 ? "south" : "north";
        }
        if (quad.cullface() != null && DIRECTIONS.contains(quad.cullface())) {
            return quad.cullface();
        }
        return null;
    }

    private static boolean isUnitAxisAlignedFace(BlockData block, Quad quad) {
        float[][] vertices = quad.verts();
        if (vertices == null || vertices.length != 4) return false;
        final float eps = 1e-5f;
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] v : vertices) {
            if (v == null || v.length < 3) return false;
            min[0] = Math.min(min[0], v[0]); max[0] = Math.max(max[0], v[0]);
            min[1] = Math.min(min[1], v[1]); max[1] = Math.max(max[1], v[1]);
            min[2] = Math.min(min[2], v[2]); max[2] = Math.max(max[2], v[2]);
        }
        float[] span = {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
        int zero = 0, one = 0;
        for (float s : span) {
            if (Math.abs(s) < eps) zero++;
            if (Math.abs(s - 1f) < eps) one++;
        }
        if (zero != 1 || one != 2) return false;
        return min[0] >= block.x - eps && max[0] <= block.x + 1 + eps
            && min[1] >= block.y - eps && max[1] <= block.y + 1 + eps
            && min[2] >= block.z - eps && max[2] <= block.z + 1 + eps;
    }

    private static AxisEdge edgeOf(Quad quad, int vertexIndex) {
        float[][] v = quad.verts();
        if (v == null || v.length < 4) return null;
        return axisEdge(subtract(v[vertexIndex], v[0]));
    }

    private static AxisEdge axisEdge(float[] edge) {
        final float eps = 1e-5f;
        int axis = -1;
        int sign = 0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(edge[i]) < eps) continue;
            if (axis != -1 || Math.abs(Math.abs(edge[i]) - 1f) > eps) return null;
            axis = i;
            sign = edge[i] > 0 ? 1 : -1;
        }
        return axis == -1 ? null : new AxisEdge(axis, sign);
    }

    private static boolean isUnitAxis(float[] edge) {
        return axisEdge(edge) != null;
    }

    private static AxisEdge fallbackEdge1(String direction) {
        return switch (direction) {
            case "up" -> new AxisEdge(2, 1);
            case "down" -> new AxisEdge(0, 1);
            default -> new AxisEdge(1, 1);
        };
    }

    private static AxisEdge fallbackEdge3(String direction) {
        return switch (direction) {
            case "north" -> new AxisEdge(0, 1);
            case "south" -> new AxisEdge(0, -1);
            case "east" -> new AxisEdge(2, 1);
            case "west" -> new AxisEdge(2, -1);
            case "up" -> new AxisEdge(0, 1);
            case "down" -> new AxisEdge(2, 1);
            default -> new AxisEdge(0, 1);
        };
    }

    private static int axisCoordinate(BlockData block, AxisEdge edge) {
        int coordinate = switch (edge.axis()) {
            case 0 -> block.x;
            case 1 -> block.y;
            default -> block.z;
        };
        return coordinate * edge.sign();
    }

    private static int plane(BlockData block, String direction) {
        return switch (direction) {
            case "west" -> block.x;
            case "east" -> block.x + 1;
            case "down" -> block.y;
            case "up" -> block.y + 1;
            case "north" -> block.z;
            case "south" -> block.z + 1;
            default -> 0;
        };
    }

    private static int[] delta(String direction) {
        return switch (direction) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "east" -> new int[]{1, 0, 0};
            case "west" -> new int[]{-1, 0, 0};
            case "up" -> new int[]{0, 1, 0};
            case "down" -> new int[]{0, -1, 0};
            default -> new int[]{0, 0, 0};
        };
    }

    private static String uvSignature(Quad quad) {
        StringBuilder value = new StringBuilder();
        for (float[] uv : quad.vertexUVs()) {
            if (value.length() > 0) value.append('|');
            value.append(Float.floatToIntBits(uv[0])).append(',')
                 .append(Float.floatToIntBits(uv[1]));
        }
        return value.toString();
    }

    private static long pair(int a, int b) {
        return (((long) a) << 32) ^ (b & 0xffffffffL);
    }

    private static float[] subtract(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] scale(float[] a, int factor) {
        return new float[]{a[0] * factor, a[1] * factor, a[2] * factor};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }
}
