use crate::{
    diagnostics,
    runtime_worker::{self, CacheResult, Progress},
};
use anyhow::{Result, anyhow};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

type Listener = Arc<Mutex<Box<dyn Fn(RuntimeCacheEvent) + Send + 'static>>>;

#[derive(Debug, Clone)]
pub enum RuntimeCacheEvent {
    Progress(Progress),
    Complete(Result<CacheResult, String>),
}

#[derive(Default)]
struct State {
    running: bool,
    version: String,
    loader: String,
    mods_path: PathBuf,
    fingerprint: String,
    ready_path: PathBuf,
    cancel: Option<Arc<AtomicBool>>,
    listeners: Vec<Listener>,
    operation: Option<diagnostics::Operation>,
}

#[derive(Clone, Default)]
pub struct RuntimeCacheManager {
    state: Arc<Mutex<State>>,
}

impl RuntimeCacheManager {
    pub fn start<F>(
        &self,
        version: String,
        mods_path: PathBuf,
        force: bool,
        listener: F,
    ) -> Result<bool>
    where
        F: Fn(RuntimeCacheEvent) + Send + 'static,
    {
        self.start_for_loader(version, "fabric".into(), mods_path, force, listener)
    }

    pub fn start_for_loader<F>(
        &self,
        version: String,
        loader: String,
        mods_path: PathBuf,
        force: bool,
        listener: F,
    ) -> Result<bool>
    where
        F: Fn(RuntimeCacheEvent) + Send + 'static,
    {
        let loader = normalize_loader(&loader);
        let logger = diagnostics::Logger::new("RUNTIME").child("REGISTRY");
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow!("runtime cache state is poisoned"))?;
        if state.running {
            if state.version != version
                || !state.loader.eq_ignore_ascii_case(&loader)
                || !same_path(&state.mods_path, &mods_path)
            {
                logger.warn(
                    "RuntimeRegistryJoinRejectedDifferentInstance",
                    "another runtime registry worker is already running for a different Minecraft instance",
                    &[
                        ("requested_version", version),
                        ("requested_loader", loader),
                        ("requested_mods", mods_path.display().to_string()),
                        ("running_version", state.version.clone()),
                        ("running_loader", state.loader.clone()),
                        ("running_mods", state.mods_path.display().to_string()),
                    ],
                );
                return Err(anyhow!(
                    "runtime cache is already running for another instance"
                ));
            }
            let (operation_id, trace_id) = state
                .operation
                .as_ref()
                .map(|operation| {
                    (
                        operation.operation_id().to_string(),
                        operation.trace_id().to_string(),
                    )
                })
                .unwrap_or_default();
            logger.info(
                "RuntimeRegistryListenerJoinedExistingJob",
                "listener joined existing runtime registry job",
                &[
                    ("operation_id", operation_id),
                    ("trace_id", trace_id),
                    ("version", version),
                    ("loader", loader),
                    ("mods_path", mods_path.display().to_string()),
                ],
            );
            state.listeners.push(wrap_listener(listener));
            return Ok(false);
        }

        let same_identity = state.version == version
            && state.loader.eq_ignore_ascii_case(&loader)
            && same_path(&state.mods_path, &mods_path);
        if !same_identity || force {
            state.fingerprint.clear();
            state.ready_path = PathBuf::new();
        }

        let operation = logger
            .operation("RuntimeRegistryPrepareFullModelCache")
            .field("version", &version)
            .field("loader", &loader)
            .field("mods_path", mods_path.display())
            .field("force", force);
        let cancel = Arc::new(AtomicBool::new(false));
        state.running = true;
        state.version = version.clone();
        state.loader = loader.clone();
        state.mods_path = mods_path.clone();
        state.cancel = Some(cancel.clone());
        state.listeners.clear();
        state.listeners.push(wrap_listener(listener));
        state.operation = Some(operation);
        drop(state);

