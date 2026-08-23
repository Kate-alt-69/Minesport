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
        let mut state = self.state.lock().map_err(|_| anyhow!("runtime cache state is poisoned"))?;
        if state.running {
            if state.version != version || !same_path(&state.mods_path, &mods_path) {
                return Err(anyhow!("another runtime registry worker is already running for a different Minecraft instance"));
            }
            diagnostics::append(&format!("Runtime registry listener joined existing job: Minecraft {version} · {}", mods_path.display()));
            state.listeners.push(Box::new(listener));
            return Ok(false);
        }

        let cancel = Arc::new(AtomicBool::new(false));
        state.running = true;
        state.version = version.clone();
        state.mods_path = mods_path.clone();
        state.cancel = Some(cancel.clone());
        state.listeners.clear();
        state.listeners.push(Box::new(listener));
        if force {
            state.fingerprint.clear();
            state.ready_path = PathBuf::new();
        }
        drop(state);

        diagnostics::append(&format!(
            "Runtime registry job started: Minecraft {version} · mods {} · force={force}",
            mods_path.display()
        ));
        let manager = self.clone();
        thread::spawn(move || {
            let result = runtime_worker::prepare_full_registry_cancellable(
                &version,
                &mods_path,
                force,
                cancel,
                |progress| {
                    diagnostics::append(&format!("Runtime registry {:>3}% · {}", progress.percent.clamp(0, 100), progress.message));
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
            diagnostics::append("Runtime registry cancellation requested");
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
        diagnostics::append("Runtime registry ready-state invalidated");
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
        match &result {
            Ok(cache) => diagnostics::append(&format!(
                "Runtime registry ready: {} · fingerprint {}{}",
                cache.registry_path.display(),
                cache.fingerprint,
                if cache.reused { " · reused" } else { " · fresh" }
            )),
            Err(error) => diagnostics::append(&format!("Runtime registry failed: {error}")),
        }

        let listeners = {
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
            std::mem::take(&mut state.listeners)
        };
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
