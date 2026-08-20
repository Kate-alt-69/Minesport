# Minesport bridge compatibility recipes

`bridge/` is the canonical Minecraft 1.21.10 Fabric bridge source. Minesport ships the compiled 1.21.10 JAR and CI verifies the same source against 1.21.9.

Minecraft versions outside that bundled compatibility range are prepared on demand. Minesport downloads the base file list declared in `manifest.json`, resolves the target Fabric Loader/API and required JDK, applies the matching JSON patch recipe, and compiles the result locally. Successful JARs are cached and reused.

Recipes deliberately avoid copying the whole bridge. Supported operations are:

- `set_property`: update/add a `key=value` entry in a properties file.
- `replace`: exact text replacement in one file; fails if the expected text is missing.
- `regex_replace`: regular-expression replacement in one file.
- `replace_tree`: text replacement across selected extensions below a directory.
- `rename_package`: package/import rename across Java source.
- `rename_file`: move/rename a file inside the build workspace.
- `overlay`: download one compatibility-specific file and place it over the base source.
- `delete`: remove a file/directory from the prepared source.

Values may contain `${minecraft_version}`, `${loader_version}`, `${fabric_api_version}`, `${fabric_version}`, `${java_version}`, `${gradle_version}`, and `${loom_version}`. The patcher expands them at preparation time.

Use an overlay only when an API changed enough that a safe declarative rename is no longer sufficient. For example, the 26.x recipe performs simple symbol migration declaratively and overlays only the renderer-facing classes that changed structurally.
