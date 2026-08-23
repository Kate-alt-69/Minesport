use crate::runtime;
use anyhow::{Context, Result};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::{Mutex, OnceLock},
    time::{SystemTime, UNIX_EPOCH},
};

static LOG_FILE: OnceLock<Mutex<File>> = OnceLock::new();

pub fn folder() -> PathBuf {
    runtime::data_root().join("diagnostics")
}

pub fn log_path() -> PathBuf {
    folder().join("minesport.log")
}

pub fn initialize() -> Result<PathBuf> {
    let folder = folder();
    fs::create_dir_all(&folder).with_context(|| format!("create diagnostics directory {}", folder.display()))?;
    let path = log_path();
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .with_context(|| format!("open diagnostics log {}", path.display()))?;
    let _ = LOG_FILE.set(Mutex::new(file));
    append("--- Minesport 0.2.0 Rust/Slint session started ---");
    Ok(path)
}

pub fn append(message: &str) {
    let Some(lock) = LOG_FILE.get() else { return; };
    let Ok(mut file) = lock.lock() else { return; };
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    for line in message.lines() {
        let _ = writeln!(file, "{stamp}  {line}");
    }
    let _ = file.flush();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagnostics_path_is_namespaced() {
        let path = log_path().to_string_lossy().to_ascii_lowercase();
        assert!(path.contains("minesport"));
        assert!(path.ends_with("minesport.log"));
    }
}
