package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.BakedQuadData;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockVariant;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 1.21.11 changed vanilla BakedQuad storage. Convert baked quads through the
 * Fabric Renderer API so Minesport does not depend on that packed layout.
 */
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
        TextureAtlas atlas = (TextureAtlas) client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        SpriteFinder spriteFinder = SpriteFinder.get(atlas);

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            var properties = new LinkedHashMap<String, String>();
            state.getValues().forEach((property, value) ->
                properties.put(property.getName(), value.toString())
            );

            var quads = new ArrayList<BakedQuadData>();
            BlockStateModel model = shaper.getBlockModel(state);
            if (model != null) {
                List<BlockModelPart> parts;
                try {
                    parts = model.collectParts(RandomSource.create(stableSeed(block, state)));
                } catch (Exception exception) {
                    parts = List.of();
                }

                MutableMesh mesh = Renderer.get().mutableMesh();
                var emitter = mesh.emitter();
                for (BlockModelPart part : parts) {
                    for (Direction direction : DIRECTIONS) {
                        List<BakedQuad> baked;
                        try {
                            baked = part.getQuads(direction);
                        } catch (Exception exception) {
                            continue;
                        }
                        for (BakedQuad quad : baked) {
                            emitter.fromBakedQuad(quad).emit();
                        }
                    }
                }

                mesh.forEach(quad -> {
                    BakedQuadData converted = convertQuad(quad, spriteFinder);
                    if (converted != null) quads.add(converted);
                });
            }

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

    private static BakedQuadData convertQuad(QuadView quad, SpriteFinder spriteFinder) {
        try {
            TextureAtlasSprite sprite = spriteFinder.find(quad);
            float[] vertices = new float[4 * 8];
            var faceNormal = quad.faceNormal();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 8;
                float u = quad.u(vertex);
                float v = quad.v(vertex);
                if (sprite != null) {
                    u = SpriteUv.local(u, sprite.getU0(), sprite.getU1());
                    v = SpriteUv.local(v, sprite.getV0(), sprite.getV1());
                }
                vertices[offset] = quad.x(vertex);
                vertices[offset + 1] = quad.y(vertex);
                vertices[offset + 2] = quad.z(vertex);
                vertices[offset + 3] = quad.hasNormal(vertex) ? quad.normalX(vertex) : faceNormal.x();
                vertices[offset + 4] = quad.hasNormal(vertex) ? quad.normalY(vertex) : faceNormal.y();
                vertices[offset + 5] = quad.hasNormal(vertex) ? quad.normalZ(vertex) : faceNormal.z();
                vertices[offset + 6] = u;
                vertices[offset + 7] = v;
            }

            Direction face = quad.lightFace();
            return new BakedQuadData(
                vertices,
                sprite == null ? "missing" : sprite.contents().name().toString(),
                face == null ? -1 : face.ordinal(),
                quad.diffuseShade(),
                quad.tintIndex()
            );
        } catch (Exception exception) {
            return null;
        }
    }
}
