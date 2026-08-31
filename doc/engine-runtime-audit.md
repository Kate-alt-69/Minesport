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
**File:** `desktop/src/ipc.rs`

The engine worker enumerates `JAVA_HOME`, PATH, launcher runtimes, and standard JDK directories, then launches `java -version` with a timeout for candidates until Java 22+ is found. This work repeats after every sidecar process restart.

**Impact:** Machines with multiple old Java installs, slow antivirus scanning, or unhealthy launchers can make engine startup/recovery noticeably slower.

**Direction:** Persist the last verified Java executable and its version/file identity, try it first on later starts, and invalidate the cache only when the executable changes or the probe fails.

---

## ENGINE-AUDIT-004 — Region input is counted before it is read

**Severity:** Low  
**Area:** Java engine / progress accounting  
**File:** `engine/src/main/java/dev/kastrick/minesport/IpcMode.java`

Export first calls `RegionReader.countSelectedChunks()` across every selected block/entity region and then makes a second pass to actually decode those regions. The count gives accurate progress, but it is an extra pass over region metadata before useful export work begins.

**Direction:** Consider returning discovered chunk totals from the normal read pipeline or using a cheap region-header estimate for progress so parsing and progress accounting share one traversal.
