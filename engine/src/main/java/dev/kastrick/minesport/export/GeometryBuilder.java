package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.util.*;

/**
 * Phase 2 geometry builder.
 *
 * Converts a BlockData + its resolved BlockModel into a list of Quads
 * (4-vertex polygons with UV coords) ready for OBJ/glTF export.
 *
 * Process per block:
 *  1. Resolve blockstate → pick model variant(s)
 *  2. For each model application: load BlockModel, apply blockstate rotation
 *  3. For each element in model: convert from/to → 8 vertices
 *  4. For each face: build quad, resolve UV, apply face rotation
 *  5. Apply element rotation if present
 *  6. Return list of Quads
 */
public class GeometryBuilder {

    private final ResolverChain resolvers;

    // Optional hidden-face culling (the "Optimize Output" experimental feature).
    // null = disabled = identical behavior to before this existed.
    private Map<Long, BlockData> occlusionIndex;
    private final Map<String, Set<String>> cullableFacesCache = new HashMap<>();

    public GeometryBuilder(ResolverChain resolvers) {
        this.resolvers = resolvers;
    }

    public ResolverChain getResolvers() { return resolvers; }

    /**
     * Enables hidden-face culling: a face is skipped entirely if the model
     * itself marks it as "cullface"-able in that direction AND the neighbor
     * block in that direction is present, non-air, and has a matching
     * cullface-marked face pointing back — i.e. the two faces are fully
     * sandwiched between two solid-in-that-direction blocks and can never
     * be seen from any angle. This is the exact mechanism vanilla Minecraft
     * itself uses (every model already carries this data — see BlockModel.Face
     * .cullface — it just wasn't being read by anything until now).
     *
     * Safe by construction: a face is only ever removed when we have hard
     * evidence it's fully enclosed. Missing/unknown neighbor data always
     * means "don't cull" (render it), never the other way around.
     */
    public void enableFaceCulling(List<BlockData> allBlocks) {
        Map<Long, BlockData> index = new HashMap<>(allBlocks.size());
        for (BlockData b : allBlocks) {
            if (!b.isAir()) index.put(spatialKey(b.x, b.y, b.z), b);
        }
        this.occlusionIndex = index;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build geometry quads for a single block.
     * Returns empty list if block can't be resolved.
     */
    public List<Quad> buildBlock(BlockData block) {
        if (block.isAir()) return List.of();

        BlockState bs = resolvers.resolveBlockState(block.blockId);
        if (bs == null) {
            // Fallback: unit cube with no texture
            return buildFallbackCube(block);
        }

        List<BlockState.ModelApplication> applications = bs.resolve(block.properties);
        if (applications.isEmpty()) return buildFallbackCube(block);

        var quads = new ArrayList<Quad>();

        for (BlockState.ModelApplication app : applications) {
            BlockModel model = resolvers.resolveModel(app.modelPath);
            if (model == null || model.isEmpty()) continue;

            for (BlockModel.Element element : model.elements) {
                buildElement(element, app, model.textures, block, quads);
            }
        }

        return quads;
    }

    // ── Element → quads ───────────────────────────────────────────────────────

    private void buildElement(
            BlockModel.Element el,
            BlockState.ModelApplication app,
            Map<String, String> textures,
            BlockData block,
            List<Quad> out
    ) {
        // Convert from/to from 0-16 space to 0-1 space
        float x0 = el.from[0] / 16f, y0 = el.from[1] / 16f, z0 = el.from[2] / 16f;
        float x1 = el.to[0]   / 16f, y1 = el.to[1]   / 16f, z1 = el.to[2]   / 16f;

        // 8 corners of the element box (local 0-1 space)
        float[][] corners = {
            {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, // south (-z) face
            {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z1}, {x1, y1, z1}, // north (+z) face
        };

        // Apply element-level rotation if present
        if (el.rotation != null) {
            rotateCorners(corners, el.rotation);
        }

        // Apply blockstate-level rotation (x/y from ModelApplication)
        if (app.x != 0 || app.y != 0) {
            applyBlockstateRotation(corners, app.x, app.y);
        }

        // Face definitions: direction → [4 corner indices, default UVs, normal]
        // Corners are indexed from the array above
        record FaceDef(String dir, int[] ci, float[] defUv, float[] normal) {}
        FaceDef[] faceDefs = {
            new FaceDef("south", new int[]{0,1,2,3}, new float[]{x0*16,y0*16,x1*16,y1*16}, new float[]{0, 0,-1}),
            new FaceDef("north", new int[]{4,5,6,7}, new float[]{x0*16,y0*16,x1*16,y1*16}, new float[]{0, 0, 1}),
            new FaceDef("east",  new int[]{1,4,7,2}, new float[]{z0*16,y0*16,z1*16,y1*16}, new float[]{ 1, 0, 0}),
            new FaceDef("west",  new int[]{5,0,3,6}, new float[]{z0*16,y0*16,z1*16,y1*16}, new float[]{-1, 0, 0}),
            new FaceDef("up",    new int[]{3,2,7,6}, new float[]{x0*16,z0*16,x1*16,z1*16}, new float[]{ 0, 1, 0}),
            new FaceDef("down",  new int[]{0,5,4,1}, new float[]{x0*16,z0*16,x1*16,z1*16}, new float[]{ 0,-1, 0}),
        };

        for (FaceDef fd : faceDefs) {
            BlockModel.Face face = el.faces.get(fd.dir());
            if (face == null) continue; // this face not defined in model

            // Resolve texture path
            String texPath = face.resolveTexture(textures);
            if (texPath == null || texPath.startsWith("#")) continue; // unresolved

            // Hidden-face culling (opt-in — see enableFaceCulling)
            if (occlusionIndex != null && face.cullface != null) {
                String worldDir = rotateDirection(face.cullface, app.x, app.y);
                if (isFaceOccluded(block, worldDir)) continue;
            }

            // UV coords (use model's UV or auto-generate from element bounds)
            float[] uv = face.uv != null ? face.uv.clone() : fd.defUv().clone();

            // Apply UV rotation
            if (face.rotation != 0) {
                uv = rotateUv(uv, face.rotation);
            }

            // Build 4 world-space vertices for this face
            float[][] verts = new float[4][3];
            for (int i = 0; i < 4; i++) {
                float[] c = corners[fd.ci()[i]];
                // Translate to world position. No Z flip: Minecraft's world
                // (X=east, Y=up, Z=south) is already right-handed Y-up,
                // same handedness as glTF/OBJ — negating just Z here used
                // to mirror the geometry without correspondingly reversing
                // face winding order, so the winding (which determines
                // front/back for backface culling) disagreed with the
                // declared normal. That produced both the "hinges on the
                // wrong side" mirroring and the black/missing-face culling
                // artifacts on doubleSided:false materials.
                verts[i][0] = block.x + c[0];
                verts[i][1] = block.y + c[1];
                verts[i][2] = block.z + c[2];
            }

            out.add(new Quad(verts, uv, texPath, fd.normal(), face.cullface, face.tintindex));
        }
    }

    // ── Rotations ─────────────────────────────────────────────────────────────

    private void rotateCorners(float[][] corners, BlockModel.Rotation rot) {
        float ox = rot.origin[0] / 16f;
        float oy = rot.origin[1] / 16f;
        float oz = rot.origin[2] / 16f;
        double rad = Math.toRadians(rot.angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        for (float[] c : corners) {
            float dx = c[0] - ox, dy = c[1] - oy, dz = c[2] - oz;
            switch (rot.axis) {
                case "y" -> { c[0] = ox + dx*cos - dz*sin; c[2] = oz + dx*sin + dz*cos; }
                case "x" -> { c[1] = oy + dy*cos - dz*sin; c[2] = oz + dy*sin + dz*cos; }
                case "z" -> { c[0] = ox + dx*cos - dy*sin; c[1] = oy + dx*sin + dy*cos; }
            }
        }
    }

    private void applyBlockstateRotation(float[][] corners, int xRot, int yRot) {
        // Rotate around block centre (0.5, 0.5, 0.5)
        float cx = 0.5f, cy = 0.5f, cz = 0.5f;

        for (float[] c : corners) {
            float dx = c[0]-cx, dy = c[1]-cy, dz = c[2]-cz;
            double yr = Math.toRadians(yRot);
            double xr = Math.toRadians(xRot);

            // Y rotation first
            if (yRot != 0) {
                float cos = (float)Math.cos(yr), sin = (float)Math.sin(yr);
                float nx = dx*cos - dz*sin; dz = dx*sin + dz*cos; dx = nx;
            }
            // Then X rotation
            if (xRot != 0) {
                float cos = (float)Math.cos(xr), sin = (float)Math.sin(xr);
                float ny = dy*cos - dz*sin; dz = dy*sin + dz*cos; dy = ny;
            }

            c[0] = cx+dx; c[1] = cy+dy; c[2] = cz+dz;
        }
    }

    private float[] rotateUv(float[] uv, int degrees) {
        // UV is [u1,v1,u2,v2] in 0-16 space
        // Rotation is around UV centre
        float cx = (uv[0]+uv[2])/2f, cy = (uv[1]+uv[3])/2f;
        float hw = (uv[2]-uv[0])/2f, hh = (uv[3]-uv[1])/2f;
        return switch (degrees) {
            case 90  -> new float[]{cx-hh, cy-hw, cx+hh, cy+hw};
            case 180 -> new float[]{cx-hw, cy-hh, cx+hw, cy+hh};
            case 270 -> new float[]{cx+hh, cy+hw, cx-hh, cy-hw};
            default  -> uv;
        };
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    /** Unit cube fallback for blocks that can't be resolved (modded, unknown). */
    private List<Quad> buildFallbackCube(BlockData block) {
        float x0 = block.x, y0 = block.y, z0 = block.z;
        float x1 = x0+1,    y1 = y0+1,    z1 = z0+1;

        var quads = new ArrayList<Quad>();
        float[] uv = {0,0,16,16};
        String tex = "MISSING_" + block.blockId.replace(":", "_");

        quads.add(new Quad(new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0}}, uv, tex, new float[]{0,0,-1}, null, -1));
        quads.add(new Quad(new float[][]{{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}}, uv, tex, new float[]{0,0, 1}, null, -1));
        quads.add(new Quad(new float[][]{{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}}, uv, tex, new float[]{ 1,0,0}, null, -1));
        quads.add(new Quad(new float[][]{{x0,y0,z1},{x0,y0,z0},{x0,y1,z0},{x0,y1,z1}}, uv, tex, new float[]{-1,0,0}, null, -1));
        quads.add(new Quad(new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}}, uv, tex, new float[]{0, 1,0}, null, -1));
        quads.add(new Quad(new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}}, uv, tex, new float[]{0,-1,0}, null, -1));
        return quads;
    }

    // ── Hidden-face culling helpers ─────────────────────────────────────────────
    // Direction convention matches MultipartResolver/BlockGrouper: raw Minecraft
    // world space (north=-Z, south=+Z, east=+X, west=-X, up=+Y, down=-Y) — same
    // space vertices are now emitted in directly (see buildElement above).

    private static final Map<String, int[]> DIR_VECTORS = Map.of(
        "north", new int[]{0, 0, -1},
        "south", new int[]{0, 0,  1},
        "east",  new int[]{1, 0,  0},
        "west",  new int[]{-1, 0, 0},
        "up",    new int[]{0, 1,  0},
        "down",  new int[]{0, -1, 0}
    );

    private static final Map<String, String> OPPOSITE_DIR = Map.of(
        "north", "south", "south", "north",
        "east",  "west",  "west",  "east",
        "up",    "down",  "down",  "up"
    );

    /**
     * Rotates a model-space direction (as authored in a face's "cullface")
     * by the blockstate's x/y rotation to find which WORLD direction it
     * actually points to. Uses the exact same rotation order/signs as
     * applyBlockstateRotation above, so this always agrees with where the
     * geometry itself actually ends up.
     */
    private static String rotateDirection(String dir, int xDeg, int yDeg) {
        int[] v = DIR_VECTORS.get(dir);
        if (v == null) return dir;
        double dx = v[0], dy = v[1], dz = v[2];

        if (yDeg != 0) {
            double yr = Math.toRadians(yDeg);
            double cos = Math.cos(yr), sin = Math.sin(yr);
            double nx = dx * cos - dz * sin;
            double nz = dx * sin + dz * cos;
            dx = nx; dz = nz;
        }
        if (xDeg != 0) {
            double xr = Math.toRadians(xDeg);
            double cos = Math.cos(xr), sin = Math.sin(xr);
            double ny = dy * cos - dz * sin;
            double nz = dy * sin + dz * cos;
            dy = ny; dz = nz;
        }

        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? "east" : "west";
        if (ay >= ax && ay >= az) return dy > 0 ? "up" : "down";
        return dz > 0 ? "south" : "north";
    }

    /** True if the neighbor in worldDirection fully occludes this face. */
    private boolean isFaceOccluded(BlockData block, String worldDirection) {
        int[] d = DIR_VECTORS.get(worldDirection);
        if (d == null) return false;

        BlockData neighbor = occlusionIndex.get(spatialKey(block.x + d[0], block.y + d[1], block.z + d[2]));
        if (neighbor == null || neighbor.isAir()) return false; // unknown/no neighbor → never cull

        String opposite = OPPOSITE_DIR.get(worldDirection);
        Set<String> neighborCullFaces = cullableFacesCache.computeIfAbsent(
            cullCacheKey(neighbor), k -> computeCullableFaces(neighbor));
        return neighborCullFaces.contains(opposite);
    }

    /** All world-space directions in which this block+state's model presents a cullface. */
    private Set<String> computeCullableFaces(BlockData b) {
        Set<String> result = new HashSet<>();
        BlockState bs = resolvers.resolveBlockState(b.blockId);
        if (bs == null) return result;

        for (BlockState.ModelApplication app : bs.resolve(b.properties)) {
            BlockModel model = resolvers.resolveModel(app.modelPath);
            if (model == null) continue;
            for (BlockModel.Element el : model.elements) {
                for (BlockModel.Face face : el.faces.values()) {
                    if (face.cullface != null) {
                        result.add(rotateDirection(face.cullface, app.x, app.y));
                    }
                }
            }
        }
        return result;
    }

    private static String cullCacheKey(BlockData b) {
        return b.blockId + "[" + BlockGrouper.stateKey(b.properties) + "]";
    }

    /** Pack x,y,z into a single long — same scheme as MultipartResolver/BlockGrouper. */
    private static long spatialKey(int x, int y, int z) {
        return ((long) (x + 1048576) << 42)
             | ((long) (y + 1048576) << 21)
             |  (long) (z + 1048576);
    }
}
