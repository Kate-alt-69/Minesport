from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"{label} moved; refusing automatic patch")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


lease = Path("desktop/src/engine_lease.rs")
if lease.exists():
    raise SystemExit("desktop/src/engine_lease.rs already exists; refusing automatic patch")
lease.write_text(r'''use crate::runtime;
use anyhow::{Context, Result};
use std::{
    fs::{self, File, OpenOptions, TryLockError},
    path::{Path, PathBuf},
};

/// Process-wide ownership token backed by an OS file lock. Keeping the File
/// alive keeps the lock alive; process termination releases it automatically.
#[derive(Debug)]
pub struct Lease {
    _file: File,
}

pub fn acquire_engine_use_shared() -> Result<Lease> {
    acquire_shared_at(&engine_use_lock_path())
}

pub fn try_acquire_engine_use_exclusive() -> Result<Option<Lease>> {
    try_acquire_exclusive_at(&engine_use_lock_path())
}

pub fn try_acquire_stage_exclusive() -> Result<Option<Lease>> {
    try_acquire_exclusive_at(&stage_lock_path())
}

fn engine_use_lock_path() -> PathBuf {
    lock_root().join("engine-use.lock")
}

fn stage_lock_path() -> PathBuf {
    lock_root().join("stage.lock")
}

fn lock_root() -> PathBuf {
    runtime::data_root().join("locks").join("engine-update")
}

fn open_lock(path: &Path) -> Result<File> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create engine lock directory {}", parent.display()))?;
    }
    OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(path)
        .with_context(|| format!("open engine coordination lock {}", path.display()))
}

fn acquire_shared_at(path: &Path) -> Result<Lease> {
    let file = open_lock(path)?;
    file.lock_shared()
        .with_context(|| format!("acquire shared engine coordination lock {}", path.display()))?;
    Ok(Lease { _file: file })
}

fn try_acquire_exclusive_at(path: &Path) -> Result<Option<Lease>> {
    let file = open_lock(path)?;
    match file.try_lock() {
        Ok(()) => Ok(Some(Lease { _file: file })),
        Err(TryLockError::WouldBlock) => Ok(None),
        Err(TryLockError::Error(error)) => Err(error)
            .with_context(|| format!("acquire exclusive engine coordination lock {}", path.display())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn test_lock(name: &str) -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "minesport-engine-lock-{name}-{}-{stamp}.lock",
            std::process::id()
        ))
    }

    #[test]
    fn shared_engine_users_block_exclusive_update() {
        let path = test_lock("shared");
        let first = acquire_shared_at(&path).unwrap();
        let second = acquire_shared_at(&path).unwrap();
        assert!(try_acquire_exclusive_at(&path).unwrap().is_none());
        drop(first);
        assert!(try_acquire_exclusive_at(&path).unwrap().is_none());
        drop(second);
        assert!(try_acquire_exclusive_at(&path).unwrap().is_some());
        let _ = fs::remove_file(path);
    }

    #[test]
    fn exclusive_stage_owner_blocks_another_writer() {
        let path = test_lock("stage");
        let first = try_acquire_exclusive_at(&path).unwrap().unwrap();
        assert!(try_acquire_exclusive_at(&path).unwrap().is_none());
        drop(first);
        assert!(try_acquire_exclusive_at(&path).unwrap().is_some());
        let _ = fs::remove_file(path);
    }
}
''', encoding="utf-8")

main = Path("desktop/src/main.rs")
replace_once(
    main,
    'mod engine_java;\n#[cfg(windows)]\nmod engine_update;\n',
    'mod engine_java;\n#[cfg(windows)]\nmod engine_lease;\n#[cfg(windows)]\nmod engine_update;\n',
    "main.rs engine module anchor",
)

sidecar = Path("desktop/src/bin/minesport-engine.rs")
replace_once(
    sidecar,
    '#[allow(dead_code)]\n#[path = "../engine_java.rs"]\nmod engine_java;\n',
    '#[allow(dead_code)]\n#[path = "../engine_java.rs"]\nmod engine_java;\n#[allow(dead_code)]\n#[cfg(windows)]\n#[path = "../engine_lease.rs"]\nmod engine_lease;\n',
    "minesport-engine.rs module anchor",
)
replace_once(
    sidecar,
    'fn run_worker() -> Result<()> {\n    let log = diagnostics::initialize()?;\n',
    'fn run_worker() -> Result<()> {\n    #[cfg(windows)]\n    let _engine_use_lease = engine_lease::acquire_engine_use_shared()?;\n    let log = diagnostics::initialize()?;\n',
    "minesport-engine.rs worker lease anchor",
)

