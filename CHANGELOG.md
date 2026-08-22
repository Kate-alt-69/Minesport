# Changelog

All notable changes to the Minesport project will be documented in this file.

## [0.2.0] — Official “bug-free*” release

> **bug-free\*** means 0.2.0 is intended to be the first stable-feeling 0.2 release after the current regression/fix pass. It is not a promise that software has become mathematically incapable of containing another bug. If you find one, you found the asterisk — please report it with diagnostics.

Full release notes: [`doc/releases/0.2.0.md`](doc/releases/0.2.0.md)

### Added
- **Repository-backed Documentation** under `/doc/`, including beginner-first pages for the Minesport app, Blender basics, finding the Minesport N-panel, FLATTER, Minecraft Light blocks, troubleshooting, and the Runtime Model Cache.
- **Interactive Documentation browser** in Minesport Settings. It streams the current Markdown page index/content from GitHub and can expose GitHub-hosted tutorial videos without bundling them into the installer.
- **FLATTER direct logical clicking** in Blender 0.2.0: normal LMB on FLATTER geometry can address the logical Minecraft voxel underneath the cursor, with Shift/Ctrl selection modifiers and immediate logical-overlay redraw.
- **FLATTER repeated SHAPE objects** for compatible repeated non-full geometry such as path-like shapes, separate from full SOLID FLATTER streams.
- **Configurable FLATTER cell size**: 8³, 16³, 32³, or 64³.
- **Minecraft Light block workflow** with a dedicated 1×1×1 `MINESPORT_LIGHT_BLOCK_LVL_<level>` helper plus a separate Blender POINT light for actual illumination.
- **Runtime Model Cache worker** for exact-version Fabric registry/baked-model capture, fingerprinted for reuse across matching Minecraft/mod environments.
- **Automatic runtime-cache preparation during Export**, with manual rebuild/debug controls retained under Advanced settings.
- Compatibility-recipe regression coverage so canonical Bridge source movement is less likely to silently break historical-version patch recipes.

### Changed
- Minesport desktop/software version, Java engine artifact, Fabric Bridge artifact, installers/packages, and Blender addon moved to the **0.2.0** release line.
- Resource-pack and resolver controls live directly inside **Settings** instead of a separate Asset Resolution Center navigation/popup.
- Manual Preflight is an **Advanced diagnostic**, not a required/export-page workflow step.
- Export optimization UI is compact; detailed activity/results use the bottom task notification and diagnostics.
- Fabric worlds activate the Fabric/Polymer resolver path instead of rescanning the same mod directory as Quilt and Forge.
- Engine-log rendering is batched to reduce Fyne UI starvation during large resolver log bursts.
- Rebuildable cache data is separated from durable application state. Windows targets `%USERPROFILE%\.cache\kastrick's_software\minesport` for caches and `%LOCALAPPDATA%\kastrick's_software\minesport` for durable settings/diagnostics.
- Runtime registry/model cache stores texture identifiers rather than duplicating texture image payload bytes.

### Fixed
- Removed the unwanted Minesport **Project** file/workspace UI and its save/open metadata path.
- Removed global Blender `overlay.show_extras` manipulation from Minesport Light-helper hiding; unrelated Blender lights/cameras/empties are no longer supposed to disappear with Minesport helpers.
- Corrected user Light-block placement to the center of the Minecraft-sized target voxel instead of placing the emitter almost directly on the clicked face.
- Isolated runtime worker excludes Crash Assistant so the intentionally short-lived capture client is not mistaken for a player-session crash.
- Replaced brittle line-number Bridge compatibility patch operations where source movement made historical recipes stale.
- Preserved registered runtime states with empty ordinary baked-quad output so “known custom/special renderer state” is not confused with “unknown state”.
- FLATTER tests no longer inherit the user's persisted FLATTER cell-size setting.
- Removed stale Asset Center startup/shutdown hooks after moving assets into Settings.

## [Unreleased / historical pre-0.2 work]

### Added
- **Gradle Wrapper for Bridge Module**: Added `gradlew`, `gradlew.bat`, and `gradle/wrapper/` directory to the bridge folder for independent Fabric mod building.
- **Build Step for Bridge**: Updated build scripts to build the Fabric bridge mod before the engine and wrapper.
- **Go Module Configuration**: Restored `go.mod` for the wrapper Go project with proper module path and dependency management.
- **Fyne GUI Dependencies**: Added the Fyne desktop UI framework.

### Fixed
- Modernized Bridge Gradle configuration and mappings/dependency resolution.
- Corrected Go import/build issues in early wrapper code.
- Fixed launcher discovery and world-map refresh compile issues.
- Updated toolchain configuration required by newer language/runtime features.

## Build Order
1. **Bridge** → `bridge/build/libs/minesport-bridge-*.jar` (Fabric mod)
2. **Engine** → `engine/build/libs/minesport-engine-*.jar` (Java export engine)
3. **Wrapper** → `wrapper/minesport.exe` / platform equivalent (Go/Fyne desktop GUI)
