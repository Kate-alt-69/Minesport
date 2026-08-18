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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Export-mode GeometryBuilder wrapper used by IPC mode.
 *
 * The engine's normal GeometryBuilder remains the low-level renderer. This
 * same-package type is intentionally selected by IpcMode's wildcard import,
 * so the export pipeline gets an experimental visibility pass without
 * changing the established resolver/geometry implementation.
 *
 * Hidden-block culling is deliberately conservative: when face culling is
 * enabled, a block is omitted only when all six neighboring positions exist
 * in the selected block set and every one is classified as FULL_BLOCK.
 * Missing/partial/custom neighbors keep the block rendered.
 */
public final class GeometryBuilder extends dev.kastrick.minesport.export.GeometryBuilder {
    private static final int[][] NEIGHBORS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    private Map<Long, BlockData> worldIndex = Map.of();
    private final Map<String, BlockGeometryKind> kindCache = new HashMap<>();
    private boolean hiddenBlockCullingEnabled;
    private BlockGeometryClassifier classifier;

    public GeometryBuilder(ResolverChain resolvers) {
        super(resolvers);
        this.classifier = new BlockGeometryClassifier(resolvers);
        this.hiddenBlockCullingEnabled = readHiddenBlockCullingSetting();
    }

    @Override
    public void enableFaceCulling(List<BlockData> allBlocks) {
        super.enableFaceCulling(allBlocks);

        Map<Long, BlockData> index = new HashMap<>(allBlocks.size());
        for (BlockData b : allBlocks) {
            if (!b.isAir()) {
                index.put(spatialKey(b.x, b.y, b.z), b);
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
            BlockData neighbor = worldIndex.get(spatialKey(
                block.x + d[0],
                block.y + d[1],
                block.z + d[2]
            ));
            if (neighbor == null || neighbor.isAir()) {
                return false;
            }
            if (classify(neighbor) != BlockGeometryKind.FULL_BLOCK) {
                return false;
            }
        }
        return true;
    }

    private BlockGeometryKind classify(BlockData block) {
        String key = block.blockId + "[" + dev.kastrick.minesport.export.BlockGrouper.stateKey(block.properties) + "]";
        return kindCache.computeIfAbsent(key, ignored -> classifier.classify(block));
    }

    /**
     * The Go UI persists settings through os.UserConfigDir(). Match the
     * standard Windows/Linux/macOS locations from Java without requiring a
     * new IPC field just for this experimental pass.
     */
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
            String json = Files.readString(settings);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has("hiddenBlockCullingEnabled")
                && obj.get("hiddenBlockCullingEnabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long spatialKey(int x, int y, int z) {
        return ((long) (x + 1048576) << 42)
             | ((long) (y + 1048576) << 21)
             |  (long) (z + 1048576);
    }
}
