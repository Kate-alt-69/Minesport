# Minesport bridge compatibility recipes

`bridge/` is the canonical Minecraft 1.21.10 Fabric bridge source. Minesport ships the compiled 1.21.10 JAR, and that bundled bridge is also used for the verified-compatible 1.21.9 target. The normal `build.ps1`, `build.bat`, and `build.sh` build only this canonical bridge. Everything else is generated from the canonical source by Minesport only when a user actually needs another supported Minecraft version.

For an unbundled Minecraft version, Minesport downloads the file list declared by `manifest.json`, resolves the matching Fabric Loader/API and JDK, applies the selected JSON recipe to a private build workspace, runs the Gradle wrapper, verifies that a bridge JAR was produced, and caches the result. The user's original source tree is never modified. `minesport --build-bridge <version>` exposes the same preparation path directly, and `minesport --build-bridges-detected` prepares the versions of detected Fabric instances.

## Compatibility families

- Minecraft 1.21 and 1.21.1: Java 21, pre-1.21.5 baked-model compatibility plus the older `NativeImage#getPixelRGBA` texture path.
- Minecraft 1.21.2 through 1.21.4: Java 21, pre-1.21.5 baked-model compatibility with the newer `NativeImage#getPixel` API.
- Minecraft 1.21.5: Java 21, new block-state model API plus the older `WorldVersion#getId()` naming.
- Minecraft 1.21.6: Java 21, new block-state model API with record-style `WorldVersion#id()`.
- Minecraft 1.21.7 and 1.21.8: Java 21, canonical renderer source with target dependency versions.
- Minecraft 1.21.9 and 1.21.10: bundled 1.21.10 bridge; no source download or compilation required.
- Minecraft 1.21.11: Java 21, Identifier migration plus renderer-safe baked-quad conversion and original PNG/`.png.mcmeta` extraction.
- Minecraft 26.1/26.1.x: Java 25, unobfuscated Mojang names and Fabric renderer API compatibility overlay.
- Minecraft 26.2/26.2.x: Java 25, same source-generation model with its own explicit profile.
- Minecraft 26.3 snapshots are experimental. They are not part of the required compile-clean matrix until Fabric API publishes a matching build and the recipe has been proven again.

## Recipe operations

Recipes deliberately avoid cloning the whole bridge. Schema 1 supports:

- `set_property`: update/add a `key=value` entry in a properties file.
- `replace`: exact text replacement in one file; fails when the expected text is missing.
- `rename_at`: replace exact text at a 1-based `line` and `column`. It fails closed if the file moved or the expected text is no longer at that coordinate.
- `regex_replace`: regular-expression replacement in one file; fails when the pattern does not match.
- `replace_tree`: text replacement across selected extensions below a directory.
- `rename_package`: package/import rename across Java source.
- `rename_file`: move/rename a file inside the build workspace.
- `overlay`: copy one compatibility-specific repository file over the canonical source.
- `module`: fetch a small external source module and place it into the generated project.
- `delete`: remove a file/directory from the generated project.

Values may contain `${minecraft_version}`, `${loader_version}`, `${fabric_api_version}`, `${fabric_version}`, `${java_version}`, `${gradle_version}`, and `${loom_version}`. The patcher expands them at preparation time.

`&PROJECT&/` refers to the root of the temporary generated Gradle project. For example:

```json
{
  "op": "rename_at",
  "file": "src/client/java/dev/kastrick/minesport/bridge/MinesportExportWorker.java",
  "line": 52,
  "column": 67,
  "from": "id()",
  "to": "getId()"
}
```

A compatibility module can be declared without putting another entire source tree in this repository:

```json
{
  "op": "module",
  "name": "RendererCompat.java",
  "url": "https://raw.githubusercontent.com/example/project/COMMIT/RendererCompat.java",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "target": "&PROJECT&/dependencies/RendererCompat.java"
}
```

Modules are intentionally strict: the URL must be absolute HTTPS, the recipe must pin the exact SHA-256, the downloaded file must be valid UTF-8, the maximum size is 8 MiB, and the target must remain inside the generated project. The Java 21 and Java 25 compatibility build overlays include `dependencies/` as a client Java source directory, so a recipe can then use `replace`, `rename_at`, or another operation to wire the downloaded module into the generated bridge.

Use declarative edits for small API migrations. Use an overlay when a Minecraft/Fabric API changed structurally enough that line edits would be brittle. Use a pinned module when a reusable compatibility helper is cleaner than replacing a whole bridge class.
