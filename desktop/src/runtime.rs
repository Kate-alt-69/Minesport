use anyhow::{Context, Result, anyhow, bail};
use std::{
    env, fs,
    path::{Component, Path, PathBuf},
    process::{Child, Command, ExitStatus, Output, Stdio},
    sync::{RwLock, RwLockReadGuard, RwLockWriteGuard, TryLockError},
    thread,
    time::{Duration, Instant},
};

const VENDOR_DIR: &str = "kastrick's_software";
const APP_DIR: &str = "minesport";
const ENGINE_BYTES: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/minesport-engine.jar"));
const ENGINE_VERSION_RAW: &str = include_str!("../../engine/VERSION");
const FABRIC_EXPORT_WORKER_BYTES: &[u8] = include_bytes!(concat!(
    env!("OUT_DIR"),
    "/minesport_export_worker-fabric.jar"
));
const FORGE_EXPORT_WORKER_BYTES: &[u8] = include_bytes!(concat!(
    env!("OUT_DIR"),
    "/minesport_export_worker-forge.jar"
));
const NEOFORGE_EXPORT_WORKER_BYTES: &[u8] = include_bytes!(concat!(
    env!("OUT_DIR"),
    "/minesport_export_worker-neoforge.jar"
));
const QUILT_EXPORT_WORKER_BYTES: &[u8] = include_bytes!(concat!(
    env!("OUT_DIR"),
    "/minesport_export_worker-quilt.jar"
));
static GENERATED_CACHE_USE: RwLock<()> = RwLock::new(());

#[derive(Debug)]
pub(crate) struct ProcessLease {
    _file: fs::File,
}

pub(crate) struct GeneratedCacheLease {
    _local: RwLockReadGuard<'static, ()>,
    _process: ProcessLease,
}

struct GeneratedCacheCleanup {
    _local: RwLockWriteGuard<'static, ()>,
    _process: ProcessLease,
}

pub fn materialize_engine() -> Result<PathBuf> {
    let version = ENGINE_VERSION_RAW.trim();
    if version.is_empty() {
        bail!("engine/VERSION must not be empty");
    }
    if !version
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'+' | b'_'))
    {
        bail!("engine/VERSION contains unsafe cache-name characters");
    }
    let name = format!("minesport-engine-{version}.jar");
    let temporary_name = format!(".minesport-engine-{version}.tmp");
    materialize_runtime_asset(&name, &temporary_name, ENGINE_BYTES)
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
    let _publish_lease = acquire_process_lease("runtime-assets", name, Duration::from_secs(30))?;
    let root = data_root().join("runtime");
    fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
    let destination = root.join(name);
    let write = match fs::read(&destination) {
        Ok(existing) => existing.as_slice() != bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
        Err(error) => return Err(error).with_context(|| format!("read {}", destination.display())),
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
    // Export Workers and compatibility build products are regenerable cache
    // data. Never require write access to Program Files for normal operation.
    cache_root().join("bridge-data")
}

pub(crate) fn acquire_generated_cache_lease() -> Result<GeneratedCacheLease> {
    let local = GENERATED_CACHE_USE
        .read()
        .map_err(|_| anyhow!("Minesport generated-cache lease lock is poisoned"))?;
    let process = acquire_generated_cache_process_lease()?;
    Ok(GeneratedCacheLease {
        _local: local,
        _process: process,
    })
}

fn acquire_generated_cache_cleanup() -> Result<GeneratedCacheCleanup> {
    let local = match GENERATED_CACHE_USE.try_write() {
        Ok(guard) => guard,
        Err(TryLockError::WouldBlock) => {
            bail!(
                "Minesport generated cache is currently in use; stop runtime preparation before clearing it"
            )
        }
        Err(TryLockError::Poisoned(_)) => {
            bail!("Minesport generated-cache cleanup lock is poisoned")
        }
    };
    let Some(process) = try_acquire_exclusive_process_lease("generated-cache", "use")? else {
        bail!(
            "Minesport generated cache is currently in use by another process; stop runtime preparation before clearing it"
        );
    };
    Ok(GeneratedCacheCleanup {
        _local: local,
        _process: process,
    })
}

