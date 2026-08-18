package dev.kastrick.minesport.export;

import com.google.gson.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Phase 4 — glTF 2.0 exporter.
 *
 * Produces a single .gltf file (JSON) with a companion .bin (binary geometry)
 * and embedded textures as base64 data URIs inside the JSON.
 *
 * glTF structure:
 *   asset, scene, nodes, meshes, materials, textures, images, samplers,
 *   accessors, bufferViews, buffers
 *
 * One mesh per block group (grouped by type like OBJ exporter).
 * One material per unique texture.
 * Geometry stored as binary float32 arrays in the .bin file.
 */
public class GltfExporter {

    private final ResolverChain resolvers;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Accumulated binary data
    private final ByteArrayOutputStream binStream = new ByteArrayOutputStream();

    // Tracking
    private final Map<String, Integer> textureIndexMap  = new LinkedHashMap<>();
    private final Map<String, Integer> materialIndexMap = new LinkedHashMap<>();
    private final List<JsonObject>     images    = new ArrayList<>();
    private final List<JsonObject>     textures  = new ArrayList<>();
    private final List<JsonObject>     materials = new ArrayList<>();
    private final List<JsonObject>     accessors = new ArrayList<>();
    private final List<JsonObject>     bufferViews = new ArrayList<>();
    private final List<JsonObject>     meshes    = new ArrayList<>();
    private final List<JsonObject>     nodes     = new ArrayList<>();

    private int totalQuadCount = 0;

    public GltfExporter(ResolverChain resolvers) {
        this.resolvers = resolvers;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public ObjExporter.ExportStats export(
            List<BlockData> blocks,
            GeometryBuilder builder,
            File outputFile,
            ObjExporter.ExportMode mode,
            boolean optimize,
            ObjExporter.ProgressCallback progress
    ) throws IOException {

        outputFile.getParentFile().mkdirs();
        File binFile = new File(outputFile.getParent(),
                outputFile.getName().replace(".gltf", ".bin"));

        // Compute center offset — same as OBJ exporter
        float[] center = BlockGrouper.boundingBoxCenter(blocks);

        // Compute connected groups
        Map<BlockData, String> blockGroups = BlockGrouper.computeGroups(blocks);

        // Group blocks
        Map<String, List<BlockData>> grouped = new LinkedHashMap<>();
        int done = 0, total = blocks.size();

        for (BlockData b : blocks) {
            if (b.isAir()) continue;
            String shortName = BlockGrouper.shortName(b.blockId);
            String key = switch (mode) {
                case ALL_MERGED  -> "__merged__";
                case INDIVIDUAL  -> shortName + BlockGrouper.stateSuffix(b.properties) + "_" + b.x + "_" + b.y + "_" + b.z;
                default          -> blockGroups.getOrDefault(b, shortName);
            };
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
            if (progress != null) progress.onProgress(++done, total);
        }

        // Build meshes — pass center offset
        boolean isMergedMode = (mode == ObjExporter.ExportMode.ALL_MERGED);
        int solidBlockCount = 0;
        for (var entry : grouped.entrySet()) {
            solidBlockCount += entry.getValue().size();
            buildMesh(entry.getKey(), entry.getValue(), builder, center, optimize, isMergedMode);
        }

        // Write .bin
        byte[] binData = binStream.toByteArray();
        try (FileOutputStream fos = new FileOutputStream(binFile)) {
            fos.write(binData);
        }

        // Assemble glTF JSON
        JsonObject root = new JsonObject();

        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "Minesport v0.1 by Kastrick");
        root.add("asset", asset);

        root.addProperty("scene", 0);

        // Scene
        JsonArray scenes = new JsonArray();
        JsonObject scene = new JsonObject();
        scene.addProperty("name", "Minesport Export");
        JsonArray sceneNodes = new JsonArray();
        for (int i = 0; i < nodes.size(); i++) sceneNodes.add(i);
        scene.add("nodes", sceneNodes);
        scenes.add(scene);
        root.add("scenes", scenes);

        root.add("nodes",       toArray(nodes));
        root.add("meshes",      toArray(meshes));
        root.add("materials",   toArray(materials));
        root.add("textures",    toArray(textures));
        root.add("images",      toArray(images));

        // Single sampler
        JsonArray samplers = new JsonArray();
        JsonObject sampler = new JsonObject();
        sampler.addProperty("magFilter", 9728); // NEAREST (pixel-art look)
        sampler.addProperty("minFilter", 9728);
        sampler.addProperty("wrapS", 33648);
        sampler.addProperty("wrapT", 33648);
        samplers.add(sampler);
        root.add("samplers", samplers);

        root.add("accessors",   toArray(accessors));
        root.add("bufferViews", toArray(bufferViews));

        // Buffer
        JsonArray buffers = new JsonArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("uri", binFile.getName());
        buffer.addProperty("byteLength", binData.length);
        buffers.add(buffer);
        root.add("buffers", buffers);

        // Write .gltf
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println(gson.toJson(root));
        }

