package dev.kastrick.minesport.export;

import com.google.gson.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.Base64;

/** glTF 2.0 exporter with optional lossless FLATTER geometry. */
public class GltfExporter {
    private final ResolverChain resolvers;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

    private final List<JsonObject> accessors = new ArrayList<>();
    private final List<JsonObject> bufferViews = new ArrayList<>();
    private final List<JsonObject> images = new ArrayList<>();
    private final List<JsonObject> textures = new ArrayList<>();
    private final List<JsonObject> materials = new ArrayList<>();
    private final List<JsonObject> meshes = new ArrayList<>();
    private final List<JsonObject> nodes = new ArrayList<>();

    private final Map<MaterialKey,Integer> textureMap = new LinkedHashMap<>();
    private final Map<MaterialKey,Integer> materialMap = new LinkedHashMap<>();

    public GltfExporter(ResolverChain resolvers) {
        this.resolvers = resolvers;
    }

    public ObjExporter.ExportStats export(
        List<BlockData> blocks,
        GeometryBuilder builder,
        File outputFile,
        ObjExporter.ExportMode mode,
        boolean optimize,
        ObjExporter.ProgressCallback progress
    ) throws IOException {
        if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
        FlatterMetadataExporter.resetForExport(outputFile);

        String outputName = outputFile.getName();
        String objectName = safeObjectName(outputName.replaceFirst("(?i)\\.gltf$", ""));
        File binFile = new File(outputFile.getParent(), outputName.replaceFirst("(?i)\\.gltf$", ".bin"));

        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);
        FlatterOptimizer.Result flatter = FlatterSettings.enabled()
            ? FlatterOptimizer.compile(blocks, resolvers)
            : FlatterOptimizer.Result.empty();
        Map<String,List<Quad>> groups = new LinkedHashMap<>();

        int done = 0;
        int total = Math.max(blocks.size(), 1);
        int solid = 0;

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

            String shortName = BlockGrouper.shortName(block.blockId);
            String physicalName = BlockGrouper.physicalName(block);

            String key = switch (mode) {
                case ALL_MERGED -> "__merged__";
                case GROUPED_BY_TYPE -> groupedIds.getOrDefault(block, shortName);
                case INDIVIDUAL -> compoundIds.getOrDefault(block, physicalName);
            };

