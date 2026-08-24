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
    pub fn start<F>(&self, version: String, mods_path: PathBuf, force: bool, listener: F) -> Result<bool>
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
        let mut state = self.state.lock().map_err(|_| anyhow!("runtime cache state is poisoned"))?;
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
                return Err(anyhow!("runtime cache is already running for another instance"));
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
                    ("loader", loader),
                    ("mods_path", mods_path.display().to_string()),
                ],
            );
            state.listeners.push(Box::new(listener));
            return Ok(false);
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
        state.listeners.push(Box::new(listener));
        state.operation = Some(operation);
        if force {
            state.fingerprint.clear();
            state.ready_path = PathBuf::new();
        }
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

    pub fn is_running(&self) -> bool {
        self.state.lock().map(|state| state.running).unwrap_or(false)
    }

    pub fn is_running_for(&self, version: &str, mods_path: &Path) -> bool {
        self.is_running_for_loader(version, "fabric", mods_path)
    }

    pub fn is_running_for_loader(&self, version: &str, loader: &str, mods_path: &Path) -> bool {
        let loader = normalize_loader(loader);
        self.state.lock().map(|state| {
            state.running
                && state.version == version
                && state.loader.eq_ignore_ascii_case(&loader)
                && same_path(&state.mods_path, mods_path)
        }).unwrap_or(false)
    }

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

        let event = RuntimeCacheEvent::Complete(
            result.map_err(|error| first_line(&error).to_string())
        );
        for listener in listeners {
            listener(event.clone());
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
            state.loader = "forge".into();
            state.mods_path = PathBuf::from("mods");
            state.fingerprint = "old-exact-fingerprint".into();
            state.ready_path = PathBuf::from("runtime-registry/old/registry.data");
        }
        assert!(manager.ready_path("1.21.10", Path::new("mods")).is_none());
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
        assert_eq!(first_line("download failed\nstack\ntrace"), "download failed");
        assert_eq!(first_line("cancelled"), "cancelled");
    }
}
