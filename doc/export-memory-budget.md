# Export memory target

The reported starting footprint is approximately 4 GB for Minesport and its Minecraft capture instance together. The target is approximately 1 GB **total process resident memory**, not a 1 GB Java heap. This is an engineering target; no full-application measurement currently establishes it.

## Budget to design toward

The two expensive phases should stay sequential. The existing runtime worker already exits before geometry export is dispatched.

| Phase | Desktop and relays | Export engine | Minecraft worker | Reserve | Target total |
|---|---:|---:|---:|---:|---:|
| Capture | 150 MiB | 100 MiB idle | 650 MiB | 124 MiB | 1,024 MiB |
| Geometry export | 150 MiB | 650 MiB | 0 | 224 MiB | 1,024 MiB |

These are proposed allocations, not measurements or enforced limits. JVM heap, metaspace, JIT code, native allocations, stacks and graphics resources all contribute to resident memory. A 512 MiB worker heap does not mean a 512 MiB worker process. Large modpacks may exceed the capture budget during registration/resource baking alone, so universal 1 GB compatibility cannot be promised by reducing heap flags.

## First implementation: glTF working memory

The exporter previously retained the entire binary payload in ByteArrayOutputStream and copied it with toByteArray before writing. It also allocated whole-material vertex arrays, boxed indices and whole-accessor ByteBuffers. Welding keys were formatted even with welding disabled.

The binary payload now streams to a 64 KiB buffered file writer. Position, normal, UV and index data write directly to that stream. Each material is emitted in primitives of at most 4,096 quads, bounding temporary vertex/index/welding allocations while keeping the same mesh and node. Welding remains within each primitive, with the existing key precision; boundary vertices may be repeated between primitives. This can increase primitive count and reduce compression slightly, but preserves geometry, materials, UVs and object origins.

The payload is still written inside the engine's existing staged AtomicExportBundle. I/O errors propagate and prevent successful publication. Buffer offsets/lengths use long values so streaming does not inherit the old byte-array size limit.

This does **not** make the full export bounded-memory: decoded block lists, spatial/grouping indexes, generated quad lists, FLATTER candidates, JSON and embedded textures remain proportional to the selection. OBJ and Litematic do not use this new binary writer.

## Evidence and checks

GltfStreamingTest verifies 4,097 quads crossing a primitive boundary, both with and without welding, including positions, normals, UVs, triangle indices, materials and one mesh identity. It also exports 500,000 synthetic quads to a 76,000,000-byte binary using a forked JVM with a **48 MiB heap limit**.

The synthetic fixture reuses one quad to isolate exporter working memory. It is not a 500,000-block world and does not include Minecraft or the desktop. The export-memory workflow compares the same fixture against the audited exporter at c825c539f3efe820ac877cde6064c43af9f3291e, reporting per-process peak RSS and elapsed time. The baseline gets a 768 MiB heap limit; the optimized fixture gets 48 MiB, so the report must retain those settings when interpreting measured differences.

Measured on Ubuntu CI / Temurin Java 22 in [workflow run 37754848004](https://github.com/Kate-alt-69/Minesport/actions/runs/37754848004):

| Same 500,000-quad fixture | Original exporter | Streaming exporter |
|---|---:|---:|
| JVM heap limit | 768 MiB | 48 MiB |
| Peak process RSS | 852,292 KiB (832 MiB) | 148,396 KiB (145 MiB) |
| Wall time | 3.70 s | 0.84 s |
| Binary output | 76,000,000 bytes | 76,000,000 bytes |

All 141 engine tests passed, including the small-heap export and boundary regression. This is one controlled synthetic run, with different heap limits, not a measured 83% reduction for the user's complete application/world.

## Worker focused on extraction

The goal is to exclude unrelated menus, overlays, audio, telemetry and gameplay work from the isolated worker, while retaining registrations, geometry, resource overrides and their dependencies. Current code only excludes existing Minesport bridges, crash-assistant and explicit Fabric/Quilt server-only mods. It does not yet provide a general dependency-aware UI-mod filter.

The next worker change should create a manifest that records why each JAR is retained or excluded. Retained roots include Minesport's bridge and loader API, selected-block providers, required dependencies and any mods/resources that can modify those models. Metadata IDs alone are insufficient: library APIs, nested JARs, provided aliases, mixins and cross-namespace resource overrides matter. Keep unclassified mods until their extraction role is established. For pure UI candidates, retain a candidate if a kept mod has a required dependency on it.

Start with the actual loader/version/modpack that produced the 4 GB observation, record baseline loaded mods/captured states/model output, then remove confirmed UI-only roots. Compare model IDs, states, quads, UVs, light metadata and errors against the full worker. Work skipped after registration should be gated by the worker flag and preserve model baking/resource readiness. Do not edit the user's normal game instance or globally disable its mods.

Selecting only a namespace currently narrows the dump **after client startup**. Selecting exact block IDs/states can reduce extraction further, but reducing resource baking and other mods' initialization requires a separate worker-loading policy or loader-specific hooks. Arbitrary mod initializers cannot be partially executed safely just by loading Minesport last.

## Remaining work toward 1 GB

1. Measure peak resident memory for each process on the user's representative world/modpack, including cold capture and cached export.
2. Replace retained whole-selection blocks/quads with section palettes and bounded batches while preserving multipart, fluids, grouping and FLATTER cell boundaries.
3. Narrow capture to selected block IDs, then verified state/dependency closures. This saves extraction, not Minecraft's entire startup resource load.
4. Reduce worker-only client startup work where model/texture readiness can be preserved. Profile Gradle child heaps on loaders without direct launch.
5. Keep preview indexes/frames bounded and avoid retaining unnecessary large previews during export.

Do not interpret these budgets as a reason to force smaller JVM heaps before the live working sets fit. A cap that causes out-of-memory errors is not a successful memory optimization.
