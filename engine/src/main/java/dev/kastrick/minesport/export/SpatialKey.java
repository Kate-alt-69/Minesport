package dev.kastrick.minesport.export;

/**
 * Collision-free packed world-position key for normal Minecraft coordinates.
 *
 * X and Z use 26 signed bits each (Minecraft's normal +/-30,000,000 world
 * border fits comfortably). Y uses 12 signed bits (-2048..2047), which covers
 * current vanilla build heights by a wide margin. The fields are packed as
 * raw two's-complement bit patterns, so negative coordinates remain unique.
 */
public final class SpatialKey {
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;

    private SpatialKey() {}

    public static long of(int x, int y, int z) {
        if (x < -(1 << 25) || x > (1 << 25) - 1
                || z < -(1 << 25) || z > (1 << 25) - 1
                || y < -(1 << 11) || y > (1 << 11) - 1) {
            throw new IllegalArgumentException("Minecraft position outside packed key range: "
                + x + "," + y + "," + z);
        }

        long xx = ((long) x) & XZ_MASK;
        long yy = ((long) y) & Y_MASK;
        long zz = ((long) z) & XZ_MASK;
        return (xx << 38) | (yy << 26) | zz;
    }
}