        let manager = self.clone();
        thread::spawn(move || {
            let result = runtime_worker::prepare_full_registry_cancellable_for_loader(
                &loader,
                &version,
                &mods_path,
                force,
                cancel,
                |progress| {
                    manager.log_progress(&progress);
                    manager.emit(RuntimeCacheEvent::Progress(progress));
                },
            );

            let completed = result.map_err(|error| format!("{error:#}"));
            manager.finish(completed);
        });
        Ok(true)
    }

    pub fn cancel(&self) -> bool {
        let state = match self.state.lock() {
            Ok(state) => state,
            Err(_) => return false,
        };
        if !state.running {
            return false;
        }
        if let Some(cancel) = &state.cancel {
            cancel.store(true, Ordering::Relaxed);
            if let Some(operation) = state.operation.as_ref() {
                operation.warn(
                    "RuntimeRegistryCancellationRequested",
                    "runtime registry cancellation requested",
                    &[],
                );
            }
            return true;
        }
        false
    }

    /// Wait for an in-flight runtime worker to fully unwind after cancellation.
    /// This deliberately polls the manager state instead of blocking the worker
    /// callback thread or holding the state mutex while cleanup runs.
    pub fn wait_for_idle(&self, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        loop {
            if !self.is_running() {
                return true;
            }
            if Instant::now() >= deadline {
                diagnostics::Logger::new("RUNTIME").child("REGISTRY").warn(
                    "RuntimeRegistryShutdownWaitTimedOut",
                    "runtime registry worker did not stop before shutdown timeout",
                    &[("timeout_ms", timeout.as_millis().to_string())],
                );
                return false;
            }
            thread::sleep(Duration::from_millis(25));
        }
    }

    pub fn is_running(&self) -> bool {
        self.state
            .lock()
            .map(|state| state.running)
            .unwrap_or(false)
    }

    pub fn is_running_for(&self, version: &str, mods_path: &Path) -> bool {
        self.is_running_for_loader(version, "fabric", mods_path)
    }

    pub fn is_running_for_loader(&self, version: &str, loader: &str, mods_path: &Path) -> bool {
        let loader = normalize_loader(loader);
        self.state
            .lock()
            .map(|state| {
                state.running
                    && state.version == version
                    && state.loader.eq_ignore_ascii_case(&loader)
                    && same_path(&state.mods_path, mods_path)
            })
            .unwrap_or(false)
    }

    /// Return the completed registry remembered for this instance. The first
    /// export still waits on the completion event, while later exports avoid
    /// starting/joining runtime preparation again. `ready_path_for_loader` is
    /// preferred by loader-aware callers; this wrapper preserves Fabric API
    /// compatibility for the existing desktop wiring.
    pub fn ready_path(&self, version: &str, mods_path: &Path) -> Option<PathBuf> {
        self.ready_path_for_loader(version, "fabric", mods_path)
    }

    pub fn ready_path_for_loader(
        &self,
        version: &str,
        loader: &str,
        mods_path: &Path,
    ) -> Option<PathBuf> {
        let loader = normalize_loader(loader);
        let state = self.state.lock().ok()?;
        if state.running
            || state.version != version
            || !state.loader.eq_ignore_ascii_case(&loader)
            || !same_path(&state.mods_path, mods_path)
            || state.ready_path.as_os_str().is_empty()
            || !state.ready_path.is_file()
        {
            return None;
        }
        Some(state.ready_path.clone())
    }

    pub fn fingerprint(&self, version: &str, mods_path: &Path) -> Option<String> {
        let state = self.state.lock().ok()?;
        if state.version != version
            || !same_path(&state.mods_path, mods_path)
            || state.fingerprint.is_empty()
        {
            return None;
        }
        Some(state.fingerprint.clone())
    }

    pub fn invalidate(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.fingerprint.clear();
            state.ready_path = PathBuf::new();
        }
        diagnostics::Logger::new("RUNTIME").child("REGISTRY").info(
            "RuntimeRegistryReadyStateInvalidated",
            "runtime registry ready-state invalidated",
            &[],
        );
    }

    fn log_progress(&self, progress: &Progress) {
        let state = match self.state.lock() {
            Ok(state) => state,
            Err(_) => return,
        };
        if let Some(operation) = state.operation.as_ref() {
            operation.event(
                "RuntimeRegistryPreparationProgress",
                &progress.message,
                &[("percent", progress.percent.clamp(0, 100).to_string())],
            );
        }
    }

    fn emit(&self, event: RuntimeCacheEvent) {
        // Clone lightweight listener handles while holding state, then invoke
        // user callbacks only after the manager mutex has been released.
        let listeners = match self.state.lock() {
            Ok(state) => state.listeners.clone(),
            Err(_) => return,
        };
        notify_listeners(&listeners, &event);
    }

    fn finish(&self, result: Result<CacheResult, String>) {
        let (listeners, operation) = {
            let mut state = match self.state.lock() {
                Ok(state) => state,
                Err(_) => return,
            };
            if let Ok(cache) = &result {
                state.fingerprint = cache.fingerprint.clone();
                state.ready_path = cache.registry_path.clone();
            }
            state.running = false;
            state.cancel = None;
            let operation = state.operation.take();
            (std::mem::take(&mut state.listeners), operation)
        };

        if let Some(operation) = operation {
            match &result {
                Ok(cache) => operation.success(
                    "runtime registry ready",
                    &[
                        ("registry_path", cache.registry_path.display().to_string()),
                        ("fingerprint", cache.fingerprint.clone()),
                        ("reused", cache.reused.to_string()),
                    ],
                ),
                Err(error) if error.to_ascii_lowercase().contains("cancel") => operation.cancelled(
                    "runtime registry job cancelled",
                    &[("error", error.clone())],
                ),
                Err(error) => {
                    operation.failure("runtime registry job failed", &[("error", error.clone())])
                }
            }
        }

        let event =
            RuntimeCacheEvent::Complete(result.map_err(|error| first_line(&error).to_string()));
        notify_listeners(&listeners, &event);
    }
}

fn wrap_listener<F>(listener: F) -> Listener
where
    F: Fn(RuntimeCacheEvent) + Send + 'static,
{
    Arc::new(Mutex::new(Box::new(listener)))
}