pub(crate) fn acquire_process_lease(
    namespace: &str,
    name: &str,
    timeout: Duration,
) -> Result<ProcessLease> {
    let path = process_lock_path(namespace, name)?;
    let file = open_process_lock(&path)?;
    let deadline = Instant::now() + timeout;
    loop {
        match file.try_lock() {
            Ok(()) => return Ok(ProcessLease { _file: file }),
            Err(std::fs::TryLockError::WouldBlock) => {
                if Instant::now() >= deadline {
                    bail!(
                        "timed out waiting for Minesport process lease {}",
                        path.display()
                    );
                }
            }
            Err(std::fs::TryLockError::Error(error)) => {
                return Err(error).with_context(|| {
                    format!("acquire Minesport process lease {}", path.display())
                });
            }
        }
        thread::sleep(Duration::from_millis(25));
    }
}

pub(crate) fn acquire_generated_cache_process_lease() -> Result<ProcessLease> {
    acquire_shared_process_lease("generated-cache", "use")
}

fn acquire_shared_process_lease(namespace: &str, name: &str) -> Result<ProcessLease> {
    let path = process_lock_path(namespace, name)?;
    let file = open_process_lock(&path)?;
    file.lock_shared()
        .with_context(|| format!("acquire shared Minesport process lease {}", path.display()))?;
    Ok(ProcessLease { _file: file })
}

fn try_acquire_exclusive_process_lease(
    namespace: &str,
    name: &str,
) -> Result<Option<ProcessLease>> {
    let path = process_lock_path(namespace, name)?;
    let file = open_process_lock(&path)?;
    match file.try_lock() {
        Ok(()) => Ok(Some(ProcessLease { _file: file })),
        Err(std::fs::TryLockError::WouldBlock) => Ok(None),
        Err(std::fs::TryLockError::Error(error)) => Err(error).with_context(|| {
            format!(
                "acquire exclusive Minesport process lease {}",
                path.display()
            )
        }),
    }
}

fn process_lock_path(namespace: &str, name: &str) -> Result<PathBuf> {
    for (label, value) in [("namespace", namespace), ("name", name)] {
        if value.is_empty()
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_'))
        {
            bail!("unsafe Minesport process-lock {label}: {value:?}");
        }
    }
    Ok(data_root()
        .join("locks")
        .join("runtime")
        .join(namespace)
        .join(format!("{name}.lock")))
}

fn open_process_lock(path: &Path) -> Result<fs::File> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| {
            format!(
                "create Minesport process-lock directory {}",
                parent.display()
            )
        })?;
    }
    fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(path)
        .with_context(|| format!("open Minesport process lock {}", path.display()))
}

pub fn remove_generated_cache() -> Result<()> {
    let _exclusive = acquire_generated_cache_cleanup()?;
    let cache = clean_path(&cache_root());
    validate_cache_root(&cache)?;

    let compiled = clean_path(&bridge_data_root().join("compiled"));
    validate_compiled_bridge_path(&compiled)?;

    if cache.exists() {
        if !cache.is_dir() {
            bail!(
                "refusing to remove non-directory Minesport cache path {}",
                cache.display()
            );
        }
        fs::remove_dir_all(&cache).with_context(|| format!("remove {}", cache.display()))?;
    }
    if compiled.exists() {
        if !compiled.is_dir() {
            bail!(
                "refusing to remove non-directory Bridge cache path {}",
                compiled.display()
            );
        }
        fs::remove_dir_all(&compiled).with_context(|| format!("remove {}", compiled.display()))?;
    }
    fs::create_dir_all(&cache).with_context(|| format!("recreate {}", cache.display()))?;
    Ok(())
}

