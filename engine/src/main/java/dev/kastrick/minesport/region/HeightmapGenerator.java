package dev.kastrick.minesport.region;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * Generates a top-down colored image of a world for the map preview.
 * Region/chunk framing is delegated to RegionReader so the 2D map uses the
 * exact same hardened Anvil path as export and preview generation.
 */
public class HeightmapGenerator {

    private static final Map<String, int[]> BLOCK_COLORS = new HashMap<>();

    static {
        put("minecraft:grass_block",        new int[]{106, 167, 79});
        put("minecraft:dirt",               new int[]{134, 96,  67});
        put("minecraft:coarse_dirt",        new int[]{120, 85,  58});
        put("minecraft:podzol",             new int[]{100, 67,  38});
        put("minecraft:mycelium",           new int[]{110, 95, 110});
        put("minecraft:farmland",           new int[]{147, 109, 72});
        put("minecraft:mud",                new int[]{60,  57,  63});
        put("minecraft:stone",              new int[]{125, 125, 125});
        put("minecraft:cobblestone",        new int[]{115, 115, 115});
        put("minecraft:deepslate",          new int[]{75,  75,  84});
        put("minecraft:granite",            new int[]{153, 114, 99});
        put("minecraft:diorite",            new int[]{188, 188, 188});
        put("minecraft:andesite",           new int[]{135, 135, 135});
        put("minecraft:tuff",               new int[]{110, 112, 102});
        put("minecraft:calcite",            new int[]{220, 220, 218});
        put("minecraft:bedrock",            new int[]{60,  60,  60});
        put("minecraft:gravel",             new int[]{150, 143, 137});
        put("minecraft:clay",               new int[]{162, 166, 182});
        put("minecraft:oak_log",            new int[]{149, 120, 70});
        put("minecraft:spruce_log",         new int[]{97,  77,  47});
        put("minecraft:birch_log",          new int[]{201, 195, 163});
        put("minecraft:jungle_log",         new int[]{145, 114, 70});
        put("minecraft:acacia_log",         new int[]{150, 93,  58});
        put("minecraft:dark_oak_log",       new int[]{62,  46,  26});
        put("minecraft:mangrove_log",       new int[]{108, 54,  48});
        put("minecraft:oak_planks",         new int[]{197, 163, 101});
        put("minecraft:spruce_planks",      new int[]{114, 84,  48});
        put("minecraft:birch_planks",       new int[]{216, 200, 148});
        put("minecraft:jungle_planks",      new int[]{160, 115, 80});
        put("minecraft:acacia_planks",      new int[]{168, 90,  50});
        put("minecraft:dark_oak_planks",    new int[]{66,  43,  20});
        put("minecraft:oak_leaves",         new int[]{75,  107, 47});
        put("minecraft:spruce_leaves",      new int[]{50,  88,  52});
        put("minecraft:birch_leaves",       new int[]{96,  130, 60});
        put("minecraft:jungle_leaves",      new int[]{48,  112, 38});
        put("minecraft:acacia_leaves",      new int[]{88,  122, 40});
        put("minecraft:dark_oak_leaves",    new int[]{58,  90,  35});
        put("minecraft:mangrove_leaves",    new int[]{60,  108, 44});
        put("minecraft:azalea_leaves",      new int[]{82,  124, 50});
        put("minecraft:water",              new int[]{63,  118, 228});
        put("minecraft:lava",               new int[]{207, 94,  0});
        put("minecraft:sand",               new int[]{220, 208, 152});
        put("minecraft:red_sand",           new int[]{180, 97,  40});
        put("minecraft:sandstone",          new int[]{216, 201, 147});
        put("minecraft:red_sandstone",      new int[]{178, 95,  38});
        put("minecraft:snow_block",         new int[]{240, 245, 255});
        put("minecraft:snow",               new int[]{240, 245, 255});
        put("minecraft:ice",                new int[]{160, 200, 255});
        put("minecraft:packed_ice",         new int[]{140, 190, 252});
        put("minecraft:blue_ice",           new int[]{100, 160, 240});
        put("minecraft:coal_ore",           new int[]{100, 100, 100});
        put("minecraft:iron_ore",           new int[]{136, 113, 94});
        put("minecraft:gold_ore",           new int[]{140, 133, 65});
        put("minecraft:diamond_ore",        new int[]{90,  155, 150});
        put("minecraft:emerald_ore",        new int[]{80,  150, 90});
        put("minecraft:redstone_ore",       new int[]{148, 80,  80});
        put("minecraft:lapis_ore",          new int[]{80,  100, 160});
        put("minecraft:bricks",             new int[]{150, 97,  83});
        put("minecraft:stone_bricks",       new int[]{120, 120, 120});
        put("minecraft:nether_bricks",      new int[]{44,  21,  26});
        put("minecraft:mossy_cobblestone",  new int[]{100, 120, 85});
        put("minecraft:white_concrete",     new int[]{207, 213, 214});
        put("minecraft:gray_concrete",      new int[]{54,  57,  61});
        put("minecraft:black_concrete",     new int[]{8,   10,  15});
        put("minecraft:red_concrete",       new int[]{142, 33,  33});
        put("minecraft:green_concrete",     new int[]{73,  91,  36});
        put("minecraft:blue_concrete",      new int[]{44,  46,  143});
        put("minecraft:yellow_concrete",    new int[]{240, 175, 21});
        put("minecraft:orange_concrete",    new int[]{224, 97,  0});
        put("minecraft:brown_concrete",     new int[]{96,  59,  31});
        put("minecraft:cyan_concrete",      new int[]{21,  119, 136});
        put("minecraft:light_blue_concrete",new int[]{35,  137, 198});
        put("minecraft:lime_concrete",      new int[]{94,  169, 24});
        put("minecraft:magenta_concrete",   new int[]{169, 48,  159});
        put("minecraft:pink_concrete",      new int[]{213, 100, 142});
        put("minecraft:purple_concrete",    new int[]{100, 31,  156});
        put("minecraft:netherrack",         new int[]{97,  41,  41});
        put("minecraft:glowstone",          new int[]{228, 183, 85});
        put("minecraft:soul_sand",          new int[]{81,  62,  50});
        put("minecraft:crimson_nylium",     new int[]{151, 43,  58});
        put("minecraft:warped_nylium",      new int[]{43,  150, 151});
        put("minecraft:end_stone",          new int[]{219, 222, 158});
        put("minecraft:obsidian",           new int[]{15,  10,  25});
        put("minecraft:crying_obsidian",    new int[]{30,  10,  50});
        put("minecraft:glass",              new int[]{180, 210, 230});
        put("minecraft:bookshelf",          new int[]{177, 144, 90});
        put("minecraft:chest",              new int[]{167, 127, 68});
        put("minecraft:crafting_table",     new int[]{140, 100, 55});
        put("minecraft:furnace",            new int[]{100, 100, 100});
        put("minecraft:tnt",                new int[]{200, 60,  40});
        put("minecraft:hay_block",          new int[]{196, 172, 38});
        put("minecraft:pumpkin",            new int[]{198, 118, 24});
        put("minecraft:melon",              new int[]{111, 157, 42});
        put("minecraft:terracotta",         new int[]{152, 95,  64});
    }

