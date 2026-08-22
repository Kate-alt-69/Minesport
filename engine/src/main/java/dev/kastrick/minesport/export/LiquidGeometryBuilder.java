package dev.kastrick.minesport.export;

import dev.kastrick.minesport.region.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds explicit Minecraft fluid geometry for blocks that do not have ordinary
 * blockstate/model JSON geometry (notably minecraft:water and minecraft:lava).
 *
 * The top surface follows the fluid state's level and, when a world index is
 * available, averages the four surrounding fluid samples per corner so flowing
 * water slopes instead of becoming a stack of fallback cubes. Internal liquid
 * faces are removed where a same-fluid neighbour covers them.
 */
public final class LiquidGeometryBuilder {
    private static final float ONE_NINTH = 1.0f / 9.0f;

    private LiquidGeometryBuilder() {}

    public static boolean isLiquid(BlockData block) {
        if (block == null || block.blockId == null) return false;
        return block.blockId.equals("minecraft:water") || block.blockId.equals("minecraft:lava");
    }

    public static List<Quad> build(BlockData block, Map<Long, BlockData> worldIndex) {
        if (!isLiquid(block)) return List.of();
        Map<Long, BlockData> index = worldIndex == null ? Map.of() : worldIndex;

        boolean water = block.blockId.equals("minecraft:water");
        String still = water ? "minecraft:block/water_still" : "minecraft:block/lava_still";
        String flow = water ? "minecraft:block/water_flow" : "minecraft:block/lava_flow";
        int tintIndex = water ? 0 : -1;

        float hNW = cornerHeight(block, index, -1, -1);
        float hNE = cornerHeight(block, index, 1, -1);
        float hSE = cornerHeight(block, index, 1, 1);
        float hSW = cornerHeight(block, index, -1, 1);

        List<Quad> out = new ArrayList<>();
        BlockData above = neighbor(index, block, 0, 1, 0);
        if (!sameLiquid(block, above)) {
            // Raw vertex order matches the exporter Quad convention; the Quad
            // accessor performs its existing winding/UV correction afterward.
            out.add(quad(
                block,
                new float[][]{
                    {0f, hNW, 0f}, {1f, hNE, 0f},
                    {1f, hSE, 1f}, {0f, hSW, 1f}
                },
                still,
                tintIndex,
                "up"
            ));
        }

        addSide(out, block, index, "north", 0, 0, -1, hNW, hNE, flow, tintIndex);
        addSide(out, block, index, "south", 0, 0, 1, hSE, hSW, flow, tintIndex);
        addSide(out, block, index, "west", -1, 0, 0, hSW, hNW, flow, tintIndex);
        addSide(out, block, index, "east", 1, 0, 0, hNE, hSE, flow, tintIndex);

        BlockData below = neighbor(index, block, 0, -1, 0);
        if (!sameLiquid(block, below)) {
            out.add(quad(
                block,
                new float[][]{
                    {0f, 0f, 0f}, {0f, 0f, 1f},
                    {1f, 0f, 1f}, {1f, 0f, 0f}
                },
                still,
                tintIndex,
                "down"
            ));
        }
        return out;
    }

    private static void addSide(
        List<Quad> out,
        BlockData block,
        Map<Long, BlockData> index,
        String direction,
        int dx,
        int dy,
        int dz,
        float leftTop,
        float rightTop,
        String texture,
        int tintIndex
    ) {
        BlockData adjacent = neighbor(index, block, dx, dy, dz);
        float adjacentHeight = sameLiquid(block, adjacent) ? ownHeight(adjacent, index) : 0f;
        float visibleBottom = Math.min(Math.min(leftTop, rightTop), adjacentHeight);

        if (sameLiquid(block, adjacent)
            && adjacentHeight >= leftTop - 1.0e-4f
            && adjacentHeight >= rightTop - 1.0e-4f) {
            return;
        }

        float[][] vertices = switch (direction) {
            case "north" -> new float[][]{
                {0f, visibleBottom, 0f}, {1f, visibleBottom, 0f},
                {1f, rightTop, 0f}, {0f, leftTop, 0f}
            };
            case "south" -> new float[][]{
                {1f, visibleBottom, 1f}, {0f, visibleBottom, 1f},
                {0f, rightTop, 1f}, {1f, leftTop, 1f}
            };
            case "west" -> new float[][]{
                {0f, visibleBottom, 1f}, {0f, visibleBottom, 0f},
                {0f, rightTop, 0f}, {0f, leftTop, 1f}
            };
            default -> new float[][]{
                {1f, visibleBottom, 0f}, {1f, visibleBottom, 1f},
                {1f, rightTop, 1f}, {1f, leftTop, 0f}
            };
        };
        out.add(quad(block, vertices, texture, tintIndex, direction));
    }

    /**
     * Approximate Minecraft's fluid corner-height sampling. Same-fluid blocks
     * around a corner contribute their state height; a fluid block directly
     * above any sample makes that corner full-height.
     */
    private static float cornerHeight(BlockData block, Map<Long, BlockData> index, int sx, int sz) {
        if (index.isEmpty()) return ownHeight(block, index);
        int[][] offsets = {
            {0, 0},
            {sx, 0},
            {0, sz},
            {sx, sz}
        };
        float sum = 0f;
        float weight = 0f;
        for (int[] offset : offsets) {
            BlockData sample = neighbor(index, block, offset[0], 0, offset[1]);
            if (!sameLiquid(block, sample)) continue;
            BlockData above = neighbor(index, sample, 0, 1, 0);
            if (sameLiquid(block, above)) return 1f;
            float height = ownHeight(sample, index);
            // Source/high fluid gets extra weight, similar to Minecraft's
            // preference for broad flat source surfaces.
            float w = height >= 8f * ONE_NINTH - 1.0e-4f ? 10f : 1f;
            sum += height * w;
            weight += w;
        }
        return weight > 0f ? sum / weight : ownHeight(block, index);
    }

    private static float ownHeight(BlockData block, Map<Long, BlockData> index) {
        if (block == null) return 0f;
        if (!index.isEmpty() && sameLiquid(block, neighbor(index, block, 0, 1, 0))) return 1f;
        int level = parseLevel(block.prop("level"));
        if (level <= 0 || level >= 8) return 8f * ONE_NINTH;
        return Math.max(ONE_NINTH, (8f - level) * ONE_NINTH);
    }

    private static int parseLevel(String value) {
        try {
            return Math.max(0, Math.min(15, Integer.parseInt(value)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static BlockData neighbor(Map<Long, BlockData> index, BlockData block, int dx, int dy, int dz) {
        if (index == null || index.isEmpty() || block == null) return null;
        return index.get(SpatialKey.of(block.x + dx, block.y + dy, block.z + dz));
    }

    private static boolean sameLiquid(BlockData a, BlockData b) {
        return a != null && b != null && a.blockId.equals(b.blockId);
    }

    private static Quad quad(
        BlockData block,
        float[][] local,
        String texture,
        int tintIndex,
        String cullface
    ) {
        float[][] world = new float[4][3];
        for (int i = 0; i < 4; i++) {
            world[i][0] = block.x + local[i][0];
            world[i][1] = block.y + local[i][1];
            world[i][2] = block.z + local[i][2];
        }
        float[] uv = {
            0f, 1f,
            1f, 1f,
            1f, 0f,
            0f, 0f
        };
        // partName marks fluid as non-FLATTER geometry while remaining a stable
        // material animation source for the Blender metadata scan.
        return new Quad(world, uv, texture, new float[3], cullface, tintIndex, "fluid");
    }
}
