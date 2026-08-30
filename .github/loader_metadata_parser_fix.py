from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


fabric = "engine/src/main/java/dev/kastrick/minesport/resolver/FabricResolver.java"
quilt = "engine/src/main/java/dev/kastrick/minesport/resolver/QuiltResolver.java"

replace_once(
    fabric,
    '''package dev.kastrick.minesport.resolver;\n\nimport dev.kastrick.minesport.model.*;''',
    '''package dev.kastrick.minesport.resolver;\n\nimport com.google.gson.JsonElement;\nimport com.google.gson.JsonObject;\nimport com.google.gson.JsonParser;\nimport dev.kastrick.minesport.model.*;''',
    "Fabric Gson imports",
)
replace_once(
    fabric,
    '''    private ModInfo parseFabricMeta(InputStream in, File jarFile) {\n        try {\n            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);\n            String modId = extractJsonString(json, "id");\n            String name = extractJsonString(json, "name");\n            String version = extractJsonString(json, "version");\n            if (modId == null) return null;\n            return new ModInfo(modId, name != null ? name : modId, version != null ? version : "?", jarFile);\n        } catch (Exception e) { return null; }\n    }\n\n    private static String extractJsonString(String json, String key) {\n        String search = "\\\"" + key + "\\\"";\n        int idx = json.indexOf(search);\n        if (idx < 0) return null;\n        int colon = json.indexOf(':', idx + search.length());\n        if (colon < 0) return null;\n        int q1 = json.indexOf('\\\"', colon + 1);\n        if (q1 < 0) return null;\n        int q2 = json.indexOf('\\\"', q1 + 1);\n        return q2 < 0 ? null : json.substring(q1 + 1, q2);\n    }''',
    '''    private ModInfo parseFabricMeta(InputStream in, File jarFile) {\n        try (InputStream source = in;\n             InputStreamReader reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {\n            JsonElement parsed = JsonParser.parseReader(reader);\n            if (!parsed.isJsonObject()) return null;\n            JsonObject root = parsed.getAsJsonObject();\n            String modId = jsonString(root, "id");\n            if (modId == null || modId.isBlank()) return null;\n            String name = jsonString(root, "name");\n            String version = jsonString(root, "version");\n            return new ModInfo(\n                modId,\n                name == null || name.isBlank() ? modId : name,\n                version == null || version.isBlank() ? "?" : version,\n                jarFile\n            );\n        } catch (Exception e) {\n            return null;\n        }\n    }\n\n    private static String jsonString(JsonObject object, String key) {\n        if (object == null || !object.has(key)) return null;\n        JsonElement value = object.get(key);\n        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()\n            ? value.getAsString()\n            : null;\n    }''',
    "Fabric metadata parser",
)

replace_once(
    quilt,
    '''package dev.kastrick.minesport.resolver;\n\nimport dev.kastrick.minesport.model.*;''',
    '''package dev.kastrick.minesport.resolver;\n\nimport com.google.gson.JsonElement;\nimport com.google.gson.JsonObject;\nimport com.google.gson.JsonParser;\nimport dev.kastrick.minesport.model.*;''',
    "Quilt Gson imports",
)
replace_once(
    quilt,
    '''    private ModInfo parseQuiltMeta(InputStream in, File jarFile) {\n        try {\n            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);\n            String loaderObj = extractJsonObjectRegion(json, "quilt_loader");\n            if (loaderObj == null) return null;\n\n            String modId = extractJsonString(loaderObj, "id");\n            String version = extractJsonString(loaderObj, "version");\n            if (modId == null) return null;\n\n            String name = modId;\n            String metaObj = extractJsonObjectRegion(loaderObj, "metadata");\n            if (metaObj != null) {\n                String metaName = extractJsonString(metaObj, "name");\n                if (metaName != null) name = metaName;\n            }\n\n            return new ModInfo(modId, name, version != null ? version : "?", jarFile, false);\n        } catch (Exception e) {\n            return null;\n        }\n    }\n\n    /** Parses fabric.mod.json for a mod that's running under Quilt in Fabric-compat mode. */\n    private ModInfo parseFabricCompatMeta(InputStream in, File jarFile) {\n        try {\n            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);\n            String modId  = extractJsonString(json, "id");\n            String name   = extractJsonString(json, "name");\n            String version = extractJsonString(json, "version");\n            if (modId == null) return null;\n            return new ModInfo(modId, name != null ? name : modId, version != null ? version : "?", jarFile, true);\n        } catch (Exception e) {\n            return null;\n        }\n    }\n\n    /** Minimal JSON string extractor — avoids pulling in Gson for just metadata. */\n    private static String extractJsonString(String json, String key) {\n        String search = "\\\"" + key + "\\\"";\n        int idx = json.indexOf(search);\n        if (idx < 0) return null;\n        int colon = json.indexOf(':', idx + search.length());\n        if (colon < 0) return null;\n        int q1 = json.indexOf('\\\"', colon + 1);\n        if (q1 < 0) return null;\n        int q2 = json.indexOf('\\\"', q1 + 1);\n        if (q2 < 0) return null;\n        return json.substring(q1 + 1, q2);\n    }\n\n    /**\n     * Finds the substring of a nested JSON object value for the given key,\n     * e.g. extractJsonObjectRegion(json, "quilt_loader") returns everything\n     * between (and including) the { } that follow "quilt_loader":.\n     * Uses simple brace counting — good enough since we never need to parse\n     * arbitrary/malformed JSON here, only the well-formed metadata Loom/Quilt\n     * itself generates.\n     */\n    private static String extractJsonObjectRegion(String json, String key) {\n        String search = "\\\"" + key + "\\\"";\n        int idx = json.indexOf(search);\n        if (idx < 0) return null;\n        int colon = json.indexOf(':', idx + search.length());\n        if (colon < 0) return null;\n        int braceStart = json.indexOf('{', colon);\n        if (braceStart < 0) return null;\n\n        int depth = 0;\n        for (int i = braceStart; i < json.length(); i++) {\n            char c = json.charAt(i);\n            if (c == '{') depth++;\n            else if (c == '}') {\n                depth--;\n                if (depth == 0) return json.substring(braceStart, i + 1);\n            }\n        }\n        return null; // unbalanced — shouldn't happen with valid metadata\n    }''',
    '''    private ModInfo parseQuiltMeta(InputStream in, File jarFile) {\n        try (InputStream source = in;\n             InputStreamReader reader = new InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8)) {\n            JsonElement parsed = JsonParser.parseReader(reader);\n            if (!parsed.isJsonObject()) return null;\n            JsonObject root = parsed.getAsJsonObject();\n            JsonObject loader = jsonObject(root, "quilt_loader");\n            if (loader == null) return null;\n\n            String modId = jsonString(loader, "id");\n            if (modId == null || modId.isBlank()) return null;\n            String version = jsonString(loader, "version");\n            JsonObject metadata = jsonObject(loader, "metadata");\n            String name = jsonString(metadata, "name");\n\n            return new ModInfo(\n                modId,\n                name == null || name.isBlank() ? modId : name,\n                version == null || version.isBlank() ? "?" : version,\n                jarFile,\n                false\n            );\n        } catch (Exception e) {\n            return null;\n        }\n    }\n\n    /** Parses fabric.mod.json for a mod that's running under Quilt in Fabric-compat mode. */\n    private ModInfo parseFabricCompatMeta(InputStream in, File jarFile) {\n        try (InputStream source = in;\n             InputStreamReader reader = new InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8)) {\n            JsonElement parsed = JsonParser.parseReader(reader);\n            if (!parsed.isJsonObject()) return null;\n            JsonObject root = parsed.getAsJsonObject();\n            String modId = jsonString(root, "id");\n            if (modId == null || modId.isBlank()) return null;\n            String name = jsonString(root, "name");\n            String version = jsonString(root, "version");\n            return new ModInfo(\n                modId,\n                name == null || name.isBlank() ? modId : name,\n                version == null || version.isBlank() ? "?" : version,\n                jarFile,\n                true\n            );\n        } catch (Exception e) {\n            return null;\n        }\n    }\n\n    private static JsonObject jsonObject(JsonObject object, String key) {\n        if (object == null || !object.has(key)) return null;\n        JsonElement value = object.get(key);\n        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;\n    }\n\n    private static String jsonString(JsonObject object, String key) {\n        if (object == null || !object.has(key)) return null;\n        JsonElement value = object.get(key);\n        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()\n            ? value.getAsString()\n            : null;\n    }''',
    "Quilt metadata parsers",
)

