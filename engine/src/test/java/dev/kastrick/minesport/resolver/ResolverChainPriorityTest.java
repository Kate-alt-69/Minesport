package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertSame;

class ResolverChainPriorityTest {
    @TempDir Path tempDir;

    @Test
    void modLayerAddedAfterVanillaStillPrecedesVanillaFallback() throws Exception {
        Path vanillaJar = tempDir.resolve("1.21.10.jar");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(vanillaJar))) {}

        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        ResourcePackResolver resourcePacks = ResourcePackResolver.empty();
        VanillaResolver vanilla = new VanillaResolver(vanillaJar.toFile());
        FabricResolver fabric = FabricResolver.load(mods.toFile(), null);

        ResolverChain chain = new ResolverChain();
        chain.addResolver(resourcePacks);
        chain.addResolver(vanilla);
        chain.addResolver(fabric);
        try {
            assertSame(resourcePacks, chain.getResolvers().get(0));
            assertSame(fabric, chain.getResolvers().get(1));
            assertSame(vanilla, chain.getResolvers().get(2));
        } finally {
            chain.close();
        }
    }
}
