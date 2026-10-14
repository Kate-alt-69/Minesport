# Minecraft capture worker memory

The reported problem is approximately 3 GB in a Java process during a tiny cold Fabric export with 3–6 building mods, probably Minecraft 1.21.10. No visible Minecraft window appeared. That is a worker/startup problem in addition to the export memory reductions in [PR #16](https://github.com/Kate-alt-69/Minesport/pull/16).

Hiding the GLFW window does not skip client mod initialization, resource loading, model baking or graphics allocations. Current namespace scope filters the dump after startup; it does not prevent unrelated mods from initializing.

## Gap fixed

The direct Fabric/Quilt 1.21.10 path already sets a 512 MiB heap and 384 MiB metaspace cap. Other supported recipes launch runClient through Gradle. Limits on the wrapper JVM previously did not constrain the child Minecraft JVM, and inherited JAVA_TOOL_OPTIONS only constrained processor count.

The isolated workspace now gets an init script that normalizes runClient's managed JVM options immediately before execution. It removes conflicting heap/processor/metaspace settings, clears an oversized initial heap, and sets 512 MiB max heap, 384 MiB max metaspace and two active processors. Non-managed model/loader JVM options remain intact. Other JavaExec tasks are unaffected. This does not change any settings or files in the user's normal Minecraft instance.

The dedicated worker-memory workflow runs actual Gradle child JVMs under Java 17, 21 and 25. Its fixture requests a 3 GiB heap and 2 GiB initial heap before the policy overrides them. It verifies the child's actual max heap, processor count, metaspace flag, preservation of a non-managed option and unchanged limits in an unrelated task.

## Process identity evidence

Desktop diagnostics now record the loader, version, direct/Gradle launch path and launcher PID. Bridges log their own PID, actual max heap, effective processor count and memory-related flags. The bridge notice is preserved in desktop diagnostics after successful capture before the private workspace is removed. For Gradle launches, the launcher's PID and Minecraft's PID differ.

These limits concern Java heap/metaspace, not total process RAM. Graphics, textures, native allocations, threads, code cache and Gradle overhead also count. If the actual 1.21.10 direct worker reports a 512 MiB heap yet still reaches 3 GB resident memory, native/resource allocation needs profiling; the heap fix alone will not explain or solve it. The user's observed installed build has not yet been identified.

## Remaining cold-start work

Neither the heap policy nor hiding a window makes capture data-only. Needed follow-ups are exact block/state capture, known-safe UI-only mod exclusions with required dependency closure, model/resource override checks, worker-only client startup hooks, and cached direct launch for additional recipes. A cold export still loads/bakes Minecraft client resources regardless of how small the selected area is.

Mod names are not required from the user. Exclusions must be established automatically from the selected block data, loader metadata, required dependency closure and verified resource/model behavior. Client-only metadata is not proof that geometry/resources are irrelevant. Keep unknown mods, needed libraries, nested dependencies, mixins and model/texture providers until verified. Building/block-mod compatibility and exported shapes remain correctness constraints. This change does not yet implement broad mod pruning or prove the approximately 1 GB combined-process target.
