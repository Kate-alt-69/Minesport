package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BakedQuadData;
import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BlockVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Compatibility extractor for the pre-1.21.5 baked-model API. */
public final class BlockGeometryExtractor {
    private BlockGeometryExtractor() {}

    private static final Direction[] DIRECTIONS = {
        null,
        Direction.DOWN, Direction.UP,
        Direction.NORTH, Direction.SOUTH,
        Direction.WEST, Direction.EAST
    };

    public static List<BlockVariant> extractBlock(Block block, Minecraft client) {
        var variants = new ArrayList<BlockVariant>();
        var shaper = client.getModelManager().getBlockModelShaper();

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            var properties = new LinkedHashMap<String, String>();
            state.getValues().forEach((property, value) ->
                properties.put(property.getName(), value.toString())
            );

            var quads = new ArrayList<BakedQuadData>();
            BakedModel model = shaper.getBlockModel(state);
            if (model != null) {
                for (Direction cullFace : DIRECTIONS) {
                    List<BakedQuad> baked;
                    try {
                        baked = model.getQuads(state, cullFace, RandomSource.create(stableSeed(block, state)));
                    } catch (Exception exception) {
                        continue;
                    }
                    for (BakedQuad quad : baked) {
                        BakedQuadData converted = convertQuad(quad, cullFace);
                        if (converted != null) quads.add(converted);
                    }
                }
            }

            // Keep empty variants too: they distinguish an existing state whose
            // renderer is custom/invisible from an unknown state.
            variants.add(new BlockVariant(properties, quads));
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

    private static BakedQuadData convertQuad(BakedQuad quad, Direction cullFace) {
        try {
            int[] packed = quad.getVertices();
            int stride = packed.length / 4;
            if (stride < 6) return null;

            Direction normal = quad.getDirection();
            float nx = normal == null ? 0.0f : normal.getStepX();
            float ny = normal == null ? 0.0f : normal.getStepY();
            float nz = normal == null ? 0.0f : normal.getStepZ();
            float[] vertices = new float[4 * 8];
            TextureAtlasSprite sprite = quad.getSprite();

            for (int vertex = 0; vertex < 4; vertex++) {
                int source = vertex * stride;
                int target = vertex * 8;
                float u = Float.intBitsToFloat(packed[source + 4]);
                float v = Float.intBitsToFloat(packed[source + 5]);
                if (sprite != null) {
                    u = SpriteUv.local(u, sprite.getU0(), sprite.getU1());
                    v = SpriteUv.local(v, sprite.getV0(), sprite.getV1());
                }
                vertices[target] = Float.intBitsToFloat(packed[source]);
                vertices[target + 1] = Float.intBitsToFloat(packed[source + 1]);
                vertices[target + 2] = Float.intBitsToFloat(packed[source + 2]);
                vertices[target + 3] = nx;
                vertices[target + 4] = ny;
                vertices[target + 5] = nz;
                vertices[target + 6] = u;
                vertices[target + 7] = v;
            }

            String textureId = sprite == null ? "missing" : sprite.contents().name().toString();
            return new BakedQuadData(
                vertices,
                textureId,
                cullFace == null ? -1 : cullFace.ordinal(),
                quad.isShade(),
                quad.getTintIndex()
            );
        } catch (Exception exception) {
            return null;
        }
    }
}
