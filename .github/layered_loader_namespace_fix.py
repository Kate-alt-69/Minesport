from pathlib import Path
import re

FILES = [
    Path("engine/src/main/java/dev/kastrick/minesport/resolver/FabricResolver.java"),
    Path("engine/src/main/java/dev/kastrick/minesport/resolver/ForgeResolver.java"),
    Path("engine/src/main/java/dev/kastrick/minesport/resolver/QuiltResolver.java"),
]


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


for path in FILES:
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "private final Map<String, ZipFile> namespaceJars = new LinkedHashMap<>();",
        "private final Map<String, List<ZipFile>> namespaceJars = new LinkedHashMap<>();",
        f"{path.name} namespace source map",
    )
    text = replace_once(
        text,
        "namespaceJars.putIfAbsent(ns, zip);",
        "namespaceJars.computeIfAbsent(ns, ignored -> new ArrayList<>()).add(zip);",
        f"{path.name} namespace registration",
    )
    text = regex_once(
        text,
        r"    private InputStream openEntry\(String namespace, String path\) throws IOException \{.*?(?=\n    private static String normalizePath)",
        '''    private InputStream openEntry(String namespace, String path) throws IOException {
        List<ZipFile> sources = namespaceJars.get(namespace);
        if (sources == null || sources.isEmpty()) return null;

        // Preserve the historical first-owner priority, but fall through to
        // later JARs sharing the namespace when the earlier source does not
        // contain this exact resource. Companion/library JARs can therefore
        // contribute models/textures without unexpectedly overriding a file
        // that Minesport already resolved from the first source.
        for (ZipFile zip : sources) {
            ZipEntry entry = zip.getEntry(path);
            if (entry != null) return zip.getInputStream(entry);
        }
        return null;
    }
''',
        f"{path.name} layered openEntry",
    )
    text = regex_once(
        text,
        r"    public Set<String> listModels\(String namespace\) \{.*?\n    \}\n\}",
        '''    public Set<String> listModels(String namespace) {
        List<ZipFile> sources = namespaceJars.get(namespace);
        if (sources == null || sources.isEmpty()) return Collections.emptySet();

        String prefix = "assets/" + namespace + "/models/block/";
        Set<String> names = new LinkedHashSet<>();
        for (ZipFile zip : sources) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".json")) {
                    String modelName = name.substring(prefix.length(), name.length() - 5);
                    if (!modelName.contains("/")) names.add(modelName);
                }
            }
        }
        return names;
    }
}''',
        f"{path.name} layered listModels",
    )
    path.write_text(text, encoding="utf-8")


test = Path("engine/src/test/java/dev/kastrick/minesport/resolver/LayeredNamespaceResolverTest.java")
if test.exists():
    raise SystemExit("LayeredNamespaceResolverTest.java already exists")
test.write_text('''package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredNamespaceResolverTest {
    private static final String BLOCKSTATE =
        "{\\\"variants\\\":{\\\"\\\":{\\\"model\\\":\\\"shared:block/example\\\"}}}";
    private static final String MODEL =
        "{\\\"textures\\\":{\\\"all\\\":\\\"minecraft:block/stone\\\"},"
            + "\\\"elements\\\":[{\\\"from\\\":[0,0,0],\\\"to\\\":[16,16,16],"
            + "\\\"faces\\\":{\\\"north\\\":{\\\"texture\\\":\\\"#all\\\"}}}]}";

    @TempDir Path tempDir;

    @Test
    void fabricFindsResourcesAcrossJarsSharingOneNamespace() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("fabric"));
        writeJar(mods.resolve("a.jar"), "fabric.mod.json", fabricMeta("fabric_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeJar(mods.resolve("b.jar"), "fabric.mod.json", fabricMeta("fabric_b"),
            "assets/shared/models/block/example.json", MODEL);

        FabricResolver resolver = FabricResolver.load(mods.toFile(), null);
        try {
            assertNotNull(resolver.resolveBlockState("shared:test"));
            assertTrue(resolver.listModels("shared").contains("example"));
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltFindsResourcesAcrossJarsSharingOneNamespace() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt"));
        writeJar(mods.resolve("a.jar"), "quilt.mod.json", quiltMeta("quilt_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeJar(mods.resolve("b.jar"), "quilt.mod.json", quiltMeta("quilt_b"),
            "assets/shared/models/block/example.json", MODEL);

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            assertNotNull(resolver.resolveBlockState("shared:test"));
            assertTrue(resolver.listModels("shared").contains("example"));
        } finally {
            resolver.close();
        }
    }

    @Test
    void forgeFindsResourcesAcrossJarsSharingOneNamespace() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("forge"));
        writeJar(mods.resolve("a.jar"), "META-INF/mods.toml", forgeMeta("forge_a"),
            "assets/shared/blockstates/test.json", BLOCKSTATE);
        writeJar(mods.resolve("b.jar"), "META-INF/mods.toml", forgeMeta("forge_b"),
            "assets/shared/models/block/example.json", MODEL);

        ForgeResolver resolver = ForgeResolver.load(mods.toFile(), null);
        try {
            assertNotNull(resolver.resolveBlockState("shared:test"));
            assertTrue(resolver.listModels("shared").contains("example"));
        } finally {
            resolver.close();
        }
    }

    private static String fabricMeta(String id) {
        return "{\\\"schemaVersion\\\":1,\\\"id\\\":\\\"" + id
            + "\\\",\\\"version\\\":\\\"1\\\",\\\"name\\\":\\\"" + id + "\\\"}";
    }

    private static String quiltMeta(String id) {
        return "{\\\"schema_version\\\":1,\\\"quilt_loader\\\":{\\\"id\\\":\\\"" + id
            + "\\\",\\\"version\\\":\\\"1\\\",\\\"metadata\\\":{\\\"name\\\":\\\"" + id + "\\\"}}}";
    }

    private static String forgeMeta(String id) {
        return "modLoader=\\\"javafml\\\"\\nloaderVersion=\\\"[1,)\\\"\\n"
            + "[[mods]]\\nmodId=\\\"" + id + "\\\"\\nversion=\\\"1\\\"\\n"
            + "displayName=\\\"" + id + "\\\"\\n";
    }

    private static void writeJar(
        Path jar,
        String metadataPath,
        String metadata,
        String assetPath,
        String asset
    ) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(metadataPath));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(assetPath));
            zip.write(asset.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
''', encoding="utf-8")

print("Enabled layered resource lookup for shared loader namespaces")
