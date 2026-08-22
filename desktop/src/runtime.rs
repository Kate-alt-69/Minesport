use anyhow::{Context, Result, bail};
use std::{env, fs, path::{Path, PathBuf}};

const ENGINE_BYTES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/minesport-engine.jar"));
const BRIDGE_BYTES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/minesport-bridge.jar"));

pub fn materialize_engine() -> Result<PathBuf> {
    materialize_runtime_asset("minesport-engine-0.2.0.jar", ".minesport-engine-0.2.0.tmp", ENGINE_BYTES)
}

pub fn materialize_bundled_bridge() -> Result<PathBuf> {
    materialize_runtime_asset("minesport-bridge-0.2.0.jar", ".minesport-bridge-0.2.0.tmp", BRIDGE_BYTES)
}

fn materialize_runtime_asset(name: &str, temporary_name: &str, bytes: &[u8]) -> Result<PathBuf> {
    if bytes.is_empty() { bail!("embedded runtime asset {name} is empty"); }
    let root = data_root().join("runtime");
    fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
    let destination = root.join(name);
    let write = match fs::metadata(&destination) {
        Ok(metadata) => metadata.len() != bytes.len() as u64,
        Err(_) => true,
    };
    if write {
        let temporary = root.join(temporary_name);
        fs::write(&temporary, bytes).with_context(|| format!("write {}", temporary.display()))?;
        let _ = fs::remove_file(&destination);
        fs::rename(&temporary, &destination).with_context(|| format!("install {}", destination.display()))?;
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

pub fn bridge_data_root() -> PathBuf {
    if let Some(root) = env::var_os("MINESPORT_BRIDGE_DATA") {
        return PathBuf::from(root);
    }
    if cfg!(windows) {
        if let Some(program_files) = env::var_os("ProgramFiles") {
            return PathBuf::from(program_files).join("kastrick's_software").join("minesport").join("bridge-data");
        }
    }
    data_root().join("bridge-data")
}

pub fn remove_generated_cache() -> Result<()> {
    let cache = cache_root();
    validate_cache_root(&cache)?;

    let compiled = bridge_data_root().join("compiled");
    validate_compiled_bridge_path(&compiled)?;

    if cache.exists() {
        fs::remove_dir_all(&cache).with_context(|| format!("remove {}", cache.display()))?;
    }
    if compiled.exists() {
        fs::remove_dir_all(&compiled).with_context(|| format!("remove {}", compiled.display()))?;
    }
    fs::create_dir_all(&cache).with_context(|| format!("recreate {}", cache.display()))?;
    Ok(())
}

fn validate_cache_root(path: &Path) -> Result<()> {
    let clean = path.components().collect::<PathBuf>();
    let text = clean.to_string_lossy().to_ascii_lowercase();
    if clean.parent().is_none() || !text.contains("minesport") || !text.contains("cache") {
        bail!("refusing unsafe Minesport cache path: {}", clean.display());
    }
    if let Some(home) = env::var_os("USERPROFILE").or_else(|| env::var_os("HOME")) {
        if clean == PathBuf::from(home) { bail!("refusing to delete user home as cache"); }
    }
    Ok(())
}

fn validate_compiled_bridge_path(path: &Path) -> Result<()> {
    if path.file_name().and_then(|name| name.to_str()) != Some("compiled") {
        bail!("refusing unexpected bridge cache path: {}", path.display());
    }
    let parent = path.parent().and_then(|p| p.file_name()).and_then(|name| name.to_str());
    if parent != Some("bridge-data") {
        bail!("refusing bridge cache outside bridge-data: {}", path.display());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_cache_root_is_namespaced() {
        let value = cache_root().to_string_lossy().to_ascii_lowercase();
        assert!(value.contains("minesport"));
        assert!(value.contains("cache"));
    }

    #[test]
    fn broad_paths_are_rejected() {
        assert!(validate_cache_root(Path::new("/")).is_err());
        assert!(validate_compiled_bridge_path(Path::new("/tmp/compiled")).is_err());
    }

    #[test]
    fn embedded_runtime_assets_are_present() {
        assert!(!ENGINE_BYTES.is_empty());
        assert!(!BRIDGE_BYTES.is_empty());
    }
}
