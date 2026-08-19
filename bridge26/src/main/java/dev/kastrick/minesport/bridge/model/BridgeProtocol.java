package dev.kastrick.minesport.bridge.model;

import java.util.List;
import java.util.Map;

/** Wire format sent from the 26.x Fabric bridge to Minesport. */
public final class BridgeProtocol {
    private BridgeProtocol() {}

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

    /**
     * PNG contains the original resource image, including vertically stacked
     * animation frames. animationMetaJson contains the matching .png.mcmeta
     * text when one exists, allowing downstream translators to reproduce the
     * Minecraft animation without a hard-coded block registry.
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
