from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# BUG A: a verified staged installer must survive generated-cache cleanup.
update = Path("desktop/src/engine_update.rs")
replace_once(
    update,
    'fn update_root() -> PathBuf {\n    runtime::cache_root().join("engine-update")\n}\n',
    'fn update_root() -> PathBuf {\n    // A verified package is pending durable state, not disposable cache. Cache\n    // cleanup must never delete an installer while another launch is applying it.\n    runtime::data_root().join("engine-update").join("staged")\n}\n',
    "engine update durable staging root",
)
replace_once(
    update,
    '        .with_context(|| format!("create engine update cache {}", update_root.display()))?;\n',
    '        .with_context(|| format!("create engine update staging directory {}", update_root.display()))?;\n',
    "engine update staging diagnostic",
)


# BUG B: generated runtime/cache operations need an OS-visible lease, not only a
# process-local RwLock. Also serialize immutable runtime asset publication.
runtime = Path("desktop/src/runtime.rs")
replace_once(
    runtime,
    'static GENERATED_CACHE_USE: RwLock<()> = RwLock::new(());\n',
    '''static GENERATED_CACHE_USE: RwLock<()> = RwLock::new(());\n\n#[derive(Debug)]\npub(crate) struct ProcessLease {\n    _file: fs::File,\n}\n\npub(crate) struct GeneratedCacheLease {\n    _local: RwLockReadGuard<'static, ()>,\n    _process: ProcessLease,\n}\n\nstruct GeneratedCacheCleanup {\n    _local: RwLockWriteGuard<'static, ()>,\n    _process: ProcessLease,\n}\n''',
    "runtime process lease types",
)
replace_once(
    runtime,
    '''fn materialize_runtime_asset(name: &str, temporary_name: &str, bytes: &[u8]) -> Result<PathBuf> {\n    if bytes.is_empty() {\n        bail!("embedded runtime asset {name} is empty");\n    }\n    let root = data_root().join("runtime");\n''',
    '''fn materialize_runtime_asset(name: &str, temporary_name: &str, bytes: &[u8]) -> Result<PathBuf> {\n    if bytes.is_empty() {\n        bail!("embedded runtime asset {name} is empty");\n    }\n    let _publish_lease = acquire_process_lease(\n        "runtime-assets",\n        name,\n        Duration::from_secs(30),\n    )?;\n    let root = data_root().join("runtime");\n''',
    "runtime asset publication lease",
)
old_cache = '''pub(crate) fn acquire_generated_cache_lease() -> Result<RwLockReadGuard<'static, ()>> {\n    GENERATED_CACHE_USE\n        .read()\n        .map_err(|_| anyhow!("Minesport generated-cache lease lock is poisoned"))\n}\n\nfn acquire_generated_cache_cleanup() -> Result<RwLockWriteGuard<'static, ()>> {\n    match GENERATED_CACHE_USE.try_write() {\n        Ok(guard) => Ok(guard),\n        Err(TryLockError::WouldBlock) => {\n            bail!(\n                "Minesport generated cache is currently in use; stop runtime preparation before clearing it"\n            )\n        }\n        Err(TryLockError::Poisoned(_)) => {\n            bail!("Minesport generated-cache cleanup lock is poisoned")\n        }\n    }\n}\n'''
new_cache = '''pub(crate) fn acquire_generated_cache_lease() -> Result<GeneratedCacheLease> {\n    let local = GENERATED_CACHE_USE\n        .read()\n        .map_err(|_| anyhow!("Minesport generated-cache lease lock is poisoned"))?;\n    let process = acquire_shared_process_lease("generated-cache", "use")?;\n    Ok(GeneratedCacheLease {\n        _local: local,\n        _process: process,\n    })\n}\n\nfn acquire_generated_cache_cleanup() -> Result<GeneratedCacheCleanup> {\n    let local = match GENERATED_CACHE_USE.try_write() {\n        Ok(guard) => guard,\n        Err(TryLockError::WouldBlock) => {\n            bail!(\n                "Minesport generated cache is currently in use; stop runtime preparation before clearing it"\n            )\n        }\n        Err(TryLockError::Poisoned(_)) => {\n            bail!("Minesport generated-cache cleanup lock is poisoned")\n        }\n    };\n    let Some(process) = try_acquire_exclusive_process_lease("generated-cache", "use")? else {\n        bail!(\n            "Minesport generated cache is currently in use by another process; stop runtime preparation before clearing it"\n        );\n    };\n    Ok(GeneratedCacheCleanup {\n        _local: local,\n        _process: process,\n    })\n}\n\npub(crate) fn acquire_process_lease(\n    namespace: &str,\n    name: &str,\n    timeout: Duration,\n) -> Result<ProcessLease> {\n    let path = process_lock_path(namespace, name)?;\n    let file = open_process_lock(&path)?;\n    let deadline = Instant::now() + timeout;\n    loop {\n        match file.try_lock() {\n            Ok(()) => return Ok(ProcessLease { _file: file }),\n            Err(std::fs::TryLockError::WouldBlock) => {\n                if Instant::now() >= deadline {\n                    bail!(\n                        "timed out waiting for Minesport process lease {}",\n                        path.display()\n                    );\n                }\n            }\n            Err(std::fs::TryLockError::Error(error)) => {\n                return Err(error)\n                    .with_context(|| format!("acquire Minesport process lease {}", path.display()));\n            }\n        }\n        thread::sleep(Duration::from_millis(25));\n    }\n}\n\nfn acquire_shared_process_lease(namespace: &str, name: &str) -> Result<ProcessLease> {\n    let path = process_lock_path(namespace, name)?;\n    let file = open_process_lock(&path)?;\n    file.lock_shared()\n        .with_context(|| format!("acquire shared Minesport process lease {}", path.display()))?;\n    Ok(ProcessLease { _file: file })\n}\n\nfn try_acquire_exclusive_process_lease(\n    namespace: &str,\n    name: &str,\n) -> Result<Option<ProcessLease>> {\n    let path = process_lock_path(namespace, name)?;\n    let file = open_process_lock(&path)?;\n    match file.try_lock() {\n        Ok(()) => Ok(Some(ProcessLease { _file: file })),\n        Err(std::fs::TryLockError::WouldBlock) => Ok(None),\n        Err(std::fs::TryLockError::Error(error)) => Err(error)\n            .with_context(|| format!("acquire exclusive Minesport process lease {}", path.display())),\n    }\n}\n\nfn process_lock_path(namespace: &str, name: &str) -> Result<PathBuf> {\n    for (label, value) in [("namespace", namespace), ("name", name)] {\n        if value.is_empty()\n            || !value.bytes().all(|byte| {\n                byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_')\n            })\n        {\n            bail!("unsafe Minesport process-lock {label}: {value:?}");\n        }\n    }\n    Ok(data_root()\n        .join("locks")\n        .join("runtime")\n        .join(namespace)\n        .join(format!("{name}.lock")))\n}\n\nfn open_process_lock(path: &Path) -> Result<fs::File> {\n    if let Some(parent) = path.parent() {\n        fs::create_dir_all(parent)\n            .with_context(|| format!("create Minesport process-lock directory {}", parent.display()))?;\n    }\n    fs::OpenOptions::new()\n        .read(true)\n        .write(true)\n        .create(true)\n        .open(path)\n        .with_context(|| format!("open Minesport process lock {}", path.display()))\n}\n'''
replace_once(runtime, old_cache, new_cache, "generated cache cross-process lease")


