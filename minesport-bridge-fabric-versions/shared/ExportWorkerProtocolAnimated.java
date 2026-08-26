package dev.kastrick.minesport.bridge.model;

import java.util.List;
import java.util.Map;

/**
 * Wire format used by bridge targets that can preserve Minecraft texture
 * animation metadata. Geometry packets carry texture IDs; texture image bytes
 * are legacy/optional because the runtime model cache resolves images through
 * Minesport's normal resource-pack/mod/vanilla/Piston chain.
 */
public final class ExportWorkerProtocol {
    private ExportWorkerProtocol() {}

    public static final String TYPE_HELLO       = "hello";
    public static final String TYPE_BLOCK_ENTRY = "block";
    public static final String TYPE_BLOCK_LIGHT = "block_light";
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

    public record BlockLightEntry(
        String blockId,
        List<LightState> states
    ) {}

    public record LightState(
        Map<String, String> properties,
        int lightLevel
    ) {}

    /**
     * Kept for wire compatibility with older bridge consumers. Runtime-registry
     * schema 2 ignores these image payloads and caches texture identifiers only.
     */
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
