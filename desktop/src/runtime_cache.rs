use crate::{diagnostics, runtime_worker::{self, CacheResult, Progress}};
use anyhow::{Result, anyhow};
use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}},
    thread,
};

type Listener = Box<dyn Fn(RuntimeCacheEvent) + Send + 'static>;

#[derive(Debug, Clone)]
pub enum RuntimeCacheEvent {
    Progress(Progress),
    Complete(Result<CacheResult, String>),
}

#[derive(Default)]
struct State {
    running: bool,
    version: String,
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
    pub fn start<F>(&self, version: String, mods_path: PathBuf, force: bool, listener: F) -> Result<bool>
    where
        F: Fn(RuntimeCacheEvent) + Send + 'static,
    {
        let logger = diagnostics::Logger::new("RUNTIME").child("REGISTRY");
        let mut state = self.state.lock().map_err(|_| anyhow!("runtime cache state is poisoned"))?;
        if state.running {
            if state.version != version || !same_path(&state.mods_path, &mods_path) {
                logger.warn(
                    "RuntimeRegistryJoinRejectedDifferentInstance",
                    "another runtime registry worker is already running for a different Minecraft instance",
                    &[
                        ("requested_version", version),
                        ("requested_mods", mods_path.display().to_string()),
                        ("running_version", state.version.clone()),
                        ("running_mods", state.mods_path.display().to_string()),
                    ],
                );
                return Err(anyhow!("another runtime registry worker is already running for a different Minecraft instance"));
            }
            let (operation_id, trace_id) = state
                .operation
                .as_ref()
                .map(|operation| (operation.operation_id().to_string(), operation.trace_id().to_string()))
                .unwrap_or_default();
            logger.info(
                "RuntimeRegistryListenerJoinedExistingJob",
                "listener joined existing runtime registry job",
                &[
                    ("operation_id", operation_id),
                    ("trace_id", trace_id),
                    ("version", version),
                    ("mods_path", mods_path.display().to_string()),
                ],
            );
            state.listeners.push(Box::new(listener));
            return Ok(false);
        }

        let operation = logger
            .operation("RuntimeRegistryPrepareFullModelCache")
            .field("version", &version)
            .field("mods_path", mods_path.display())
            .field("force", force);
        let cancel = Arc::new(AtomicBool::new(false));
        state.running = true;
        state.version = version.clone();
        state.mods_path = mods_path.clone();
        state.cancel = Some(cancel.clone());
        state.listeners.clear();
        state.listeners.push(Box::new(listener));
        state.operation = Some(operation);
        if force {
            state.fingerprint.clear();
            state.ready_path = PathBuf::new();
        }
        drop(state);

        let manager = self.clone();
        thread::spawn(move || {
            let result = runtime_worker::prepare_full_registry_cancellable(
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

    pub fn is_running(&self) -> bool {
        self.state.lock().map(|state| state.running).unwrap_or(false)
    }

    pub fn is_running_for(&self, version: &str, mods_path: &Path) -> bool {
        self.state.lock().map(|state| {
            state.running && state.version == version && same_path(&state.mods_path, mods_path)
        }).unwrap_or(false)
    }

    /// A remembered registry path is deliberately not authoritative for a new
    /// export. A mod JAR can be replaced in-place while Minesport stays open,
    /// leaving the same version/path but a different exact content identity.
    /// Call `start` instead: its background worker hashes the current JAR set
    /// and cheaply returns `reused=true` when the exact fingerprint still
    /// matches, without launching Minecraft again.
    pub fn ready_path(&self, _version: &str, _mods_path: &Path) -> Option<PathBuf> {
        None
    }

    pub fn fingerprint(&self, version: &str, mods_path: &Path) -> Option<String> {
        let state = self.state.lock().ok()?;
        if state.version != version || !same_path(&state.mods_path, mods_path) || state.fingerprint.is_empty() {
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
        let state = match self.state.lock() {
            Ok(state) => state,
            Err(_) => return,
        };
        for listener in &state.listeners {
            listener(event.clone());
        }
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
                Err(error) => operation.failure(
                    "runtime registry job failed",
                    &[("error", error.clone())],
                ),
            }
        }

        let event = RuntimeCacheEvent::Complete(result);
        for listener in listeners {
            listener(event.clone());
        }
    }
}

fn same_path(a: &Path, b: &Path) -> bool {
    if cfg!(windows) {
        a.to_string_lossy().eq_ignore_ascii_case(&b.to_string_lossy())
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
    }

    #[test]
    fn remembered_registry_is_not_export_authoritative_without_rehash() {
        let manager = RuntimeCacheManager::default();
        {
            let mut state = manager.state.lock().unwrap();
            state.version = "1.21.10".into();
            state.mods_path = PathBuf::from("mods");
            state.fingerprint = "old-exact-fingerprint".into();
            state.ready_path = PathBuf::from("runtime-registry/old/registry.data");
        }
        assert!(manager.ready_path("1.21.10", Path::new("mods")).is_none());
    }
}