# Serialize the complete cache-miss -> source prepare -> Gradle build -> publish
# transaction for one loader/version and make it participate in cache cleanup.
bridge = Path("desktop/src/bridge_cli.rs")
replace_once(
    bridge,
    '    path::{Path, PathBuf},\n};\n',
    '    path::{Path, PathBuf},\n    time::Duration,\n};\n',
    "bridge duration import",
)
replace_once(
    bridge,
    '''    let destination = compiled_bridge_path(family, &version);\n    let fingerprint_path = compiled_bridge_fingerprint_path(&destination);\n''',
    '''    let _cache_lease = runtime::acquire_generated_cache_lease()?;\n    let build_key = format!(\n        "{}-{}",\n        family.label().to_ascii_lowercase(),\n        safe_version(&version)\n    );\n    let _build_lease = runtime::acquire_process_lease(\n        "bridge-build",\n        &build_key,\n        Duration::from_secs(20 * 60),\n    )\n    .with_context(|| {\n        format!(\n            "coordinate {} Export Worker build for Minecraft {version}",\n            family.label()\n        )\n    })?;\n\n    let destination = compiled_bridge_path(family, &version);\n    let fingerprint_path = compiled_bridge_fingerprint_path(&destination);\n''',
    "bridge build transaction lease",
)
