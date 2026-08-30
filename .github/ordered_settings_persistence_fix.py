from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


settings_path = Path("desktop/src/settings.rs")
settings = settings_path.read_text(encoding="utf-8")

settings = replace_once(
    settings,
    '''use crate::runtime;\nuse anyhow::{Context, Result};''',
    '''use crate::runtime;\nuse anyhow::{Context, Result, anyhow};''',
    "settings anyhow import",
)

settings = replace_once(
    settings,
    '''    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};''',
    '''    path::{Path, PathBuf},
    sync::{Mutex, atomic::{AtomicU64, Ordering}},
    time::{SystemTime, UNIX_EPOCH},
};''',
    "settings synchronization imports",
)

settings = replace_once(
    settings,
    '''pub fn load() -> DesktopSettings {''',
    '''struct SaveCoordinator {
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

pub fn load() -> DesktopSettings {''',
    "settings save coordinator",
)

settings = replace_once(
    settings,
    '''pub fn save(settings: &DesktopSettings) -> Result<()> {
    let path = settings_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("create settings directory {}", parent.display()))?;
    }
    restore_backup_if_needed(&path)?;
    let bytes = serde_json::to_vec_pretty(settings).context("encode Rust desktop settings")?;
    crash_safe_replace(&path, &bytes)
}''',
    '''pub fn save(settings: &DesktopSettings) -> Result<()> {
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
}''',
    "ordered settings save API",
)

settings = replace_once(
    settings,
    '''    fn backup_restore_recovers_interrupted_publication() {''',
    '''    fn save_coordinator_rejects_stale_generations() {
        let coordinator = SaveCoordinator::new();
        let stale = coordinator.reserve();
        let newest = coordinator.reserve();
        let stale_published = std::sync::atomic::AtomicBool::new(false);
        let newest_published = std::sync::atomic::AtomicBool::new(false);

        assert!(!coordinator.publish_latest(stale, || {
            stale_published.store(true, Ordering::Release);
            Ok(())
        }).unwrap());
        assert!(!stale_published.load(Ordering::Acquire));

        assert!(coordinator.publish_latest(newest, || {
            newest_published.store(true, Ordering::Release);
            Ok(())
        }).unwrap());
        assert!(newest_published.load(Ordering::Acquire));
    }

    #[test]
    fn backup_restore_recovers_interrupted_publication() {''',
    "settings coordinator regression test",
)

settings_path.write_text(settings, encoding="utf-8")


app_path = Path("desktop/src/app.rs")
app = app_path.read_text(encoding="utf-8")
app = replace_once(
    app,
    '''fn persist_settings_snapshot(ui: &MainWindow, state: &SharedState) {
    let snapshot = collect_settings(ui, state);
    thread::spawn(move || {
        if let Err(error) = settings::save(&snapshot) {
            diagnostics::append(&format!("Could not persist Minesport desktop settings: {error:#}"));
        }
    });
}''',
    '''fn persist_settings_snapshot(ui: &MainWindow, state: &SharedState) {
    let snapshot = collect_settings(ui, state);
    // Reserve publication order before scheduling the worker. Thread scheduling
    // can run snapshots out of order; save_reserved discards any snapshot that
    // was superseded before it reaches the serialized publication point.
    let generation = settings::reserve_save();
    thread::spawn(move || {
        if let Err(error) = settings::save_reserved(generation, &snapshot) {
            diagnostics::append(&format!("Could not persist Minesport desktop settings: {error:#}"));
        }
    });
}''',
    "ordered app settings persistence",
)
app_path.write_text(app, encoding="utf-8")

print("Serialized settings publication and rejected stale async snapshots")
