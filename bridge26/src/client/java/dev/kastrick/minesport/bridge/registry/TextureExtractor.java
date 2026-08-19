package dev.kastrick.minesport.bridge.registry;

import dev.kastrick.minesport.bridge.model.BridgeProtocol.TextureEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Reads the original resource-pack/mod PNG and .mcmeta rather than scraping a
 * GPU atlas. This survives the 26.x renderer/Vulkan changes and preserves
 * animated texture frames for Blender/other DCC translators.
 */
public final class TextureExtractor {
    private TextureExtractor() {}

    public static TextureEntry extractTexture(String textureId, Minecraft client) {
        try {
            Identifier id = Identifier.tryParse(textureId);
            if (id == null) {
                return null;
            }

            Identifier pngId = Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/" + id.getPath() + ".png"
            );

            ResourceManager resources = client.getResourceManager();
            Optional<Resource> resource = resources.getResource(pngId);
            if (resource.isEmpty()) {
                return null;
            }

            BufferedImage image;
            try (InputStream input = resource.get().open()) {
                image = ImageIO.read(input);
            }
            if (image == null) {
                return null;
            }

            ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", pngBytes);

            Identifier metadataId = Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/" + id.getPath() + ".png.mcmeta"
            );
            String animationMeta = null;
            Optional<Resource> metadata = resources.getResource(metadataId);
            if (metadata.isPresent()) {
                try (InputStream input = metadata.get().open()) {
                    animationMeta = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }

            return new TextureEntry(
                textureId,
                image.getWidth(),
                image.getHeight(),
                Base64.getEncoder().encodeToString(pngBytes.toByteArray()),
                animationMeta
            );
        } catch (Exception exception) {
            System.err.println(
                "[MinesportBridge] Failed texture " + textureId + ": " + exception.getMessage()
            );
            return null;
        }
    }
}