ipc = Path("desktop/src/ipc.rs")
replace_once(
    ipc,
    'use crate::{diagnostics, runtime};\n',
    'use crate::{diagnostics, runtime};\n#[cfg(windows)]\nuse crate::engine_lease;\n',
    "ipc.rs import anchor",
)
replace_once(
    ipc,
    '    launch: BackendLaunch,\n    generation: Arc<AtomicU64>,\n',
    '    launch: BackendLaunch,\n    #[cfg(windows)]\n    _engine_use_lease: engine_lease::Lease,\n    generation: Arc<AtomicU64>,\n',
    "ipc.rs EngineInner anchor",
)
replace_once(
    ipc,
    '    pub fn start() -> Result<(Self, Receiver<EngineEvent>)> {\n        let logger = diagnostics::Logger::new("IPC").child("UI");\n',
    '    pub fn start() -> Result<(Self, Receiver<EngineEvent>)> {\n        #[cfg(windows)]\n        let engine_use_lease = engine_lease::acquire_engine_use_shared()\n            .context("coordinate Minesport engine sidecar use")?;\n        let logger = diagnostics::Logger::new("IPC").child("UI");\n',
    "ipc.rs Engine::start lease anchor",
)
replace_once(
    ipc,
    '                    events: tx,\n                    launch,\n                    generation,\n',
    '                    events: tx,\n                    launch,\n                    #[cfg(windows)]\n                    _engine_use_lease: engine_use_lease,\n                    generation,\n',
    "ipc.rs EngineInner init anchor",
)

update = Path("desktop/src/engine_update.rs")
replace_once(
    update,
    'use crate::{diagnostics, ipc, runtime};\n',
    'use crate::{diagnostics, engine_lease, ipc, runtime};\n',
    "engine_update.rs import anchor",
)
replace_once(
    update,
    '''    let stage_path = staged_update_path();
    if !stage_path.is_file() {
        return Ok(());
    }

    let stage_bytes = fs::read(&stage_path)
''',
    '''    let stage_path = staged_update_path();
    if !stage_path.is_file() {
        return Ok(());
    }
    let Some(_stage_lease) = engine_lease::try_acquire_stage_exclusive()? else {
        diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
            "EngineStagedUpdateBusy",
            "another Minesport process owns the engine update staging area; deferring apply",
            &[],
        );
        return Ok(());
    };
    // The stage can disappear between the cheap pre-check and acquiring the
    // cross-process lock if another process just completed or cleared it.
    if !stage_path.is_file() {
        return Ok(());
    }
    let Some(_engine_use_lease) = engine_lease::try_acquire_engine_use_exclusive()? else {
        diagnostics::Logger::new("ENGINE").child("UPDATE").info(
            "EngineStagedUpdateDeferredInUse",
            "another Minesport process is using the engine; keeping the verified update staged for a later launch",
            &[],
        );
        return Ok(());
    };

    let stage_bytes = fs::read(&stage_path)
''',
    "engine_update.rs apply lock anchor",
)
replace_once(
    update,
    '''    if !is_installed_layout(&executable) {
        if !matches!(installed, LocalEngine::Valid(_)) {
            diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
                "EngineUpdateSkippedDevelopmentLayout",
                "engine auto-repair is disabled outside an installed Windows layout",
                &[("executable", executable.display().to_string())],
            );
        }
        return Ok(());
    }

    if staged_update_path().is_file() {
''',
    '''    if !is_installed_layout(&executable) {
        if !matches!(installed, LocalEngine::Valid(_)) {
            diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
                "EngineUpdateSkippedDevelopmentLayout",
                "engine auto-repair is disabled outside an installed Windows layout",
                &[("executable", executable.display().to_string())],
            );
        }
        return Ok(());
    }

    let Some(_stage_lease) = engine_lease::try_acquire_stage_exclusive()? else {
        diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
            "EngineUpdateCheckBusy",
            "another Minesport process owns the engine update staging area; skipping this background pass",
            &[],
        );
        return Ok(());
    };

    if staged_update_path().is_file() {
''',
    "engine_update.rs background stage lock anchor",
)
