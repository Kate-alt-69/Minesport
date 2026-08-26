use anyhow::{Context, Result, anyhow, bail};
use std::{
    env, fs,
    path::{Component, Path, PathBuf},
    sync::{RwLock, RwLockReadGuard, RwLockWriteGuard, TryLockError},
};

const VENDOR_DIR: &str = "kastrick's_software";
const APP_DIR: &str = "minesport";
const ENGINE_BYTES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/minesport-engine.jar"));
const FABRIC_EXPORT_WORKER_BYTES: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/minesport_export_worker-fabric.jar"));
const FORGE_EXPORT_WORKER_BYTES: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/minesport_export_worker-forge.jar"));
const NEOFORGE_EXPORT_WORKER_BYTES: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/minesport_export_worker-neoforge.jar"));
const QUILT_EXPORT_WORKER_BYTES: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/minesport_export_worker-quilt.jar"));
static GENERATED_CACHE_USE: RwLock<()> = RwLock::new(());

pub fn materialize_engine() -> Result<PathBuf> {
    materialize_runtime_asset(
        "minesport-engine-0.2.1.jar",
        ".minesport-engine-0.2.1.tmp",
        ENGINE_BYTES,
    )
}

/// Backward-compatible name for callers that historically meant the Fabric
/// Export Worker when they asked for the legacy single bundled Bridge.
pub fn materialize_bundled_bridge() -> Result<PathBuf> {
    materialize_bundled_fabric_bridge()
}

pub fn materialize_bundled_fabric_bridge() -> Result<PathBuf> {
    materialize_runtime_asset(
        "minesport_export_worker-fabric-1.21.10.jar",
        ".minesport_export_worker-fabric-1.21.10.tmp",
        FABRIC_EXPORT_WORKER_BYTES,
    )
}

pub fn materialize_bundled_forge_bridge() -> Result<PathBuf> {
    materialize_runtime_asset(
        "minesport_export_worker-forge-1.21.10.jar",
        ".minesport_export_worker-forge-1.21.10.tmp",
        FORGE_EXPORT_WORKER_BYTES,
    )
}

pub fn materialize_bundled_neoforge_bridge() -> Result<PathBuf> {
    materialize_runtime_asset(
        "minesport_export_worker-neoforge-1.21.10.jar",
        ".minesport_export_worker-neoforge-1.21.10.tmp",
        NEOFORGE_EXPORT_WORKER_BYTES,
    )
}

pub fn materialize_bundled_quilt_bridge() -> Result<PathBuf> {
    materialize_runtime_asset(
        "minesport_export_worker-quilt-1.21.10.jar",
        ".minesport_export_worker-quilt-1.21.10.tmp",
        QUILT_EXPORT_WORKER_BYTES,
    )
}

fn materialize_runtime_asset(name: &str, temporary_name: &str, bytes: &[u8]) -> Result<PathBuf> {
    if bytes.is_empty() {
        bail!("embedded runtime asset {name} is empty");
    }
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
        fs::rename(&temporary, &destination)
            .with_context(|| format!("install {}", destination.display()))?;
    }
    Ok(destination)
}

/// Durable per-user Minesport state. This preserves the retired Go appdirs
/// contract, including environment overrides and the macOS Application Support
/// location, so the Rust migration does not move users' data unexpectedly.
pub fn data_root() -> PathBuf {
    if let Some(path) = env_path("MINESPORT_DATA_DIR") {
        return path;
    }

    if cfg!(windows) {
        if let Some(local) = env_path("LOCALAPPDATA") {
            return local.join(VENDOR_DIR).join(APP_DIR);
        }
    } else if cfg!(target_os = "macos") {
        if let Some(home) = user_home() {
            return home
                .join("Library")
                .join("Application Support")
                .join(VENDOR_DIR)
                .join(APP_DIR);
        }
    } else {
        if let Some(data) = env_path("XDG_DATA_HOME") {
            return data.join(VENDOR_DIR).join(APP_DIR);
        }
        if let Some(home) = user_home() {
            return home
                .join(".local")
                .join("share")
                .join(VENDOR_DIR)
                .join(APP_DIR);
        }
    }

    if let Some(config) = env_path("XDG_CONFIG_HOME") {
        return config.join(VENDOR_DIR).join(APP_DIR);
    }
    env::temp_dir().join(VENDOR_DIR).join(APP_DIR)
}

/// Regenerable Minesport data. The original Go implementation deliberately
/// used ~/.cache on every desktop OS when a home directory is available.
pub fn cache_root() -> PathBuf {
    if let Some(path) = env_path("MINESPORT_CACHE_DIR") {
        return path;
    }
    if let Some(home) = user_home() {
        return home.join(".cache").join(VENDOR_DIR).join(APP_DIR);
    }
    if let Some(cache) = env_path("XDG_CACHE_HOME") {
        return cache.join(VENDOR_DIR).join(APP_DIR);
    }
    env::temp_dir()
        .join(VENDOR_DIR)
        .join(APP_DIR)
        .join(".cache")
}

pub fn bridge_data_root() -> PathBuf {
    if let Some(root) = env_path("MINESPORT_BRIDGE_DATA") {
        return root;
    }
    if cfg!(windows) {
        if let Some(program_files) = env_path("ProgramFiles") {
            return program_files
                .join(VENDOR_DIR)
                .join(APP_DIR)
                .join("bridge-data");
        }
    }
    data_root().join("bridge-data")
}

pub(crate) fn acquire_generated_cache_lease() -> Result<RwLockReadGuard<'static, ()>> {
    GENERATED_CACHE_USE
        .read()
        .map_err(|_| anyhow!("Minesport generated-cache lease lock is poisoned"))
}

