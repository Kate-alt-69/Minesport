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

    private static final RandomSource RAND = RandomSource.create(42L);

    private static final Direction[] DIRECTIONS = {
        null,
        Direction.DOWN, Direction.UP,
        Direction.NORTH, Direction.SOUTH,
        Direction.EAST, Direction.WEST
    };

    public static List<BlockVariant> extractBlock(Block block, Minecraft client) {
        var variants = new ArrayList<BlockVariant>();
        var shaper = client.getModelManager().getBlockModelShaper();
        var seenModels = new java.util.IdentityHashMap<BlockStateModel, Boolean>();

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            BlockStateModel model = shaper.getBlockModel(state);
            if (model == null) continue;
            if (seenModels.containsKey(model)) continue;
            seenModels.put(model, true);

            Map<String, String> props = new LinkedHashMap<>();
            state.getValues().forEach((prop, val) ->
                props.put(prop.getName(), val.toString()));

            List<BakedQuadData> quads = extractQuads(model, state);
            if (!quads.isEmpty()) {
                variants.add(new BlockVariant(props, quads));
            }
        }

        return variants;
    }

    private static List<BakedQuadData> extractQuads(BlockStateModel model, BlockState state) {
        var quads = new ArrayList<BakedQuadData>();
        List<BlockModelPart> parts;

        try {
            parts = model.collectParts(RandomSource.create(RAND.nextLong()));
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
            // Each vertex: x, y, z, color, u, v, normal, misc (8 ints)
            int stride = vertexData.length / 4;
            float[] vertices = new float[4 * 8];

            for (int v = 0; v < 4; v++) {
                int base = v * stride;
                float x  = Float.intBitsToFloat(vertexData[base]);
                float y  = Float.intBitsToFloat(vertexData[base + 1]);
                float z  = Float.intBitsToFloat(vertexData[base + 2]);
                float u  = Float.intBitsToFloat(vertexData[base + 4]);
                float wv = Float.intBitsToFloat(vertexData[base + 5]);

                int normalInt = vertexData[base + 6];
                float nx = ((normalInt & 0xFF) - 128) / 127f;
                float ny = (((normalInt >> 8) & 0xFF) - 128) / 127f;
                float nz = (((normalInt >> 16) & 0xFF) - 128) / 127f;

                int vi = v * 8;
                vertices[vi]   = x;  vertices[vi+1] = y;  vertices[vi+2] = z;
                vertices[vi+3] = nx; vertices[vi+4] = ny; vertices[vi+5] = nz;
                vertices[vi+6] = u;  vertices[vi+7] = wv;
            }

            TextureAtlasSprite sprite = quad.sprite();
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
