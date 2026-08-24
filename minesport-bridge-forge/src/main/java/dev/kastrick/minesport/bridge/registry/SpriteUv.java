package dev.kastrick.minesport.bridge.registry;

/**
 * Converts Minecraft block-atlas UV coordinates back into sprite-local UVs.
 *
 * Minecraft baked quads reference the stitched block atlas. Minesport exports
 * the original standalone texture PNG, so the atlas coordinate must be
 * un-interpolated before it is written to the runtime registry. Values outside
 * the sprite bounds are intentionally preserved as <0 or >1 so wrapping model
 * UVs continue to tile correctly in OBJ/glTF/Blender.
 */
public final class SpriteUv {
    private static final float EPSILON = 1.0e-8f;

    private SpriteUv() {}

    public static float local(float atlasValue, float spriteMin, float spriteMax) {
        if (!Float.isFinite(atlasValue)
            || !Float.isFinite(spriteMin)
            || !Float.isFinite(spriteMax)) {
            return atlasValue;
        }
        float span = spriteMax - spriteMin;
        if (Math.abs(span) <= EPSILON) return atlasValue;
        float local = (atlasValue - spriteMin) / span;
        if (Math.abs(local) <= EPSILON) return 0.0f;
        if (Math.abs(local - 1.0f) <= EPSILON) return 1.0f;
        return local;
    }
}
