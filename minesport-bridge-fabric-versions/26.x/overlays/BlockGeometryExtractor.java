package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BakedQuadData;
import dev.kastrick.minesport.bridge.model.ExportWorkerProtocol.BlockVariant;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Shared 26.1/26.2 geometry extraction. Fabric's renderer API is the
 * compatibility boundary so Minesport does not depend on Minecraft's packed
 * baked-quad internals.
 */
public final class BlockGeometryExtractor {
    private BlockGeometryExtractor() {}

    public static List<BlockVariant> extractBlock(Block block, Minecraft client) {
        var variants = new ArrayList<BlockVariant>();
        var modelSet = client.getModelManager().getBlockStateModelSet();
        SpriteFinder spriteFinder = blockSpriteFinder(client);

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            var properties = new LinkedHashMap<String, String>();
            for (var property : state.getProperties()) {
                properties.put(property.getName(), propertyValue(state, property));
            }

            var quads = new ArrayList<BakedQuadData>();
            BlockStateModel model = modelSet.get(state);
            if (model != null) {
                MutableMesh mesh = Renderer.get().mutableMesh();
                try {
                    ((FabricBlockStateModel) (Object) model).emitQuads(
                        mesh.emitter(),
                        BlockAndTintGetter.EMPTY,
                        BlockPos.ZERO,
                        state,
                        RandomSource.create(stableSeed(block, state)),
                        direction -> false
                    );
                } catch (Exception ignored) {
                    // Preserve the state with empty geometry. A mod may use a
                    // custom renderer that is outside the baked-model path.
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

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return String.valueOf(state.getValue(property));
    }

    private static SpriteFinder blockSpriteFinder(Minecraft client) {
        TextureAtlas atlas = (TextureAtlas) client.getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS);
        return ((FabricTextureAtlas) (Object) atlas).spriteFinder();
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

            Direction face = quad.cullFace();
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
