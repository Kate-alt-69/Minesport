use anyhow::{Context, Result};
use std::{env, fs, path::PathBuf};

const ENGINE_BYTES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/minesport-engine.jar"));

pub fn materialize_engine() -> Result<PathBuf> {
    let root = data_root().join("runtime");
    fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
    let destination = root.join("minesport-engine-0.2.0.jar");

    let write = match fs::metadata(&destination) {
        Ok(metadata) => metadata.len() != ENGINE_BYTES.len() as u64,
        Err(_) => true,
    };
    if write {
        let temporary = root.join(".minesport-engine-0.2.0.tmp");
        fs::write(&temporary, ENGINE_BYTES)
            .with_context(|| format!("write {}", temporary.display()))?;
        let _ = fs::remove_file(&destination);
        fs::rename(&temporary, &destination)
            .with_context(|| format!("install {}", destination.display()))?;
    }
    Ok(destination)
}

pub fn data_root() -> PathBuf {
    if cfg!(windows) {
        if let Some(local) = env::var_os("LOCALAPPDATA") {
            return PathBuf::from(local).join("kastrick's_software").join("minesport");
        }
    }
    if let Some(xdg) = env::var_os("XDG_DATA_HOME") {
        return PathBuf::from(xdg).join("kastrick's_software").join("minesport");
    }
    if let Some(home) = env::var_os("HOME").or_else(|| env::var_os("USERPROFILE")) {
        return PathBuf::from(home).join(".local").join("share").join("kastrick's_software").join("minesport");
    }
    env::temp_dir().join("kastrick's_software").join("minesport")
}

pub fn cache_root() -> PathBuf {
    if cfg!(windows) {
        if let Some(home) = env::var_os("USERPROFILE") {
            return PathBuf::from(home).join(".cache").join("kastrick's_software").join("minesport");
        }
    }
    if let Some(xdg) = env::var_os("XDG_CACHE_HOME") {
        return PathBuf::from(xdg).join("kastrick's_software").join("minesport");
    }
    if let Some(home) = env::var_os("HOME") {
        return PathBuf::from(home).join(".cache").join("kastrick's_software").join("minesport");
    }
    env::temp_dir().join("minesport-cache")
}
