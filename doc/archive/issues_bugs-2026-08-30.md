# Minesport Engine + UI Bug Audit

> **Audit target:** `main` at `df451a675a4a1e8b8e5588ab4acee7bd5cc77795`  
> **Audit date:** 2026-08-30  
> **Scope:** active Rust/Slint desktop/UI source under `desktop/` and the complete Java engine source under `engine/src/main/java/dev/kastrick/minesport/`. Archived Go/Fyne code is intentionally excluded from active-runtime ratings.

This is an exhaustive **static source audit**, not a claim that runtime testing can never discover another bug. Findings below are limited to defects, high-confidence failure risks, observed regressions, and release-feature mismatches supported by the current source. Cosmetic preferences and speculative feature ideas are deliberately excluded.

## Severity scale

| Severity | Meaning |
|---|---|
| **Critical** | Can disable a core Minesport pipeline for the rest of the session, wedge a subsystem, or make the app effectively unusable without restart/recovery. |
| **High** | Can lose world data, produce materially wrong exports, break a supported world/loader path, corrupt durable state, or repeatedly fail a major workflow. |
| **Medium** | Wrong output/behavior in narrower cases, significant reliability/performance problem, or failure with a practical workaround. |
| **Low** | Dead/stale code, diagnostic drift, or maintenance debt that is unlikely to break a normal export by itself. |

---

# P0 — Core pipeline blockers

## BUG-001 — Engine IPC death has no recovery path

**Severity:** Critical  
**Area:** Desktop ↔ Java engine IPC  
**Files:** `desktop/src/ipc.rs`, `desktop/src/app.rs`

The desktop starts an isolated `--engine-worker` process and treats stdout closure/read failure as `EngineEvent::ReadEnded`. Once that backend or its Java child exits, the desktop has no automatic restart/rebind path for the existing `Engine` handle.

This matches the observed failure class where Minesport reported an IPC read ending/closed pipe. A single backend death can therefore leave the UI alive while the actual engine is permanently unavailable until the application is restarted.

**Impact:** Core export, heightmap and block-list requests can stop working for the remainder of the session.

**Fix direction:**
- Track engine lifecycle explicitly (`Starting/Ready/Failed/Restarting/Stopped`).
- On unexpected `ReadEnded`, invalidate the handle and restart the isolated backend once with bounded retry/backoff.
- Re-ping before reenabling export controls.
- Preserve a clear terminal diagnostic if restart fails.
- Add a regression test that intentionally kills the backend between two requests.

---

## BUG-002 — Runtime model capture can leave an uncancellable listener/thread on a fixed port

**Severity:** Critical  
**Area:** Runtime Model Cache / Export Worker  
**Files:** `desktop/src/registry.rs`, `desktop/src/runtime_worker.rs`

`registry::capture_once()` binds `127.0.0.1:25590` and then performs a blocking `TcpListener::accept()`. The capture is launched from a detached thread. Cancelling the worker kills/stops the Minecraft worker process, but the capture listener itself has no cancellation token, accept timeout, or guaranteed join.

If cancellation/failure happens before a connection is accepted, the detached receiver can remain blocked while still owning port `25590`.

**Impact:** Later cache builds can fail to bind, repeated model-cache attempts can wedge, and multiple Minesport instances conflict with each other.

**Fix direction:**
- Bind an ephemeral port (`127.0.0.1:0`) and pass the selected port to the worker.
- Make the listener nonblocking or use a short accept timeout.
- Check the same cancellation token while waiting.
- Keep and join the capture thread before returning from the cache job.
- Add cancel-before-connect and two-simultaneous-instance tests.

---

## BUG-003 — Runtime registry `error`/malformed packets can still produce a seemingly successful partial cache

**Severity:** High  
**Area:** Runtime Model Cache protocol  
**File:** `desktop/src/registry.rs`

Malformed wire packets are logged and ignored. A worker packet of type `error` is also only surfaced as `WorkerMessage`; it does not make the capture fail. If the stream later sends `done`, the partially collected snapshot can be written as a valid cache.

