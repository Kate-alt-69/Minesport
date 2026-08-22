package dev.kastrick.minesport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.export.BlockGeometryClassifier;
import dev.kastrick.minesport.export.BlockGeometryKind;
import dev.kastrick.minesport.export.BlockGrouper;
import dev.kastrick.minesport.export.ExportWorldContext;
import dev.kastrick.minesport.export.FlatterSettings;
import dev.kastrick.minesport.export.LiquidGeometryBuilder;
import dev.kastrick.minesport.export.Quad;
import dev.kastrick.minesport.export.SpatialKey;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;
import dev.kastrick.minesport.resolver.RuntimeModelRegistry;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Export-mode GeometryBuilder wrapper used by IPC mode. */
public final class GeometryBuilder extends dev.kastrick.minesport.export.GeometryBuilder {
    private static final int[][] NEIGHBORS = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };
    private static final Map<String,int[]> DIRECTIONS = Map.of(
        "east", new int[]{1,0,0},
        "west", new int[]{-1,0,0},
        "up", new int[]{0,1,0},
        "down", new int[]{0,-1,0},
        "south", new int[]{0,0,1},
        "north", new int[]{0,0,-1}
    );
    private static final float FACE_EPSILON = 1.0e-4f;

    private Map<Long,BlockData> worldIndex;
    private final Map<String,BlockGeometryKind> kindCache = new HashMap<>();
    private final Map<String,RuntimeModelRegistry> runtimeRegistries = new ConcurrentHashMap<>();
    private final Set<String> failedRuntimeRegistries = ConcurrentHashMap.newKeySet();
    private final BlockGeometryClassifier classifier;
    private boolean hiddenBlockCullingEnabled;

    public GeometryBuilder(ResolverChain resolvers) {
        super(resolvers);
        this.classifier = new BlockGeometryClassifier(resolvers);
        this.hiddenBlockCullingEnabled = readHiddenBlockCullingSetting();
        this.worldIndex = ExportWorldContext.takeIndex();
    }

    @Override
    public void enableFaceCulling(List<BlockData> allBlocks) {
        super.enableFaceCulling(allBlocks);
        buildWorldIndex(allBlocks);
    }

    /** Enables only the hidden-block visibility pass; does not enable face culling on surfaces. */
    public void enableHiddenBlockCulling(List<BlockData> allBlocks) {
        hiddenBlockCullingEnabled = true;
        buildWorldIndex(allBlocks);
    }

    private void buildWorldIndex(List<BlockData> allBlocks) {
        Map<Long,BlockData> index = new HashMap<>(Math.max(16, allBlocks.size() * 2));
        for (BlockData block : allBlocks) {
            if (block != null && !block.isAir()) {
                index.put(SpatialKey.of(block.x, block.y, block.z), block);
            }
        }
        worldIndex = index;
        kindCache.clear();
    }

    @Override
    public List<Quad> buildBlock(BlockData block) {
        if (block == null || block.isAir()) return List.of();
        if (hiddenBlockCullingEnabled
            && !LiquidGeometryBuilder.isLiquid(block)
            && isFullyEnclosed(block)) {
            return List.of();
        }

        // Standalone fluid blocks are FluidState-rendered and normally have no
        // useful ordinary block model. Handle them before registry/static model
        // fallback so water/lava can never become fallback cubes.
        if (LiquidGeometryBuilder.isLiquid(block)) {
            return LiquidGeometryBuilder.build(block, worldIndex);
        }

        RuntimeModelRegistry runtime = runtimeRegistry(block.runtimeRegistryPath);
        if (runtime != null && runtime.shouldOverride(block)) {
            // Runtime capture knows this exact state. A non-null empty list is
            // authoritative too: Minecraft baked an empty model, so falling back
            // to static JSON/fallback-cube geometry would invent visuals that do
            // not exist. finalizeGeometry still appends independent FluidState
            // geometry for waterlogged hosts.
            List<Quad> baked = runtime.build(block);
            if (baked != null) {
                return finalizeGeometry(block, baked);
            }
        }
        return finalizeGeometry(block, super.buildBlock(block));
    }

    /** Apply world-dependent render rules equally to runtime and static geometry. */
    private List<Quad> finalizeGeometry(BlockData block, List<Quad> base) {
        List<Quad> result = base == null ? List.of() : base;

        if (isFullGlassBlock(block) && !result.isEmpty() && !worldIndex.isEmpty()) {
            List<Quad> filtered = new ArrayList<>(result.size());
            for (Quad quad : result) {
                String direction = boundaryDirection(block, quad);
                if (direction != null && sameGlassNeighbour(block, direction)) continue;
                filtered.add(quad);
            }
            result = filtered;
        }

        if (LiquidGeometryBuilder.isWaterlogged(block)) {
            List<Quad> water = LiquidGeometryBuilder.buildWaterlogged(block, worldIndex);
            if (!water.isEmpty()) {
                List<Quad> combined = new ArrayList<>(result.size() + water.size());
                combined.addAll(result);
                combined.addAll(water);
                result = combined;
            }
        }
        return result;
    }

    /**
     * Vanilla skips the shared face between equal full glass blocks. Do this
     * independently of opaque face-culling: transparent glass must not occlude
     * other blocks, but two identical glass cubes should not retain a doubled
     * coplanar internal surface.
     */
    private boolean sameGlassNeighbour(BlockData block, String direction) {
        int[] offset = DIRECTIONS.get(direction);
        if (offset == null) return false;
        BlockData neighbour = worldIndex.get(SpatialKey.of(
            block.x + offset[0],
            block.y + offset[1],
            block.z + offset[2]
        ));
        return neighbour != null && block.blockId.equals(neighbour.blockId);
    }

    private static String boundaryDirection(BlockData block, Quad quad) {
        if (quad == null) return null;
        String declared = quad.cullface();
        if (DIRECTIONS.containsKey(declared)) return declared;

        float[][] vertices = quad.verts();
        if (vertices == null || vertices.length < 4) return null;
        if (allNear(vertices, 0, block.x)) return "west";
        if (allNear(vertices, 0, block.x + 1f)) return "east";
        if (allNear(vertices, 1, block.y)) return "down";
        if (allNear(vertices, 1, block.y + 1f)) return "up";
        if (allNear(vertices, 2, block.z)) return "north";
        if (allNear(vertices, 2, block.z + 1f)) return "south";
        return null;
    }

    private static boolean allNear(float[][] vertices, int axis, float plane) {
        for (float[] vertex : vertices) {
            if (vertex == null || vertex.length <= axis || Math.abs(vertex[axis] - plane) > FACE_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullGlassBlock(BlockData block) {
        if (block == null || block.blockId == null) return false;
        String id = block.blockId.toLowerCase(Locale.ROOT);
        return id.equals("minecraft:glass")
            || id.equals("minecraft:tinted_glass")
            || (id.startsWith("minecraft:") && id.endsWith("_stained_glass"));
    }

    private RuntimeModelRegistry runtimeRegistry(String path) {
        if (path == null || path.isBlank() || failedRuntimeRegistries.contains(path)) return null;
        RuntimeModelRegistry cached = runtimeRegistries.get(path);
        if (cached != null) return cached;
        RuntimeModelRegistry loaded = RuntimeModelRegistry.load(new File(path), "", null);
        if (loaded == null) {
            failedRuntimeRegistries.add(path);
            return null;
        }
        RuntimeModelRegistry previous = runtimeRegistries.putIfAbsent(path, loaded);
        return previous != null ? previous : loaded;
    }

    private boolean isFullyEnclosed(BlockData block) {
        if (worldIndex.isEmpty()) return false;
        for (int[] d : NEIGHBORS) {
            BlockData neighbor = worldIndex.get(SpatialKey.of(block.x + d[0], block.y + d[1], block.z + d[2]));
            if (neighbor == null || neighbor.isAir()) return false;
            // Geometry-only FULL_BLOCK classification is not enough for glass.
            // Transparent full cubes must never hide the block behind them.
            if (isTransparentOccluder(neighbor)) return false;
            if (classify(neighbor) != BlockGeometryKind.FULL_BLOCK) return false;
        }
        return true;
    }

    private static boolean isTransparentOccluder(BlockData block) {
        if (block == null || block.blockId == null) return false;
        String id = block.blockId.toLowerCase(Locale.ROOT);
        return LiquidGeometryBuilder.isLiquid(block)
            || LiquidGeometryBuilder.isWaterlogged(block)
            || id.equals("minecraft:glass")
            || id.equals("minecraft:tinted_glass")
            || id.endsWith("_stained_glass")
            || id.endsWith("_glass_pane")
            || id.contains(":glass_");
    }

    private BlockGeometryKind classify(BlockData block) {
        String key = block.blockId + "[" + BlockGrouper.stateKey(block.properties) + "]";
        return kindCache.computeIfAbsent(key, ignored -> classifier.classify(block));
    }

    private static boolean readHiddenBlockCullingSetting() {
        try {
            File settings = FlatterSettings.settingsFile();
            if (settings == null || !settings.isFile()) return false;
            JsonObject obj = JsonParser.parseString(Files.readString(settings.toPath())).getAsJsonObject();
            return obj.has("hiddenBlockCullingEnabled")
                && obj.get("hiddenBlockCullingEnabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
}