    private static void put(String id, int[] rgb) {
        BLOCK_COLORS.put(id, rgb);
    }

    private static final int[] DEFAULT_COLOR = {130, 90, 140};
    // TYPE_INT_RGB uses an int per pixel. Keep the raw raster near 64 MiB so
    // PNG encoding/base64 and the Rust RGBA copy still have comfortable headroom.
    static final long MAX_HEIGHTMAP_PIXELS = 16L * 1024L * 1024L;

    public record HeightmapResult(
        String base64Png,
        int scale,
        int minX,
        int minZ,
        int maxX,
        int maxZ
    ) {}

    record HeightmapLayout(
        int scale,
        int regionPx,
        int width,
        int height,
        int minX,
        int minZ,
        int maxX,
        int maxZ
    ) {}

    /** Generate a top-down PNG image of the world as a base64 string. */
    public static String generateBase64Png(File regionDir, int scale) throws IOException {
        HeightmapResult result = generate(regionDir, scale);
        return result == null ? null : result.base64Png();
    }

    /** Generate a bounded heightmap and return the effective raster scale/bounds. */
    public static HeightmapResult generate(File regionDir, int scale) throws IOException {
        if (scale < 1 || scale > 512) {
            throw new IllegalArgumentException("Heightmap scale must be between 1 and 512 blocks per pixel");
        }

        File[] regionFiles = regionDir.listFiles((d, n) -> isRegionFile(n));
        if (regionFiles == null || regionFiles.length == 0) return null;

        int minRX = Integer.MAX_VALUE, minRZ = Integer.MAX_VALUE;
        int maxRX = Integer.MIN_VALUE, maxRZ = Integer.MIN_VALUE;

        record RC(int x, int z, File file) {}
        List<RC> regions = new ArrayList<>();

        for (File f : regionFiles) {
            String[] parts = f.getName().split("\\.");
            if (parts.length < 4) continue;
            try {
                int rx = Integer.parseInt(parts[1]);
                int rz = Integer.parseInt(parts[2]);
                regions.add(new RC(rx, rz, f));
                minRX = Math.min(minRX, rx); minRZ = Math.min(minRZ, rz);
                maxRX = Math.max(maxRX, rx); maxRZ = Math.max(maxRZ, rz);
            } catch (NumberFormatException ignored) {}
        }

        if (regions.isEmpty()) return null;

        HeightmapLayout layout = chooseLayout(minRX, minRZ, maxRX, maxRZ, scale);
        final int effectiveScale = layout.scale();
        final int regionPx = layout.regionPx();
        int imgW = layout.width();
        int imgH = layout.height();

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                img.setRGB(x, y, 0x1e1e24);
            }
        }

        int failedRegions = 0;
        IOException firstFailure = null;
        for (RC r : regions) {
            try {
                paintRegion(
                    img,
                    r.file(),
                    r.x(), r.z(),
                    (r.x() - minRX) * regionPx,
                    (r.z() - minRZ) * regionPx,
                    regionPx,
                    effectiveScale
                );
            } catch (IOException e) {
                failedRegions++;
                if (firstFailure == null) firstFailure = e;
                System.err.println("[WARN] Heightmap skipped region " + r.file().getName() + ": " + e.getMessage());
            }
        }
        if (failedRegions == regions.size() && firstFailure != null) {
            throw new IOException("Heightmap could not read any selected region files", firstFailure);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        String encoded = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        return new HeightmapResult(
            encoded,
            effectiveScale,
            layout.minX(),
            layout.minZ(),
            layout.maxX(),
            layout.maxZ()
        );
    }

    static HeightmapLayout chooseLayout(
        int minRX,
        int minRZ,
        int maxRX,
        int maxRZ,
        int requestedScale
    ) throws IOException {
        if (requestedScale < 1 || requestedScale > 512) {
            throw new IllegalArgumentException("Heightmap scale must be between 1 and 512 blocks per pixel");
        }

        long spanX = (long)maxRX - (long)minRX + 1L;
        long spanZ = (long)maxRZ - (long)minRZ + 1L;
        if (spanX <= 0L || spanZ <= 0L) {
            throw new IOException("Heightmap region bounds are invalid");
        }

        long minXLong = (long)minRX * 512L;
        long minZLong = (long)minRZ * 512L;
        long maxXLong = ((long)maxRX + 1L) * 512L;
        long maxZLong = ((long)maxRZ + 1L) * 512L;
        if (
            minXLong < Integer.MIN_VALUE || minZLong < Integer.MIN_VALUE ||
            maxXLong > Integer.MAX_VALUE || maxZLong > Integer.MAX_VALUE
        ) {
            throw new IOException(
                "Heightmap region coordinates exceed supported world bounds: "
                    + minRX + "," + minRZ + " to " + maxRX + "," + maxRZ
            );
        }

        int effectiveScale = requestedScale;
        while (true) {
            int regionPx = (512 + effectiveScale - 1) / effectiveScale;
            long width = spanX * (long)regionPx;
            long height = spanZ * (long)regionPx;
            boolean dimensionsFit = width > 0L && height > 0L
                && width <= Integer.MAX_VALUE && height <= Integer.MAX_VALUE;
            boolean pixelsFit = dimensionsFit && width <= MAX_HEIGHTMAP_PIXELS / height;
            if (pixelsFit) {
                return new HeightmapLayout(
                    effectiveScale,
                    regionPx,
                    (int)width,
                    (int)height,
                    (int)minXLong,
                    (int)minZLong,
                    (int)maxXLong,
                    (int)maxZLong
                );
            }

            if (effectiveScale >= 512) break;
            effectiveScale = Math.min(512, effectiveScale * 2);
        }

        throw new IOException(
            "Heightmap world span is too large for the safe raster budget even at scale 512: "
                + spanX + "x" + spanZ + " regions"
        );
    }

    static boolean isRegionFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mca") || lower.endsWith(".mcr");
    }

    private static void paintRegion(
            BufferedImage img,
            File regionFile,
            int regionX,
            int regionZ,
            int imgOffX,
            int imgOffZ,
            int regionPx,
            int scale
    ) throws IOException {
        long regionMinXLong = (long) regionX * 512L;
        long regionMinZLong = (long) regionZ * 512L;
        long regionMaxXLong = regionMinXLong + 511L;
        long regionMaxZLong = regionMinZLong + 511L;
        if (regionMinXLong < Integer.MIN_VALUE || regionMaxXLong > Integer.MAX_VALUE
            || regionMinZLong < Integer.MIN_VALUE || regionMaxZLong > Integer.MAX_VALUE) {
            throw new IOException("Region coordinates are outside supported integer world bounds: " + regionFile.getName());
        }
        int regionMinX = (int) regionMinXLong;
        int regionMinZ = (int) regionMinZLong;
        int regionMaxX = (int) regionMaxXLong;
        int regionMaxZ = (int) regionMaxZLong;

        List<BlockData> blocks = RegionReader.readRegion(
            regionFile,
            regionMinX, -2048, regionMinZ,
            regionMaxX, 2048, regionMaxZ,
            null
        );

        int[] topY = new int[regionPx * regionPx];
        int[] topRgb = new int[regionPx * regionPx];
        Arrays.fill(topY, Integer.MIN_VALUE);

        for (BlockData block : blocks) {
            int localX = block.x - regionMinX;
            int localZ = block.z - regionMinZ;
            if (localX < 0 || localX >= 512 || localZ < 0 || localZ >= 512) continue;
            int px = localX / scale;
            int pz = localZ / scale;
            if (px < 0 || pz < 0 || px >= regionPx || pz >= regionPx) continue;
            int index = pz * regionPx + px;
            if (block.y <= topY[index]) continue;
            topY[index] = block.y;
            int[] color = colorForBlock(block.blockId);
            float shade = Math.min(1.0f, Math.max(0.4f, (block.y + 64) / 200.0f));
            int red = (int) (color[0] * shade);
            int green = (int) (color[1] * shade);
            int blue = (int) (color[2] * shade);
            topRgb[index] = (red << 16) | (green << 8) | blue;
        }

        for (int pz = 0; pz < regionPx; pz++) {
            for (int px = 0; px < regionPx; px++) {
                int index = pz * regionPx + px;
                if (topY[index] == Integer.MIN_VALUE) continue;
                int imageX = imgOffX + px;
                int imageZ = imgOffZ + pz;
                if (imageX >= 0 && imageZ >= 0 && imageX < img.getWidth() && imageZ < img.getHeight()) {
                    img.setRGB(imageX, imageZ, topRgb[index]);
                }
            }
        }
    }

    public static int[] colorForBlock(String id) {
        int[] c = BLOCK_COLORS.get(id);
        if (c != null) return c;
        if (!id.startsWith("minecraft:")) {
            int h = id.hashCode();
            return new int[]{
                100 + (Math.abs(h) % 80),
                60  + (Math.abs(h >> 8) % 60),
                120 + (Math.abs(h >> 16) % 80)
            };
        }
        return DEFAULT_COLOR;
    }
}