**Impact:** Minesport can cache incomplete runtime baked-model data and then reuse it, producing missing/fallback geometry while reporting the cache as ready.

**Fix direction:**
- Treat worker `error` as terminal capture failure.
- Decide whether malformed packets are fatal for schema-4 capture; for model/state packets they should normally fail closed.
- Require a valid start/header and terminal summary with expected counts before publishing `registry.data`.
- Never publish a cache after any protocol-fatal error.

---

# P1 — World-data correctness / supported-world failures

## BUG-004 — External Anvil `.mcc` chunks are silently skipped

**Severity:** High  
**Area:** Region reader / world copy  
**Files:** `engine/.../region/RegionReader.java`, `engine/.../safety/WorldCopier.java`

`RegionReader` skips chunk records whose compression byte has the external-stream flag (`compressionType >= 128`) instead of opening the matching external `.mcc` payload. `WorldCopier` also focuses on region container files and does not preserve the external payload required by those chunks.

**Impact:** Oversized modern/modded chunks can disappear from exports. Blocks, block entities, entities or ticks may be omitted without the export necessarily failing.

**Fix direction:**
- Implement external-chunk resolution to `c.<chunkX>.<chunkZ>.mcc` according to the Anvil external stream flag.
- Copy required `.mcc` files into the temporary world snapshot.
- Fail loudly if a referenced external chunk is missing/corrupt instead of silently skipping it.
- Add a fixture containing an external chunk.

---

## BUG-005 — Legacy `.mcr` support is internally contradictory

**Severity:** High  
**Area:** Legacy region support  
**Files:** `engine/.../safety/WorldCopier.java`, `engine/.../IpcMode.java`, `engine/.../region/HeightmapGenerator.java`

The safe-copy layer deliberately recognizes both `.mca` and `.mcr`, and the Rust storage discovery also accepts both. Downstream Java export and heightmap enumeration still filter specifically for `.mca`.

Result: a legacy world can be considered valid/copyable and then fail with “No .mca region files found” or produce no heightmap.

**Impact:** Entire pre-Anvil/legacy-region world paths fail despite partial advertised support.

**Fix direction:**
- Centralize region-file enumeration (`.mca` + `.mcr`) in one helper.
- Use it consistently in world copy, export, heightmap, entities and tests.
- Add a legacy `.mcr` fixture to engine tests.

---

## BUG-006 — Legacy block metadata is not translated into modern render-state properties

**Severity:** High  
**Area:** Minecraft 1.5–1.12 compatibility  
**File:** `engine/.../region/LegacyBlockIds.java`

Numeric block IDs are mapped to modern names, but most legacy metadata remains only as `legacy_id` / `legacy_data`. It is not decoded into modern logical properties such as `facing`, `axis`, slab `type`, door `half/open/hinge`, rail `shape`, stair orientation, log axis, etc.

**Impact:** Legacy blocks can decode to the correct *name* while rendering with the wrong geometry/orientation/state. This is especially serious for the planned support down to Minecraft 1.5.

**Fix direction:**
- Add per-family metadata decoders for every supported legacy numeric ID that uses metadata bits.
- Produce canonical modern properties before blockstate/model resolution.
- Keep `legacy_data` only as diagnostic provenance.
- Add golden fixtures for stairs, slabs, logs, doors, rails, wool/colors and redstone-era states.

---

## BUG-007 — `MultipartResolver` spatial key collides far from spawn

**Severity:** High  
**Area:** Fence/wall/pane multipart connection resolution  
**File:** `engine/.../region/MultipartResolver.java`

`MultipartResolver` packs coordinates into only 21 bits per axis using a ±1,048,576-style offset, while normal Minecraft X/Z coordinates extend to roughly ±30,000,000 and the rest of the exporter already has a dedicated 26/12/26-bit `SpatialKey` implementation.

**Impact:** At large X/Z coordinates unrelated blocks can collide in the neighbor map, causing incorrect fence/wall/pane connections and therefore wrong exported geometry.

