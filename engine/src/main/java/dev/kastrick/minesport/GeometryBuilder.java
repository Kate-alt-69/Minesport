package dev.kastrick.minesport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.export.BlockGeometryClassifier;
import dev.kastrick.minesport.export.BlockGeometryKind;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GeometryBuilder extends dev.kastrick.minesport.export.GeometryBuilder {
    private static final int[][] NEIGHBORS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    private Map<SpatialKey, BlockData> worldIndex = Map.of();
    private final Map<String, BlockGeometryKind> kindCache = new HashMap<>();
    private final boolean hiddenBlockCullingEnabled;
    private final BlockGeometryClassifier classifier;

    public GeometryBuilder(ResolverChain resolvers) {
        super(resolvers);
        this.classifier = new BlockGeometryClassifier(resolvers);
        this.hiddenBlockCullingEnabled = readHiddenBlockCullingSetting();
    }

    @Override
    public void enableFaceCulling(List<BlockData> allBlocks) {
        super.enableFaceCulling(allBlocks);

        Map<SpatialKey, BlockData> index = new HashMap<>(allBlocks.size());
        for (BlockData b : allBlocks) {
            if (!b.isAir()) {
                index.put(new SpatialKey(b.x, b.y, b.z), b);
            }
        }
        worldIndex = index;
        kindCache.clear();
    }

    @Override
    public List<dev.kastrick.minesport.export.Quad> buildBlock(BlockData block) {
        if (hiddenBlockCullingEnabled && !block.isAir() && isFullyEnclosed(block)) {
            return List.of();
        }
        return super.buildBlock(block);
    }

    private boolean isFullyEnclosed(BlockData block) {
        if (worldIndex.isEmpty()) return false;

        for (int[] d : NEIGHBORS) {
            BlockData neighbor = worldIndex.get(new SpatialKey(
                block.x + d[0], block.y + d[1], block.z + d[2]
            ));
            if (neighbor == null || neighbor.isAir()) return false;
            if (classify(neighbor) != BlockGeometryKind.FULL_BLOCK) return false;
        }
        return true;
    }

    private BlockGeometryKind classify(BlockData block) {
        String key = block.blockId + "[" + dev.kastrick.minesport.export.BlockGrouper.stateKey(block.properties) + "]";
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
                Path root = (xdg == null || xdg.isBlank())
                    ? Path.of(System.getProperty("user.home"), ".config")
                    : Path.of(xdg);
                settings = root.resolve("minesport").resolve("settings.json");
            }
            if (!Files.isRegularFile(settings)) return false;
            JsonObject obj = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
            return obj.has("hiddenBlockCullingEnabled") && obj.get("hiddenBlockCullingEnabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private record SpatialKey(int x, int y, int z) {}
}
