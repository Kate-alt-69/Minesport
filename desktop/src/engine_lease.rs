use crate::runtime;
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
        Err(TryLockError::Error(error)) => Err(error).with_context(|| {
            format!(
                "acquire exclusive engine coordination lock {}",
                path.display()
            )
        }),
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
