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

## Remaining work toward 1 GB

1. Measure peak resident memory for each process on the user's representative world/modpack, including cold capture and cached export.
2. Replace retained whole-selection blocks/quads with section palettes and bounded batches while preserving multipart, fluids, grouping and FLATTER cell boundaries.
3. Narrow capture to selected block IDs, then verified state/dependency closures. This saves extraction, not Minecraft's entire startup resource load.
4. Reduce worker-only client startup work where model/texture readiness can be preserved. Profile Gradle child heaps on loaders without direct launch.
5. Keep preview indexes/frames bounded and avoid retaining unnecessary large previews during export.

Do not interpret these budgets as a reason to force smaller JVM heaps before the live working sets fit. A cap that causes out-of-memory errors is not a successful memory optimization.
