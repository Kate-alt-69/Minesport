# Minesport

**Minecraft world exporter by Kastrick**

Minesport reads Minecraft save data and the game's resource assets, resolves vanilla and modded block geometry, and exports selected parts of a world to **glTF 2.0** or **OBJ + MTL** for Blender and other 3D tools.

> **Current status:** the Java engine and Fabric bridge build successfully. The native Go/Fyne wrapper supports the current loader/resource resolver architecture described below.

## What Minesport actually exports

Minesport is primarily an **asset-aware block/world exporter**. It does not need a separate model database for every mod. It resolves the assets Minecraft already ships in its JARs, mod JARs, and resource packs:

- Block IDs and block properties from region/NBT data
- `blockstates/*.json`
- `models/**/*.json`
- Model parent inheritance
- Model rotations and weighted variants
- Multipart blockstates and connection logic
- PNG textures referenced by models
- Minecraft render-time grass/foliage/water tinting (neutral plains tint when biome color data is unavailable)
- Resource-pack overrides
- Mod namespaces found inside mod JARs
- Polymer-style model-only blocks where the required model can be inferred

The exporter can therefore handle many mods automatically when they use Minecraft's normal resource/model system, without Minesport having to know the mod's gameplay code.

## Mod and loader compatibility

Compatibility is **asset-format based**, not a hardcoded list of individual mods. A mod can be compatible even if it is not named below, provided its block assets follow formats Minesport understands.

| Loader / source | Status | What is supported |
|---|---|---|
| **Vanilla Minecraft** | Supported | Models, blockstates, parents, textures from the installed Minecraft JAR |
| **Fabric** | Supported | Fabric mod JAR metadata plus `assets/<namespace>/...` blockstates, models and textures |
| **Quilt** | Supported | Native `quilt.mod.json` and Fabric-compatible `fabric.mod.json` asset layouts |
| **Forge** | Supported | Modern Forge `META-INF/mods.toml` mods using normal `assets/<namespace>/...` client assets |
| **NeoForge** | Supported | Modern NeoForge JARs using the same `assets/<namespace>/...` resource layout |
| **Polymer-based Fabric content** | Supported / best effort | Model-only Polymer blocks using discoverable `assets/<namespace>/models/block/...` assets |
| **Resource packs** | Supported | Pack assets can override the normal resolver chain and are applied with highest priority |
| **Data packs** | Partial | Block tags are readable; gameplay/data-pack behavior is not simulated |

### Important: "supported" does not mean every mod is perfect

Minesport resolves **rendering assets**, not arbitrary mod code. A mod is a strong candidate for correct export when its visible block geometry is represented by ordinary Minecraft blockstate/model JSON and PNG textures.

Mods are likely to need fallback geometry or special handling when they depend on:

- Runtime-generated geometry
- Custom renderers / shader-driven rendering
- Java-only model generation
- Dynamic block entities
- Animated or stateful entity/block renderers
- Geometry that exists only after the game executes mod code
- Assets stored in an unusual format instead of Minecraft resource locations

Minesport does **not** execute a mod's gameplay code just to obtain a mesh.

## Known limitations

The following areas are not currently equivalent to rendering the world inside Minecraft:

- Vanilla chests use their entity atlas, separate base/lid geometry and Blender lid-rig metadata. Other block entities such as signs and banners may still use fallback geometry.
- Fluids are not exported as full Minecraft fluid rendering.
- Biome-dependent tinting is not fully reproduced.
- Custom/dynamic renderers can fall back when no static model is available.
- Legacy Forge formats such as pre-1.13 `mcmod.info` are not supported by the Forge resolver.
- Polymer inference is best-effort and depends on a discoverable model following the resolver's naming conventions.
- Data packs are used for supported block-tag information, not for executing arbitrary data-pack behavior.

## Export formats

### glTF 2.0 — recommended

- Embedded textures
- Native top-left Minecraft UVs, nearest filtering and alpha-tested cutouts for multipart models
- Suitable for Blender and modern 3D pipelines
- Post-processing normalizes glTF texture samplers

### OBJ + MTL

- Traditional OBJ geometry
- Companion MTL material file
- Chest base/lid parts remain separate so Blender can rig the lid
- Useful for simple interchange workflows

Export object modes:

- **Grouped** — group geometry by block/geometry type
- **Individual blocks** — keep blocks as separate export objects where supported
- **Merged** — combine exported geometry into a merged object

## Selection

The native UI supports:

- **Box selection** — choose X/Z bounds from the map
- **Bubble selection** — choose a center and X/Y/Z radius; the engine applies an ellipsoid filter
- Manual coordinate entry
- Automatic world-bound detection from region files
- 2D world-map navigation
- Cached 2D heightmaps that invalidate when the save's region files change
- Middle-mouse panning
- Scroll-wheel zoom
- Live OpenGL 3D preview hosted inside the Fyne window on Windows
- View controls overlaid at the top-right of either viewport

3D controls:

- `W` / `A` / `S` / `D` — fly
- `Space` / `Shift` — fly up/down
- `Ctrl` — sprint
- Middle-mouse drag — look/orbit; `Shift` + middle-mouse pans
- Scroll — move forward/back; while holding `W`, `A`, `S`, or `D`, changes flight speed by 10% per step
- Hold `E` while scrolling to resize a selection
- Left click — set point A / confirm; right click — set point B
- `C` — clear the selection; `F` — fit/reset the camera
- `F6` — fit the 2D map or center the 3D camera, whichever view is active
- `F8` — save the current 3D view to `Pictures/Minesport`
- `Esc` — open the Minecraft-style controls menu