test = Path("engine/src/test/java/dev/kastrick/minesport/resolver/LoaderMetadataParserTest.java")
if test.exists():
    raise SystemExit("LoaderMetadataParserTest.java already exists")
test.write_text(r'''package dev.kastrick.minesport.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderMetadataParserTest {
    @TempDir Path tempDir;

    @Test
    void fabricUsesTopLevelJsonFieldsAndDecodesEscapes() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("fabric"));
        writeJar(
            mods.resolve("quoted.jar"),
            "fabric.mod.json",
            """
            {
              "custom": {"id": "wrong_nested_id"},
              "schemaVersion": 1,
              "id": "real_mod",
              "version": "1.2.3",
              "name": "Quoted \\\"Mod\\\""
            }
            """,
            "assets/real_mod/models/block/example.json"
        );

        FabricResolver resolver = FabricResolver.load(mods.toFile(), null);
        try {
            Map<String, FabricResolver.ModInfo> modsById = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(FabricResolver.ModInfo::modId, Function.identity()));
            assertTrue(modsById.containsKey("real_mod"));
            assertFalse(modsById.containsKey("wrong_nested_id"));
            assertEquals("Quoted \"Mod\"", modsById.get("real_mod").name());
            assertEquals("1.2.3", modsById.get("real_mod").version());
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltParsesNestedLoaderMetadataWithoutBraceScanning() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt"));
        writeJar(
            mods.resolve("quilted.jar"),
            "quilt.mod.json",
            """
            {
              "schema_version": 1,
              "unrelated": {"id": "wrong_outer_id"},
              "quilt_loader": {
                "id": "quilt_real",
                "version": "9.8.7",
                "metadata": {"name": "Braces } { and \\\"quotes\\\""}
              }
            }
            """,
            "assets/quilt_real/models/block/example.json"
        );

        QuiltResolver resolver = QuiltResolver.load(mods.toFile(), null);
        try {
            Map<String, QuiltResolver.ModInfo> modsById = resolver.getDetectedMods().stream()
                .collect(Collectors.toMap(QuiltResolver.ModInfo::modId, Function.identity()));
            assertTrue(modsById.containsKey("quilt_real"));
            assertFalse(modsById.containsKey("wrong_outer_id"));
            assertEquals("Braces } { and \"quotes\"", modsById.get("quilt_real").name());
            assertEquals("9.8.7", modsById.get("quilt_real").version());
        } finally {
            resolver.close();
        }
    }

    @Test
    void quiltFabricCompatUsesTopLevelFabricFields() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("quilt-fabric"));
        writeJar(
            mods.resolve("compat.jar"),
            "fabric.mod.json",
            """
            {
              "nested": {"id": "not_this_one"},
              "id": "compat_real",
              "version": "4.5.6",
              "name": "Compat Mod"
            }
            """,
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
