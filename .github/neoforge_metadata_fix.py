from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    return updated


path = Path("engine/src/main/java/dev/kastrick/minesport/resolver/ForgeResolver.java")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    ''' *   META-INF/mods.toml            ← mod metadata (modId, version, displayName)''',
    ''' *   META-INF/mods.toml            ← Forge mod metadata
 *   META-INF/neoforge.mods.toml   ← modern NeoForge mod metadata''',
    "Forge metadata documentation",
)

text = replace_once(
    text,
    '''     * A jar only registers with this resolver if it has a valid
     * META-INF/mods.toml with at least one [[mods]] entry.''',
    '''     * A jar only registers with this resolver if it has a valid
     * META-INF/mods.toml or META-INF/neoforge.mods.toml with at least one
     * [[mods]] entry.''',
    "Forge loader documentation",
)

text = regex_once(
    text,
    r'''        ZipEntry modsToml = zip\.getEntry\("META-INF/mods\.toml"\);\n        if \(modsToml == null\) \{.*?        List<ModInfo> mods = parseModsToml\(toml, jarFile\);''',
    '''        String metadataPath = "META-INF/mods.toml";
        ZipEntry modsToml = zip.getEntry(metadataPath);
        if (modsToml == null) {
            metadataPath = "META-INF/neoforge.mods.toml";
            modsToml = zip.getEntry(metadataPath);
        }
        if (modsToml == null) {
            // The selected Forge/NeoForge resolver must not claim arbitrary
            // library/resource JARs. Require one of the loader metadata files,
            // but support both the Forge and modern NeoForge names.
            zip.close();
            return;
        }

        String toml;
        try (InputStream metadata = zip.getInputStream(modsToml)) {
            toml = new String(metadata.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        List<ModInfo> mods = parseModsToml(toml, jarFile);''',
    "Forge/NeoForge metadata selection",
)

text = replace_once(
    text,
    '''            if (log != null) log.accept("[ForgeResolver] mods.toml present but no [[mods]] entries in " + jarFile.getName());''',
    '''            if (log != null) log.accept(
                "[ForgeResolver] " + metadataPath
                    + " present but no [[mods]] entries in " + jarFile.getName()
            );''',
    "Forge metadata failure diagnostic",
)

path.write_text(text, encoding="utf-8")


test = Path("engine/src/test/java/dev/kastrick/minesport/resolver/ForgeResolverNeoForgeMetadataTest.java")
if test.exists():
    raise SystemExit("ForgeResolverNeoForgeMetadataTest.java already exists")

test.write_text('''package dev.kastrick.minesport.resolver;

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
        String metadata = "modLoader=\\\"javafml\\\"\\n"
            + "loaderVersion=\\\"[1,)\\\"\\n"
            + "[[mods]]\\n"
            + "modId=\\\"neo_example\\\"\\n"
            + "version=\\\"1.2.3\\\"\\n"
            + "displayName=\\\"Neo Example\\\"\\n";
        String blockstate = "{\\\"variants\\\":{\\\"\\\":{"
            + "\\\"model\\\":\\\"neo_example:block/example\\\"}}}";

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
''', encoding="utf-8")

print("Added modern NeoForge metadata filename support")
