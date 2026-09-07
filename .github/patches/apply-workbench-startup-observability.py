from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Build provenance: every installed executable must identify the source
# revision that produced it. This removes ambiguity between a pulled checkout
# and an older Program Files executable that still reports the same 0.2.x
# package version.
# ---------------------------------------------------------------------------
build = Path("desktop/build.rs")
text = build.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''    path::{Component, Path, PathBuf},
    thread,
''',
    '''    path::{Component, Path, PathBuf},
    process::Command,
    thread,
''',
    "build script Command import",
)

revision_helpers = r'''
fn normalize_build_revision(raw: &str) -> Option<String> {
    let value = raw.trim();
    if value.is_empty()
        || value.len() > 64
        || !value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'+')
        })
    {
        return None;
    }
    Some(value.to_string())
}

fn git_metadata_path(root: &Path, name: &str) -> Option<PathBuf> {
    let output = Command::new("git")
        .args(["rev-parse", "--git-path", name])
        .current_dir(root)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = String::from_utf8(output.stdout).ok()?;
    let value = raw.trim();
    if value.is_empty() {
        return None;
    }
    let path = PathBuf::from(value);
    Some(if path.is_absolute() { path } else { root.join(path) })
}

fn configure_build_revision(root: &Path) {
    println!("cargo:rerun-if-env-changed=MINESPORT_BUILD_REVISION");

    // Cargo already reruns this script for its declared runtime/UI inputs. Track
    // Git HEAD as well so a new source commit cannot keep a stale embedded
    // revision merely because the build script itself did not change.
    if let Some(head) = git_metadata_path(root, "HEAD") {
        println!("cargo:rerun-if-changed={}", head.display());
        if let Ok(head_text) = fs::read_to_string(&head) {
            if let Some(reference) = head_text.trim().strip_prefix("ref: ") {
                if let Some(reference_path) = git_metadata_path(root, reference) {
                    println!("cargo:rerun-if-changed={}", reference_path.display());
                }
            }
        }
    }

    let revision = env::var("MINESPORT_BUILD_REVISION")
        .ok()
        .and_then(|value| normalize_build_revision(&value))
        .or_else(|| {
            let output = Command::new("git")
                .args(["rev-parse", "--short=12", "HEAD"])
                .current_dir(root)
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let raw = String::from_utf8(output.stdout).ok()?;
            normalize_build_revision(&raw)
        })
        .unwrap_or_else(|| "unknown".to_string());
    println!("cargo:rustc-env=MINESPORT_BUILD_REVISION={revision}");
}

