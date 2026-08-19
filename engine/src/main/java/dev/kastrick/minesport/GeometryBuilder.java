package dev.kastrick.minesport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.export.BlockGeometryClassifier;
import dev.kastrick.minesport.export.BlockGeometryKind;
import dev.kastrick.minesport.export.BlockGrouper;
import dev.kastrick.minesport.export.SpatialKey;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Export-mode GeometryBuilder wrapper used by IPC mode.
 *
 * Hidden-block culling is deliberately conservative: a block is omitted only
 * when all six neighboring positions exist in the selected block set and
 * every one is classified as FULL_BLOCK.
 */
public final class GeometryBuilder extends dev.kastrick.minesport.export.GeometryBuilder {
    private static final int[][] NEIGHBORS = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };

    private Map<Long, BlockData> worldIndex = Map.of();
    private final Map<String, BlockGeometryKind> kindCache = new HashMap<>();
    private final BlockGeometryClassifier classifier;
    private final boolean hiddenBlockCullingEnabled;

    public GeometryBuilder(ResolverChain resolvers) {
        super(resolvers);
        this.classifier = new BlockGeometryClassifier(resolvers);
        this.hiddenBlockCullingEnabled = readHiddenBlockCullingSetting();
    }

    @Override
    public void enableFaceCulling(List<BlockData> allBlocks) {
        super.enableFaceCulling(allBlocks);
        Map<Long, BlockData> index = new HashMap<>(Math.max(16, allBlocks.size() * 2));
        for (BlockData b : allBlocks) {
            if (!b.isAir()) index.put(SpatialKey.of(b.x, b.y, b.z), b);
        }
        worldIndex = index;
        kindCache.clear();
    }

    @Override
    public List<dev.kastrick.minesport.export.Quad> buildBlock(BlockData block) {
        if (hiddenBlockCullingEnabled && !block.isAir() && isFullyEnclosed(block)) return List.of();
        return super.buildBlock(block);
    }

    private boolean isFullyEnclosed(BlockData block) {
        if (worldIndex.isEmpty()) return false;
        for (int[] d : NEIGHBORS) {
            BlockData neighbor = worldIndex.get(SpatialKey.of(block.x + d[0], block.y + d[1], block.z + d[2]));
            if (neighbor == null || neighbor.isAir()) return false;
            if (classify(neighbor) != BlockGeometryKind.FULL_BLOCK) return false;
        }
        return true;
    }

    private BlockGeometryKind classify(BlockData block) {
        String key = block.blockId + "[" + BlockGrouper.stateKey(block.properties) + "]";
        return kindCache.computeIfAbsent(key, ignored -> classifier.classify(block));
    }

    private static boolean readHiddenBlockCullingSetting() {
        try {
            Path settings;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData == null || appData.isBlank()) return false;
                settings = Path.of(appData, "minesport", "settings.json");
            } else if (os.contains("mac")) {
                settings = Path.of(System.getProperty("user.home"), "Library", "Application Support", "minesport", "settings.json");
            } else {
                String xdg = System.getenv("XDG_CONFIG_HOME");
                Path root = (xdg == null || xdg.isBlank()) ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
                settings = root.resolve("minesport").resolve("settings.json");
            }
            if (!Files.isRegularFile(settings)) return false;
            JsonObject obj = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
            return obj.has("hiddenBlockCullingEnabled") && obj.get("hiddenBlockCullingEnabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
}
