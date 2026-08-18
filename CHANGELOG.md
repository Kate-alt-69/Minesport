# Changelog

All notable changes to the Minesport project will be documented in this file.

## [Unreleased]

### Added
- **Gradle Wrapper for Bridge Module**: Added `gradlew`, `gradlew.bat`, and `gradle/wrapper/` directory to the bridge folder for independent Fabric mod building
- **Build Step for Bridge**: Updated `build.bat` and `build.sh` scripts to build the Fabric bridge mod as step [1/3] before the engine and wrapper
- **Go Module Configuration**: Restored `go.mod` for the wrapper Go project with proper module path and dependency management
- **Fyne GUI Dependencies**: Added Fyne v2.4.5 framework to Go wrapper for building the desktop UI

### Fixed
- **Bridge build.gradle**: 
  - Updated from deprecated `archivesBaseName` to modern `archivesName` property with `base` plugin (Gradle 8.0+ compatibility)
  - Fixed yarn mappings version from `1.21.10+build.1:v2` to `1.21.10+build.7` (stable Fabric release)
  - Removed problematic `https://maven.pb4.eu/` repository and added `mavenCentral()` as fallback to ensure dependencies resolve correctly
  
- **Go Import Paths**: Changed all Go imports from `github.com/kastrick/minesport/*` to local module path `minesport/*` to support local compilation
  - Updated `main.go` imports
  - Updated `wrapper/ui/window.go` imports  
  - Updated `wrapper/ui/world_picker.go` imports

- **Go Code Issues**:
  - Fixed `launcher/discovery.go` line 342: Changed `entry.Name` to `entry.Name()` to properly call the function
  - Fixed `wrapper/ui/worldmap.go`: Changed lowercase `refresh()` calls to capitalized `Refresh()` to use inherited Fyne BaseWidget method
  - Removed duplicate/recursive refresh function definition

- **Go Version**: Updated from Go 1.21 to 1.22 in `go.mod` to support range over integers syntax (required for worldmap rendering)

- **Gradle Configuration**:
  - Updated bridge and engine gradle wrappers to use Gradle 9.3.1 from official repository (https://services.gradle.org/distributions/gradle-9.3.1-bin.zip)
  - Added fabric-loom plugin repository to bridge's `settings.gradle` pluginManagement block

### Changed
- **Build Script**: Updated build scripts [build.bat](build.bat) and [build.sh](build.sh) to follow 3-step build process:
  1. Build Fabric bridge mod
  2. Build Java engine
  3. Build Go wrapper GUI

## Build Order
1. **Bridge** → `bridge/build/libs/minesport-bridge-*.jar` (Fabric mod)
2. **Engine** → `engine/build/libs/minesport-engine-*.jar` (Java export engine)  
3. **Wrapper** → `wrapper/minesport.exe` (Go/Fyne desktop GUI)

## Dependencies
- **Gradle**: 9.3.1
- **Go**: 1.22
- **Java**: 22 (source/target compatibility)
- **Fyne**: v2.4.5 (Go GUI framework)
- **Fabric Loom**: 1.9.2 (Fabric mod development)
- **Minecraft**: 1.21.10
