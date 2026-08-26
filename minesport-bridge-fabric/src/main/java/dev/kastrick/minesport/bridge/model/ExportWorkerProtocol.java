package dev.kastrick.minesport.bridge.model;

import java.util.List;
import java.util.Map;

/**
 * Wire format sent from the Minesport Export Worker → Minesport engine over local socket.
 * Serialized as newline-delimited JSON (one object per line).
 */
public class ExportWorkerProtocol {

    // ── Message types (field "type") ──────────────────────────────────────────

    public static final String TYPE_HELLO       = "hello";       // handshake
    public static final String TYPE_BLOCK_ENTRY = "block";       // one block's full data
    public static final String TYPE_BLOCK_LIGHT = "block_light"; // state-dependent light emission
    public static final String TYPE_TEXTURE     = "texture";     // PNG bytes as base64
    public static final String TYPE_DONE        = "done";        // all data sent
    public static final String TYPE_ERROR       = "error";

    // ── Block entry ───────────────────────────────────────────────────────────

    public record BlockEntry(
        String blockId,           // e.g. "polydecorations:oak_bench"
        String vanillaMapping,    // Polymer vanilla state e.g. "minecraft:note_block[note=2]" or null
        String loaderType,        // "fabric", "forge", "polymer", "vanilla"
        List<BlockVariant> variants
    ) {}

    public record BlockVariant(
        Map<String, String> properties, // block state properties e.g. {facing=north}
        List<BakedQuadData> quads
    ) {}

    public record BakedQuadData(
        float[] vertices,   // 4 verts × 8 floats: x,y,z, nx,ny,nz, u,v
        String textureId,   // namespaced texture path e.g. "polydecorations:block/oak_bench"
        int face,           // 0=DOWN 1=UP 2=NORTH 3=SOUTH 4=WEST 5=EAST -1=none
        boolean shade,
        int tintIndex
    ) {}

    // ── Light metadata ────────────────────────────────────────────────────────

    /**
     * Separate message instead of extending BlockEntry so older compatibility
     * overlays and consumers remain source/wire compatible. Only emitting
     * states need to be sent; an absent state means Minecraft light level 0.
     */
    public record BlockLightEntry(
        String blockId,
        List<LightState> states
    ) {}

    public record LightState(
        Map<String, String> properties,
        int lightLevel
    ) {}

    // ── Texture entry ─────────────────────────────────────────────────────────

    public record TextureEntry(
        String textureId,   // e.g. "polydecorations:block/oak_bench"
        int width,
        int height,
        String pngBase64    // full PNG encoded as base64
    ) {}

    // ── Hello ─────────────────────────────────────────────────────────────────

    public record Hello(
        String mcVersion,
        String loaderVersion,
        int totalBlocks,
        boolean polymerPresent,
        List<String> loadedMods
    ) {}
}
