from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


heightmap = Path("engine/src/main/java/dev/kastrick/minesport/region/HeightmapGenerator.java")

replace_once(
    heightmap,
    '''    private static final int[] DEFAULT_COLOR = {130, 90, 140};

    /** Generate a top-down PNG image of the world as a base64 string. */
    public static String generateBase64Png(File regionDir, int scale) throws IOException {
        if (scale < 1 || scale > 512) {
            throw new IllegalArgumentException("Heightmap scale must be between 1 and 512 blocks per pixel");
        }
''',
    '''    private static final int[] DEFAULT_COLOR = {130, 90, 140};
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
''',
)

replace_once(
    heightmap,
    '''        final int regionBlocks = 512;
        final int regionPx = (regionBlocks + scale - 1) / scale;
        long imgWLong = (long) (maxRX - minRX + 1) * regionPx;
        long imgHLong = (long) (maxRZ - minRZ + 1) * regionPx;
        if (imgWLong <= 0 || imgHLong <= 0 || imgWLong > Integer.MAX_VALUE || imgHLong > Integer.MAX_VALUE) {
            throw new IOException("Heightmap dimensions exceed supported image bounds: " + imgWLong + "x" + imgHLong);
        }
        int imgW = (int) imgWLong;
        int imgH = (int) imgHLong;
''',
    '''        HeightmapLayout layout = chooseLayout(minRX, minRZ, maxRX, maxRZ, scale);
        final int effectiveScale = layout.scale();
        final int regionPx = layout.regionPx();
        int imgW = layout.width();
        int imgH = layout.height();
''',
)

replace_once(
    heightmap,
    '''                    regionPx,
                    scale
                );
''',
    '''                    regionPx,
                    effectiveScale
                );
''',
)

replace_once(
    heightmap,
    '''        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static boolean isRegionFile(String name) {
''',
    '''        ByteArrayOutputStream baos = new ByteArrayOutputStream();
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
''',
)

ipc = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
replace_once(
    ipc,
    '''            String base64 = dev.kastrick.minesport.region.HeightmapGenerator
                .generateBase64Png(regionDir, scale);
            if (base64 == null) {
                error("No region files found");
                return;
            }

            File[] mcaFiles = regionDir.listFiles((directory, name) -> name.endsWith(".mca"));
            int minRegionX = Integer.MAX_VALUE;
            int minRegionZ = Integer.MAX_VALUE;
            int maxRegionX = Integer.MIN_VALUE;
            int maxRegionZ = Integer.MIN_VALUE;

            if (mcaFiles != null) {
                for (File file : mcaFiles) {
                    String[] parts = file.getName().split("\\\\.");
                    if (parts.length < 4) continue;
                    try {
                        int regionX = Integer.parseInt(parts[1]);
                        int regionZ = Integer.parseInt(parts[2]);
                        minRegionX = Math.min(minRegionX, regionX);
                        minRegionZ = Math.min(minRegionZ, regionZ);
                        maxRegionX = Math.max(maxRegionX, regionX);
                        maxRegionZ = Math.max(maxRegionZ, regionZ);
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (minRegionX == Integer.MAX_VALUE) {
                error("No valid region coordinates found");
                return;
            }

            final int minX = minRegionX * 512;
            final int minZ = minRegionZ * 512;
            final int maxX = (maxRegionX + 1) * 512;
            final int maxZ = (maxRegionZ + 1) * 512;
            final String imageData = base64;

            send("heightmap", json -> {
                json.addProperty("image", imageData);
                json.addProperty("minX", minX);
                json.addProperty("minZ", minZ);
                json.addProperty("maxX", maxX);
                json.addProperty("maxZ", maxZ);
                json.addProperty("scale", scale);
            });
''',
    '''            HeightmapGenerator.HeightmapResult heightmap = HeightmapGenerator.generate(regionDir, scale);
            if (heightmap == null) {
                error("No region files found");
                return;
            }
            if (heightmap.scale() != scale) {
                log(
                    "Heightmap scale increased from " + scale + " to " + heightmap.scale()
                        + " to stay inside the safe raster budget"
                );
            }

            send("heightmap", json -> {
                json.addProperty("image", heightmap.base64Png());
                json.addProperty("minX", heightmap.minX());
                json.addProperty("minZ", heightmap.minZ());
                json.addProperty("maxX", heightmap.maxX());
                json.addProperty("maxZ", heightmap.maxZ());
                json.addProperty("scale", heightmap.scale());
            });
''',
)

test = Path("engine/src/test/java/dev/kastrick/minesport/region/HeightmapGeneratorLayoutTest.java")
test.write_text(
    '''package dev.kastrick.minesport.region;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class HeightmapGeneratorLayoutTest {
    @Test
    void keepsRequestedScaleForNormalWorldSpan() throws Exception {
        HeightmapGenerator.HeightmapLayout layout = HeightmapGenerator.chooseLayout(
            -1, -2, 1, 0, 4
        );
        assertEquals(4, layout.scale());
        assertEquals(128, layout.regionPx());
        assertEquals(384, layout.width());
        assertEquals(384, layout.height());
        assertEquals(-512, layout.minX());
        assertEquals(-1024, layout.minZ());
        assertEquals(1024, layout.maxX());
        assertEquals(512, layout.maxZ());
    }

    @Test
    void automaticallyCoarsensSparseLargeWorldsBeforeAllocation() throws Exception {
        HeightmapGenerator.HeightmapLayout layout = HeightmapGenerator.chooseLayout(
            0, 0, 127, 127, 1
        );
        assertEquals(16, layout.scale());
        assertEquals(4096, layout.width());
        assertEquals(4096, layout.height());
        assertTrue(
            (long)layout.width() * layout.height() <= HeightmapGenerator.MAX_HEIGHTMAP_PIXELS
        );
    }

    @Test
    void rejectsRegionCoordinatesThatCannotBeRepresentedByDesktopProtocol() {
        IOException error = assertThrows(
            IOException.class,
            () -> HeightmapGenerator.chooseLayout(
                Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0, 1
            )
        );
        assertTrue(error.getMessage().contains("supported world bounds"));
    }

    @Test
    void recognizesLegacyAndAnvilRegionFiles() {
        assertTrue(HeightmapGenerator.isRegionFile("r.-2.7.mca"));
        assertTrue(HeightmapGenerator.isRegionFile("r.-2.7.mcr"));
        assertFalse(HeightmapGenerator.isRegionFile("r.-2.7.tmp"));
    }
}
''',
    encoding="utf-8",
)