**Fix direction:** Delete the private multipart key function and use `export.SpatialKey.of()` everywhere.

---

## BUG-008 — Modern Overworld heightmap cache fingerprints the wrong region directory

**Severity:** High  
**Area:** 2D map cache  
**Files:** `desktop/src/ipc.rs`, `desktop/src/heightmap_cache.rs`

IPC correctly knows that modern worlds may store Overworld data under:

`dimensions/minecraft/overworld/region`

and rewrites heightmap requests to that storage root. `heightmap_cache.rs`, however, fingerprints `<world>/level.dat` and `<world>/region` only.

**Impact:** A 26.x/new-layout world can change while Minesport continues serving an old cached 2D map because the actual region files are absent from the fingerprint.

**Fix direction:** Reuse the same Overworld storage-root resolver for both request dispatch and cache fingerprinting. Include all selected region files plus `level.dat` in the cache key.

---

## BUG-009 — Heightmap generation accepts invalid scale values at the engine boundary

**Severity:** Medium  
**Area:** Heightmap IPC  
**Files:** `engine/.../IpcMode.java`, `engine/.../region/HeightmapGenerator.java`

The engine accepts a numeric `scale` from IPC without validating its range. `HeightmapGenerator` derives `512 / scale`; zero can divide by zero and excessive values can collapse dimensions to zero/invalid images.

The current desktop normally supplies sane values, but the engine contract itself is unsafe.

**Fix direction:** Clamp/reject scale in `IpcMode` before calling the generator and make `HeightmapGenerator` validate independently.

---

## BUG-010 — Heightmap generation silently swallows region/chunk failures

**Severity:** Medium  
**Area:** 2D map correctness / diagnostics  
**File:** `engine/.../region/HeightmapGenerator.java`

Bad region/chunk reads are caught and skipped with little/no surfaced diagnostic. The operation can therefore return an apparently valid image with holes/missing terrain.

**Impact:** Users can make selections against an incomplete map without knowing world data failed to decode.

**Fix direction:** Count failed regions/chunks, emit warnings through IPC, and fail when the missing fraction crosses a reasonable threshold.

---

## BUG-011 — Region decompression has no decompressed-size ceiling

**Severity:** Medium  
**Area:** Region parser stability  
**File:** `engine/.../region/RegionReader.java`

GZip/zlib chunk data is expanded into a `ByteArrayOutputStream` without a hard decompressed-size limit. Container-sector length bounds compressed input, but a highly compressible/corrupt payload can still inflate far beyond a normal chunk.

**Impact:** Corrupt or hostile region data can cause excessive heap use or OOM.

**Fix direction:** Stream through a bounded output/input wrapper and reject chunks above a generous maximum decoded NBT size.

---

# P1 — Resolver/model correctness

## BUG-012 — Unmatched blockstate variants silently fall back to the first variant

**Severity:** High  
**Area:** Model resolution  
**File:** `engine/.../model/BlockState.java`

When no variant key matches a block’s property map, the resolver returns the first variant instead of returning unresolved.

**Impact:** Upstream state-decoding bugs, unsupported properties, legacy metadata mistakes and mod incompatibilities can turn into believable but incorrect geometry instead of a visible fallback/error. This hides real bugs.

**Fix direction:**
- Only use the empty-string/default variant when it actually exists.
- Otherwise return no application and log the unmatched state once.
- Let runtime/fallback resolution handle genuinely unknown states.

---

## BUG-013 — Multipart “solid neighbor” detection is a fragile name blacklist

**Severity:** Medium  
**Area:** Fence/wall/pane connectivity  
**File:** `engine/.../region/MultipartResolver.java`

Connectivity relies on a small `KNOWN_NON_SOLID`/name heuristic instead of actual collision/face solidity. Many partial vanilla blocks and arbitrary modded blocks can therefore be treated as solid neighbors.

**Impact:** Fences, panes and walls can connect to blocks Minecraft would not connect them to.

**Fix direction:** Use resolved full-face geometry/collision semantics or captured runtime state metadata, with conservative “unknown = do not force-connect” behavior.

