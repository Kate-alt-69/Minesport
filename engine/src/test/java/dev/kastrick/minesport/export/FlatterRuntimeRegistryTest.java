package dev.kastrick.minesport.export;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.AssetResolver;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlatterRuntimeRegistryTest {
    @Test
    void flatterAnalyzesRuntimeBakedQuadsInsteadOfStaticFallbackCube() throws Exception {
        File snapshot = File.createTempFile("minesport-flatter-runtime-", ".json");
        snapshot.deleteOnExit();
        Files.writeString(snapshot.toPath(), """
            {
              "schema": 2,
              "minecraftVersion": "1.21.10",
              "modsFingerprint": "test",
              "blocks": {
                "minecraft:test_runtime_shape": {
                  "loaderType": "vanilla",
                  "variants": [{
                    "properties": {},
                    "quads": [{
                      "vertices": [
                        0,0,0, 0,0,-1, 0,0,
                        1,0,0, 0,0,-1, 1,0,
                        1,1,0, 0,0,-1, 1,1,
                        0,1,0, 0,0,-1, 0,1
                      ],
                      "textureId": "minecraft:block/runtime_face",
                      "face": 2,
                      "shade": true,
                      "tintIndex": -1
                    }]
                  }]
                }
              }
            }
            """);

        BlockData first = new BlockData(0, 64, 0, "minecraft:test_runtime_shape", Map.of());
        BlockData second = new BlockData(1, 64, 0, "minecraft:test_runtime_shape", Map.of());
        first.runtimeRegistryPath = snapshot.getAbsolutePath();
        second.runtimeRegistryPath = snapshot.getAbsolutePath();

        ResolverChain chain = new ResolverChain();
        chain.addResolver(new TextureOnlyResolver());

        FlatterOptimizer.Result result = FlatterOptimizer.compile(List.of(first, second), chain, 16);

        assertFalse(result.isEmpty());
        assertEquals(2, result.blockCount());
        assertEquals(1, result.objects().size());
        FlatterOptimizer.FlatterObject object = result.objects().getFirst();
        assertTrue(object.id().startsWith("FLATTER_SHAPE_minecraft_test_runtime_shape_"),
            "one-face runtime geometry must stay a SHAPE; a static fallback cube would become SOLID");
        assertEquals(
            "minecraft:block/runtime_face",
            object.palette().getFirst().faces().get("north").getFirst().texturePath()
        );
    }

    private static final class TextureOnlyResolver implements AssetResolver {
        @Override public boolean canResolve(String blockId) { return blockId.startsWith("minecraft:"); }
        @Override public BlockState resolveBlockState(String blockId) { return null; }
        @Override public BlockModel resolveModel(String modelPath) { return null; }

        @Override
        public BufferedImage resolveTexture(String texturePath) {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xffffffff);
            }
            return image;
        }

        @Override public String name() { return "runtime texture test"; }
    }
}