'''
text = replace_once(
    text,
    '''fn main() {
    let manifest = manifest_dir();
''',
    revision_helpers + '''fn main() {
    let manifest = manifest_dir();
''',
    "build revision helpers",
)
text = replace_once(
    text,
    '''    let blender_addon = manifest
        .join("assets")
        .join("blender")
        .join("minesport_translator");

    println!("cargo:rerun-if-changed={}", ui.display());
''',
    '''    let blender_addon = manifest
        .join("assets")
        .join("blender")
        .join("minesport_translator");

    configure_build_revision(&root);
    println!("cargo:rerun-if-changed={}", ui.display());
''',
    "configure build revision",
)
build.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Startup operations and a real pre-IPC Workbench-construction probe.
# ---------------------------------------------------------------------------
app = Path("desktop/src/app.rs")
text = app.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''type SharedState = Arc<Mutex<AppState>>;

struct DocPage {
''',
    '''type SharedState = Arc<Mutex<AppState>>;

struct WorkbenchShell {
    ui: MainWindow,
    state: SharedState,
    cache: RuntimeCacheManager,
}

struct DocPage {
''',
    "Workbench shell type",
)

old_startup = '''pub fn run() -> Result<()> {
    if handle_cli()? {
        return Ok(());
    }

    let ui = MainWindow::new().context("create Minesport Slint window")?;
    ui.set_doc_total(DOC_PAGES.len() as i32);

    let saved = settings::load();
    apply_saved_settings(&ui, &saved);
    let state: SharedState = Arc::new(Mutex::new(AppState {
        resource_packs: saved.resource_packs.clone(),
        data_packs: saved.data_packs.clone(),
        export_format_index: saved.export_format_index,
        ..AppState::default()
    }));
    let cache = RuntimeCacheManager::default();
    refresh_asset_summaries(&ui, &state);

    let (engine, events) = JavaEngine::start()?;
'''
new_startup = '''fn prepare_workbench_shell() -> Result<WorkbenchShell> {
    let startup = diagnostics::Logger::new("DESKTOP").child("STARTUP");

    let window_operation = startup.operation("DesktopSlintWindowInitialize");
    let ui = match MainWindow::new().context("create Minesport Slint window") {
        Ok(ui) => {
            ui.set_doc_total(DOC_PAGES.len() as i32);
            window_operation.success("Slint Workbench window constructed", &[]);
            ui
        }
        Err(error) => {
            window_operation.failure(
                "Slint Workbench window construction failed",
                &[("error", format!("{error:#}"))],
            );
            return Err(error);
        }
    };

    let settings_operation = startup.operation("DesktopSettingsInitialize");
    let saved = settings::load();
    apply_saved_settings(&ui, &saved);
    let state: SharedState = Arc::new(Mutex::new(AppState {
        resource_packs: saved.resource_packs.clone(),
        data_packs: saved.data_packs.clone(),
        export_format_index: saved.export_format_index,
        ..AppState::default()
    }));
    let cache = RuntimeCacheManager::default();
    settings_operation.success(
        "Workbench settings and shared state initialized",
        &[
            ("resource_packs", saved.resource_packs.len().to_string()),
            ("data_packs", saved.data_packs.len().to_string()),
        ],
    );

    let assets_operation = startup.operation("DesktopAssetSummaryInitialize");
    refresh_asset_summaries(&ui, &state);
    assets_operation.success("Workbench asset summaries initialized", &[]);

    Ok(WorkbenchShell { ui, state, cache })
}

pub fn run() -> Result<()> {
    if handle_cli()? {
        return Ok(());
    }

    let WorkbenchShell { ui, state, cache } = prepare_workbench_shell()?;

    let (engine, events) = JavaEngine::start()?;
'''
text = replace_once(text, old_startup, new_startup, "instrument pre-IPC Workbench startup")

text = replace_once(
    text,
    '''        "--engine-worker" => {
            let jar = runtime::materialize_engine()?;
            crate::ipc::run_engine_worker(&jar)?;
            Ok(true)
        }
        "--install-blender-translator" => {
''',
    '''        "--engine-worker" => {
            let jar = runtime::materialize_engine()?;
            crate::ipc::run_engine_worker(&jar)?;
            Ok(true)
        }
        "--ui-construction-check" => {
            let _shell = prepare_workbench_shell()?;
            println!("Minesport Workbench construction check passed.");
            Ok(true)
        }
        "--install-blender-translator" => {
''',
    "native Workbench construction CLI probe",
)
app.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Update policy is GUI-only. Argument-driven CLI/helper invocations should not
# spawn a background update helper or attempt staged package application.
# This also keeps --ui-construction-check an exact, isolated pre-IPC probe.
# ---------------------------------------------------------------------------
main = Path("desktop/src/main.rs")
text = main.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''    let engine_worker_mode = std::env::args().nth(1).as_deref() == Some("--engine-worker");

    // The self-worker owns cached Java/toolchain files for its complete
''',
    '''    let desktop_gui_mode = std::env::args_os().len() == 1;
    let engine_worker_mode = std::env::args().nth(1).as_deref() == Some("--engine-worker");

    // The self-worker owns cached Java/toolchain files for its complete
''',
    "desktop GUI invocation classification",
)
text = replace_once(
    text,
    '''    #[cfg(windows)]
    if !engine_worker_mode {
        if let Err(error) = engine_update::apply_staged_update() {
''',
    '''    #[cfg(windows)]
    if desktop_gui_mode {
        if let Err(error) = engine_update::apply_staged_update() {
''',
    "GUI-only update policy",
)
main.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Session provenance in both the human log and operation journal.
# ---------------------------------------------------------------------------
diagnostics = Path("desktop/src/diagnostics.rs")
text = diagnostics.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''    let _ = OPERATIONS_FILE.set(Mutex::new(operations_file));
    let _ = display_log();

    Logger::new("DESKTOP").info(
''',
    '''    let _ = OPERATIONS_FILE.set(Mutex::new(operations_file));
    let _ = display_log();

    let executable = std::env::current_exe()
        .map(|path| path.display().to_string())
        .unwrap_or_else(|error| format!("<unavailable: {error}>"));
    Logger::new("DESKTOP").info(
''',
    "diagnostic executable provenance",
)
text = replace_once(
    text,
    '''        &[
            ("pid", std::process::id().to_string()),
            ("operations_log", operations.display().to_string()),
        ],
''',
    '''        &[
            ("pid", std::process::id().to_string()),
            ("build_revision", env!("MINESPORT_BUILD_REVISION").to_string()),
            ("executable", executable),
            ("operations_log", operations.display().to_string()),
        ],
''',
    "session build provenance fields",
)
diagnostics.write_text(text, encoding="utf-8")