/// Terminate a child and, on Windows, the complete process tree rooted at that
/// child. Every wait is bounded so cancellation/shutdown cannot trade one hang
/// for another. The returned status is present only when the direct child was
/// reaped before the deadline.
pub(crate) fn terminate_process_tree(child: &mut Child, timeout: Duration) -> Option<ExitStatus> {
    if let Ok(Some(status)) = child.try_wait() {
        return Some(status);
    }

    let deadline = Instant::now() + timeout;
    #[cfg(windows)]
    terminate_windows_process_tree(child.id(), timeout.min(Duration::from_secs(4)));

    if let Ok(Some(status)) = child.try_wait() {
        return Some(status);
    }
    let _ = child.kill();

    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status),
            Ok(None) => {}
            Err(_) => return None,
        }
        if Instant::now() >= deadline {
            return None;
        }
        thread::sleep(Duration::from_millis(25));
    }
}

/// Run a tiny probe command with captured output and a hard deadline. This is
/// used for Java/Javac version detection where an unhealthy launcher/shim must
/// never freeze Minesport startup or runtime preparation.
pub(crate) fn output_with_timeout(
    command: &mut Command,
    timeout: Duration,
) -> std::io::Result<Option<Output>> {
    command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = command.spawn()?;
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait()? {
            Some(_) => return child.wait_with_output().map(Some),
            None => {}
        }
        if Instant::now() >= deadline {
            let _ = terminate_process_tree(&mut child, Duration::from_secs(2));
            return Ok(None);
        }
        thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(windows)]
fn terminate_windows_process_tree(pid: u32, timeout: Duration) {
    let taskkill = env::var_os("SystemRoot")
        .map(PathBuf::from)
        .map(|root| root.join("System32").join("taskkill.exe"))
        .filter(|path| path.is_file())
        .unwrap_or_else(|| PathBuf::from("taskkill.exe"));
    let pid = pid.to_string();
    let mut command = Command::new(taskkill);
    command
        .args(["/PID", pid.as_str(), "/T", "/F"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    hide_process_window(&mut command);

    let Ok(mut killer) = command.spawn() else {
        return;
    };
    let deadline = Instant::now() + timeout;
    loop {
        match killer.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => {}
            Err(_) => return,
        }
        if Instant::now() >= deadline {
            let _ = killer.kill();
            return;
        }
        thread::sleep(Duration::from_millis(25));
    }
}

#[cfg(windows)]
fn hide_process_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
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
        bail!(
            "refusing to remove an overly broad Minesport cache path: {}",
            clean.display()
        );
    }
    if is_filesystem_root(&clean) {
        bail!(
            "refusing to remove filesystem root as Minesport cache: {}",
            clean.display()
        );
    }

    if let Some(home) = user_home().map(|path| clean_path(&path)) {
        if same_path(&clean, &home) || contains_path(&clean, &home) {
            bail!(
                "refusing to remove a path that contains the user home directory: {}",
                clean.display()
            );
        }
    }

    let durable = clean_path(&data_root());
    if same_path(&clean, &durable) || contains_path(&clean, &durable) {
        bail!(
            "refusing to remove a path that contains Minesport durable data: {}",
            clean.display()
        );
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
        bail!(
            "refusing bridge cache outside bridge-data: {}",
            path.display()
        );
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
        left.to_string_lossy()
            .eq_ignore_ascii_case(&right.to_string_lossy())
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
        let custom = env::temp_dir()
            .join("custom-minesport-tests")
            .join("generated");
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

    #[test]
    fn bridge_data_defaults_to_user_cache() {
        if env::var_os("MINESPORT_BRIDGE_DATA").is_none() {
            assert_eq!(bridge_data_root(), cache_root().join("bridge-data"));
        }
    }

    #[test]
    fn process_cleanup_deadlines_are_finite() {
        assert!(Duration::from_secs(4) < Duration::from_secs(15));
    }
}
