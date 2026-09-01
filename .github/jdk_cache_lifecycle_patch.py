from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


runtime = Path("desktop/src/runtime.rs")
replace_once(
    runtime,
    '''fn acquire_shared_process_lease(namespace: &str, name: &str) -> Result<ProcessLease> {\n''',
    '''pub(crate) fn acquire_generated_cache_process_lease() -> Result<ProcessLease> {\n    acquire_shared_process_lease("generated-cache", "use")\n}\n\nfn acquire_shared_process_lease(namespace: &str, name: &str) -> Result<ProcessLease> {\n''',
    "generated-cache process-only lease helper",
)
replace_once(
    runtime,
    '    let process = acquire_shared_process_lease("generated-cache", "use")?;\n',
    '    let process = acquire_generated_cache_process_lease()?;\n',
    "generated-cache shared lease helper use",
)

toolchain = Path("desktop/src/toolchain.rs")
replace_once(
    toolchain,
    '''    check_cancelled(&cancel, "checking JDK cache")?;\n    progress(ToolchainProgress {\n''',
    '''    // Every cached-JDK lookup/install participates in the process-wide\n    // generated-cache lease so another Minesport process cannot clear the\n    // toolchain tree underneath discovery, download, verification, or extract.\n    let _generated_cache_process_lease = runtime::acquire_generated_cache_process_lease()?;\n    let toolchain_key = format!("jdk-{required}");\n    let _toolchain_lease = runtime::acquire_process_lease(\n        "toolchain",\n        &toolchain_key,\n        Duration::from_secs(10 * 60),\n    )\n    .with_context(|| format!("coordinate Minesport-managed JDK {required}"))?;\n\n    // Re-check the cache only after the per-JDK lease is held: another process\n    // may have completed this exact JDK while we were waiting.\n    check_cancelled(&cancel, "checking JDK cache")?;\n    progress(ToolchainProgress {\n''',
    "toolchain cache/install lease",
)

main = Path("desktop/src/main.rs")
replace_once(
    main,
    '''    // The legacy self-worker fallback must be just as self-contained as the\n    // installed sidecar. Provision/select the engine JDK before app::handle_cli\n    // hands --engine-worker to the IPC relay.\n    if engine_worker_mode {\n        let _ = engine_java::prepare_engine_java()?;\n    }\n''',
    '''    // The self-worker owns cached Java/toolchain files for its complete\n    // lifetime. Cache cleanup from another Minesport process must not delete\n    // the JDK after preparation while the Java engine is still running.\n    let _engine_worker_cache_lease = if engine_worker_mode {\n        Some(runtime::acquire_generated_cache_lease()?)\n    } else {\n        None\n    };\n    if engine_worker_mode {\n        let _ = engine_java::prepare_engine_java()?;\n    }\n''',
    "self-worker generated cache lifetime lease",
)

sidecar = Path("desktop/src/bin/minesport-engine.rs")
replace_once(
    sidecar,
    '''fn run_worker() -> Result<()> {\n    #[cfg(windows)]\n    let _engine_use_lease = engine_lease::acquire_engine_use_shared()?;\n    let log = diagnostics::initialize()?;\n''',
    '''fn run_worker() -> Result<()> {\n    #[cfg(windows)]\n    let _engine_use_lease = engine_lease::acquire_engine_use_shared()?;\n    // Keep all generated Java/toolchain files leased until the Java engine has\n    // exited. This prevents another Minesport process from clearing a live JDK.\n    let _generated_cache_lease = runtime::acquire_generated_cache_lease()?;\n    let log = diagnostics::initialize()?;\n''',
    "sidecar generated cache lifetime lease",
)
