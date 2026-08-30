package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeResolverNeoForgeMetadataTest {
    @TempDir Path tempDir;

    @Test
    void recognizesModernNeoForgeMetadataFilename() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path jar = mods.resolve("neo-example.jar");
        String metadata = "modLoader=\"javafml\"\n"
            + "loaderVersion=\"[1,)\"\n"
            + "[[mods]]\n"
            + "modId=\"neo_example\"\n"
            + "version=\"1.2.3\"\n"
            + "displayName=\"Neo Example\"\n";
        String blockstate = "{\"variants\":{\"\":{"
            + "\"model\":\"neo_example:block/example\"}}}";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/neo_example/blockstates/test.json"));
            zip.write(blockstate.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        ForgeResolver resolver = ForgeResolver.load(mods.toFile(), null);
        try {
            Map<String, ForgeResolver.ModInfo> byId = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(ForgeResolver.ModInfo::modId, Function.identity()));
            assertTrue(byId.containsKey("neo_example"));
            assertEquals("Neo Example", byId.get("neo_example").name());
            assertEquals("1.2.3", byId.get("neo_example").version());
            assertTrue(resolver.canResolve("neo_example:test"));
            assertNotNull(resolver.resolveBlockState("neo_example:test"));
        } finally {
            resolver.close();
        }
    }
}