---

## BUG-014 — Resolver ZIP/JAR handles are not closed by the production export lifecycle

**Severity:** High  
**Area:** Asset resolver lifecycle  
**Files:** `engine/.../IpcMode.java`, `resolver/VanillaResolver.java`, `FabricResolver.java`, `ForgeResolver.java`, `QuiltResolver.java`, `ResourcePackResolver.java`, `ResolverChain.java`

The individual resolvers expose `close()` methods because they retain `ZipFile` handles. `ResolverChain` is not `AutoCloseable` and the production IPC export/preview paths do not close the constituent resolvers after use. `ResolverChain.CURRENT` also retains the most recent chain on the request thread.

**Impact:** Repeated exports can leak file handles. On Windows the last-used Minecraft/mod/resource-pack JARs may remain locked until the Java engine exits, preventing updates/deletion and increasing resource usage.

**Fix direction:**
- Make `AssetResolver` optionally `AutoCloseable` or make `ResolverChain implements AutoCloseable`.
- Close chains in `finally` / try-with-resources after each operation.
- Clear the `ResolverChain.CURRENT` ThreadLocal.
- Add a repeated-export handle-leak test on Windows.

---

## BUG-015 — Only the first JAR claiming a namespace is visible to a loader resolver

**Severity:** Medium  
**Area:** Mod resource layering  
**Files:** `FabricResolver.java`, `ForgeResolver.java`, `QuiltResolver.java`

Each resolver stores a single `ZipFile` per namespace using `namespaceJars.putIfAbsent(...)`. Minecraft’s resource system can layer resources from multiple mod JARs under the same namespace (including `minecraft`). Minesport silently ignores later JARs for that namespace.

**Impact:** Mod-provided overrides/companion resources can be missing even though the relevant JAR is installed.

**Fix direction:** Store an ordered list of sources per namespace and search them according to loader/resource-pack priority semantics.

---

## BUG-016 — Forge/NeoForge resolver rejects any loader JAR without `META-INF/mods.toml`

**Severity:** High  
**Area:** Forge/NeoForge mod discovery  
**File:** `engine/.../resolver/ForgeResolver.java`

The shared resolver accepts a JAR only when `META-INF/mods.toml` exists and contains at least one parsed `[[mods]]` entry. Any otherwise valid loader JAR using another metadata filename/layout is closed before its `assets/` namespaces are scanned.

**Impact:** A valid Forge/NeoForge installation can report zero detected mods and fall back to cubes/missing textures even though standard `assets/<namespace>/...` data is present.

**Fix direction:** Loader-aware metadata detection should accept all metadata filenames/layouts supported by the matching Export Worker/version. Asset discovery should also be able to index standard `assets/` from a positively selected loader instance without requiring this one hand-written TOML form.

---

## BUG-017 — Hand-written Fabric/Quilt metadata parsers are not real JSON parsers

**Severity:** Medium  
**Area:** Loader mod discovery  
**Files:** `FabricResolver.java`, `QuiltResolver.java`

Fabric and Quilt metadata are parsed with substring/quote scanning and manual brace counting rather than Gson (already a dependency). Escaped quotes/strings or unusual valid formatting can confuse these parsers.

**Impact:** Valid mod metadata can be misread or a wrong `id/name/version` can be selected, affecting diagnostics and namespace ownership.

**Fix direction:** Parse `fabric.mod.json` and `quilt.mod.json` with Gson data structures.

---

## BUG-018 — Texture-animation discovery drops coordinate-dependent weighted variants

**Severity:** Medium  
**Area:** Blender animation metadata  
**Files:** `model/BlockState.java`, `export/TextureAnimationExporter.java`

Weighted variants are selected deterministically using block coordinates, but `TextureAnimationExporter` deduplicates its scan only by `blockId + state properties`. It inspects the first coordinate for that logical state and skips all later coordinates.

**Impact:** If weighted variants use different animated textures/models, the exported geometry can contain them while the `.minesport.json` animation descriptors omit some materials.

