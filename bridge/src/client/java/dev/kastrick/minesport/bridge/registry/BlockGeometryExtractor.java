package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class BlockGeometryExtractor {

    private static final Direction[] DIRECTIONS = {
        null,
        Direction.DOWN, Direction.UP,
        Direction.NORTH, Direction.SOUTH,
        Direction.EAST, Direction.WEST
    };

    public static List<BlockVariant> extractBlock(Block block, Minecraft client) {
        var variants = new ArrayList<BlockVariant>();
        var shaper = client.getModelManager().getBlockModelShaper();

        // Every state remains addressable in the runtime registry. Multiple
        // states are allowed to share identical baked geometry, but we must not
        // erase their property keys merely because Minecraft reused one model
        // object internally.
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            BlockStateModel model = shaper.getBlockModel(state);
            if (model == null) continue;

            Map<String, String> props = new LinkedHashMap<>();
            state.getValues().forEach((prop, val) ->
                props.put(prop.getName(), val.toString()));

            List<BakedQuadData> quads = extractQuads(model, stableSeed(block, state));
            variants.add(new BlockVariant(props, quads));
        }

        return variants;
    }

    private static long stableSeed(Block block, BlockState state) {
        long seed = 1469598103934665603L;
        seed ^= System.identityHashCode(block);
        seed *= 1099511628211L;
        seed ^= state.toString().hashCode();
        seed *= 1099511628211L;
        return seed;
    }

    private static List<BakedQuadData> extractQuads(BlockStateModel model, long seed) {
        var quads = new ArrayList<BakedQuadData>();
        List<BlockModelPart> parts;

        try {
            parts = model.collectParts(RandomSource.create(seed));
        } catch (Exception e) {
            return quads;
        }

        for (Direction dir : DIRECTIONS) {
            for (BlockModelPart part : parts) {
                List<BakedQuad> baked;
                try {
                    baked = part.getQuads(dir);
                } catch (Exception e) {
                    continue;
                }
                if (baked == null) continue;

                for (BakedQuad quad : baked) {
                    BakedQuadData data = convertQuad(quad, dir);
                    if (data != null) quads.add(data);
                }
            }
        }

        return quads;
    }

    private static BakedQuadData convertQuad(BakedQuad quad, Direction dir) {
        try {
            int[] vertexData = quad.vertices();
            if (vertexData == null || vertexData.length < 24 || vertexData.length % 4 != 0) {
                return null;
            }
            int stride = vertexData.length / 4;
            if (stride < 7) return null;
            float[] vertices = new float[4 * 8];
            TextureAtlasSprite sprite = quad.sprite();

            for (int v = 0; v < 4; v++) {
                int base = v * stride;
                float x  = Float.intBitsToFloat(vertexData[base]);
                float y  = Float.intBitsToFloat(vertexData[base + 1]);
                float z  = Float.intBitsToFloat(vertexData[base + 2]);
                float u  = Float.intBitsToFloat(vertexData[base + 4]);
                float wv = Float.intBitsToFloat(vertexData[base + 5]);

                // BakedQuad UVs address Minecraft's stitched block atlas. The
                // runtime registry stores the sprite's standalone PNG path, so
                // convert those atlas coordinates back to sprite-local UVs.
                if (sprite != null) {
                    u = SpriteUv.local(u, sprite.getU0(), sprite.getU1());
                    wv = SpriteUv.local(wv, sprite.getV0(), sprite.getV1());
                }

                int normalInt = vertexData[base + 6];
                float nx = ((byte)(normalInt & 0xFF)) / 127f;
                float ny = ((byte)((normalInt >> 8) & 0xFF)) / 127f;
                float nz = ((byte)((normalInt >> 16) & 0xFF)) / 127f;

                int vi = v * 8;
                vertices[vi]   = x;  vertices[vi+1] = y;  vertices[vi+2] = z;
                vertices[vi+3] = nx; vertices[vi+4] = ny; vertices[vi+5] = nz;
                vertices[vi+6] = u;  vertices[vi+7] = wv;
            }

            String textureId = sprite != null
                ? sprite.contents().name().toString()
                : "missing";

            int faceIdx = dir != null ? dir.ordinal() : -1;
            return new BakedQuadData(
                vertices, textureId, faceIdx,
                quad.shade(), quad.tintIndex()
            );

        } catch (Exception e) {
            return null;
        }
    }
}