fn notify_listeners(listeners: &[Listener], event: &RuntimeCacheEvent) {
    for listener in listeners {
        if let Ok(callback) = listener.lock() {
            if catch_unwind(AssertUnwindSafe(|| callback(event.clone()))).is_err() {
                diagnostics::Logger::new("RUNTIME").child("REGISTRY").warn(
                    "RuntimeRegistryListenerPanicked",
                    "runtime registry listener panicked; continuing worker lifecycle",
                    &[],
                );
            }
        }
    }
}

fn normalize_loader(value: &str) -> String {
    value.trim().to_ascii_lowercase()
}

fn first_line(value: &str) -> &str {
    value.lines().next().unwrap_or(value)
}

fn same_path(a: &Path, b: &Path) -> bool {
    if cfg!(windows) {
        a.to_string_lossy()
            .eq_ignore_ascii_case(&b.to_string_lossy())
    } else {
        a == b
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn path_identity_is_case_insensitive_on_windows_only() {
        let a = Path::new("C:/Minecraft/mods");
        let b = Path::new("c:/minecraft/MODS");
        if cfg!(windows) {
            assert!(same_path(a, b));
        } else {
            assert!(!same_path(a, b));
        }
    }

    #[test]
    fn idle_manager_has_no_ready_registry() {
        let manager = RuntimeCacheManager::default();
        assert!(!manager.is_running());
        assert!(manager.ready_path("1.21.10", Path::new("mods")).is_none());
        assert!(manager.wait_for_idle(Duration::from_millis(0)));
    }

    #[test]
    fn wait_for_idle_times_out_for_a_stuck_running_state() {
        let manager = RuntimeCacheManager::default();
        manager.state.lock().unwrap().running = true;
        assert!(!manager.wait_for_idle(Duration::from_millis(5)));
        manager.state.lock().unwrap().running = false;
    }

    #[test]
    fn completed_registry_is_reused_only_for_matching_loader_instance() {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "minesport-runtime-ready-{}-{stamp}",
            std::process::id()
        ));
        std::fs::create_dir_all(&root).unwrap();
        let registry = root.join("registry.data");
        std::fs::write(&registry, b"ready").unwrap();
        let manager = RuntimeCacheManager::default();
        {
            let mut state = manager.state.lock().unwrap();
            state.version = "1.21.10".into();
            state.loader = "forge".into();
            state.mods_path = PathBuf::from("mods");
            state.fingerprint = "exact-fingerprint".into();
            state.ready_path = registry.clone();
        }
        assert_eq!(
            manager.ready_path_for_loader("1.21.10", "Forge", Path::new("mods")),
            Some(registry)
        );
        assert!(
            manager
                .ready_path_for_loader("1.21.10", "fabric", Path::new("mods"))
                .is_none()
        );
        assert!(manager.ready_path("1.21.10", Path::new("mods")).is_none());
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn loader_identity_is_part_of_running_job_key() {
        let manager = RuntimeCacheManager::default();
        {
            let mut state = manager.state.lock().unwrap();
            state.running = true;
            state.version = "1.21.10".into();
            state.loader = "forge".into();
            state.mods_path = PathBuf::from("mods");
        }
        assert!(manager.is_running_for_loader("1.21.10", "Forge", Path::new("mods")));
        assert!(!manager.is_running_for_loader("1.21.10", "fabric", Path::new("mods")));
        assert!(!manager.is_running_for("1.21.10", Path::new("mods")));
    }

    #[test]
    fn listener_error_is_single_line_but_operation_error_can_stay_detailed() {
        assert_eq!(
            first_line("download failed\nstack\ntrace"),
            "download failed"
        );
        assert_eq!(first_line("cancelled"), "cancelled");
    }

    #[test]
    fn panicking_listener_does_not_abort_following_listeners() {
        let observed = Arc::new(AtomicBool::new(false));
        let observed_listener = observed.clone();
        let listeners = vec![
            wrap_listener(|_| panic!("listener failure")),
            wrap_listener(move |_| {
                observed_listener.store(true, Ordering::Relaxed);
            }),
        ];
        notify_listeners(
            &listeners,
            &RuntimeCacheEvent::Progress(Progress {
                percent: 50,
                message: "test".into(),
            }),
        );
        assert!(observed.load(Ordering::Relaxed));
    }

    #[test]
    fn emit_releases_manager_lock_before_listener_runs() {
        let manager = RuntimeCacheManager::default();
        let observed = Arc::new(AtomicBool::new(false));
        let observed_listener = observed.clone();
        let manager_listener = manager.clone();
        {
            let mut state = manager.state.lock().unwrap();
            state.listeners.push(wrap_listener(move |_| {
                // This would deadlock if emit still held manager.state.
                let _ = manager_listener.is_running();
                observed_listener.store(true, Ordering::Relaxed);
            }));
        }
        manager.emit(RuntimeCacheEvent::Progress(Progress {
            percent: 1,
            message: "test".into(),
        }));
        assert!(observed.load(Ordering::Relaxed));
    }
}