**Fix direction:** Deduplicate on the resolved model/material set, or include the coordinate-derived weighted-variant choice in the discovery key.

---

## BUG-019 — Dormant geometry-template cache is unsafe for coordinate-dependent variants

**Severity:** Medium (dormant)  
**Area:** Geometry optimization  
**Files:** `export/GeometryTemplateCache.java`, `model/BlockState.java`

`GeometryTemplateCache` keys only on block ID + canonical state properties. That is insufficient for states whose weighted model selection depends on world coordinates. If this cache is wired into the production path unchanged, the first chosen model can be cloned to every occurrence.

**Impact:** Currently appears to be optimization scaffolding rather than an active production path, but it is a ready-made correctness regression if enabled.

**Fix direction:** Cache the stable candidate set and perform coordinate selection at instantiation, or include the deterministic variant-selection bucket in the key.

---

# P1 — Runtime/build/cache integrity

## BUG-020 — Embedded engine/worker repair trusts file length instead of content

**Severity:** High  
**Area:** Embedded runtime materialization  
**File:** `desktop/src/runtime.rs`

`materialize_runtime_asset()` rewrites an existing embedded runtime file only when its byte length differs from the embedded asset length.

**Impact:** A corrupted or altered JAR with the same length is accepted indefinitely. Minesport can repeatedly launch a bad engine/worker while believing its embedded runtime is healthy.

**Fix direction:** Compare SHA-256 (or bytes) and atomically replace on mismatch.

---

## BUG-021 — Compatibility Export Worker cache is reused by filename existence only

**Severity:** High  
**Area:** Historical-version worker cache  
**File:** `desktop/src/bridge_cli.rs`

The compiled worker path is keyed by loader/version, and `ensure_bridge()` immediately reuses it if the file exists. It does not verify the Minesport source revision, compatibility recipe revision, worker artifact version, or content hash.

**Impact:** After Minesport/recipe updates, an old broken worker can continue being reused forever unless the user manually clears the cache.

**Fix direction:** Persist a build manifest/fingerprint next to each compiled worker and rebuild whenever source + recipe + toolchain inputs change.

---

## BUG-022 — Gradle worker artifact selection can pick the wrong JAR

**Severity:** Medium  
**Area:** Compatibility worker build  
**File:** `desktop/src/bridge_build.rs`

After Gradle succeeds, `find_built_bridge()` collects acceptable-looking JARs, sorts them lexically and returns the first. It does not verify the expected remapped artifact name/content.

**Impact:** Builds emitting multiple non-source/non-javadoc JARs can cache/package the wrong worker artifact.

**Fix direction:** Require the exact expected Loom/remap output or inspect manifest/mod metadata before accepting an artifact.

---

## BUG-023 — Mutable bridge data defaults under `%ProgramFiles%` on Windows

**Severity:** Medium  
**Area:** Cache/data paths  
**File:** `desktop/src/runtime.rs`

`bridge_data_root()` falls back to `%ProgramFiles%/.../bridge-data` for generated/mutable bridge data. Normal non-admin users should not need write permission under Program Files.

**Impact:** Dynamic compatibility-worker generation/caching can fail with permission errors depending on install context.

**Fix direction:** Put all mutable/rebuildable worker data under the existing user cache root; reserve Program Files for immutable installed binaries only.

---

## BUG-024 — Runtime registry capture can require enormous desktop RAM before writing cache

**Severity:** High  
**Area:** Runtime Model Cache scalability  
**File:** `desktop/src/registry.rs`

Capture accumulates the runtime snapshot in in-memory maps/lists before serializing `registry.data`. Protocol limits allow very large block/variant/quad counts and packets up to 128 MiB.

**Impact:** Large modpacks can make the Rust desktop consume hundreds of MB or multiple GB and OOM during model capture even though the final Java reader is designed to be lazy/on-disk.

**Fix direction:** Stream validated block records directly to a temporary schema-4 data file and build the index incrementally. Publish atomically only after a valid `done` summary.

---