fn acquire_generated_cache_cleanup() -> Result<RwLockWriteGuard<'static, ()>> {
    match GENERATED_CACHE_USE.try_write() {
        Ok(guard) => Ok(guard),
        Err(TryLockError::WouldBlock) => {
            bail!("Minesport generated cache is currently in use; stop runtime preparation before clearing it")
        }
        Err(TryLockError::Poisoned(_)) => {
            bail!("Minesport generated-cache cleanup lock is poisoned")
        }
    }
}

pub fn remove_generated_cache() -> Result<()> {
    let _exclusive = acquire_generated_cache_cleanup()?;
    let cache = clean_path(&cache_root());
    validate_cache_root(&cache)?;

    let compiled = clean_path(&bridge_data_root().join("compiled"));
    validate_compiled_bridge_path(&compiled)?;

    if cache.exists() {
        if !cache.is_dir() {
            bail!("refusing to remove non-directory Minesport cache path {}", cache.display());
        }
        fs::remove_dir_all(&cache).with_context(|| format!("remove {}", cache.display()))?;
    }
    if compiled.exists() {
        if !compiled.is_dir() {
            bail!("refusing to remove non-directory Bridge cache path {}", compiled.display());
        }
        fs::remove_dir_all(&compiled)
            .with_context(|| format!("remove {}", compiled.display()))?;
    }
    fs::create_dir_all(&cache).with_context(|| format!("recreate {}", cache.display()))?;
    Ok(())
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

fn user_home() -> Option<PathBuf> {
    env_path(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .or_else(|| env_path("HOME"))
        .or_else(|| env_path("USERPROFILE"))
}

fn clean_path(path: &Path) -> PathBuf {
    let mut clean = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                let _ = clean.pop();
            }
            other => clean.push(other.as_os_str()),
        }
    }
    clean
}

fn validate_cache_root(path: &Path) -> Result<()> {
    let clean = clean_path(path);
    if clean.as_os_str().is_empty() || path_depth(&clean) < 2 {
        bail!("refusing to remove an overly broad Minesport cache path: {}", clean.display());
    }
    if is_filesystem_root(&clean) {
        bail!("refusing to remove filesystem root as Minesport cache: {}", clean.display());
    }

    if let Some(home) = user_home().map(|path| clean_path(&path)) {
        if same_path(&clean, &home) || contains_path(&clean, &home) {
            bail!("refusing to remove a path that contains the user home directory: {}", clean.display());
        }
    }

    let durable = clean_path(&data_root());
    if same_path(&clean, &durable) || contains_path(&clean, &durable) {
        bail!("refusing to remove a path that contains Minesport durable data: {}", clean.display());
    }
    Ok(())
}

fn validate_compiled_bridge_path(path: &Path) -> Result<()> {
    if path.file_name().and_then(|name| name.to_str()) != Some("compiled") {
        bail!("refusing unexpected bridge cache path: {}", path.display());
    }
    let parent = path
        .parent()
        .and_then(|parent| parent.file_name())
        .and_then(|name| name.to_str());
    if parent != Some("bridge-data") {
        bail!("refusing bridge cache outside bridge-data: {}", path.display());
    }
    Ok(())
}

fn path_depth(path: &Path) -> usize {
    path.components()
        .filter(|component| matches!(component, Component::Normal(_)))
        .count()
}

fn is_filesystem_root(path: &Path) -> bool {
    path.parent().is_none() || path.parent().is_some_and(|parent| parent == path)
}

fn contains_path(parent: &Path, child: &Path) -> bool {
    !same_path(parent, child) && child.starts_with(parent)
}

fn same_path(left: &Path, right: &Path) -> bool {
    if cfg!(windows) {
        left.to_string_lossy().eq_ignore_ascii_case(&right.to_string_lossy())
    } else {
        left == right
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_cache_root_is_namespaced() {
        let value = cache_root().to_string_lossy().to_ascii_lowercase();
        assert!(value.contains("minesport") || env::var_os("MINESPORT_CACHE_DIR").is_some());
    }

    #[test]
    fn broad_paths_are_rejected() {
        assert!(validate_cache_root(Path::new("/")).is_err());
        assert!(validate_cache_root(Path::new("/tmp")).is_err());
        assert!(validate_compiled_bridge_path(Path::new("/tmp/compiled")).is_err());
    }

    #[test]
    fn dedicated_custom_cache_shape_is_allowed() {
        let custom = env::temp_dir().join("custom-minesport-tests").join("generated");
        assert!(validate_cache_root(&custom).is_ok());
    }

    #[test]
    fn durable_data_parent_is_rejected() {
        let durable = clean_path(&data_root());
        if let Some(parent) = durable.parent() {
            assert!(validate_cache_root(parent).is_err());
        }
    }

    #[test]
    fn cleanup_refuses_while_runtime_cache_is_leased() {
        let lease = acquire_generated_cache_lease().unwrap();
        assert!(acquire_generated_cache_cleanup().is_err());
        drop(lease);
        assert!(acquire_generated_cache_cleanup().is_ok());
    }

    #[test]
    fn embedded_runtime_assets_are_present() {
        assert!(!ENGINE_BYTES.is_empty());
        assert!(!FABRIC_EXPORT_WORKER_BYTES.is_empty());
        assert!(!FORGE_EXPORT_WORKER_BYTES.is_empty());
        assert!(!NEOFORGE_EXPORT_WORKER_BYTES.is_empty());
        assert!(!QUILT_EXPORT_WORKER_BYTES.is_empty());
    }
}