The status bar shows **Preparing 2D map…** while a first map is generated. Later openings use the cache until `level.dat` or a region file changes.

Minesport exports one block unit as one metre. Non-full Minecraft models retain their real game dimensions—for example, a dirt path is intentionally 15/16 m high. The Blender translator sets metric units and uses Closest filtering so Minecraft textures remain crisp; using **Open with… → Blender** enables the translator before import automatically.

## Safety

Minesport copies the selected world to a temporary working directory before reading region data. The original save is not modified by the exporter.

## Requirements

- Windows 10/11 x64, Linux, or macOS
- Java 22+
- Go 1.26+
- A Minecraft installation containing the matching Minecraft client JAR for best vanilla geometry resolution
- The target world's `level.dat` and `.mca` region files

For modded worlds, keep the relevant instance's **mods folder** available. Minesport attempts to locate it automatically and can also use configured resource/data-pack paths.

## Building

### Windows

```text
build.bat
```

PowerShell users can also run the repository build script directly:

```powershell
.\build.ps1
```

Windows installer packaging is opt-in. NSIS is the default EXE packager; the MSI can be built alongside it, and Inno Setup remains available explicitly:

```powershell
.\build.ps1 --build-installer       # NSIS EXE
.\build.ps1 --build-installer-all   # NSIS EXE + WiX MSI
.\build.ps1 --build-installer-msi   # WiX MSI only
.\build.ps1 --build-installer-inno  # optional Inno Setup EXE
```

Every Windows build also stages the standalone application at `dist\source\Minesport.exe`. The installer projects consume this same file, so the loose executable, NSIS package, Inno package, and MSI cannot accidentally package different wrapper builds.

The MSI project is pinned to WiX Toolset 7.0.0 and explicitly accepts the WiX 7 EULA. Building it requires the .NET SDK 6 or newer; the SDK and WiX extensions restore automatically through `dotnet build`. Review the [WiX Open Source Maintenance Fee terms](https://docs.firegiant.com/wix/osmf/) before building the MSI for a revenue-generating organization.

The current Windows builds require Windows 10 or 11 x64. Windows 7 is not supported by the Go toolchain used by Minesport.

### Linux / macOS

```bash
chmod +x build.sh
./build.sh
```

### Manual build

```bash
cd engine
./gradlew jar

cd ../bridge
./gradlew jar

cd ../wrapper
go mod tidy
go build -o minesport .
```

The repository's build pipeline produces three components:

1. **Fabric bridge mod** — captures Minecraft-side asset/geometry information when required.
2. **Java engine** — reads worlds, resolves assets and writes OBJ/glTF.
3. **Go/Fyne wrapper** — native desktop UI and 3D tooling connected to the Java engine through IPC.

## Running

Recommended native UI:

```bash
cd wrapper
./minesport
```

Java development UI:

```bash
cd engine
./gradlew run
```

## Architecture

```text
Minesport/
├── bridge/       Fabric-side bridge and asset extraction
├── engine/       Java exporter core
│   ├── nbt/      NBT reader
│   ├── region/   Anvil/MCA world reader
│   ├── model/    Blockstate/model representation
│   ├── resolver/ Vanilla + Fabric + Quilt + Forge/NeoForge + Polymer + resource packs
│   └── export/   OBJ/glTF geometry and material exporters
└── wrapper/      Go/Fyne desktop UI
    ├── ipc/      Java engine communication
    ├── launcher/ Minecraft launcher/world discovery
    ├── ui/      World picker, map and export controls
    └── viewer/  OpenGL 3D preview
```

## Resolver priority

When exporting, Minesport builds a resolver chain so overrides can be layered instead of blindly replacing everything:

1. Configured **resource packs**
2. Matching **vanilla Minecraft JAR** assets
3. Detected **Fabric** mod assets
4. **Polymer** model-only fallback for compatible Fabric namespaces
5. Detected **Quilt** mod assets
6. Detected **Forge / NeoForge** mod assets
7. Built-in exporter fallbacks when no usable static model is found

This is why a resource pack can override a vanilla or mod model without requiring a special Minesport integration.

## Troubleshooting

### "minecraft.jar not found"

Minesport can still export blocks using fallback geometry, but vanilla and inherited model accuracy will be reduced. Make sure the matching Minecraft version is installed through your launcher.

### A mod block becomes a cube / fallback shape

Check whether the mod provides a normal:

```text
assets/<modid>/blockstates/<block>.json
assets/<modid>/models/block/<model>.json
assets/<modid>/textures/...
```

If its renderer is generated entirely at runtime, Minesport may not be able to reproduce it without dedicated support.

### A resource pack does not appear to override a model

Add the resource pack in **Settings → Advanced → Resource packs**. Packs are searched before the normal vanilla/mod resolver chain.

## Development notes

The exporter deliberately prefers Minecraft's own geometry information over maintaining a giant manually-authored model registry. The resolver layer is designed around the same resource-pack concepts Minecraft already uses, which makes support for new conventional mods much easier.

## License

See the repository license and individual dependency licenses for the applicable terms.