## BUG-025 — Successful runtime capture deletes sibling fingerprints for the same Minecraft version

**Severity:** Medium  
**Area:** Runtime Model Cache reuse  
**File:** `desktop/src/registry.rs`

After writing one fingerprinted registry, `prune_sibling_fingerprints()` removes other fingerprints for that Minecraft version.

**Impact:** Switching between two modpacks/instances on the same Minecraft version forces repeated expensive captures instead of reusing each environment’s own fingerprinted cache.

**Fix direction:** Keep multiple fingerprints and prune by age/size/LRU rather than deleting every sibling immediately.

---

## BUG-026 — Runtime-cache event listeners are invoked while the manager state mutex is held

**Severity:** Medium  
**Area:** Desktop concurrency  
**File:** `desktop/src/runtime_cache.rs`

`emit()` locks cache state and iterates/calls listeners without first releasing the mutex.

**Impact:** A listener that queries/mutates the cache manager can deadlock; a slow listener stalls every cache state transition under the lock.

**Fix direction:** Clone the listener handles under the mutex, release the lock, then invoke callbacks.

---

# P1 — Durable settings / desktop state

## BUG-027 — Settings writes race each other and can install stale state

**Severity:** High  
**Area:** Desktop settings persistence  
**Files:** `desktop/src/app.rs`, `desktop/src/settings.rs`

Every `persist_settings_snapshot()` spawns an independent thread. `settings::save()` uses the same fixed `.json.tmp` path, writes it, removes the current settings file, and renames the temp file.

Two rapid UI changes can therefore race on the same temporary path; an older snapshot can win after a newer one, or one writer can remove/rename the other writer’s temp/output.

**Impact:** Resource-pack order, export settings, output paths and other durable preferences can revert or fail to save.

**Fix direction:**
- Use one serialized settings writer/debouncer.
- Give each temporary file a unique name if needed.
- Atomically replace destination where supported.
- Never remove the durable file before a ready replacement exists.

---

## BUG-028 — Settings replacement has a crash window with no settings file

**Severity:** Medium  
**Area:** Desktop settings durability  
**File:** `desktop/src/settings.rs`

The save path explicitly removes the destination before renaming the temporary file. A crash/power loss/rename error between those operations can leave no settings file.

**Fix direction:** Use atomic replace/rename semantics and retain the old file until replacement succeeds.

---

# P2 — Output correctness / robustness

## BUG-029 — Sidecar/glTF post-processing rewrites files in place instead of transactionally

**Severity:** Medium  
**Area:** glTF / Minesport metadata publication  
**Files:** `export/GltfPostProcessor.java`, `FlatterMetadataExporter.java`, `BlenderMetadataExporter.java`

Generated JSON files are parsed and then rewritten directly to the final path. A write failure/crash can leave a truncated `.gltf` or `.minesport.json` after mesh generation already succeeded.

**Impact:** Finished-looking exports can become unreadable due to a post-processing failure.

**Fix direction:** Write to a sibling temporary file, fsync/close, then atomically replace.

---

## BUG-030 — Preview intentionally samples above 60k blocks without exposing that the scene is incomplete

**Severity:** Medium  
**Area:** 3D preview  
**File:** `desktop/src/preview.rs`

When the scene has more than `MAX_PREVIEW_BLOCKS = 60_000`, blocks are stride-sampled down to the cap. This is a reasonable performance strategy, but the UI does not clearly expose that the 3D preview is now a sampled approximation.

**Impact:** Users can interpret missing structures/blocks as export failures even though the renderer intentionally dropped them.

**Fix direction:** Surface “Preview sampled: N / total blocks” and ensure 2D remains authoritative. Consider chunk/LOD rendering instead of global stride sampling.

---

## BUG-031 — Mod/resource resolution can retain the last ResolverChain through ThreadLocal state

**Severity:** Medium  
**Area:** Java engine lifecycle  
**File:** `engine/.../resolver/ResolverChain.java`

