package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.*;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Builds export geometry while preserving Minecraft model-face UVs through
 * block/model rotations.
 *
 * Important invariants:
 * - north is local Z=0 and south is local Z=1;
 * - UVs stay attached to the original four face vertices;
 * - face rotation permutes UV corners instead of rotating the numeric UV
 *   rectangle around 0.5 (which breaks partial stair/model UV rectangles);
 * - uvlock only compensates rotations that are actually in-plane for the face;
 * - culling is conservative and only removes faces hidden by full opaque coverage.
 */
public class GeometryBuilder {
    private final ResolverChain resolvers;

    private Map<Long,BlockData> occlusionIndex = Map.of();
    private final Map<String,Boolean> fullFaceCache = new HashMap<>();
    private final Map<String,Boolean> opaqueTextureCache = new HashMap<>();
    private boolean faceCullingEnabled;
    private boolean hiddenBlockCullingEnabled;

    private record FaceDef(String dir, int[] corners) {}
    private record Rect(float a0, float a1, float b0, float b1) {}

    private static final FaceDef[] FACES = {
        // Minecraft north is -Z (local z=0), south is +Z (local z=1).
        new FaceDef("north", new int[]{0,1,2,3}),
        new FaceDef("south", new int[]{4,5,6,7}),
        new FaceDef("east",  new int[]{1,4,7,2}),
        new FaceDef("west",  new int[]{5,0,3,6}),
        new FaceDef("up",    new int[]{3,2,7,6}),
        new FaceDef("down",  new int[]{0,5,4,1})
    };

    private static final Map<String,int[]> DIR_VECTORS = Map.of(
        "north", new int[]{0,0,-1},
        "south", new int[]{0,0,1},
        "east",  new int[]{1,0,0},
        "west",  new int[]{-1,0,0},
        "up",    new int[]{0,1,0},
        "down",  new int[]{0,-1,0}
    );

    private static final Map<String,String> OPPOSITE = Map.of(
        "north", "south",
        "south", "north",
        "east", "west",
        "west", "east",
        "up", "down",
        "down", "up"
    );

    public GeometryBuilder(ResolverChain resolvers) {
        this.resolvers = resolvers;
    }

    public ResolverChain getResolvers() {
        return resolvers;
    }

    public void enableFaceCulling(List<BlockData> allBlocks) {
        ensureOcclusionIndex(allBlocks);
        enableFaceCullingWithIndex(occlusionIndex);
    }

    /**
     * Enable face culling against an already-built export spatial index.
     * Subclasses may share an immutable/read-only map; this class never mutates
     * the supplied index.
     */
    protected final void enableFaceCullingWithIndex(Map<Long,BlockData> index) {
        occlusionIndex = index == null ? Map.of() : index;
        faceCullingEnabled = true;
        fullFaceCache.clear();
        opaqueTextureCache.clear();
    }

    /**
     * Experimental whole-block culling. Kept separate from ordinary face
     * culling because it can be much more aggressive on large selections.
     * A block is removed only when every one of its six sides is fully covered.
     */
    public void enableHiddenBlockCulling(List<BlockData> allBlocks) {
        ensureOcclusionIndex(allBlocks);
        hiddenBlockCullingEnabled = true;
        fullFaceCache.clear();
        opaqueTextureCache.clear();
    }

    private void ensureOcclusionIndex(List<BlockData> allBlocks) {
        Map<Long,BlockData> index = new HashMap<>(Math.max(16, allBlocks.size() * 2));
        for (BlockData block : allBlocks) {
            if (!block.isAir()) index.put(SpatialKey.of(block.x, block.y, block.z), block);
        }
        occlusionIndex = index;
    }

