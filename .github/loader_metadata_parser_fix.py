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


fabric_path = Path("engine/src/main/java/dev/kastrick/minesport/resolver/FabricResolver.java")
fabric = fabric_path.read_text(encoding="utf-8")
fabric = replace_once(
    fabric,
    "package dev.kastrick.minesport.resolver;\n\nimport dev.kastrick.minesport.model.*;",
    "package dev.kastrick.minesport.resolver;\n\n"
    "import com.google.gson.JsonElement;\n"
    "import com.google.gson.JsonObject;\n"
    "import com.google.gson.JsonParser;\n"
    "import dev.kastrick.minesport.model.*;",
    "Fabric Gson imports",
)
fabric = regex_once(
    fabric,
    r"    private ModInfo parseFabricMeta\(InputStream in, File jarFile\) \{.*?(?=\n    @Override\n    public boolean canResolve)",
    '''    private ModInfo parseFabricMeta(InputStream in, File jarFile) {
        try (InputStream source = in;
             InputStreamReader reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            String modId = jsonString(root, "id");
            if (modId == null || modId.isBlank()) return null;
            String name = jsonString(root, "name");
            String version = jsonString(root, "version");
            return new ModInfo(
                modId,
                name == null || name.isBlank() ? modId : name,
                version == null || version.isBlank() ? "?" : version,
                jarFile
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString()
            : null;
    }
''',
    "Fabric metadata parser",
)
fabric_path.write_text(fabric, encoding="utf-8")


quilt_path = Path("engine/src/main/java/dev/kastrick/minesport/resolver/QuiltResolver.java")
quilt = quilt_path.read_text(encoding="utf-8")
quilt = replace_once(
    quilt,
    "package dev.kastrick.minesport.resolver;\n\nimport dev.kastrick.minesport.model.*;",
    "package dev.kastrick.minesport.resolver;\n\n"
    "import com.google.gson.JsonElement;\n"
    "import com.google.gson.JsonObject;\n"
    "import com.google.gson.JsonParser;\n"
    "import dev.kastrick.minesport.model.*;",
    "Quilt Gson imports",
)
quilt = regex_once(
    quilt,
    r"    private ModInfo parseQuiltMeta\(InputStream in, File jarFile\) \{.*?(?=\n    // ── AssetResolver impl)",
    '''    private ModInfo parseQuiltMeta(InputStream in, File jarFile) {
        try (InputStream source = in;
             InputStreamReader reader = new InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            JsonObject loader = jsonObject(root, "quilt_loader");
            if (loader == null) return null;

            String modId = jsonString(loader, "id");
            if (modId == null || modId.isBlank()) return null;
            String version = jsonString(loader, "version");
            JsonObject metadata = jsonObject(loader, "metadata");
            String name = jsonString(metadata, "name");
            return new ModInfo(
                modId,
                name == null || name.isBlank() ? modId : name,
                version == null || version.isBlank() ? "?" : version,
                jarFile,
                false
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses fabric.mod.json for a mod running under Quilt in Fabric-compat mode. */
    private ModInfo parseFabricCompatMeta(InputStream in, File jarFile) {
        try (InputStream source = in;
             InputStreamReader reader = new InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            String modId = jsonString(root, "id");
            if (modId == null || modId.isBlank()) return null;
            String name = jsonString(root, "name");
            String version = jsonString(root, "version");
            return new ModInfo(
                modId,
                name == null || name.isBlank() ? modId : name,
                version == null || version.isBlank() ? "?" : version,
                jarFile,
                true
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject jsonObject(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString()
            : null;
    }
''',
    "Quilt metadata parsers",
)
quilt_path.write_text(quilt, encoding="utf-8")


test = Path("engine/src/test/java/dev/kastrick/minesport/resolver/LoaderMetadataParserTest.java")
if test.exists():
    raise SystemExit("LoaderMetadataParserTest.java already exists")
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderMetadataParserTest {
    @TempDir Path tempDir;

    @Test
    void fabricUsesTopLevelJsonFields() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("fabric"));
        writeJar(
            mods.resolve("fabric.jar"),
            "fabric.mod.json",
            "{\\\"custom\\\":{\\\"id\\\":\\\"wrong_nested_id\\\"},"
                + "\\\"schemaVersion\\\":1,\\\"id\\\":\\\"real_mod\\\","
                + "\\\"version\\\":\\\"1.2.3\\\",\\\"name\\\":\\\"Real Mod\\\"}",
            "assets/real_mod/models/block/example.json"
        );

        FabricResolver resolver = FabricResolver.load(mods.toFile(), null);
        try {
            Map<String, FabricResolver.ModInfo> byId = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(FabricResolver.ModInfo::modId, Function.identity()));
            assertTrue(byId.containsKey("real_mod"));
            assertFalse(byId.containsKey("wrong_nested_id"));
            assertEquals("Real Mod", byId.get("real_mod").name());
            assertEquals("1.2.3", byId.get("real_mod").version());
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltReadsOnlyTheQuiltLoaderObject() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt"));
        writeJar(
            mods.resolve("quilt.jar"),
            "quilt.mod.json",
            "{\\\"schema_version\\\":1,\\\"unrelated\\\":{\\\"id\\\":\\\"wrong_outer_id\\\"},"
                + "\\\"quilt_loader\\\":{\\\"id\\\":\\\"quilt_real\\\","
                + "\\\"version\\\":\\\"9.8.7\\\","
                + "\\\"metadata\\\":{\\\"name\\\":\\\"Braces } { Mod\\\"}}}",
            "assets/quilt_real/models/block/example.json"
        );

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            Map<String, QuiltResolver.ModInfo> byId = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(QuiltResolver.ModInfo::modId, Function.identity()));
            assertTrue(byId.containsKey("quilt_real"));
            assertFalse(byId.containsKey("wrong_outer_id"));
            assertEquals("Braces } { Mod", byId.get("quilt_real").name());
            assertEquals("9.8.7", byId.get("quilt_real").version());
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltFabricCompatUsesTopLevelFabricFields() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("compat"));
        writeJar(
            mods.resolve("compat.jar"),
            "fabric.mod.json",
            "{\\\"nested\\\":{\\\"id\\\":\\\"not_this_one\\\"},"
                + "\\\"id\\\":\\\"compat_real\\\",\\\"version\\\":\\\"4.5.6\\\","
                + "\\\"name\\\":\\\"Compat Mod\\\"}",
            "assets/compat_real/models/block/example.json"
        );

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            QuiltResolver.ModInfo info = resolver.getDetectedMods().iterator().next();
            assertEquals("compat_real", info.modId());
            assertEquals("Compat Mod", info.name());
            assertEquals("4.5.6", info.version());
            assertTrue(info.fabricCompat());
        } finally {
            resolver.close();
        }
    }

    private static void writeJar(Path jar, String metadataPath, String metadata, String assetPath)
        throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(metadataPath));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(assetPath));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
''', encoding="utf-8")

print("Replaced Fabric/Quilt metadata scanners with Gson parsers")