        return ObjExporter.ExportStats.of(solidBlockCount, totalQuadCount);
    }

    // ── Mesh builder ──────────────────────────────────────────────────────────

    private void buildMesh(String name, List<BlockData> blockGroup, GeometryBuilder builder, float[] center, boolean optimize, boolean isMergedMode) {
        // Collect all quads for this group, bucketed by texture
        Map<String, List<Quad>> byTexture = new LinkedHashMap<>();

        for (BlockData b : blockGroup) {
            List<Quad> quads = builder.buildBlock(b);
            totalQuadCount += quads.size();
            for (Quad q : quads) {
                byTexture.computeIfAbsent(q.texturePath(), k -> new ArrayList<>()).add(q);
            }
        }

        if (byTexture.isEmpty()) return;

        JsonArray primitives = new JsonArray();

        // Atlas textures together in "All Merged" mode (with Optimize Output
        // on) — this is the mode where a build easily ends up with dozens of
        // separate materials on one object, which is the exact complaint
        // this addresses. Grouped/Individual modes already keep texture
        // counts low per-object, so they're left as-is.
        //
        // Textures whose UV span goes beyond [0,1] (a model intentionally
        // tiling within a single face — rare, but real) are kept OUT of the
        // atlas and left as their own separately-wrapped texture. This is
        // deliberate, not a missed case: GL_REPEAT wraps across the WHOLE
        // bound image, so a texture packed into a sub-rectangle of a shared
        // atlas can never tile correctly there — no shader trick fixes that
        // in a standard glTF file. Keeping repeat-needing textures separate
        // is the actual correct, standard way atlasing tools handle this,
        // not a shortcut.
        if (optimize && isMergedMode && byTexture.size() > 1) {
            Set<String> eligible = new LinkedHashSet<>();
            Set<String> excluded = new LinkedHashSet<>();
            for (var e : byTexture.entrySet()) {
                boolean needsRepeat = e.getValue().stream().anyMatch(GltfExporter::needsRepeatWrap);
                (needsRepeat ? excluded : eligible).add(e.getKey());
            }

            if (eligible.size() > 1) {
                AtlasResult atlas = buildAtlas(eligible);
                if (atlas != null) {
                    int matIdx = getOrCreateAtlasMaterial(atlas.image, name, atlas.alphaMode);
                    Map<String, List<Quad>> atlasQuads = new LinkedHashMap<>();
                    for (String tex : eligible) atlasQuads.put(tex, byTexture.get(tex));
                    primitives.add(buildPrimitiveAdaptive(atlasQuads, matIdx, center, optimize, atlas.uvTransform));
                } else {
                    // Atlas build failed (e.g. no textures resolved) — fall
                    // back to normal per-texture primitives for these.
                    excluded.addAll(eligible);
                }
            } else {
                // Nothing to gain from atlasing a single texture.
                excluded.addAll(eligible);
            }

            for (String texPath : excluded) {
                int matIdx = getOrCreateMaterial(texPath);
                primitives.add(buildPrimitiveAdaptive(Map.of(texPath, byTexture.get(texPath)), matIdx, center, optimize, null));
            }
        } else {
            for (var entry : byTexture.entrySet()) {
                int matIdx = getOrCreateMaterial(entry.getKey());
                primitives.add(buildPrimitiveAdaptive(Map.of(entry.getKey(), entry.getValue()), matIdx, center, optimize, null));
            }
        }

        JsonObject mesh = new JsonObject();
        mesh.addProperty("name", name.replace(':', '_').replace('/', '_'));
        mesh.add("primitives", primitives);
        meshes.add(mesh);

        JsonObject node = new JsonObject();
        node.addProperty("name", name.replace(':', '_').replace('/', '_'));
        node.addProperty("mesh", meshes.size() - 1);
        nodes.add(node);
    }

    /** True if any of this quad's UV spans beyond [0,1] — i.e. the model intentionally tiles within one face. */
    private static boolean needsRepeatWrap(Quad q) {
        float du = q.u2() - q.u1(), dv = q.v2() - q.v1();
        return Math.abs(du) > 1.001f || Math.abs(dv) > 1.001f;
    }

    /** Routes to the welded or unwelded primitive builder. Both accept multiple textures at once (for atlasing) with an optional per-texture UV remap. */
    private JsonObject buildPrimitiveAdaptive(Map<String, List<Quad>> quadsByTexture, int materialIndex, float[] center, boolean weld, Map<String, float[]> remapByTexture) {
        return weld
            ? buildPrimitiveWelded(quadsByTexture, materialIndex, center, remapByTexture)
            : buildPrimitiveUnwelded(quadsByTexture, materialIndex, center, remapByTexture);
    }

    /** Applies a texture's atlas remap to a UV pair, or returns it unchanged if remap is null. */
    private static float[] applyRemap(float u, float v, float[] remap) {
        if (remap == null) return new float[]{u, v};
        return new float[]{remap[0] + u * remap[2], remap[1] + v * remap[3]};
    }

    private JsonObject buildPrimitiveUnwelded(Map<String, List<Quad>> quadsByTexture, int materialIndex, float[] center, Map<String, float[]> remapByTexture) {
        List<Float> positions = new ArrayList<>();
        List<Float> normals   = new ArrayList<>();
        List<Float> uvs       = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        float[] minPos = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] maxPos = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        for (var texEntry : quadsByTexture.entrySet()) {
            float[] remap = remapByTexture != null ? remapByTexture.get(texEntry.getKey()) : null;

            for (Quad q : texEntry.getValue()) {
                float[][] vertUvs = q.vertexUVs();
                int base = positions.size() / 3;

                for (int vi = 0; vi < 4; vi++) {
                    float x = q.verts()[vi][0] - center[0];
                    float y = q.verts()[vi][1] - center[1];
                    float z = q.verts()[vi][2] - center[2];
                    float[] uv = applyRemap(vertUvs[vi][0], 1f - vertUvs[vi][1], remap);

                    positions.add(x); positions.add(y); positions.add(z);
                    normals.add(q.normal()[0]); normals.add(q.normal()[1]); normals.add(q.normal()[2]);
                    uvs.add(uv[0]); uvs.add(uv[1]);

                    minPos[0] = Math.min(minPos[0], x); maxPos[0] = Math.max(maxPos[0], x);
                    minPos[1] = Math.min(minPos[1], y); maxPos[1] = Math.max(maxPos[1], y);
                    minPos[2] = Math.min(minPos[2], z); maxPos[2] = Math.max(maxPos[2], z);
                }

                indices.add(base);   indices.add(base+1); indices.add(base+2);
                indices.add(base);   indices.add(base+2); indices.add(base+3);
            }
        }

        return finishPrimitive(positions, normals, uvs, indices, minPos, maxPos, materialIndex);
    }

    /**
     * Optimized version: dedupes vertices that share the exact same
     * position+normal+UV before writing them, and points the index buffer
     * at the deduped set instead of writing 4 fresh vertices per quad
     * unconditionally.
     *
     * glTF interleaves position/normal/UV into one vertex (unlike OBJ, which
     * indexes them independently), so this only collapses vertices where
     * ALL THREE match — a strictly narrower win than the OBJ optimizer, but
     * a real one: multi-element models (stairs, fences, anything built from
     * several boxes) commonly share exact corners across elements, and
     * those duplicates go away here.
     */
    private JsonObject buildPrimitiveWelded(Map<String, List<Quad>> quadsByTexture, int materialIndex, float[] center, Map<String, float[]> remapByTexture) {
        Map<String, Integer> vertexIndex = new LinkedHashMap<>();
        List<Float> positions = new ArrayList<>();
        List<Float> normals   = new ArrayList<>();
        List<Float> uvs       = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        float[] minPos = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] maxPos = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        for (var texEntry : quadsByTexture.entrySet()) {
            float[] remap = remapByTexture != null ? remapByTexture.get(texEntry.getKey()) : null;

            for (Quad q : texEntry.getValue()) {
                float[][] vertUvs = q.vertexUVs();
                int[] localIdx = new int[4];

                for (int vi = 0; vi < 4; vi++) {
                    float x = q.verts()[vi][0] - center[0];
                    float y = q.verts()[vi][1] - center[1];
                    float z = q.verts()[vi][2] - center[2];
                    float nx = q.normal()[0], ny = q.normal()[1], nz = q.normal()[2];
                    float[] uv = applyRemap(vertUvs[vi][0], 1f - vertUvs[vi][1], remap);
                    float u = uv[0], v = uv[1];

                    String key = String.format("%.5f|%.5f|%.5f|%.3f|%.3f|%.3f|%.5f|%.5f",
                        x, y, z, nx, ny, nz, u, v);

                    Integer existing = vertexIndex.get(key);
                    if (existing != null) {
                        localIdx[vi] = existing;
                        continue;
                    }

                    int newIdx = positions.size() / 3;
                    positions.add(x); positions.add(y); positions.add(z);
                    normals.add(nx);  normals.add(ny);  normals.add(nz);
                    uvs.add(u);       uvs.add(v);
                    vertexIndex.put(key, newIdx);
                    localIdx[vi] = newIdx;

                    minPos[0] = Math.min(minPos[0], x); maxPos[0] = Math.max(maxPos[0], x);
                    minPos[1] = Math.min(minPos[1], y); maxPos[1] = Math.max(maxPos[1], y);
                    minPos[2] = Math.min(minPos[2], z); maxPos[2] = Math.max(maxPos[2], z);
                }

                indices.add(localIdx[0]); indices.add(localIdx[1]); indices.add(localIdx[2]);
                indices.add(localIdx[0]); indices.add(localIdx[2]); indices.add(localIdx[3]);
            }
        }

        return finishPrimitive(positions, normals, uvs, indices, minPos, maxPos, materialIndex);
    }

    private JsonObject finishPrimitive(List<Float> positions, List<Float> normals, List<Float> uvs,
                                        List<Integer> indices, float[] minPos, float[] maxPos, int materialIndex) {
        float[] posArr  = toFloatArray(positions);
        float[] normArr = toFloatArray(normals);
        float[] uvArr   = toFloatArray(uvs);
        int vertexCount = posArr.length / 3;

        int posAccIdx  = writeFloatAccessor(posArr, 3, minPos, maxPos);
        int normAccIdx = writeFloatAccessor(normArr, 3, null, null);
        int uvAccIdx   = writeFloatAccessor(uvArr, 2, null, null);
        int idxAccIdx  = writeIndexAccessor(indices, vertexCount);

        JsonObject primitive = new JsonObject();
        JsonObject attributes = new JsonObject();
        attributes.addProperty("POSITION", posAccIdx);
        attributes.addProperty("NORMAL",   normAccIdx);
        attributes.addProperty("TEXCOORD_0", uvAccIdx);
        primitive.add("attributes", attributes);
        primitive.addProperty("indices", idxAccIdx);
        primitive.addProperty("material", materialIndex);
        return primitive;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    // ── Binary writers ────────────────────────────────────────────────────────

    private int writeFloatAccessor(float[] data, int components, float[] min, float[] max) {
        int byteOffset = binStream.size();
        ByteBuffer buf = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data) buf.putFloat(f);
        byte[] bytes = buf.array();

        // Pad to 4-byte alignment
        int padding = (4 - (bytes.length % 4)) % 4;
        try {
            binStream.write(bytes);
            for (int i = 0; i < padding; i++) binStream.write(0);
        } catch (IOException ignored) {}

        JsonObject bv = new JsonObject();
        bv.addProperty("buffer", 0);
        bv.addProperty("byteOffset", byteOffset);
        bv.addProperty("byteLength", bytes.length);
        bv.addProperty("target", 34962); // ARRAY_BUFFER
        int bvIdx = bufferViews.size();
        bufferViews.add(bv);

        JsonObject acc = new JsonObject();
        acc.addProperty("bufferView", bvIdx);
        acc.addProperty("byteOffset", 0);
        acc.addProperty("componentType", 5126); // FLOAT
        acc.addProperty("count", data.length / components);
        acc.addProperty("type", components == 3 ? "VEC3" : "VEC2");

        if (min != null) {
            JsonArray minArr = new JsonArray(), maxArr = new JsonArray();
            for (float v : min) minArr.add(v);
            for (float v : max) maxArr.add(v);
            acc.add("min", minArr);
            acc.add("max", maxArr);
        }

        int idx = accessors.size();
        accessors.add(acc);
        return idx;
    }

    private int writeShortAccessor(short[] data) {
        int byteOffset = binStream.size();
        ByteBuffer buf = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : data) buf.putShort(s);
        byte[] bytes = buf.array();

        int padding = (4 - (bytes.length % 4)) % 4;
        try {
            binStream.write(bytes);
            for (int i = 0; i < padding; i++) binStream.write(0);
        } catch (IOException ignored) {}

        JsonObject bv = new JsonObject();
        bv.addProperty("buffer", 0);
        bv.addProperty("byteOffset", byteOffset);
        bv.addProperty("byteLength", bytes.length);
        bv.addProperty("target", 34963); // ELEMENT_ARRAY_BUFFER
        int bvIdx = bufferViews.size();
        bufferViews.add(bv);

        JsonObject acc = new JsonObject();
        acc.addProperty("bufferView", bvIdx);
        acc.addProperty("byteOffset", 0);
        acc.addProperty("componentType", 5123); // UNSIGNED_SHORT
        acc.addProperty("count", data.length);
        acc.addProperty("type", "SCALAR");
        int idx = accessors.size();
        accessors.add(acc);
        return idx;
    }

    /**
     * Chooses the index component type based on actual vertex count and
     * writes it. UNSIGNED_SHORT (2 bytes/index, componentType 5123) can only
     * address up to 65,535 distinct vertices — silently wrapping around
     * past that (index 65536 aliases back to 0), which corrupts geometry in
     * exactly the way "All Merged" mode can hit on any reasonably large
     * selection, since a single texture there can easily accumulate more
     * than 16,384 quads in one primitive. Falls back to UNSIGNED_INT
     * (4 bytes/index, componentType 5125) whenever the vertex count actually
     * needs it, and stays compact otherwise.
     */
    private int writeIndexAccessor(List<Integer> indices, int vertexCount) {
        if (vertexCount <= 65535) {
            short[] arr = new short[indices.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = (short) (int) indices.get(i);
            return writeShortAccessor(arr);
        }
        int[] arr = new int[indices.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = indices.get(i);
        return writeIntAccessor(arr);
    }

    private int writeIntAccessor(int[] data) {
        int byteOffset = binStream.size();
        ByteBuffer buf = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) buf.putInt(v);
        byte[] bytes = buf.array(); // already a multiple of 4 — no padding needed

        try {
            binStream.write(bytes);
        } catch (IOException ignored) {}

        JsonObject bv = new JsonObject();
        bv.addProperty("buffer", 0);
        bv.addProperty("byteOffset", byteOffset);
        bv.addProperty("byteLength", bytes.length);
        bv.addProperty("target", 34963); // ELEMENT_ARRAY_BUFFER
        int bvIdx = bufferViews.size();
        bufferViews.add(bv);

        JsonObject acc = new JsonObject();
        acc.addProperty("bufferView", bvIdx);
        acc.addProperty("byteOffset", 0);
        acc.addProperty("componentType", 5125); // UNSIGNED_INT
        acc.addProperty("count", data.length);
        acc.addProperty("type", "SCALAR");
        int idx = accessors.size();
        accessors.add(acc);
        return idx;
    }

    // ── Material + texture ────────────────────────────────────────────────────

    private int getOrCreateMaterial(String texPath) {
        if (materialIndexMap.containsKey(texPath)) {
            return materialIndexMap.get(texPath);
        }

        int texIdx = getOrCreateTexture(texPath);

        JsonObject mat = new JsonObject();
        String safeName = texPath.replace(':', '_').replace('/', '_');
        mat.addProperty("name", safeName);

        JsonObject pbr = new JsonObject();
        String alphaMode = "OPAQUE";

        if (texIdx >= 0) {
            JsonObject baseColorTex = new JsonObject();
            baseColorTex.addProperty("index", texIdx);
            pbr.add("baseColorTexture", baseColorTex);

            alphaMode = determineAlphaMode(resolvers.resolveTexture(texPath));
        } else {
            // Texture couldn't be resolved at all — an obvious, distinct
            // color instead of leaving baseColorFactor unset (glTF's
            // spec default there is opaque white, but leaving it implicit
            // means "something's missing" is invisible instead of obvious).
            JsonArray missingColor = new JsonArray();
            missingColor.add(1.0); missingColor.add(0.0); missingColor.add(1.0); missingColor.add(1.0);
            pbr.add("baseColorFactor", missingColor);
        }

        pbr.addProperty("metallicFactor", 0.0);
        pbr.addProperty("roughnessFactor", 1.0);
        mat.add("pbrMetallicRoughness", pbr);
        applyAlphaMode(mat, alphaMode);

        // Use KHR_materials_unlit extension for flat MC look
        JsonObject extensions = new JsonObject();
        extensions.add("KHR_materials_unlit", new JsonObject());
        mat.add("extensions", extensions);

        int idx = materials.size();
        materials.add(mat);
        materialIndexMap.put(texPath, idx);
        return idx;
    }

    /**
     * Inspects a texture's actual alpha channel to pick the correct glTF
     * alphaMode. Without this, every material defaults to OPAQUE (glTF's
     * spec default when alphaMode is unset) — meaning any texture with real
     * transparency (grass, flowers, saplings, leaves, glass, ice) renders
     * its "invisible" pixels as whatever raw, usually garbage, color sits
     * there instead of actually being cut away. This is almost certainly
     * why cross-model sprite blocks (flowers etc.) come out looking like
     * solid fuzzy blobs instead of thin crosses.
     *
     *   all pixels alpha=255            → OPAQUE (cheapest, most blocks)
     *   some pixels alpha=0, rest 255   → MASK   (hard cutout — Minecraft's
     *                                     own "cutout" render layer: grass,
     *                                     flowers, saplings, most leaves)
     *   any pixel with partial alpha    → BLEND  (true translucency —
     *                                     glass, water, ice)
     */
    private static String determineAlphaMode(BufferedImage img) {
        if (img == null) return "OPAQUE";

        boolean hasTransparent = false;
        boolean hasPartial = false;

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha == 0) {
                    hasTransparent = true;
                } else if (alpha < 255) {
                    hasPartial = true;
                    break;
                }
            }
            if (hasPartial) break;
        }

        if (hasPartial) return "BLEND";
        if (hasTransparent) return "MASK";
        return "OPAQUE";
    }

    /** Applies alphaMode/alphaCutoff/doubleSided to a material JSON object. */
    private static void applyAlphaMode(JsonObject mat, String alphaMode) {
        mat.addProperty("alphaMode", alphaMode);
        if (alphaMode.equals("MASK")) {
            mat.addProperty("alphaCutoff", 0.5);
        }
        // Cutout/blend materials are almost always cross-model sprites
        // (flowers, tall grass, saplings) or panes/leaves meant to be seen
        // from both sides — Minecraft itself always renders these double-
        // sided. Solid opaque blocks keep normal single-sided culling.
        mat.addProperty("doubleSided", !alphaMode.equals("OPAQUE"));
    }

    private int getOrCreateTexture(String texPath) {
        if (textureIndexMap.containsKey(texPath)) {
            return textureIndexMap.get(texPath);
        }

        BufferedImage img = resolvers.resolveTexture(texPath);
        if (img == null) {
            textureIndexMap.put(texPath, -1);
            return -1;
        }

        int idx = embedTexture(img, texPath.replace(':', '_').replace('/', '_'));
        textureIndexMap.put(texPath, idx);
        return idx;
    }

    /** Encodes an image as an embedded base64 PNG data URI and registers it as a glTF texture. Returns -1 on failure. */
    private int embedTexture(BufferedImage img, String name) {
        String dataUri;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            dataUri = "data:image/png;base64," + b64;
        } catch (IOException e) {
            return -1;
        }

        JsonObject image = new JsonObject();
        image.addProperty("name", name);
        image.addProperty("uri", dataUri);
        images.add(image);

        JsonObject texture = new JsonObject();
        texture.addProperty("sampler", 0);
        texture.addProperty("source", images.size() - 1);
        textures.add(texture);

        return textures.size() - 1;
    }

    // ── Texture atlas (All Merged + Optimize Output) ────────────────────────────

    private static class AtlasResult {
        BufferedImage image;
        Map<String, float[]> uvTransform; // texPath → [destU0, destV0, scaleU, scaleV]
        String alphaMode; // strongest mode needed by any packed texture — OPAQUE < MASK < BLEND
    }

    /**
     * Packs a set of textures into one grid atlas image. Cell size is the
     * max width/height among the inputs — exact for a uniform-resolution
     * resource pack (the normal case), and just wastes a little space in
     * smaller cells for mixed resolutions rather than breaking anything.
     * Returns null if none of the textures could actually be resolved.
     */
    private AtlasResult buildAtlas(Set<String> texPaths) {
        Map<String, BufferedImage> imgs = new LinkedHashMap<>();
        int cellW = 1, cellH = 1;
        for (String p : texPaths) {
            BufferedImage img = resolvers.resolveTexture(p);
            if (img == null) continue;
            imgs.put(p, img);
            cellW = Math.max(cellW, img.getWidth());
            cellH = Math.max(cellH, img.getHeight());
        }
        if (imgs.isEmpty()) return null;

        int n = imgs.size();
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        int atlasW = cols * cellW;
        int atlasH = rows * cellH;

        BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();

        Map<String, float[]> transforms = new LinkedHashMap<>();
        String combinedAlphaMode = "OPAQUE";
        int i = 0;
        for (var e : imgs.entrySet()) {
            int col = i % cols, row = i / cols;
            BufferedImage img = e.getValue();
            int px = col * cellW, py = row * cellH;
            g.drawImage(img, px, py, null);

            String mode = determineAlphaMode(img);
            if (mode.equals("BLEND") || (mode.equals("MASK") && !combinedAlphaMode.equals("BLEND"))) {
                combinedAlphaMode = mode;
            }

            // destU0/destV0/scaleU/scaleV map a face's local [0,1] UV into
            // the exact sub-rectangle THIS image occupies in the atlas —
            // not the full padded cell, in case this image is smaller than
            // a bigger sibling texture set the cell size around.
            transforms.put(e.getKey(), new float[]{
                px / (float) atlasW,
                py / (float) atlasH,
                img.getWidth()  / (float) atlasW,
                img.getHeight() / (float) atlasH,
            });
            i++;
        }
        g.dispose();

        // Cell padding around textures smaller than the max cell size is
        // fully transparent by BufferedImage's default init. If the atlas
        // needs any alpha handling at all, that matters — a MASK/BLEND
        // material correctly treats the padding as invisible; an OPAQUE
        // one would render it as visible black squares. combinedAlphaMode
        // already reflects whichever mode the real content needs, so the
        // padding is automatically handled correctly either way.

        AtlasResult result = new AtlasResult();
        result.image = atlas;
        result.uvTransform = transforms;
        result.alphaMode = combinedAlphaMode;
        return result;
    }

    private int getOrCreateAtlasMaterial(BufferedImage atlasImage, String meshName, String alphaMode) {
        String safeName = (meshName + "_atlas").replace(':', '_').replace('/', '_');
        int texIdx = embedTexture(atlasImage, safeName);

        JsonObject mat = new JsonObject();
        mat.addProperty("name", safeName);

        JsonObject pbr = new JsonObject();
        if (texIdx >= 0) {
            JsonObject baseColorTex = new JsonObject();
            baseColorTex.addProperty("index", texIdx);
            pbr.add("baseColorTexture", baseColorTex);
        }
        pbr.addProperty("metallicFactor", 0.0);
        pbr.addProperty("roughnessFactor", 1.0);
        mat.add("pbrMetallicRoughness", pbr);
        applyAlphaMode(mat, alphaMode);

        JsonObject extensions = new JsonObject();
        extensions.add("KHR_materials_unlit", new JsonObject());
        mat.add("extensions", extensions);

        int idx = materials.size();
        materials.add(mat);
        return idx;
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private JsonArray toArray(List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        return arr;
    }
}