    public List<Quad> buildBlock(BlockData block) {
        if (block.isAir()) return List.of();
        if (hiddenBlockCullingEnabled && isCompletelyHidden(block)) return List.of();

        BlockState state = resolvers.resolveBlockState(block.blockId);
        if (state == null) {
            return isVanillaChest(block)
                ? buildChestFallback(block)
                : buildFallbackCube(block);
        }

        List<BlockState.ModelApplication> applications =
            state.resolve(block.properties, block.x, block.y, block.z);

        if (applications.isEmpty()) {
            return isVanillaChest(block)
                ? buildChestFallback(block)
                : buildFallbackCube(block);
        }

        List<Quad> quads = new ArrayList<>();
        for (BlockState.ModelApplication application : applications) {
            BlockModel model = resolvers.resolveModel(application.modelPath);
            if (model == null || model.isEmpty()) continue;

            for (BlockModel.Element element : model.elements) {
                buildElement(element, application, model.textures, block, quads);
            }
        }

        if (!quads.isEmpty()) return quads;
        return isVanillaChest(block)
            ? buildChestFallback(block)
            : buildFallbackCube(block);
    }

    private void buildElement(
        BlockModel.Element element,
        BlockState.ModelApplication application,
        Map<String,String> textures,
        BlockData block,
        List<Quad> out
    ) {
        float x0 = element.from[0] / 16f;
        float y0 = element.from[1] / 16f;
        float z0 = element.from[2] / 16f;
        float x1 = element.to[0] / 16f;
        float y1 = element.to[1] / 16f;
        float z1 = element.to[2] / 16f;

        float[][] corners = {
            {x0,y0,z0}, {x1,y0,z0}, {x1,y1,z0}, {x0,y1,z0},
            {x1,y0,z1}, {x0,y0,z1}, {x0,y1,z1}, {x1,y1,z1}
        };

        if (element.rotation != null) rotateCorners(corners, element.rotation);
        if (application.x != 0 || application.y != 0) {
            applyBlockstateRotation(corners, application.x, application.y);
        }

        for (FaceDef faceDef : FACES) {
            BlockModel.Face face = element.faces.get(faceDef.dir());
            if (face == null) continue;

            String texturePath = face.resolveTexture(textures);
            if (texturePath == null || texturePath.startsWith("#")) continue;

            if (faceCullingEnabled && face.cullface != null) {
                String cullDirection =
                    rotateDirection(face.cullface, application.x, application.y);
                if (isFaceOccluded(block, cullDirection)) continue;
            }

            float[] rect = face.uv != null
                ? face.uv.clone()
                : defaultUv(faceDef.dir(), x0, y0, z0, x1, y1, z1);

            float[] explicitUvs = faceUvPoints(faceDef.dir(), rect, face.rotation);
            if (application.uvlock) {
                applyUvLock(
                    explicitUvs,
                    faceDef.dir(),
                    application.x,
                    application.y
                );
            }

            float[][] vertices = new float[4][3];
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[faceDef.corners()[i]];
                vertices[i][0] = block.x + corner[0];
                vertices[i][1] = block.y + corner[1];
                vertices[i][2] = block.z + corner[2];
            }

            out.add(new Quad(
                vertices,
                explicitUvs,
                texturePath,
                new float[3],
                face.cullface,
                face.tintindex
            ));
        }
    }

    private static float[] defaultUv(
        String direction,
        float x0, float y0, float z0,
        float x1, float y1, float z1
    ) {
        return switch (direction) {
            case "east", "west" -> new float[]{z0 * 16f, y0 * 16f, z1 * 16f, y1 * 16f};
            case "up", "down" -> new float[]{x0 * 16f, z0 * 16f, x1 * 16f, z1 * 16f};
            default -> new float[]{x0 * 16f, y0 * 16f, x1 * 16f, y1 * 16f};
        };
    }

    /**
     * Build explicit normalized UVs in the same order as the raw quad vertices.
     *
     * The north/south mapping was previously treated like the down face. That
     * is what made rotated stair sides/doors appear quarter-turned or mirrored.
     */
    private static float[] faceUvPoints(String direction, float[] rect, int rotation) {
        float u1 = rect[0] / 16f;
        float v1 = rect[1] / 16f;
        float u2 = rect[2] / 16f;
        float v2 = rect[3] / 16f;

        float[] result = switch (direction) {
            case "north", "south", "east", "west" ->
                new float[]{u2,v2, u1,v2, u1,v1, u2,v1};
            case "up" ->
                new float[]{u1,v1, u2,v1, u2,v2, u1,v2};
            case "down" ->
                new float[]{u1,v2, u1,v1, u2,v1, u2,v2};
            default ->
                new float[]{u2,v2, u1,v2, u1,v1, u2,v1};
        };

        if (rotation != 0) rotateUvAssignments(result, rotation);
        return result;
    }

    /**
     * Rotate texture assignment by permuting the four UV corners. Do not rotate
     * the numeric UV rectangle around (0.5,0.5): stair/model faces frequently
     * use partial rectangles and that old behavior moved them into another part
     * of the texture.
     */
    private static void rotateUvAssignments(float[] uv, int degrees) {
        if (uv == null || uv.length < 8) return;
        int turns = Math.floorMod(degrees, 360) / 90;
        if (turns == 0) return;

        float[] old = uv.clone();
        for (int i = 0; i < 4; i++) {
            int source = Math.floorMod(i - turns, 4);
            uv[i * 2] = old[source * 2];
            uv[i * 2 + 1] = old[source * 2 + 1];
        }
    }

    /**
     * Minecraft uvlock keeps world-facing texture orientation stable.
     *
     * A Y block rotation is only an in-plane rotation for UP/DOWN faces; doing
     * it to vertical stair faces is exactly what turned their side textures
     * sideways. Likewise X rotation is in-plane only for EAST/WEST faces.
     */
    private static void applyUvLock(
        float[] uv,
        String sourceFace,
        int xRotation,
        int yRotation
    ) {
        switch (sourceFace) {
            case "up" -> rotateUvAssignments(uv, -yRotation);
            case "down" -> rotateUvAssignments(uv, yRotation);
            case "east" -> rotateUvAssignments(uv, -xRotation);
            case "west" -> rotateUvAssignments(uv, xRotation);
            default -> {
                // Side faces keep their own up/down axis through Y rotations.
            }
        }
    }

    private static void rotateCorners(float[][] corners, BlockModel.Rotation rotation) {
        float ox = rotation.origin[0] / 16f;
        float oy = rotation.origin[1] / 16f;
        float oz = rotation.origin[2] / 16f;
        double radians = Math.toRadians(rotation.angle);
        float cos = (float)Math.cos(radians);
        float sin = (float)Math.sin(radians);

        for (float[] corner : corners) {
            float dx = corner[0] - ox;
            float dy = corner[1] - oy;
            float dz = corner[2] - oz;

            switch (rotation.axis) {
                case "y" -> {
                    corner[0] = ox + dx * cos - dz * sin;
                    corner[2] = oz + dx * sin + dz * cos;
                }
                case "x" -> {
                    corner[1] = oy + dy * cos - dz * sin;
                    corner[2] = oz + dy * sin + dz * cos;
                }
                case "z" -> {
                    corner[0] = ox + dx * cos - dy * sin;
                    corner[1] = oy + dx * sin + dy * cos;
                }
            }

            if (rotation.rescale && Math.abs(rotation.angle) > 1e-6) {
                float scale = 1f / Math.max(.001f, Math.abs(cos));
                switch (rotation.axis) {
                    case "x" -> {
                        corner[1] = oy + (corner[1] - oy) * scale;
                        corner[2] = oz + (corner[2] - oz) * scale;
                    }
                    case "y" -> {
                        corner[0] = ox + (corner[0] - ox) * scale;
                        corner[2] = oz + (corner[2] - oz) * scale;
                    }
                    case "z" -> {
                        corner[0] = ox + (corner[0] - ox) * scale;
                        corner[1] = oy + (corner[1] - oy) * scale;
                    }
                }
            }
        }
    }

    private static void applyBlockstateRotation(float[][] corners, int xRotation, int yRotation) {
        double yRadians = Math.toRadians(yRotation);
        double xRadians = Math.toRadians(xRotation);

        for (float[] corner : corners) {
            float dx = corner[0] - .5f;
            float dy = corner[1] - .5f;
            float dz = corner[2] - .5f;

            if (yRotation != 0) {
                float cos = (float)Math.cos(yRadians);
                float sin = (float)Math.sin(yRadians);
                float nx = dx * cos - dz * sin;
                dz = dx * sin + dz * cos;
                dx = nx;
            }

            if (xRotation != 0) {
                float cos = (float)Math.cos(xRadians);
                float sin = (float)Math.sin(xRadians);
                float ny = dy * cos - dz * sin;
                dz = dy * sin + dz * cos;
                dy = ny;
            }

            corner[0] = .5f + dx;
            corner[1] = .5f + dy;
            corner[2] = .5f + dz;
        }
    }

    private static String rotateDirection(String direction, int xDegrees, int yDegrees) {
        int[] vector = DIR_VECTORS.get(direction);
        if (vector == null) return direction;

        double dx = vector[0];
        double dy = vector[1];
        double dz = vector[2];

        if (yDegrees != 0) {
            double radians = Math.toRadians(yDegrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double nx = dx * cos - dz * sin;
            dz = dx * sin + dz * cos;
            dx = nx;
        }

        if (xDegrees != 0) {
            double radians = Math.toRadians(xDegrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double ny = dy * cos - dz * sin;
            dz = dy * sin + dz * cos;
            dy = ny;
        }

        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ax >= ay && ax >= az) return dx > 0 ? "east" : "west";
        if (ay >= ax && ay >= az) return dy > 0 ? "up" : "down";
        return dz > 0 ? "south" : "north";
    }

    /** Only cull when the neighbor actually covers the complete shared face. */
    private boolean isFaceOccluded(BlockData block, String worldDirection) {
        int[] offset = DIR_VECTORS.get(worldDirection);
        if (offset == null || occlusionIndex.isEmpty()) return false;

        BlockData neighbor = occlusionIndex.get(
            SpatialKey.of(
                block.x + offset[0],
                block.y + offset[1],
                block.z + offset[2]
            )
        );

        if (neighbor == null || neighbor.isAir()) return false;

        String opposite = OPPOSITE.get(worldDirection);
        String key = fullFaceCacheKey(neighbor, opposite);
        return fullFaceCache.computeIfAbsent(
            key,
            ignored -> coversWholeFace(neighbor, opposite)
        );
    }

    private boolean isCompletelyHidden(BlockData block) {
        if (occlusionIndex.isEmpty()) return false;
        for (String direction : DIR_VECTORS.keySet()) {
            if (!isFaceOccluded(block, direction)) return false;
        }
        return true;
    }

    private static String fullFaceCacheKey(BlockData block, String side) {
        return block.blockId
            + "[" + BlockGrouper.stateKey(block.properties) + "]|"
            + side;
    }

    private boolean coversWholeFace(BlockData block, String side) {
        BlockState state = resolvers.resolveBlockState(block.blockId);
        if (state == null) return false;

        List<BlockState.ModelApplication> applications =
            state.resolve(block.properties, block.x, block.y, block.z);
        if (applications.isEmpty()) return false;

        List<Rect> rectangles = new ArrayList<>();

        for (BlockState.ModelApplication application : applications) {
            BlockModel model = resolvers.resolveModel(application.modelPath);
            if (model == null) continue;

            for (BlockModel.Element element : model.elements) {
                float x0 = element.from[0] / 16f;
                float y0 = element.from[1] / 16f;
                float z0 = element.from[2] / 16f;
                float x1 = element.to[0] / 16f;
                float y1 = element.to[1] / 16f;
                float z1 = element.to[2] / 16f;

                float[][] corners = {
                    {x0,y0,z0}, {x1,y0,z0}, {x1,y1,z0}, {x0,y1,z0},
                    {x1,y0,z1}, {x0,y0,z1}, {x0,y1,z1}, {x1,y1,z1}
                };

                if (element.rotation != null) rotateCorners(corners, element.rotation);
                if (application.x != 0 || application.y != 0) {
                    applyBlockstateRotation(corners, application.x, application.y);
                }

                for (FaceDef faceDef : FACES) {
                    BlockModel.Face face = element.faces.get(faceDef.dir());
                    if (face == null || face.cullface == null) continue;

                    String worldCull =
                        rotateDirection(face.cullface, application.x, application.y);
                    if (!worldCull.equals(side)) continue;

                    // Geometry alone is not enough to occlude a neighbour. A
                    // full-cube leaf/glass/cutout face still has visible holes.
                    // Only fully opaque resolved face textures may contribute
                    // to the shared-face coverage mask.
                    String texturePath = face.resolveTexture(model.textures);
                    if (!isTextureFullyOpaque(texturePath)) continue;

                    Rect rectangle =
                        projectBoundaryRect(corners, faceDef.corners(), side);
                    if (rectangle != null) rectangles.add(rectangle);
                }
            }
        }

        return coversUnitSquare(rectangles);
    }

    private boolean isTextureFullyOpaque(String texturePath) {
        if (texturePath == null || texturePath.isBlank() || texturePath.startsWith("#")) {
            return false;
        }
        return opaqueTextureCache.computeIfAbsent(texturePath, path -> {
            BufferedImage image;
            try {
                image = resolvers.resolveTexture(path);
            } catch (Exception ignored) {
                return false;
            }
            if (image == null) return false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 255) return false;
                }
            }
            return true;
        });
    }

    private static Rect projectBoundaryRect(
        float[][] corners,
        int[] ids,
        String side
    ) {
        double plane = switch (side) {
            case "east", "up", "south" -> 1.0;
            default -> 0.0;
        };

        float minA = Float.MAX_VALUE;
        float maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE;
        float maxB = -Float.MAX_VALUE;
        float epsilon = 1e-3f;

        for (int id : ids) {
            float[] corner = corners[id];
            double boundary = switch (side) {
                case "north", "south" -> corner[2];
                case "east", "west" -> corner[0];
                default -> corner[1];
            };

            if (Math.abs(boundary - plane) > epsilon) return null;

            float a = switch (side) {
                case "north", "south" -> corner[0];
                case "east", "west" -> corner[2];
                default -> corner[0];
            };

            float b = switch (side) {
                case "north", "south", "east", "west" -> corner[1];
                default -> corner[2];
            };

            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }

        minA = Math.max(0, minA);
        maxA = Math.min(1, maxA);
        minB = Math.max(0, minB);
        maxB = Math.min(1, maxB);

        if (maxA - minA < epsilon || maxB - minB < epsilon) return null;
        return new Rect(minA, maxA, minB, maxB);
    }

    private static boolean coversUnitSquare(List<Rect> rectangles) {
        if (rectangles.isEmpty()) return false;

        TreeSet<Float> xs = new TreeSet<>();
        TreeSet<Float> ys = new TreeSet<>();
        xs.add(0f);
        xs.add(1f);
        ys.add(0f);
        ys.add(1f);

        for (Rect rect : rectangles) {
            xs.add(rect.a0);
            xs.add(rect.a1);
            ys.add(rect.b0);
            ys.add(rect.b1);
        }

        Float[] xValues = xs.toArray(Float[]::new);
        Float[] yValues = ys.toArray(Float[]::new);

        for (int x = 0; x < xValues.length - 1; x++) {
            for (int y = 0; y < yValues.length - 1; y++) {
                float cx = (xValues[x] + xValues[x + 1]) / 2f;
                float cy = (yValues[y] + yValues[y + 1]) / 2f;
                boolean covered = false;

                for (Rect rect : rectangles) {
                    if (
                        cx >= rect.a0 - 1e-4f &&
                        cx <= rect.a1 + 1e-4f &&
                        cy >= rect.b0 - 1e-4f &&
                        cy <= rect.b1 + 1e-4f
                    ) {
                        covered = true;
                        break;
                    }
                }

                if (!covered) return false;
            }
        }

        return true;
    }

    private static boolean isVanillaChest(BlockData block) {
        return block.blockId.equals("minecraft:chest")
            || block.blockId.equals("minecraft:trapped_chest");
    }

    /**
     * Static geometry for vanilla chest block entities.
     *
     * Chest textures are entity atlases, not six ordinary block-face textures.
     * The previous fallback mapped the entire 64x64 image onto every face. This
     * now uses Minecraft-style cuboid unwrap regions for base/lid/latch and the
     * left/right entity textures for double-chest halves.
     */
    private List<Quad> buildChestFallback(BlockData block) {
        boolean trapped = block.blockId.equals("minecraft:trapped_chest");
        String type = block.prop("type");

        String textureBase = trapped ? "trapped" : "normal";
        String texture = switch (type) {
            case "left" -> "minecraft:entity/chest/" + textureBase + "_left";
            case "right" -> "minecraft:entity/chest/" + textureBase + "_right";
            default -> "minecraft:entity/chest/" + textureBase;
        };

        // If a particular version/resource pack lacks a split texture name,
        // fall back to the single texture rather than creating magenta geometry.
        if (resolvers.resolveTexture(texture) == null) {
            texture = "minecraft:entity/chest/" + textureBase;
        }

        int facingRotation = switch (block.prop("facing")) {
            case "east" -> 90;
            case "south" -> 180;
            case "west" -> 270;
            default -> 0;
        };

        List<Quad> out = new ArrayList<>();

        // Base: ModelPart UV origin (0,19), 14x10x14.
        addEntityCuboid(
            out, block,
            1f/16f, 0f, 1f/16f,
            15f/16f, 10f/16f, 15f/16f,
            texture,
            0f, 19f,
            14f, 10f, 14f,
            64f, 64f,
            facingRotation,
            "base"
        );

        // Lid: UV origin (0,0), 14x5x14.
        addEntityCuboid(
            out, block,
            1f/16f, 10f/16f, 1f/16f,
            15f/16f, 15f/16f, 15f/16f,
            texture,
            0f, 0f,
            14f, 5f, 14f,
            64f, 64f,
            facingRotation,
            "lid"
        );

        // Single-chest latch: the small UV area at the top-left of the entity
        // texture is intentionally shared by the latch cuboid.
        if (!type.equals("left") && !type.equals("right")) {
            addEntityCuboid(
                out, block,
                7f/16f, 8f/16f, 0f,
                9f/16f, 12f/16f, 1f/16f,
                texture,
                0f, 0f,
                2f, 4f, 1f,
                64f, 64f,
                facingRotation,
                "lid"
            );
        }

        return out;
    }

    /**
     * Add an entity-model cuboid using the standard Minecraft box unwrap:
     * one strip of four side faces and two top/bottom rectangles.
     */
    private static void addEntityCuboid(
        List<Quad> out,
        BlockData block,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        String texture,
        float textureU,
        float textureV,
        float pixelX,
        float pixelY,
        float pixelZ,
        float textureWidth,
        float textureHeight,
        int yRotation,
        String partName
    ) {
        float a = textureU;
        float b = a + pixelZ;
        float c = b + pixelX;
        float d = c + pixelZ;
        float e = d + pixelX;

        float top = textureV;
        float sideTop = top + pixelZ;
        float sideBottom = sideTop + pixelY;

        Map<String,float[]> uvRects = Map.of(
            "west",  new float[]{a, sideTop, b, sideBottom},
            "north", new float[]{b, sideTop, c, sideBottom},
            "east",  new float[]{c, sideTop, d, sideBottom},
            "south", new float[]{d, sideTop, e, sideBottom},
            "up",    new float[]{b, top, c, sideTop},
            "down",  new float[]{c, top, c + pixelX, sideTop}
        );

        float[][] corners = {
            {x0,y0,z0}, {x1,y0,z0}, {x1,y1,z0}, {x0,y1,z0},
            {x1,y0,z1}, {x0,y0,z1}, {x0,y1,z1}, {x1,y1,z1}
        };

        if (yRotation != 0) rotateLocalY(corners, yRotation);

        for (FaceDef faceDef : FACES) {
            float[] rect = uvRects.get(faceDef.dir());
            float[] uv = entityUvPoints(
                faceDef.dir(),
                rect,
                textureWidth,
                textureHeight
            );

            float[][] vertices = new float[4][3];
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[faceDef.corners()[i]];
                vertices[i][0] = block.x + corner[0];
                vertices[i][1] = block.y + corner[1];
                vertices[i][2] = block.z + corner[2];
            }

            out.add(new Quad(
                vertices,
                uv,
                texture,
                new float[3],
                null,
                -1,
                partName
            ));
        }
    }

    private static float[] entityUvPoints(
        String direction,
        float[] rect,
        float textureWidth,
        float textureHeight
    ) {
        float u1 = rect[0] / textureWidth;
        float v1 = rect[1] / textureHeight;
        float u2 = rect[2] / textureWidth;
        float v2 = rect[3] / textureHeight;

        return switch (direction) {
            case "north", "south", "east", "west" ->
                new float[]{u2,v2, u1,v2, u1,v1, u2,v1};
            case "up" ->
                new float[]{u1,v1, u2,v1, u2,v2, u1,v2};
            case "down" ->
                new float[]{u1,v2, u1,v1, u2,v1, u2,v2};
            default ->
                new float[]{u2,v2, u1,v2, u1,v1, u2,v1};
        };
    }

    private static void rotateLocalY(float[][] corners, int degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float)Math.cos(radians);
        float sin = (float)Math.sin(radians);

        for (float[] corner : corners) {
            float dx = corner[0] - .5f;
            float dz = corner[2] - .5f;
            float nx = dx * cos - dz * sin;
            float nz = dx * sin + dz * cos;
            corner[0] = .5f + nx;
            corner[2] = .5f + nz;
        }
    }

    private List<Quad> buildFallbackCube(BlockData block) {
        String texture = "MISSING_" + block.blockId.replace(":", "_");
        List<Quad> out = new ArrayList<>();
        addSimpleBox(out, block, 0f, 0f, 0f, 1f, 1f, 1f, texture);
        return out;
    }

    private static void addSimpleBox(
        List<Quad> out,
        BlockData block,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        String texture
    ) {
        float[][] corners = {
            {x0,y0,z0}, {x1,y0,z0}, {x1,y1,z0}, {x0,y1,z0},
            {x1,y0,z1}, {x0,y0,z1}, {x0,y1,z1}, {x1,y1,z1}
        };

        for (FaceDef faceDef : FACES) {
            float[][] vertices = new float[4][3];
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[faceDef.corners()[i]];
                vertices[i][0] = block.x + corner[0];
                vertices[i][1] = block.y + corner[1];
                vertices[i][2] = block.z + corner[2];
            }

            float[] uv = entityUvPoints(
                faceDef.dir(),
                new float[]{0f, 0f, 1f, 1f},
                1f,
                1f
            );

            out.add(new Quad(
                vertices,
                uv,
                texture,
                new float[3],
                faceDef.dir(),
                -1
            ));
        }
    }
}