            List<Quad> blockQuads = builder.buildBlock(block);
            for (Quad quad : blockQuads) {
                String meshKey = quad.partName() == null
                    ? key
                    : BlockGrouper.partName(block, quad.partName());
                groups.computeIfAbsent(meshKey, ignored -> new ArrayList<>()).add(quad);
            }
            solid++;
            if (progress != null) progress.onProgress(++done, total);
        }

        // FLATTER objects deliberately remain separate even in ALL_MERGED mode.
        // Their mesh is disposable render geometry; the logical block grid lives
        // in the sidecar and must retain a stable object identity for Blender.
        for (FlatterOptimizer.FlatterObject object : flatter.objects()) {
            groups.put(object.id(), new ArrayList<>(object.quads()));
        }

        int quadCount = 0;
        int vertexCount = 0;

        JsonObject rootNode = new JsonObject();
        rootNode.addProperty("name", objectName);
        nodes.add(rootNode);
        int rootNodeIndex = 0;

        List<Quad> mergedQuads = groups.remove("__merged__");
        if (mode == ObjExporter.ExportMode.ALL_MERGED && mergedQuads != null) {
            MeshResult result = buildMesh(mergedQuads, center, optimize, objectName);
            if (result != null) {
                meshes.add(result.mesh());
                rootNode.addProperty("mesh", meshes.size() - 1);
                quadCount += result.quadCount();
                vertexCount += result.vertexCount();
            }
        }

        JsonArray children = new JsonArray();
        Set<String> usedNames = new HashSet<>();

        for (var entry : groups.entrySet()) {
            String childName;
            if (flatter.isFlatterObject(entry.getKey())) {
                childName = safeObjectName(entry.getKey());
                usedNames.add(childName);
            } else {
                childName = uniqueName(safeObjectName(entry.getKey()), usedNames);
            }
            MeshResult result = buildMesh(entry.getValue(), center, optimize, childName);
            if (result == null) continue;

            meshes.add(result.mesh());
            JsonObject child = new JsonObject();
            child.addProperty("name", childName);
            child.addProperty("mesh", meshes.size() - 1);

            JsonObject extras = new JsonObject();
            extras.addProperty("minesportGroup", entry.getKey());
            extras.addProperty("minesportObjectMode", mode.name());
            if (flatter.isFlatterObject(entry.getKey())) {
                extras.addProperty("minesportType", "FLATTER");
                extras.addProperty("minesportFlatterId", entry.getKey());
                extras.addProperty(
                    "minesportFlatterSchema",
                    FlatterMetadataExporter.FLATTER_SCHEMA
                );
            }
            child.add("extras", extras);

            nodes.add(child);
            children.add(nodes.size() - 1);

            quadCount += result.quadCount();
            vertexCount += result.vertexCount();
        }

        if (children.size() > 0) rootNode.add("children", children);

        JsonObject rootExtras = new JsonObject();
        JsonObject minesportExtras = new JsonObject();
        minesportExtras.addProperty("schema", 1);
        minesportExtras.addProperty("exportName", objectName);
        minesportExtras.addProperty("objectMode", mode.name());
        minesportExtras.addProperty("metresPerBlock", 1.0);
        minesportExtras.addProperty("flatter", !flatter.isEmpty());
        minesportExtras.addProperty(
            "flatterSchema",
            flatter.isEmpty() ? 0 : FlatterMetadataExporter.FLATTER_SCHEMA
        );
        rootExtras.add("minesport", minesportExtras);
        rootNode.add("extras", rootExtras);

        byte[] binData = bin.toByteArray();
        try (FileOutputStream fos = new FileOutputStream(binFile)) {
            fos.write(binData);
        }

        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "Minesport v0.1 by Kastrick");
        root.add("asset", asset);
        root.addProperty("scene", 0);

        JsonObject scene = new JsonObject();
        scene.addProperty("name", objectName);
        JsonArray sceneNodes = new JsonArray();
        sceneNodes.add(rootNodeIndex);
        scene.add("nodes", sceneNodes);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        root.add("scenes", scenes);

        root.add("nodes", toArray(nodes));
        root.add("meshes", toArray(meshes));
        root.add("materials", toArray(materials));
        root.add("textures", toArray(textures));
        root.add("images", toArray(images));
        root.add("accessors", toArray(accessors));
        root.add("bufferViews", toArray(bufferViews));

        JsonArray samplers = new JsonArray();
        JsonObject sampler = new JsonObject();
        sampler.addProperty("magFilter", 9728);
        sampler.addProperty("minFilter", 9728);
        sampler.addProperty("wrapS", 10497);
        sampler.addProperty("wrapT", 10497);
        samplers.add(sampler);
        root.add("samplers", samplers);

        JsonArray buffers = new JsonArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("uri", binFile.getName());
        buffer.addProperty("byteLength", binData.length);
        buffers.add(buffer);
        root.add("buffers", buffers);

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            writer.println(gson.toJson(root));
        }

        // Native glTF lighting is part of the format export itself, not a Blender
        // special case. Any glTF consumer that supports KHR_lights_punctual now
        // receives Minecraft emitters; Blender sidecars only add extra semantics.
        GltfPostProcessor.addMinecraftLights(outputFile, blocks);

        if (!flatter.isEmpty()) {
            FlatterMetadataExporter.write(outputFile, flatter, mode, "gltf", center);
        }
        return new ObjExporter.ExportStats(solid, quadCount, vertexCount);
    }

    private record MeshResult(JsonObject mesh, int quadCount, int vertexCount) {}
    private record PrimitiveResult(JsonObject primitive, int vertexCount) {}

    private MeshResult buildMesh(
        List<Quad> sourceQuads,
        float[] center,
        boolean optimize,
        String meshName
    ) throws IOException {
        Map<MaterialKey,List<Quad>> byTexture = new LinkedHashMap<>();
        int quads = 0;

        for (Quad quad : sourceQuads) {
            byTexture.computeIfAbsent(MaterialKey.forQuad(quad), ignored -> new ArrayList<>()).add(quad);
            quads++;
        }

        if (byTexture.isEmpty()) return null;

        JsonArray primitives = new JsonArray();
        int vertices = 0;
        for (var textureEntry : byTexture.entrySet()) {
            PrimitiveResult primitive = buildPrimitive(
                textureEntry.getValue(),
                textureEntry.getKey(),
                center,
                optimize
            );
            if (primitive == null) continue;
            primitives.add(primitive.primitive());
            vertices += primitive.vertexCount();
        }

        if (primitives.size() == 0) return null;

        JsonObject mesh = new JsonObject();
        mesh.addProperty("name", meshName);
        mesh.add("primitives", primitives);
        return new MeshResult(mesh, quads, vertices);
    }

    private PrimitiveResult buildPrimitive(
        List<Quad> quads,
        MaterialKey texture,
        float[] center,
        boolean weld
    ) throws IOException {
        List<float[]> positions = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        Map<String,Integer> vertexMap = new LinkedHashMap<>();

        for (Quad quad : quads) {
            float[][] vertices = quad.verts();
            float[][] quadUvs = quad.vertexUVs();
            float[] normal = quad.normal();
            int[] local = new int[4];

            for (int i = 0; i < 4; i++) {
                float x = vertices[i][0] - center[0];
                float y = vertices[i][1] - center[1];
                float z = vertices[i][2] - center[2];
                float u = quadUvs[i][0];
                float v = quadUvs[i][1];

                String key = String.format(
                    Locale.ROOT,
                    "%.6f|%.6f|%.6f|%.4f|%.4f|%.4f|%.6f|%.6f",
                    x, y, z, normal[0], normal[1], normal[2], u, v
                );

                Integer existing = weld ? vertexMap.get(key) : null;
                if (existing != null) {
                    local[i] = existing;
                    continue;
                }

                int id = positions.size();
                local[i] = id;
                positions.add(new float[]{x, y, z});
                normals.add(new float[]{normal[0], normal[1], normal[2]});
                uvs.add(new float[]{u, v});
                if (weld) vertexMap.put(key, id);
            }

            indices.add(local[0]);
            indices.add(local[1]);
            indices.add(local[2]);
            indices.add(local[0]);
            indices.add(local[2]);
            indices.add(local[3]);
        }

        if (positions.isEmpty()) return null;

        JsonObject primitive = new JsonObject();
        JsonObject attributes = new JsonObject();
        attributes.addProperty("POSITION", writeVec3(positions, true));
        attributes.addProperty("NORMAL", writeVec3(normals, false));
        attributes.addProperty("TEXCOORD_0", writeVec2(uvs));
        primitive.add("attributes", attributes);
        primitive.addProperty("indices", writeIndices(indices));
        primitive.addProperty("material", getMaterial(texture));
        primitive.addProperty("mode", 4);
        return new PrimitiveResult(primitive, positions.size());
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

    private int getMaterial(MaterialKey texture) throws IOException {
        Integer existing = materialMap.get(texture);
        if (existing != null) return existing;

        int textureIndex = getTexture(texture);

        JsonObject pbr = new JsonObject();
        JsonObject base = new JsonObject();
        base.addProperty("index", textureIndex);
        pbr.add("baseColorTexture", base);
        pbr.addProperty("metallicFactor", 0);
        pbr.addProperty("roughnessFactor", 1);

        JsonObject material = new JsonObject();
        material.addProperty("name", texture.materialName());
        material.add("pbrMetallicRoughness", pbr);
        material.addProperty("doubleSided", true);

        BufferedImage image = texture.apply(resolvers.resolveTexture(texture.texturePath()));
        if (image != null && hasAlpha(image)) {
            material.addProperty("alphaMode", "MASK");
            material.addProperty("alphaCutoff", 0.5);
        }

        materials.add(material);
        int index = materials.size() - 1;
        materialMap.put(texture, index);
        return index;
    }

    private int getTexture(MaterialKey texture) throws IOException {
        Integer existing = textureMap.get(texture);
        if (existing != null) return existing;

        BufferedImage image = texture.apply(resolvers.resolveTexture(texture.texturePath()));
        if (image == null) {
            image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xffff00ff);
            }
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", png);

        JsonObject imageJson = new JsonObject();
        imageJson.addProperty("name", texture.materialName());
        imageJson.addProperty(
            "uri",
            "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())
        );
        images.add(imageJson);

        JsonObject textureJson = new JsonObject();
        textureJson.addProperty("sampler", 0);
        textureJson.addProperty("source", images.size() - 1);
        textures.add(textureJson);

        int index = textures.size() - 1;
        textureMap.put(texture, index);
        return index;
    }

    private static boolean hasAlpha(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 255) return true;
            }
        }
        return false;
    }

    private int writeVec3(List<float[]> values, boolean position) {
        pad4();
        int offset = bin.size();
        ByteBuffer buffer = ByteBuffer.allocate(values.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        for (float[] value : values) {
            for (int i = 0; i < 3; i++) {
                buffer.putFloat(value[i]);
                min[i] = Math.min(min[i], value[i]);
                max[i] = Math.max(max[i], value[i]);
            }
        }

        byte[] bytes = buffer.array();
        writeBytes(bytes);
        int view = addView(offset, bytes.length, 34962);

        JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", view);
        accessor.addProperty("componentType", 5126);
        accessor.addProperty("count", values.size());
        accessor.addProperty("type", "VEC3");

        if (position) {
            JsonArray minJson = new JsonArray();
            JsonArray maxJson = new JsonArray();
            for (float value : min) minJson.add(value);
            for (float value : max) maxJson.add(value);
            accessor.add("min", minJson);
            accessor.add("max", maxJson);
        }

        accessors.add(accessor);
        return accessors.size() - 1;
    }

    private int writeVec2(List<float[]> values) {
        pad4();
        int offset = bin.size();
        ByteBuffer buffer = ByteBuffer.allocate(values.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] value : values) {
            buffer.putFloat(value[0]);
            buffer.putFloat(value[1]);
        }

        byte[] bytes = buffer.array();
        writeBytes(bytes);
        int view = addView(offset, bytes.length, 34962);

        JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", view);
        accessor.addProperty("componentType", 5126);
        accessor.addProperty("count", values.size());
        accessor.addProperty("type", "VEC2");
        accessors.add(accessor);
        return accessors.size() - 1;
    }

    private int writeIndices(List<Integer> values) {
        pad4();
        int offset = bin.size();
        ByteBuffer buffer = ByteBuffer.allocate(values.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);

        byte[] bytes = buffer.array();
        writeBytes(bytes);
        int view = addView(offset, bytes.length, 34963);

        JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", view);
        accessor.addProperty("componentType", 5125);
        accessor.addProperty("count", values.size());
        accessor.addProperty("type", "SCALAR");
        accessors.add(accessor);
        return accessors.size() - 1;
    }

    private int addView(int offset, int length, int target) {
        JsonObject view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", offset);
        view.addProperty("byteLength", length);
        view.addProperty("target", target);
        bufferViews.add(view);
        return bufferViews.size() - 1;
    }

    private void writeBytes(byte[] bytes) {
        bin.writeBytes(bytes);
    }

    private void pad4() {
        while ((bin.size() & 3) != 0) bin.write(0);
    }

    private static JsonArray toArray(List<JsonObject> list) {
        JsonArray array = new JsonArray();
        for (JsonObject object : list) array.add(object);
        return array;
    }
}
