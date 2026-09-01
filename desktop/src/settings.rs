use crate::runtime;
use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File},
    io::Write,
    path::{Path, PathBuf},
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct DesktopSettings {
    pub output_path: String,
    pub export_name: String,
    pub export_format_index: i32,
    pub export_mode_index: i32,
    pub optimize: bool,
    pub face_culling: bool,
    pub flatter_enabled: bool,
    pub flatter_cell_index: i32,
    pub hidden_culling: bool,
    pub blender_export: bool,
    pub select_by_model: bool,
    pub debug_mode: bool,
    pub blender_animation_index: i32,
    pub blender_translator_prompted: bool,
    pub resource_packs: Vec<PathBuf>,
    pub data_packs: Vec<PathBuf>,
}

impl Default for DesktopSettings {
    fn default() -> Self {
        Self {
            output_path: String::new(),
            export_name: "Minesport_Export".to_string(),
            export_format_index: 0,
            export_mode_index: 0,
            optimize: true,
            face_culling: true,
            flatter_enabled: true,
            flatter_cell_index: 3,
            hidden_culling: false,
            blender_export: true,
            select_by_model: false,
            debug_mode: false,
            blender_animation_index: 0,
            blender_translator_prompted: false,
            resource_packs: Vec::new(),
            data_packs: Vec::new(),
        }
    }
}

struct SaveCoordinator {
    latest_requested: AtomicU64,
    publication_lock: Mutex<()>,
}

impl SaveCoordinator {
    const fn new() -> Self {
        Self {
            latest_requested: AtomicU64::new(0),
            publication_lock: Mutex::new(()),
        }
    }

    fn reserve(&self) -> u64 {
        self.latest_requested.fetch_add(1, Ordering::AcqRel) + 1
    }

    fn publish_latest<F>(&self, generation: u64, publish: F) -> Result<bool>
    where
        F: FnOnce() -> Result<()>,
    {
        let _guard = self
            .publication_lock
            .lock()
            .map_err(|_| anyhow!("Minesport settings publication lock is poisoned"))?;
        if generation != self.latest_requested.load(Ordering::Acquire) {
            return Ok(false);
        }
        publish()?;
        Ok(true)
    }
}

static SAVE_COORDINATOR: SaveCoordinator = SaveCoordinator::new();

/// Reserve ordering for an asynchronous settings snapshot before its worker is
/// spawned. Reserving on the UI thread guarantees that a newer snapshot can
/// never be overwritten later by an older worker that happened to run slowly.
pub fn reserve_save() -> u64 {
    SAVE_COORDINATOR.reserve()
}

pub fn load() -> DesktopSettings {
    let path = settings_path();
    let _ = restore_backup_if_needed(&path);
    let Ok(bytes) = fs::read(&path) else {
        return DesktopSettings::default();
    };
    serde_json::from_slice(&bytes).unwrap_or_default()
}

pub fn save(settings: &DesktopSettings) -> Result<()> {
    let generation = reserve_save();
    let _ = save_reserved(generation, settings)?;
    Ok(())
}

/// Publish a previously reserved snapshot only if it is still the newest
/// requested state. All file-mutating recovery/replacement work is serialized
/// behind the same lock, including the final synchronous shutdown save.
pub fn save_reserved(generation: u64, settings: &DesktopSettings) -> Result<bool> {
    let bytes = serde_json::to_vec_pretty(settings).context("encode Rust desktop settings")?;
    SAVE_COORDINATOR.publish_latest(generation, || {
        let path = settings_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("create settings directory {}", parent.display()))?;
        }
        restore_backup_if_needed(&path)?;
        crash_safe_replace(&path, &bytes)
    })
}

fn settings_path() -> PathBuf {
    runtime::data_root().join("settings").join("desktop.json")
}

fn backup_path(path: &Path) -> PathBuf {
    path.with_extension("json.bak")
}

fn restore_backup_if_needed(path: &Path) -> Result<()> {
    if path.is_file() {
        return Ok(());
    }
    let backup = backup_path(path);
    if backup.is_file() {
        fs::rename(&backup, path).with_context(|| {
            format!(
                "restore settings {} from {}",
                path.display(),
                backup.display()
            )
        })?;
    }
    Ok(())
}

fn crash_safe_replace(path: &Path, bytes: &[u8]) -> Result<()> {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let temporary = path.with_extension(format!("json.{}.{}.tmp", std::process::id(), stamp));
    let backup = backup_path(path);

    {
        let mut file =
            File::create(&temporary).with_context(|| format!("create {}", temporary.display()))?;
        file.write_all(bytes)
            .with_context(|| format!("write {}", temporary.display()))?;
        file.sync_all()
            .with_context(|| format!("sync {}", temporary.display()))?;
    }

    let had_previous = path.is_file();
    if had_previous {
        let _ = fs::remove_file(&backup);
        fs::rename(path, &backup)
            .with_context(|| format!("stage previous settings {}", path.display()))?;
    }

    match fs::rename(&temporary, path) {
        Ok(()) => {
            let _ = fs::remove_file(&backup);
            Ok(())
        }
        Err(error) => {
            let _ = fs::remove_file(&temporary);
            if had_previous && backup.is_file() {
                let _ = fs::rename(&backup, path);
            }
            Err(error).with_context(|| format!("install {}", path.display()))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_workbench_expectations() {
        let value = DesktopSettings::default();
        assert!(value.face_culling);
        assert!(value.flatter_enabled);
        assert_eq!(value.flatter_cell_index, 3);
        assert!(value.blender_export);
        assert!(!value.blender_translator_prompted);
    }

    #[test]
    fn save_coordinator_rejects_stale_generations() {
        let coordinator = SaveCoordinator::new();
        let stale = coordinator.reserve();
        let newest = coordinator.reserve();
        let stale_published = std::sync::atomic::AtomicBool::new(false);
        let newest_published = std::sync::atomic::AtomicBool::new(false);

        assert!(
            !coordinator
                .publish_latest(stale, || {
                    stale_published.store(true, Ordering::Release);
                    Ok(())
                })
                .unwrap()
        );
        assert!(!stale_published.load(Ordering::Acquire));

        assert!(
            coordinator
                .publish_latest(newest, || {
                    newest_published.store(true, Ordering::Release);
                    Ok(())
                })
                .unwrap()
        );
        assert!(newest_published.load(Ordering::Acquire));
    }

    #[test]
    fn backup_restore_recovers_interrupted_publication() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "minesport-settings-backup-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let path = root.join("desktop.json");
        let backup = backup_path(&path);
        fs::write(&backup, b"{\"export_name\":\"Recovered\"}").unwrap();
        restore_backup_if_needed(&path).unwrap();
        assert!(path.is_file());
        assert!(!backup.exists());
        let _ = fs::remove_dir_all(root);
    }
}
