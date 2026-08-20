package dev.kastrick.minesport.bridge.model;

import java.util.List;
import java.util.Map;

/**
 * Wire format used by bridge targets that can preserve Minecraft texture
 * animation metadata. The protocol stays Java-only so the same overlay can be
 * shared across Minecraft versions with different renderer APIs.
 */
public final class BridgeProtocol {
    private BridgeProtocol() {}

    public static final String TYPE_HELLO       = "hello";
    public static final String TYPE_BLOCK_ENTRY = "block";
    public static final String TYPE_TEXTURE     = "texture";
    public static final String TYPE_DONE        = "done";
    public static final String TYPE_ERROR       = "error";

    public record BlockEntry(
        String blockId,
        String vanillaMapping,
        String loaderType,
        List<BlockVariant> variants
    ) {}

    public record BlockVariant(
        Map<String, String> properties,
        List<BakedQuadData> quads
    ) {}

    public record BakedQuadData(
        float[] vertices,
        String textureId,
        int face,
        boolean shade,
        int tintIndex
    ) {}

    public record TextureEntry(
        String textureId,
        int width,
        int height,
        String pngBase64,
        String animationMetaJson
    ) {}

    public record Hello(
        String mcVersion,
        String loaderVersion,
        int totalBlocks,
        boolean polymerPresent,
        List<String> loadedMods
    ) {}
}
