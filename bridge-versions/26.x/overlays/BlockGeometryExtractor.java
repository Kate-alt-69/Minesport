package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.BakedQuadData;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockVariant;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 26.x geometry extraction deliberately uses Fabric Renderer API QuadView
 * rather than Minecraft's internal baked vertex layout. 26.1 removed
 * obfuscation and the renderer changed heavily; QuadView is the compatibility
 * boundary designed for renderer-independent model inspection.
 */
public final class BlockGeometryExtractor {
    private BlockGeometryExtractor() {}

    public static List<BlockVariant> extractBlock(Block block, Minecraft client) {
        var variants = new ArrayList<BlockVariant>();
        var modelSet = client.getModelManager().getBlockStateModelSet();
        SpriteFinder spriteFinder = blockSpriteFinder(client);

        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            BlockStateModel model = modelSet.get(state);
            if (model == null) {
                continue;
            }

            var properties = new LinkedHashMap<String, String>();
            state.getValues().forEach((property, value) ->
                properties.put(property.getName(), value.toString())
            );

            MutableMesh mesh = Renderer.get().mutableMesh();
            try {
                model.emitQuads(
                    mesh.emitter(),
                    BlockAndTintGetter.EMPTY,
                    BlockPos.ZERO,
                    state,
                    RandomSource.create(42L),
                    direction -> false
                );
            } catch (Exception exception) {
                continue;
            }

            var quads = new ArrayList<BakedQuadData>();
            mesh.forEach(quad -> {
                BakedQuadData converted = convertQuad(quad, spriteFinder);
                if (converted != null) {
                    quads.add(converted);
                }
            });

            if (!quads.isEmpty()) {
                variants.add(new BlockVariant(properties, quads));
            }
        }

        return variants;
    }

    private static SpriteFinder blockSpriteFinder(Minecraft client) {
        TextureAtlas atlas = (TextureAtlas) client.getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS);
        return ((FabricTextureAtlas) atlas).spriteFinder();
    }

    private static BakedQuadData convertQuad(QuadView quad, SpriteFinder spriteFinder) {
        try {
            float[] vertices = new float[4 * 8];
            var faceNormal = quad.faceNormal();

            for (int vertex = 0; vertex < 4; vertex++) {
                float nx = quad.hasNormal(vertex) ? quad.normalX(vertex) : faceNormal.x();
                float ny = quad.hasNormal(vertex) ? quad.normalY(vertex) : faceNormal.y();
                float nz = quad.hasNormal(vertex) ? quad.normalZ(vertex) : faceNormal.z();

                int offset = vertex * 8;
                vertices[offset] = quad.x(vertex);
                vertices[offset + 1] = quad.y(vertex);
                vertices[offset + 2] = quad.z(vertex);
                vertices[offset + 3] = nx;
                vertices[offset + 4] = ny;
                vertices[offset + 5] = nz;
                vertices[offset + 6] = quad.u(vertex);
                vertices[offset + 7] = quad.v(vertex);
            }

            TextureAtlasSprite sprite = spriteFinder.find(quad);
            String textureId = sprite == null
                ? "missing"
                : sprite.contents().name().toString();

            Direction face = quad.lightFace();
            int faceIndex = face == null ? -1 : face.ordinal();

            return new BakedQuadData(
                vertices,
                textureId,
                faceIndex,
                quad.diffuseShade(),
                quad.tintIndex()
            );
        } catch (Exception exception) {
            return null;
        }
    }
}
