# Windows runtime setup and repair

Full NSIS, Inno Setup and MSI installation now prepares the required Java and Visual C++ runtimes before reporting success. Blender remains optional. Engine-only NSIS updates retain their existing narrow contract and do not run full runtime setup.

Setup uses Microsoft's signed Visual C++ x64 redistributable and Minesport's existing SHA-256-verified Eclipse Temurin downloader. The owned Java runtime is installed under `ProgramData\Minesport\toolchains`, so an elevated setup does not accidentally prepare Java only in the administrator's personal profile. Normal users can discover this shared runtime; additional loader/tooling Java versions remain downloadable into their own caches when needed. No permanent JAVA_HOME or PATH changes are made.

The engine requires Java 22+. Setup prepares that requirement without relying on an unrelated account's installed JDK. Gradle and loader libraries are supplied/prepared by the existing embedded recipes; users do not need to install Rust, Gradle, Python or a Java compiler separately. OpenGL/graphics-driver compatibility is still a hardware requirement; setup does not replace drivers.

Windows Java archives now extract natively rather than through a fragile PowerShell command. Extraction supports cancellation, rejects escaping paths/symlinks, and stages/validates a Java/compiler pair before publishing the replacement. Both java and javac must execute successfully for a runtime to be accepted. Version probes discard inherited Java heap/agent options.

## Setup failures and repair

First setup needs internet access if dependencies are absent. Download, verification or runtime failures are logged to `ProgramData\Minesport\logs\runtime-setup.log`. NSIS/Inno offer retry or fail the required-runtime step; MSI treats it as a checked action. Silent failures return a failure status rather than claiming runtime readiness. Re-running full setup repairs a missing/damaged runtime; normal startup retains on-demand provisioning.

`minesport-engine --prepare-runtime` downloads/repairs the owned Java runtime. `minesport-engine --check-runtime` checks local Java and the embedded engine payload without downloading. The desktop executable accepts the same flags. These commands do not launch Minecraft or the GUI. The installer helper also offers an offline `-CheckOnly` mode. `MINESPORT_TOOLCHAIN_ROOT` is a process-only override for setup/diagnostics; normal installations discover ProgramData automatically.

Shared runtimes/setup caches are retained on uninstall to support repair/reinstallation. User Minecraft instances and their mods are not modified. The worker's model-baking/startup cost and approximately 1 GB RAM target are separate from dependency installation.

## Windows laptop test after merging

1. Merge the independent export PR #16, worker PR #17 and runtime-setup PR. Download the resulting Windows installer artifact (EXE or MSI); Inno is also compiled in CI.
2. Install normally, letting setup prepare dependencies. It should finish without asking you to install Java manually.
3. Run `minesport-engine.exe --check-runtime` from the installed directory. Expect a `runtimeReady` record and exit code 0.
4. Open Minesport as a normal, non-admin user. Try a small cold Fabric export, then repeat with cache. Record process working-set/private RAM and elapsed time, and retain the worker PID/heap diagnostics.
5. Disconnect internet and repeat a cached export. Fresh loader/version dependency downloads still need internet; that case should give an actionable preparation failure.
6. If setup fails, keep `runtime-setup.log` and its installer error. Re-run setup to repair rather than deleting your Minecraft installation.

CI adds native ZIP regressions, Windows PowerShell parsing, all three installer compilers, and a real Java download/offline readiness/damaged-runtime repair test using a path with spaces and no usable user Java configuration. Those results must be checked before marking the runtime PR ready.