Every new `ResolverChain()` assigns itself to a static `ThreadLocal`, but there is no matching clear operation. This compounds BUG-014 by ensuring the most recent resolver chain and its caches/resources remain strongly reachable for the lifetime of the IPC request thread.

**Fix direction:** Add `ResolverChain.clearCurrent()` and call it from operation `finally` blocks after metadata sidecars are complete.

---

## BUG-032 — Toolchain JDK search can abort traversal on one unreadable child

**Severity:** Medium  
**Area:** Desktop toolchain discovery  
**File:** `desktop/src/toolchain.rs`

Recursive JDK discovery uses fallible directory/entry operations in a way that can terminate a branch/search rather than skipping an unreadable/broken child.

**Impact:** A valid cached JDK can be missed, causing unnecessary redownload or a false “JDK unavailable” failure.

**Fix direction:** Treat inaccessible children as skippable and continue searching; only fail when the root itself cannot be inspected and no alternative exists.

---

# Low-severity source drift / dead code

## BUG-033 — `desktop/ui/main.slint` is a stale duplicate UI and is not compiled

**Severity:** Low  
**Area:** UI maintenance  
**Files:** `desktop/ui/main.slint`, `desktop/build.rs`

`build.rs` compiles `workbench-v3.slint`, not `main.slint`. Keeping a second large UI definition invites fixes being made in the wrong file.

**Fix direction:** Delete/archive it or replace it with an explicit comment-only redirect.

---

## BUG-034 — `selection_window.rs` is orphaned retired selector code

**Severity:** Low  
**Area:** Desktop maintenance  
**Files:** `desktop/src/selection_window.rs`, `desktop/src/main.rs`, `desktop/src/selection.rs`

The module is not included by `main.rs`. It also references selector-era APIs that are test-gated/retired in the current 2D-authoritative design.

**Fix direction:** Remove it unless there is a concrete migration plan. Do not accidentally re-enable it as-is.

---

## BUG-035 — Diagnostics still identify some 0.2.1 sessions/components as older architecture/version text

**Severity:** Low  
**Area:** Diagnostics / developer UX  
**Files:** `desktop/src/diagnostics.rs`, `engine/.../Main.java`, `engine/.../ui/TestUI.java`

There are stale `0.2.0`, `v0.3`, and Go/Fyne-era descriptions while the production desktop is Rust/Slint 0.2.1.

**Impact:** Bug reports can misidentify the running architecture/version and waste debugging time.

**Fix direction:** Generate version/architecture strings from one build-time source of truth.

---

# Incomplete 0.2.1 implementation / release-note mismatches

These are not counted as active bugs above unless current behavior contradicts an already-supported path, but they are important because the 0.2.1 release document describes them as in-development architecture.

## GAP-001 — Persistent World Cache (`wc/.../diem/.../*.chunk`) is not implemented in the current source

**Priority:** P0 before claiming 0.2.1 complete

`doc/releases/0.2.1.md` describes a persistent parsed World Cache using Minesport `.chunk` files under a `wc/<launcher>/<instance>/<world>/diem/<dimension>/` hierarchy. The audited active Rust/Java source contains no implementation matching that storage format/path.

Current export still parses copied region files directly.

**Action:** Either implement WC or rewrite the release note/status so it cannot be mistaken for shipped behavior.

---

## GAP-002 — Nether/End/custom-dimension World Cache path is not present

**Priority:** P1

The same 0.2.1 plan says Overworld, Nether and End are supported first, but the current safe-copy/export pipeline is strongly Overworld-oriented and the advertised World Cache itself is absent.

---

## GAP-003 — Legacy 1.5 support is only partial even though legacy IDs/textures exist

**Priority:** P1

The engine contains substantial legacy ID/texture/synthetic-model work, but BUG-005 and BUG-006 mean region ingestion and metadata-driven states are not yet sufficient for trustworthy 1.5-era output.

---

# Observed regressions that need permanent tests

These failures were seen during the recent 0.2.x work. Some source fixes now exist, so they should not all be reopened as active defects without reproduction; they need regression coverage.

## REG-001 — Invalid inherited `JAVA_HOME` broke runtime worker startup

