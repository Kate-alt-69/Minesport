# Engine runtime audit notes

This note records engine/runtime concerns found while introducing the independently installable `minesport-engine` sidecar. These are intentionally recorded instead of being mixed into the sidecar migration, because changing export ingestion and memory layout at the same time would make regressions much harder to isolate.

## ENGINE-AUDIT-001 — Export duplicates region-file I/O before parsing

**Severity:** Medium  
**Area:** Java engine / world safety copy  
**Files:** `engine/src/main/java/dev/kastrick/minesport/IpcMode.java`, `engine/src/main/java/dev/kastrick/minesport/safety/WorldCopier.java`

`WorldCopier.copyToTemp()` copies every region file that intersects the requested X/Z bounds into a temporary world and `IpcMode.handleExport()` then opens and scans those copied files again. Even a very small selection inside a region causes the whole region file to be copied.

**Impact:** Large worlds or slow disks can spend substantial time and temporary storage on duplicate I/O before geometry work starts.

**Direction:** Keep the original-world safety boundary, but investigate read-only region snapshots, chunk-granular staging, or a bounded copy/parser pipeline so Minesport does not always write the full intersecting region before reading it.

---

## ENGINE-AUDIT-002 — Export retains the complete selected world as Java object lists

**Severity:** High  
**Area:** Java engine / export scalability  
**File:** `engine/src/main/java/dev/kastrick/minesport/IpcMode.java`

`handleExport()` accumulates blocks, block entities, entities, scheduled block ticks, and fluid ticks into `ArrayList`s for the complete selection before later selection filtering and export work.

**Impact:** Multi-million-block selections can create heavy heap usage and GC pressure and can fail from memory exhaustion even when the final output could be produced with a more compact intermediate representation.

**Direction:** Move selection rejection as close to chunk decoding as possible and replace object-heavy whole-world accumulation with chunk/section batches plus compact spatial state needed by multipart resolution and face culling.

---

## ENGINE-AUDIT-003 — Java discovery can spawn several `java -version` probes per engine start

**Severity:** Medium  
**Area:** Rust engine worker startup  
**Files:** `desktop/src/ipc.rs`, `desktop/src/engine_java.rs`, `desktop/src/toolchain.rs`

The IPC resolver can enumerate several Java candidates and launch version probes until Java 22+ is found. This can be noticeable on machines with multiple old runtimes or aggressive antivirus scanning.

**Control implemented:** Engine-worker startup now resolves/provisions JDK 22 through Minesport's shared toolchain manager first and sets process-local `JAVA_HOME` before the IPC Java resolver runs. That gives `ipc::resolve_java()` a known-good first candidate and keeps the path consistent for both the installed sidecar and the `Minesport.exe --engine-worker` fallback.

**Remaining direction:** The shared toolchain manager still checks ordinary installed JDK locations before its cache. If startup profiling shows those probes are meaningful, persist/validate the last managed runtime identity or prefer an already-valid Minesport cache before broad machine discovery.

---

## ENGINE-AUDIT-004 — Region input is counted before it is read

**Severity:** Low  
**Area:** Java engine / progress accounting  
**File:** `engine/src/main/java/dev/kastrick/minesport/IpcMode.java`

Export first calls `RegionReader.countSelectedChunks()` across every selected block/entity region and then makes a second pass to actually decode those regions. The count gives accurate progress, but it is an extra pass over region metadata before useful export work begins.

**Direction:** Consider returning discovered chunk totals from the normal read pipeline or using a cheap region-header estimate for progress so parsing and progress accounting share one traversal.

---

## ENGINE-AUDIT-005 — Engine requires Java 22+ and originally depended on machine Java state

**Severity:** High  
**Area:** Engine runtime availability  
**Files:** `desktop/src/engine_java.rs`, `desktop/src/toolchain.rs`, `desktop/src/ipc.rs`

The Java engine requires Java 22+. Previously the engine worker only searched `JAVA_HOME`, PATH, launcher runtimes, and standard JDK folders, so a clean machine could have valid Minesport binaries but still fail backend startup.

**Control implemented:** Both `minesport-engine` and the legacy self-worker fallback now call the existing Minesport toolchain manager before the IPC relay starts. It reuses a compatible installed/cached JDK or downloads Eclipse Temurin JDK 22 into Minesport's own toolchain cache, then exposes that home only to the worker process through `JAVA_HOME`.

---

## ENGINE-AUDIT-006 — Updating a live sidecar conflicts with Windows executable locking and elevation

**Severity:** High  
**Area:** Engine updater / process control  
**Files:** `desktop/src/engine_update.rs`, `installer/windows/minesport.nsi`

A healthy `minesport-engine.exe` is a running child process for most of the desktop session. Windows can prevent the installer from renaming/replacing that executable while it is live, and the per-machine NSIS installer also requires elevation. Triggering replacement during an export would therefore be both unreliable and poor UX.

**Control implemented:** Engine update discovery/download runs in the background and only stages a package after GitHub SHA-256 and Authenticode publisher verification. The staged package is applied on the next launch before `app::run()` starts the sidecar. Missing/corrupt engines continue to use the embedded self-worker fallback until that clean install point, so an update never tears down an active export merely to replace the executable.

---

## ENGINE-AUDIT-007 — Toolchain JDK metadata currently permits a missing checksum

**Severity:** Medium  
**Area:** Toolchain download integrity  
**File:** `desktop/src/toolchain.rs`

The Adoptium package metadata parser accepts an empty `checksum`, and the downloader only calls SHA-256 verification when that string is non-empty. Adoptium normally publishes the checksum, but the current code treats its absence as permission to continue.

**Impact:** The engine's new managed-Java path inherits a fail-open integrity edge case from the existing Bridge toolchain downloader. HTTPS still protects transport, but Minesport should not silently weaken from explicit package digest verification to transport-only trust.

**Direction:** Make a non-empty, valid SHA-256 checksum mandatory before downloading/extracting a JDK. Reject metadata without one and keep the old cached runtime/fallback path instead.
