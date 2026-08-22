package dev.kastrick.minesport.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import dev.kastrick.minesport.resolver.AssetResolver;
import dev.kastrick.minesport.resolver.ResolverChain;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TextureAnimationExporterTest {
    @Test
    void mcmetaCustomDurationsBecomeExactTickSequenceAndTimeline() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FakeAnimatedResolver("""
            {
              "animation": {
                "frametime": 2,
                "frames": [0, {"index": 2, "time": 3}, 1]
              }
            }
            """));

        JsonObject descriptor = TextureAnimationExporter.describeMaterial(
            new MaterialKey("minecraft:block/water_still", 0x3f76e4),
            chain
        );
        assertNotNull(descriptor);
        assertEquals("texture_frames", descriptor.get("kind").getAsString());
        assertEquals(3, descriptor.get("frameCount").getAsInt());
        assertEquals(7, descriptor.get("cycleTicks").getAsInt());
        assertEquals(1, descriptor.get("frameTime").getAsInt());

        JsonArray frames = descriptor.getAsJsonArray("frames");
        assertEquals(7, frames.size());
        assertEquals(0, frames.get(0).getAsInt());
        assertEquals(0, frames.get(1).getAsInt());
        assertEquals(2, frames.get(2).getAsInt());
        assertEquals(2, frames.get(4).getAsInt());
        assertEquals(1, frames.get(5).getAsInt());
        assertEquals(1, frames.get(6).getAsInt());

        JsonArray timeline = descriptor.getAsJsonArray("timeline");
        assertEquals(3, timeline.size());
        assertEquals(0, timeline.get(0).getAsJsonObject().get("tick").getAsInt());
        assertEquals(2, timeline.get(1).getAsJsonObject().get("tick").getAsInt());
        assertEquals(5, timeline.get(2).getAsJsonObject().get("tick").getAsInt());
    }

    @Test
    void defaultMcmetaWalksVerticalSpriteStrip() {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FakeAnimatedResolver("{" +
            "\"animation\":{\"frametime\":1,\"interpolate\":false}}"));

        JsonObject descriptor = TextureAnimationExporter.describeMaterial(
            new MaterialKey("minecraft:block/water_flow", 0x3f76e4),
            chain
        );
        assertNotNull(descriptor);
        assertEquals(3, descriptor.getAsJsonArray("frames").size());
        assertFalse(descriptor.get("interpolate").getAsBoolean());
    }

    private static final class FakeAnimatedResolver implements AssetResolver {
        private final String metadata;
        private final BufferedImage image = new BufferedImage(16, 48, BufferedImage.TYPE_INT_ARGB);

        FakeAnimatedResolver(String metadata) {
            this.metadata = metadata;
        }

        @Override public boolean canResolve(String blockId) { return blockId.startsWith("minecraft:"); }
        @Override public BlockState resolveBlockState(String blockId) { return null; }
        @Override public BlockModel resolveModel(String modelPath) { return null; }
        @Override public BufferedImage resolveTexture(String texturePath) { return image; }
        @Override public String resolveTextureMetadata(String texturePath) { return metadata; }
        @Override public String name() { return "FakeAnimatedResolver"; }
    }
}