Current `runtime_worker.rs`/bridge build paths sanitize Java-related environment variables before launching workers, so the original source defect appears fixed. Add a test that launches with a deliberately invalid inherited `JAVA_HOME` and verifies the resolved JDK is still used.

## REG-002 — Export completed on disk while UI/progress appeared frozen

The latest lifecycle/progress changes separate queued export/cache states, use bounded progress phases, and publish Litematica only after staging. Keep a regression test that verifies terminal `done` immediately follows publication and no older progress event can overwrite terminal state.

## REG-003 — Queued export could race cancellation

Current `main` serializes pending-export cancellation/dispatch through the same state lock and has a Rust test for terminal cancellation. Keep this as a permanent gate.

## REG-004 — Modern world reported no region folder / wrong storage root

Current Rust IPC and Java world copy have modern Overworld storage-root handling. Keep fixtures for both `<world>/region` and `<world>/dimensions/minecraft/overworld/region`; BUG-008 shows the cache side is still inconsistent.

## REG-005 — Runtime renderer/3D attachment failures from the retired embedded-window implementation

The current source no longer exposes the old child-window attach path found in earlier testing. Do not reopen that exact implementation bug unless current preview code reproduces it; test current 3D preview switching independently.

---

# Recommended fix order

1. **BUG-001 / BUG-002 / BUG-003** — make IPC + runtime capture recoverable and impossible to wedge.
2. **BUG-004 / BUG-005 / BUG-006 / BUG-007 / BUG-008** — stop losing/misreading world data.
3. **BUG-012 / BUG-014 / BUG-016** — stop silently producing wrong mod/legacy geometry and leaking JAR handles.
4. **BUG-020 / BUG-021 / BUG-024 / BUG-027** — harden runtime/cache/settings integrity.
5. Remaining Medium findings.
6. Low dead-code/diagnostic cleanup.
7. Only then treat World Cache/legacy expansion as safe to build on.

---

# Audit coverage

## Active desktop/UI reviewed

- `desktop/src/app.rs`
- `desktop/src/aux_windows.rs`
- `desktop/src/bin/bridge-prepare.rs`
- `desktop/src/blender.rs`
- `desktop/src/bridge_build.rs`
- `desktop/src/bridge_cli.rs`
- `desktop/src/bridge_compat.rs`
- `desktop/src/bridge_family.rs`
- `desktop/src/bridge_java.rs`
- `desktop/src/diagnostics.rs`
- `desktop/src/error_reporter.rs`
- `desktop/src/heightmap_cache.rs`
- `desktop/src/ipc.rs`
- `desktop/src/launcher.rs`
- `desktop/src/main.rs`
- `desktop/src/preview.rs`
- `desktop/src/preview_picking.rs`
- `desktop/src/registry.rs`
- `desktop/src/runtime.rs`
- `desktop/src/runtime_cache.rs`
- `desktop/src/runtime_worker.rs`
- `desktop/src/selection.rs`
- `desktop/src/selection_window.rs`
- `desktop/src/settings.rs`
- `desktop/src/toolchain.rs`
- `desktop/src/viewer_camera.rs`
- `desktop/src/viewer_screenshot.rs`
- `desktop/src/viewer_selection.rs`
- `desktop/src/world_context.rs`
- `desktop/src/world_picker.rs`
- `desktop/ui/asset-editor.slint`
- `desktop/ui/main.slint`
- `desktop/ui/minesport-loader.slint`
- `desktop/ui/workbench-v3.slint`
- `desktop/build.rs`

## Java engine reviewed

- Top-level IPC/main/geometry classes
- Complete `datapack/`
- Complete `model/`
- Complete `nbt/`
- Complete `region/`
- Complete `safety/`
- Complete `resolver/`
- Complete `export/`
- Java `ui/TestUI.java`

## Deliberately excluded

- Archived Go/Fyne implementation: not part of the active Rust/Slint runtime.
- Plugin SDK: intentionally **not proposed or added**.
- Pure feature wishes that do not represent a current defect.
