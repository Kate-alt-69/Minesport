package dev.kastrick.minesport.region;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * Generates a top-down colored image of a world for the map preview.
 * Reads each .mca file, finds the highest non-air block per column,
 * and maps it to a color for display.
 *
 * Runs fast by reading only the chunk palette + heightmap data,
 * not full block geometry.
 */
public class HeightmapGenerator {

    // ── Block ID → map color ─────────────────────────────────────────────────

    // Top-down color palette — approximate surface colors
    private static final Map<String, int[]> BLOCK_COLORS = new HashMap<>();

    static {
        // Grass / terrain
        put("minecraft:grass_block",        new int[]{106, 167, 79});
        put("minecraft:dirt",               new int[]{134, 96,  67});
        put("minecraft:coarse_dirt",        new int[]{120, 85,  58});
        put("minecraft:podzol",             new int[]{100, 67,  38});
        put("minecraft:mycelium",           new int[]{110, 95, 110});
        put("minecraft:farmland",           new int[]{147, 109, 72});
        put("minecraft:mud",                new int[]{60,  57,  63});
        // Stone / rock
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
        // Wood / logs
        put("minecraft:oak_log",            new int[]{149, 120, 70});
        put("minecraft:spruce_log",         new int[]{97,  77,  47});
        put("minecraft:birch_log",          new int[]{201, 195, 163});
        put("minecraft:jungle_log",         new int[]{145, 114, 70});
        put("minecraft:acacia_log",         new int[]{150, 93,  58});
        put("minecraft:dark_oak_log",       new int[]{62,  46,  26});
        put("minecraft:mangrove_log",       new int[]{108, 54,  48});
        // Planks
        put("minecraft:oak_planks",         new int[]{197, 163, 101});
        put("minecraft:spruce_planks",      new int[]{114, 84,  48});
        put("minecraft:birch_planks",       new int[]{216, 200, 148});
        put("minecraft:jungle_planks",      new int[]{160, 115, 80});
        put("minecraft:acacia_planks",      new int[]{168, 90,  50});
        put("minecraft:dark_oak_planks",    new int[]{66,  43,  20});
        // Leaves
        put("minecraft:oak_leaves",         new int[]{75,  107, 47});
        put("minecraft:spruce_leaves",      new int[]{50,  88,  52});
        put("minecraft:birch_leaves",       new int[]{96,  130, 60});
        put("minecraft:jungle_leaves",      new int[]{48,  112, 38});
        put("minecraft:acacia_leaves",      new int[]{88,  122, 40});
        put("minecraft:dark_oak_leaves",    new int[]{58,  90,  35});
        put("minecraft:mangrove_leaves",    new int[]{60,  108, 44});
        put("minecraft:azalea_leaves",      new int[]{82,  124, 50});
        // Water / liquid
        put("minecraft:water",              new int[]{63,  118, 228});
        put("minecraft:lava",               new int[]{207, 94,  0});
        // Sand
        put("minecraft:sand",               new int[]{220, 208, 152});
        put("minecraft:red_sand",           new int[]{180, 97,  40});
        put("minecraft:sandstone",          new int[]{216, 201, 147});
        put("minecraft:red_sandstone",      new int[]{178, 95,  38});
        // Snow / ice
        put("minecraft:snow_block",         new int[]{240, 245, 255});
        put("minecraft:snow",               new int[]{240, 245, 255});
        put("minecraft:ice",                new int[]{160, 200, 255});
        put("minecraft:packed_ice",         new int[]{140, 190, 252});
        put("minecraft:blue_ice",           new int[]{100, 160, 240});
        // Ore / minerals
        put("minecraft:coal_ore",           new int[]{100, 100, 100});
        put("minecraft:iron_ore",           new int[]{136, 113, 94});
        put("minecraft:gold_ore",           new int[]{140, 133, 65});
        put("minecraft:diamond_ore",        new int[]{90,  155, 150});
        put("minecraft:emerald_ore",        new int[]{80,  150, 90});
        put("minecraft:redstone_ore",       new int[]{148, 80,  80});
        put("minecraft:lapis_ore",          new int[]{80,  100, 160});
        // Brick / processed
        put("minecraft:bricks",             new int[]{150, 97,  83});
        put("minecraft:stone_bricks",       new int[]{120, 120, 120});
        put("minecraft:nether_bricks",      new int[]{44,  21,  26});
        put("minecraft:mossy_cobblestone",  new int[]{100, 120, 85});
        // Concrete/wool (use approximate block namespace colors)
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
        // Nether
        put("minecraft:netherrack",         new int[]{97,  41,  41});
        put("minecraft:nether_bricks",      new int[]{44,  21,  26});
        put("minecraft:glowstone",          new int[]{228, 183, 85});
        put("minecraft:soul_sand",          new int[]{81,  62,  50});
        put("minecraft:crimson_nylium",     new int[]{151, 43,  58});
        put("minecraft:warped_nylium",      new int[]{43,  150, 151});
        // End
        put("minecraft:end_stone",          new int[]{219, 222, 158});
        put("minecraft:obsidian",           new int[]{15,  10,  25});
        put("minecraft:crying_obsidian",    new int[]{30,  10,  50});
        // Misc
        put("minecraft:glass",              new int[]{180, 210, 230});
        put("minecraft:glowstone",          new int[]{228, 183, 85});
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

    // Default color for unknown blocks (modded etc)
    private static final int[] DEFAULT_COLOR = {130, 90, 140}; // purple-ish — visually distinct

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate a top-down PNG image of the world as a base64 string.
     * Each pixel covers `scale` blocks.
     * @param regionDir   the world's region/ folder
     * @param scale       blocks per pixel (4 = fast, 1 = full res)
     */
    public static String generateBase64Png(File regionDir, int scale) throws IOException {
        File[] mcaFiles = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
        if (mcaFiles == null || mcaFiles.length == 0) return null;

        // Determine bounds from filenames
        int minRX = Integer.MAX_VALUE, minRZ = Integer.MAX_VALUE;
        int maxRX = Integer.MIN_VALUE, maxRZ = Integer.MIN_VALUE;

        record RC(int x, int z, File file) {}
        List<RC> regions = new ArrayList<>();

        for (File f : mcaFiles) {
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

        final int regionBlocks = 512;
        final int regionPx    = regionBlocks / scale;
        int imgW = (maxRX - minRX + 1) * regionPx;
        int imgH = (maxRZ - minRZ + 1) * regionPx;

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);

        // Fill with void/sky color
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                img.setRGB(x, y, 0x1e1e24);
            }
        }

