package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;

import java.util.List;

/**
 * Conservative first-pass classifier for the world-mapping stage.
 *
 * FULL_BLOCK is intentionally strict: the resolved model must be a single
 * unrotated 0..16 cube with all six faces. Anything less is kept in the
 * partial/custom path until a more specialized renderer handles it.
 */
public final class BlockGeometryClassifier {
    private final ResolverChain resolvers;

    public BlockGeometryClassifier(ResolverChain resolvers) {
        this.resolvers = resolvers;
    }

    public BlockGeometryKind classify(BlockData block) {
        if (block.isAir()) return BlockGeometryKind.AIR;
        if (isFluid(block.blockId)) return BlockGeometryKind.FLUID;

        BlockState state = resolvers.resolveBlockState(block.blockId);
        if (state == null) return BlockGeometryKind.UNKNOWN;

        // Weighted variants are position-dependent in Minecraft. Use the same
        // coordinate-aware selection as the real geometry builder so a block is
        // never classified from a different model than the one later exported.
        List<BlockState.ModelApplication> applications = state.resolve(
            block.properties,
            block.x,
            block.y,
            block.z
        );
        if (applications.isEmpty()) return BlockGeometryKind.UNKNOWN;

        if (state.format == BlockState.Format.MULTIPART && applications.size() > 1) {
            return BlockGeometryKind.MULTIPART;
        }

        boolean full = true;
        for (BlockState.ModelApplication app : applications) {
            if (app.x != 0 || app.y != 0) {
                full = false;
                break;
            }

            BlockModel model = resolvers.resolveModel(app.modelPath);
            if (!isFullCube(model)) {
                full = false;
                break;
            }
        }

        return full ? BlockGeometryKind.FULL_BLOCK : BlockGeometryKind.PARTIAL_BLOCK;
    }

    private static boolean isFullCube(BlockModel model) {
        if (model == null || model.elements == null || model.elements.size() != 1) return false;
        BlockModel.Element element = model.elements.getFirst();
        if (element.rotation != null) return false;

        float[] from = element.from;
        float[] to = element.to;
        if (!isExactCube(from, to)) return false;

        return element.faces != null
            && element.faces.keySet().containsAll(List.of(
                "north", "south", "east", "west", "up", "down"));
    }

    private static boolean isExactCube(float[] from, float[] to) {
        return from != null && to != null
            && from.length >= 3 && to.length >= 3
            && from[0] == 0f && from[1] == 0f && from[2] == 0f
            && to[0] == 16f && to[1] == 16f && to[2] == 16f;
    }

    private static boolean isFluid(String blockId) {
        int colon = blockId.indexOf(':');
        String name = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        return name.equals("water") || name.equals("lava")
            || name.endsWith("_water") || name.endsWith("_lava");
    }
}