        for (RC r : regions) {
            try {
                paintRegion(img, r.file(),
                    (r.x() - minRX) * regionPx,
                    (r.z() - minRZ) * regionPx,
                    regionPx, scale);
            } catch (Exception e) {
                // skip bad region files silently
            }
        }

        // Encode to base64 PNG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // ── Region painting ───────────────────────────────────────────────────────

    private static void paintRegion(
            BufferedImage img, File mcaFile,
            int imgOffX, int imgOffZ,
            int regionPx, int scale
    ) throws IOException {
        if (mcaFile.length() < 8192) return;

        try (var raf = new java.io.RandomAccessFile(mcaFile, "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);

            for (int ci = 0; ci < 1024; ci++) {
                int localCX = ci % 32;
                int localCZ = ci / 32;

                int offset      = ((header[ci*4] & 0xFF) << 16)
                                | ((header[ci*4+1] & 0xFF) << 8)
                                |  (header[ci*4+2] & 0xFF);
                int sectorCount = header[ci*4+3] & 0xFF;
                if (offset == 0 || sectorCount == 0) continue;

                long seekPos = (long) offset * 4096;
                if (seekPos + 5 > raf.length()) continue;

                raf.seek(seekPos);
                int dataLen = raf.readInt();
                if (dataLen <= 1) continue;

                int compType = raf.readByte() & 0xFF;
                if (compType >= 128) continue;

                int payLen = dataLen - 1;
                if (seekPos + 5 + payLen > raf.length()) continue;

                byte[] compressed = new byte[payLen];
                raf.readFully(compressed);

                try {
                    byte[] nbt = decompress(compressed, compType);
                    if (nbt == null || nbt.length == 0) continue;

                    // Get top-surface colors for this chunk
                    int[][] colors = extractSurfaceColors(nbt);
                    if (colors == null) continue;

                    // Paint onto image at correct location
                    // Each block in the chunk = 1/scale pixels
                    for (int bz = 0; bz < 16; bz++) {
                        for (int bx = 0; bx < 16; bx++) {
                            int px = imgOffX + (localCX * 16 + bx) / scale;
                            int pz = imgOffZ + (localCZ * 16 + bz) / scale;
                            if (px >= 0 && pz >= 0 && px < img.getWidth() && pz < img.getHeight()) {
                                int[] c = colors[bz * 16 + bx];
                                if (c != null) {
                                    img.setRGB(px, pz, (c[0] << 16) | (c[1] << 8) | c[2]);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Parse NBT chunk data and return the top-surface block color per column.
     * Returns int[256][3] (16x16 columns, each with RGB), or null on failure.
     */
    private static int[][] extractSurfaceColors(byte[] nbtBytes) throws IOException {
        var nbt = dev.kastrick.minesport.nbt.NbtReader.readBytes(nbtBytes);
        if (!nbt.has("sections")) return null;

        // Build a column-major map of highest non-air block
        int[] topY   = new int[256];
        int[][] topColor = new int[256][];
        Arrays.fill(topY, Integer.MIN_VALUE);

        for (Object secObj : nbt.getList("sections")) {
            if (!(secObj instanceof dev.kastrick.minesport.nbt.NbtCompound sec)) continue;
            if (!sec.has("block_states")) continue;

            int sectionY = sec.getInt("Y", 0);
            int baseY    = sectionY * 16;

            var bs = sec.getCompound("block_states");
            if (!bs.has("palette")) continue;

            var palette = bs.getList("palette");
            long[] data = bs.has("data") ? bs.getLongArray("data") : new long[0];

            // Build palette colors
            String[] ids    = new String[palette.size()];
            int[][]  colors = new int[palette.size()][];
            for (int pi = 0; pi < palette.size(); pi++) {
                if (!(palette.get(pi) instanceof dev.kastrick.minesport.nbt.NbtCompound e)) continue;
                ids[pi] = e.getString("Name", "minecraft:air");
                colors[pi] = colorForBlock(ids[pi]);
            }

            int bpe = Math.max(4, (int) Math.ceil(Math.log(Math.max(palette.size(), 2)) / Math.log(2)));
            long mask = (1L << bpe) - 1;
            int bpl  = 64 / bpe;

            for (int idx = 0; idx < 4096; idx++) {
                int pi;
                if (data.length == 0) {
                    pi = 0;
                } else {
                    int li = idx / bpl;
                    int bi = (idx % bpl) * bpe;
                    pi = (int) ((data[li] >> bi) & mask);
                }
                if (pi >= ids.length) continue;
                if (ids[pi] == null || isAir(ids[pi])) continue;

                int lx = idx & 0xF;
                int ly = (idx >> 8) & 0xF;
                int lz = (idx >> 4) & 0xF;
                int wy = baseY + ly;
                int col = lz * 16 + lx;

                if (wy > topY[col]) {
                    topY[col] = wy;
                    topColor[col] = colors[pi];
                }
            }
        }

        // Apply simple height shading
        for (int col = 0; col < 256; col++) {
            if (topColor[col] == null) continue;
            float shade = Math.min(1.0f, Math.max(0.4f, (topY[col] + 64) / 200.0f));
            topColor[col] = new int[]{
                (int)(topColor[col][0] * shade),
                (int)(topColor[col][1] * shade),
                (int)(topColor[col][2] * shade)
            };
        }

        return topColor;
    }

    /**
     * Maps a block ID to an approximate top-down surface color. Public so
     * other consumers (e.g. the 3D preview viewer's colored-voxel rendering)
     * can reuse the same palette instead of maintaining a second one.
     */
    public static int[] colorForBlock(String id) {
        int[] c = BLOCK_COLORS.get(id);
        if (c != null) return c;
        // Fallback: hash the namespace for a consistent modded-block color
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

    private static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static byte[] decompress(byte[] data, int type) throws IOException {
        var baos = new ByteArrayOutputStream();
        InputStream in = switch (type) {
            case 1 -> new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data));
            case 2 -> new java.util.zip.InflaterInputStream(new ByteArrayInputStream(data));
            case 3 -> new ByteArrayInputStream(data);
            default -> null;
        };
        if (in == null) return null;
        in.transferTo(baos);
        return baos.toByteArray();
    }
}
