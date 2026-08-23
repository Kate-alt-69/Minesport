use crate::{
    MainWindow, aux_windows, blender, bridge_compat, diagnostics, heightmap_cache,
    ipc::{Engine as JavaEngine, EngineEvent, Response},
    preview, runtime, runtime_cache::{RuntimeCacheEvent, RuntimeCacheManager}, selection, settings,
    viewer_selection, world_context, world_picker,
};
use anyhow::{Context, Result, anyhow};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use rfd::{MessageButtons, MessageDialog, MessageDialogResult, MessageLevel};
use serde::Deserialize;
use serde_json::{Value, json};
use settings::DesktopSettings;
use slint::{ComponentHandle, Image, ModelRc, Rgba8Pixel, SharedPixelBuffer, SharedString, VecModel};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    process::Command,
    sync::{Arc, Mutex, mpsc::{Receiver, RecvTimeoutError}},
    thread,
    time::Duration,
};

const VERSION: &str = "0.2.0";

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
enum BlockRequestPurpose {
    #[default]
    Preflight,
    Preview,
}

#[derive(Debug, Clone, Copy)]
enum PreviewCameraAction {
    Orbit { dx: f32, dy: f32 },
    Pan { dx: f32, dy: f32 },
    Dolly(f32),
    Fit,
}

impl PreviewCameraAction {
    fn label(self) -> &'static str {
        match self {
            Self::Orbit { .. } => "orbiting",
            Self::Pan { .. } => "panning",
            Self::Dolly(_) => "dollying",
            Self::Fit => "fitting",
        }
    }
}

#[derive(Default)]
struct AppState {
    resource_packs: Vec<PathBuf>,
    data_packs: Vec<PathBuf>,
    pending_export: Option<Value>,
    block_request_purpose: BlockRequestPurpose,
    selected_world: Option<PathBuf>,
    selected_mods_path: Option<PathBuf>,
    selected_version: Option<String>,
    selected_loader: Option<String>,
    preview_pick_map: Option<preview::PreviewPickMap>,
    exact_selection: Option<selection::ExactSelection>,
}

type SharedState = Arc<Mutex<AppState>>;

struct DocPage {
    title: &'static str,
    body: &'static str,
    github: &'static str,
    video: &'static str,
}

const DOC_PAGES: &[DocPage] = &[
    DocPage { title: "Start Here", body: include_str!("../../doc/page/01.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/01.md", video: "" },
    DocPage { title: "Minesport Main App", body: include_str!("../../doc/page/02.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/02.md", video: "" },
    DocPage { title: "Blender Beginner Basics", body: include_str!("../../doc/page/10.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/10.md", video: "" },
    DocPage { title: "Find the Minesport Panel", body: include_str!("../../doc/page/11.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/11.md", video: "" },
    DocPage { title: "FLATTER for Blender Beginners", body: include_str!("../../doc/page/12.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/12.md", video: "" },
    DocPage { title: "Minecraft Light Blocks in Blender", body: include_str!("../../doc/page/13.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/13.md", video: "" },
    DocPage { title: "Troubleshooting", body: include_str!("../../doc/page/20.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/20.md", video: "" },
    DocPage { title: "Runtime Model Cache", body: include_str!("../../doc/page/90.md"), github: "https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/90.md", video: "" },
];

pub fn run() -> Result<()> {
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
        ..AppState::default()
    }));
    let cache = RuntimeCacheManager::default();
    refresh_asset_summaries(&ui, &state);

    let (engine, events) = JavaEngine::start()?;
    pump_engine_events(ui.as_weak(), events, state.clone());
    wire_file_pickers(&ui, engine.clone(), state.clone(), cache.clone());
    wire_export(&ui, engine.clone(), state.clone(), cache.clone());
    wire_preflight(&ui, engine.clone(), state.clone());
    wire_viewer(&ui, engine.clone(), state.clone());
    wire_cache_actions(&ui, engine.clone(), state.clone(), cache.clone());
    wire_asset_pickers(&ui, state.clone());
    wire_settings_persistence(&ui, state.clone());
    sync_debug_console(&ui, state.clone());
    wire_docs(&ui);
    wire_blender(&ui, state.clone());

    append_diagnostic(&ui, "Backend boundary: Minesport.exe --engine-worker → embedded Java engine");
    append_diagnostic(&ui, "Desktop: Rust + Slint · Fyne UI archived under /archive/go-fyne-ui");
    append_diagnostic(&ui, "Runtime registry: Rust binary registry.data capture + isolated Fabric/Loom worker");
    append_diagnostic(&ui, "Compatibility: embedded Rust patch recipes cover the manifest-supported Fabric version families");
    append_diagnostic(&ui, "World context: launcher/instance discovery is authoritative when available; folder inference is fallback-only");
    append_diagnostic(&ui, "3D selection: camera-derived voxel DDA + Fyne point A / point B box workflow");
    append_diagnostic(&ui, "3D camera: retained Rust scene supports MMB orbit, Shift+MMB pan, wheel dolly and F6 fit without IPC reruns");

    let ping_engine = engine.clone();
    let ping_weak = ui.as_weak();
    thread::spawn(move || {
        if let Err(error) = ping_engine.ping() {
            let _ = ping_weak.upgrade_in_event_loop(move |ui| {
                ui.set_engine_status("ENGINE ERROR".into());
                append_diagnostic(&ui, &format!("Could not ping Minesport backend: {error:#}"));
            });
        }
    });

    ui.run().context("run Minesport Slint event loop")?;
    cache.cancel();
    if let Err(error) = settings::save(&collect_settings(&ui, &state)) {
        diagnostics::append(&format!("Could not save Minesport desktop settings: {error:#}"));
        eprintln!("Could not save Minesport desktop settings: {error:#}");
    }
    engine.shutdown();
    Ok(())
}

fn handle_cli() -> Result<bool> {
    let mut args = std::env::args().skip(1);
    let Some(arg) = args.next() else { return Ok(false); };
    match arg.as_str() {
        "--engine-worker" => {
            let jar = runtime::materialize_engine()?;
            crate::ipc::run_engine_worker(&jar)?;
            Ok(true)
        }
        "--install-blender-translator" => {
            let report = blender::install_detected_profiles()?;
            println!("Installed Minesport Blender translator into {} profile(s).", report.installed_profiles.len());
            for profile in report.installed_profiles {
                println!("  {}", profile.display());
            }
            Ok(true)
        }
        "-h" | "--help" => {
            println!("Minesport {VERSION}\nRust + Slint desktop by Kastrick\n\nUsage:\n  minesport                            Open the desktop app\n  minesport --install-blender-translator\n  minesport --version                  Print version\n  minesport --help                     Show this help");
            Ok(true)
        }
        "-V" | "--version" => {
            println!("Minesport {VERSION}");
            Ok(true)
        }
        _ => Ok(false),
    }
}

fn apply_saved_settings(ui: &MainWindow, saved: &DesktopSettings) {
    ui.set_output_path(saved.output_path.clone().into());
    ui.set_export_name(saved.export_name.clone().into());
    ui.set_export_format_index(saved.export_format_index);
    ui.set_export_mode_index(saved.export_mode_index);
    ui.set_optimize(saved.optimize);
    ui.set_face_culling(saved.face_culling);
    ui.set_flatter_enabled(saved.flatter_enabled);
    ui.set_flatter_cell_index(saved.flatter_cell_index.clamp(0, 3));
    ui.set_hidden_culling(saved.hidden_culling);
    ui.set_blender_export(saved.blender_export);
    ui.set_select_by_model(saved.select_by_model);
    ui.set_debug_mode(saved.debug_mode);
    ui.set_blender_animation_index(saved.blender_animation_index.clamp(0, 1));
    ui.set_blender_translator_prompted(saved.blender_translator_prompted);
}

fn collect_settings(ui: &MainWindow, state: &SharedState) -> DesktopSettings {
    let guard = state.lock().expect("settings state");
    DesktopSettings {
        output_path: ui.get_output_path().to_string(),
        export_name: ui.get_export_name().to_string(),
        export_format_index: ui.get_export_format_index(),
        export_mode_index: ui.get_export_mode_index(),
        optimize: ui.get_optimize(),
        face_culling: ui.get_face_culling(),
        flatter_enabled: ui.get_flatter_enabled(),
        flatter_cell_index: ui.get_flatter_cell_index(),
        hidden_culling: ui.get_hidden_culling(),
        blender_export: ui.get_blender_export(),
        select_by_model: ui.get_select_by_model(),
        debug_mode: ui.get_debug_mode(),
        blender_animation_index: ui.get_blender_animation_index(),
        blender_translator_prompted: ui.get_blender_translator_prompted(),
        resource_packs: guard.resource_packs.clone(),
        data_packs: guard.data_packs.clone(),
    }
}

fn wire_settings_persistence(ui: &MainWindow, state: SharedState) {
    let weak = ui.as_weak();
    ui.on_settings_changed(move || {
        let Some(ui) = weak.upgrade() else { return; };
        sync_debug_console(&ui, state.clone());
        persist_settings_snapshot(&ui, &state);
    });
}

fn sync_debug_console(ui: &MainWindow, state: SharedState) {
    ui.set_debug_console_visible(false);
    if !ui.get_debug_mode() {
        aux_windows::hide_debug_console();
        return;
    }

    let close_weak = ui.as_weak();
    let close_state = state.clone();
    let clear_weak = ui.as_weak();
    aux_windows::show_debug_console(
        ui,
        move || {
            let Some(ui) = close_weak.upgrade() else { return; };
            ui.set_debug_mode(false);
            ui.set_debug_console_visible(false);
            persist_settings_snapshot(&ui, &close_state);
        },
        move || {
            let Some(ui) = clear_weak.upgrade() else { return; };
            ui.set_diagnostics("".into());
            aux_windows::update_debug_console(&ui);
        },
    );
}

fn persist_settings_snapshot(ui: &MainWindow, state: &SharedState) {
    let snapshot = collect_settings(ui, state);
    thread::spawn(move || {
        if let Err(error) = settings::save(&snapshot) {
            diagnostics::append(&format!("Could not persist Minesport desktop settings: {error:#}"));
        }
    });
}

fn wire_file_pickers(ui: &MainWindow, engine: JavaEngine, state: SharedState, cache: RuntimeCacheManager) {
    let world_state = state.clone();
    let weak = ui.as_weak();
    let picker_engine = engine.clone();
    ui.on_pick_world(move || {
        let weak = weak.clone();
        let engine = picker_engine.clone();
        let state = world_state.clone();
        let cache = cache.clone();
        if let Some(ui) = weak.upgrade() {
            ui.set_task_title("WORLD PICKER".into());
            ui.set_task_detail("Discovering Minecraft launchers, instances and worlds…".into());
        }
        thread::spawn(move || {
            let worlds = world_context::all_worlds();
            let count = worlds.len();
            let event_weak = weak.clone();
            let _ = weak.upgrade_in_event_loop(move |ui| {
                append_diagnostic(&ui, &format!("World picker discovered {count} world(s) across installed launchers"));

                let select_weak = event_weak.clone();
                let select_engine = engine.clone();
                let select_state = state.clone();
                let select_cache = cache.clone();

                let browse_weak = event_weak.clone();
                let browse_engine = engine.clone();
                let browse_state = state.clone();
                let browse_cache = cache.clone();

                world_picker::show(
                    worlds,
                    move |context| {
                        activate_world(
                            select_weak.clone(),
                            select_engine.clone(),
                            select_state.clone(),
                            select_cache.clone(),
                            context.world_path.clone(),
                            Some(context),
                        );
                    },
                    move || {
                        browse_world_folder(
                            browse_weak.clone(),
                            browse_engine.clone(),
                            browse_state.clone(),
                            browse_cache.clone(),
                        );
                    },
                );
            });
        });
    });

    let weak = ui.as_weak();
    let output_state = state.clone();
    ui.on_pick_output(move || {
        let weak = weak.clone();
        let state = output_state.clone();
        thread::spawn(move || {
            let picked = rfd::FileDialog::new().set_title("Choose Minesport export folder").pick_folder();
            let Some(path) = picked else { return; };
            let display = path.display().to_string();
            let _ = weak.upgrade_in_event_loop(move |ui| {
                ui.set_output_path(display.clone().into());
                append_diagnostic(&ui, &format!("Export folder: {display}"));
                persist_settings_snapshot(&ui, &state);
            });
        });
    });

    let weak = ui.as_weak();
    let reload_engine = engine;
    ui.on_reload_map(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let world = PathBuf::from(ui.get_world_path().to_string());
        if !world.join("level.dat").is_file() { return; }
        ui.set_map_loading(true);
        ui.set_map_available(false);
        ui.set_task_title("MAP REFRESH".into());
        ui.set_task_detail("Invalidating saved heightmap and rebuilding from the world…".into());
        let engine = reload_engine.clone();
        let weak = weak.clone();
        thread::spawn(move || {
            if let Err(error) = heightmap_cache::invalidate(&world) {
                diagnostics::append(&format!("Could not invalidate heightmap cache: {error:#}"));
            }
            let result = engine.send_value(json!({ "command": "heightmap", "worldPath": world, "scale": 1 }));
            if let Err(error) = result {
                let _ = weak.upgrade_in_event_loop(move |ui| {
                    ui.set_map_loading(false);
                    ui.set_task_title("MAP FAILED".into());
                    ui.set_task_detail(error.to_string().into());
                    append_diagnostic(&ui, &format!("Reload heightmap request failed: {error:#}"));
                });
            }
        });
    });
}

fn browse_world_folder(
    weak: slint::Weak<MainWindow>,
    engine: JavaEngine,
    state: SharedState,
    cache: RuntimeCacheManager,
) {
    thread::spawn(move || {
        let picked = rfd::FileDialog::new().set_title("Select a Minecraft world folder").pick_folder();
        let Some(path) = picked else { return; };
        activate_world(weak, engine, state, cache, path, None);
    });
}

fn activate_world(
    weak: slint::Weak<MainWindow>,
    engine: JavaEngine,
    state: SharedState,
    cache: RuntimeCacheManager,
    path: PathBuf,
    supplied_context: Option<world_context::WorldContext>,
) {
    thread::spawn(move || {
        if !path.join("level.dat").is_file() {
            let display = path.display().to_string();
            let _ = weak.upgrade_in_event_loop(move |ui| {
                append_diagnostic(&ui, &format!("Rejected world without level.dat: {display}"));
                ui.set_task_title("INVALID WORLD".into());
                ui.set_task_detail("The selected folder does not contain level.dat".into());
            });
            return;
        }

        let discovered = supplied_context.or_else(|| world_context::resolve(&path));
        let name = discovered
            .as_ref()
            .map(|context| context.world_name.clone())
            .or_else(|| path.file_name().and_then(|name| name.to_str()).map(str::to_string))
            .unwrap_or_else(|| "Minecraft World".to_string());
        let version = discovered
            .as_ref()
            .map(|context| context.version.clone())
            .filter(|value| !value.trim().is_empty() && value != "?")
            .or_else(|| detect_minecraft_version(&path))
            .unwrap_or_else(|| "1.21.10".to_string());
        let loader = discovered
            .as_ref()
            .map(|context| context.loader.clone())
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| detect_loader(&path));
        let mods_path = discovered
            .as_ref()
            .map(|context| context.mods_path.clone())
            .unwrap_or_else(|| infer_mods_path(&path));
        let display = path.display().to_string();
        let context_line = discovered.as_ref().map(|context| {
            format!(
                "Launcher context: {} › {} · MC {} · {}{} · minecraft dir {}",
                context.launcher,
                context.instance,
                context.version,
                context.loader,
                if context.has_polymer { " · Polymer" } else { "" },
                context.minecraft_dir.display()
            )
        });

        if cache.is_running() && !cache.is_running_for(&version, &mods_path) {
            cache.cancel();
        }
        viewer_selection::reset();
        if let Ok(mut guard) = state.lock() {
            guard.pending_export = None;
            guard.selected_world = Some(path.clone());
            guard.selected_mods_path = Some(mods_path.clone());
            guard.selected_version = Some(version.clone());
            guard.selected_loader = Some(loader.clone());
            guard.preview_pick_map = None;
            guard.exact_selection = None;
        }

        let cached_map = match heightmap_cache::load(&path) {
            Ok(Some((metadata, png))) => match decode_png(&png) {
                Ok(raster) => Some((metadata, raster)),
                Err(error) => {
                    diagnostics::append(&format!("Ignored unreadable heightmap cache for {}: {error:#}", path.display()));
                    None
                }
            },
            Ok(None) => None,
            Err(error) => {
                diagnostics::append(&format!("Heightmap cache lookup failed for {}: {error:#}", path.display()));
                None
            }
        };
        let cache_hit = cached_map.is_some();

        let display_for_ui = display.clone();
        let version_for_ui = version.clone();
        let loader_for_ui = loader.clone();
        let _ = weak.upgrade_in_event_loop(move |ui| {
            ui.set_world_path(display_for_ui.clone().into());
            ui.set_world_name(name.into());
            ui.set_minecraft_version(version_for_ui.clone().into());
            ui.set_loader_type(loader_for_ui.clone().into());
            ui.set_runtime_cache_status("PREPARING · full instance registry starts automatically".into());
            ui.set_preview_available(false);
            ui.set_preview_loading(false);
            ui.set_preview_focus_label("LMB point A · RMB point B · LMB again confirms".into());
            if let Some((metadata, raster)) = cached_map {
                apply_map_raster(&ui, raster, metadata.min_x, metadata.min_z, metadata.max_x, metadata.max_z, metadata.scale);
                ui.set_task_title("MAP CACHE HIT".into());
                ui.set_task_detail(format!("Minecraft {version_for_ui} · {loader_for_ui} · saved 2D map restored").into());
                append_diagnostic(&ui, "2D map cache hit: using saved heightmap");
            } else {
                ui.set_map_loading(true);
                ui.set_map_available(false);
                ui.set_task_title("WORLD".into());
                ui.set_task_detail(format!("Minecraft {version_for_ui} · {loader_for_ui} · loading 2D map").into());
            }
            append_diagnostic(&ui, &format!("Selected world: {display_for_ui} (Minecraft {version_for_ui}, {loader_for_ui})"));
            if let Some(line) = context_line { append_diagnostic(&ui, &line); }
        });

        if !cache_hit {
            if let Err(error) = engine.send_value(json!({ "command": "heightmap", "worldPath": display, "scale": 1 })) {
                let _ = weak.upgrade_in_event_loop(move |ui| {
                    ui.set_map_loading(false);
                    ui.set_task_title("MAP FAILED".into());
                    ui.set_task_detail(error.to_string().into());
                    append_diagnostic(&ui, &format!("Heightmap request failed: {error:#}"));
                });
            }
        }

        if runtime_registry_supported(&loader, &version, &mods_path) {
            let _ = start_runtime_cache_job(
                weak.clone(), cache, engine, state, version, mods_path, false, false,
            );
        } else {
            let detail = runtime_registry_unavailable_reason(&loader, &version, &mods_path);
            let _ = weak.upgrade_in_event_loop(move |ui| ui.set_runtime_cache_status(detail.into()));
        }
    });
}

fn wire_export(ui: &MainWindow, engine: JavaEngine, state: SharedState, cache: RuntimeCacheManager) {
    let weak = ui.as_weak();
    ui.on_export_requested(move || {
        let Some(ui) = weak.upgrade() else { return; };
        if !ui.get_engine_ready() || ui.get_task_active() { return; }

        let world = PathBuf::from(ui.get_world_path().to_string());
        if !world.join("level.dat").is_file() {
            ui.set_task_title("EXPORT BLOCKED".into());
            ui.set_task_detail("Select a valid Minecraft world first".into());
            return;
        }

        let output_dir = output_directory(&ui);
        if let Err(error) = fs::create_dir_all(&output_dir) {
            ui.set_task_title("EXPORT FAILED".into());
            ui.set_task_detail(error.to_string().into());
            return;
        }

        let format = if ui.get_export_format_index() == 1 { "obj" } else { "gltf" };
        let name = sanitize_export_name(&ui.get_export_name().to_string());
        let requested_output = output_dir.join(format!("{name}.{format}"));
        let output = resolve_export_collision(&requested_output);
        let Some(output) = output else {
            ui.set_task_title("EXPORT CANCELLED".into());
            ui.set_task_detail("Existing file was left unchanged".into());
            return;
        };
        let export_mode = match ui.get_export_mode_index() { 1 => "individual", 2 => "merged", _ => "grouped" };
        let (version, loader, mods_path) = selected_runtime_context(&state, &ui, &world);
        let cell_size = [8, 16, 32, 64].get(ui.get_flatter_cell_index().max(0) as usize).copied().unwrap_or(16);
        let (resource_packs, data_packs) = asset_paths(&state);

        let mut request = json!({
            "command": "export",
            "worldPath": world,
            "modsPath": mods_path,
            "modLoader": loader,
            "outputPath": output,
            "format": format,
            "minX": ui.get_min_x(), "minY": ui.get_min_y(), "minZ": ui.get_min_z(),
            "maxX": ui.get_max_x(), "maxY": ui.get_max_y(), "maxZ": ui.get_max_z(),
            "exportMode": export_mode,
            "options": {
                "minecraftVersion": version,
                "modLoader": loader,
                "optimize": bool_text(ui.get_optimize()),
                "faceCulling": bool_text(ui.get_face_culling()),
                "hiddenBlockCulling": bool_text(ui.get_hidden_culling()),
                "blenderExport": bool_text(ui.get_blender_export()),
                "blenderAnimationMode": if ui.get_blender_animation_index() == 1 { "animate_static" } else { "animate_export" },
                "flatterOptimization": bool_text(ui.get_flatter_enabled()),
                "flatterCellSize": cell_size.to_string(),
                "resourcePacks": resource_packs,
                "dataPacks": data_packs
            }
        });
        add_bubble_fields(&ui, &mut request);
        if let Err(error) = attach_exact_selection(&ui, &state, &mut request) {
            ui.set_task_title("EXPORT FAILED".into());
            ui.set_task_detail(error.to_string().into());
            append_diagnostic(&ui, &format!("Could not prepare exact preview selection: {error:#}"));
            return;
        }

        if let Some(registry_path) = cache.ready_path(&version, &mods_path) {
            attach_registry(&mut request, &registry_path);
            send_export_now(&ui, &engine, request, &output);
            return;
        }

        if runtime_registry_supported(&loader, &version, &mods_path) {
            if let Ok(mut guard) = state.lock() {
                guard.pending_export = Some(request);
            }
            ui.set_task_active(false);
            ui.set_task_progress(0.01);
            ui.set_task_title("RUNTIME CACHE".into());
            ui.set_task_detail("Export is waiting for the full Minecraft registry…".into());

            if !cache.is_running_for(&version, &mods_path) {
                if let Err(error) = start_runtime_cache_job(
                    weak.clone(), cache.clone(), engine.clone(), state.clone(), version, mods_path, false, true,
                ) {
                    append_diagnostic(&ui, &format!("Runtime registry could not start: {error:#}"));
                    dispatch_pending_export(&weak, &engine, &state, None, Some(error.to_string()));
                }
            }
            return;
        }

        append_diagnostic(&ui, &format!("{}; exporting through static asset resolvers.", runtime_registry_unavailable_reason(&loader, &version, &mods_path)));
        send_export_now(&ui, &engine, request, &output);
    });
}

fn resolve_export_collision(path: &Path) -> Option<PathBuf> {
    if !path.exists() { return Some(path.to_path_buf()); }
    let replace = MessageDialog::new()
        .set_level(MessageLevel::Warning)
        .set_title("Replace existing Minesport export?")
        .set_description(format!("{} already exists.\n\nYes: replace it.\nNo: keep it and export with the next available name.", path.display()))
        .set_buttons(MessageButtons::YesNo)
        .show();
    if replace == MessageDialogResult::Yes {
        Some(path.to_path_buf())
    } else if replace == MessageDialogResult::No {
        Some(next_export_path(path))
    } else {
        None
    }
}

fn next_export_path(path: &Path) -> PathBuf {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let stem = path.file_stem().and_then(|value| value.to_str()).unwrap_or("Minesport_Export");
    let extension = path.extension().and_then(|value| value.to_str()).unwrap_or("");
    for index in 2u32.. {
        let name = if extension.is_empty() {
            format!("{stem}_{index}")
        } else {
            format!("{stem}_{index}.{extension}")
        };
        let candidate = parent.join(name);
        if !candidate.exists() { return candidate; }
    }
    unreachable!("u32 export suffix space exhausted")
}

fn send_export_now(ui: &MainWindow, engine: &JavaEngine, request: Value, output: &Path) {
    ui.set_task_active(true);
    ui.set_task_progress(0.01);
    ui.set_task_title("EXPORT".into());
    ui.set_task_detail(format!("Preparing {}", output.display()).into());
    append_diagnostic(ui, &format!("IPC -> export · {}", output.display()));
    if let Err(error) = engine.send_value(request) {
        ui.set_task_active(false);
        ui.set_task_title("EXPORT FAILED".into());
        ui.set_task_detail(error.to_string().into());
        append_diagnostic(ui, &format!("Export request failed: {error:#}"));
    }
}

fn wire_preflight(ui: &MainWindow, engine: JavaEngine, state: SharedState) {
    let weak = ui.as_weak();
    ui.on_run_preflight(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let world = PathBuf::from(ui.get_world_path().to_string());
        if !world.join("level.dat").is_file() { return; }
        if let Ok(mut guard) = state.lock() { guard.block_request_purpose = BlockRequestPurpose::Preflight; }

        let mut request = block_list_request(&ui, &world, &state);
        add_bubble_fields(&ui, &mut request);
        ui.set_task_active(true);
        ui.set_task_progress(0.05);
        ui.set_task_title("PREFLIGHT".into());
        ui.set_task_detail("Reading selected block set and preview assets…".into());
        append_diagnostic(&ui, "IPC -> listBlocks · preflight");
        if let Err(error) = engine.send_value(request) {
            ui.set_task_active(false);
            ui.set_task_title("PREFLIGHT FAILED".into());
            ui.set_task_detail(error.to_string().into());
        }
    });
}

fn wire_viewer(ui: &MainWindow, engine: JavaEngine, state: SharedState) {
    let weak = ui.as_weak();
    let open_state = state.clone();
    ui.on_open_3d(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let world = PathBuf::from(ui.get_world_path().to_string());
        if !world.join("level.dat").is_file() { return; }
        viewer_selection::reset();
        if let Ok(mut guard) = open_state.lock() {
            guard.block_request_purpose = BlockRequestPurpose::Preview;
            guard.preview_pick_map = None;
        }

        let mut request = block_list_request(&ui, &world, &open_state);
        add_bubble_fields(&ui, &mut request);
        ui.set_preview_loading(true);
        ui.set_preview_available(false);
        ui.set_preview_focus_label("Building camera-correct Rust preview…".into());
        ui.set_task_active(true);
        ui.set_task_progress(0.05);
        ui.set_task_title("3D PREVIEW".into());
        ui.set_task_detail("Preparing block list for the Rust software renderer…".into());
        if let Err(error) = engine.send_value(request) {
            ui.set_preview_loading(false);
            ui.set_task_active(false);
            ui.set_task_title("3D PREVIEW FAILED".into());
            ui.set_task_detail(error.to_string().into());
        }
    });

    let weak = ui.as_weak();
    let orbit_state = state.clone();
    ui.on_preview_orbit(move |dx, dy| {
        rerender_preview(weak.clone(), orbit_state.clone(), PreviewCameraAction::Orbit { dx, dy });
    });

    let weak = ui.as_weak();
    let pan_state = state.clone();
    ui.on_preview_pan(move |dx, dy| {
        rerender_preview(weak.clone(), pan_state.clone(), PreviewCameraAction::Pan { dx, dy });
    });

    let weak = ui.as_weak();
    let dolly_state = state.clone();
    ui.on_preview_dolly(move |steps| {
        rerender_preview(weak.clone(), dolly_state.clone(), PreviewCameraAction::Dolly(steps));
    });

    let weak = ui.as_weak();
    let fit_state = state.clone();
    ui.on_preview_fit(move || {
        rerender_preview(weak.clone(), fit_state.clone(), PreviewCameraAction::Fit);
    });

    let weak = ui.as_weak();
    ui.on_preview_click(move |mouse_x, mouse_y, view_width, view_height| {
        let Some(ui) = weak.upgrade() else { return; };
        let pick_map = state.lock().ok().and_then(|guard| guard.preview_pick_map.clone());
        let Some(pick_map) = pick_map else {
            ui.set_preview_focus_label("Preview voxel picker is not ready yet".into());
            return;
        };

        let button = ui.get_preview_pick_button();
        if button == 1 {
            if let viewer_selection::PrimaryAction::Confirm(box_selection) = viewer_selection::primary_action() {
                let count = pick_map.blocks_in_box(box_selection.min, box_selection.max).len();
                apply_viewer_box_selection(&ui, &state, box_selection, count);
                return;
            }
        } else if button == 2 {
            if viewer_selection::point_a().is_none() {
                ui.set_preview_focus_label("Set point A with LMB before choosing point B".into());
                return;
            }
        } else {
            return;
        }

        let (image_width, image_height) = pick_map.dimensions();
        let Some((source_x, source_y)) = selection::preview_source_point(
            mouse_x, mouse_y, view_width, view_height, image_width, image_height,
        ) else {
            ui.set_preview_focus_label("Click inside the rendered 3D image".into());
            return;
        };
        let Some(picked) = pick_map.pick(source_x, source_y) else {
            ui.set_preview_focus_label("No solid block under the camera ray".into());
            return;
        };
        let point = [picked.x, picked.y, picked.z];

        if button == 1 {
            viewer_selection::set_point_a(point);
            ui.set_preview_focus_label(format!(
                "POINT A · {},{},{} · RMB chooses point B",
                point[0], point[1], point[2]
            ).into());
            append_diagnostic(&ui, &format!("3D point A set: {},{},{}", point[0], point[1], point[2]));
            return;
        }

        let Some(box_selection) = viewer_selection::set_point_b(point) else {
            return;
        };
        let count = pick_map.blocks_in_box(box_selection.min, box_selection.max).len();
        ui.set_preview_focus_label(format!(
            "POINT B · {},{},{} · {} solid block(s) · LMB confirms · C clears",
            point[0], point[1], point[2], count
        ).into());
        append_diagnostic(&ui, &format!(
            "3D point B set: {},{},{} · box X {}..{} Y {}..{} Z {}..{} · {} solid block(s)",
            point[0], point[1], point[2],
            box_selection.min[0], box_selection.max[0],
            box_selection.min[1], box_selection.max[1],
            box_selection.min[2], box_selection.max[2],
            count,
        ));
    });
}

fn apply_viewer_box_selection(
    ui: &MainWindow,
    state: &SharedState,
    box_selection: viewer_selection::BoxSelection,
    count: usize,
) {
    // Match archive/go-fyne-ui/ui/viewer_launcher.go exactly: confirming the
    // embedded viewer clears custom/exact selection state and writes raw A/B
    // cuboid bounds into the ordinary Box Selection controls.
    ui.set_selection_mode(0);
    ui.set_min_x(box_selection.min[0]);
    ui.set_min_y(box_selection.min[1]);
    ui.set_min_z(box_selection.min[2]);
    ui.set_max_x(box_selection.max[0]);
    ui.set_max_y(box_selection.max[1]);
    ui.set_max_z(box_selection.max[2]);
    ui.set_preview_focus_label(format!(
        "BOX CONFIRMED · {} solid block(s) · X {}..{} · Y {}..{} · Z {}..{}",
        count,
        box_selection.min[0], box_selection.max[0],
        box_selection.min[1], box_selection.max[1],
        box_selection.min[2], box_selection.max[2],
    ).into());
    if let Ok(mut guard) = state.lock() {
        guard.exact_selection = None;
    }
    append_diagnostic(ui, &format!(
        "3D box confirmed: X {}..{} · Y {}..{} · Z {}..{} · {} solid block(s)",
        box_selection.min[0], box_selection.max[0],
        box_selection.min[1], box_selection.max[1],
        box_selection.min[2], box_selection.max[2],
        count,
    ));
}

fn rerender_preview(weak: slint::Weak<MainWindow>, state: SharedState, action: PreviewCameraAction) {
    let Some(ui) = weak.upgrade() else { return; };
    if ui.get_preview_loading() {
        return;
    }
    let pick_map = state.lock().ok().and_then(|guard| guard.preview_pick_map.clone());
    let Some(pick_map) = pick_map else {
        ui.set_preview_focus_label("Preview scene is not ready yet".into());
        return;
    };

    ui.set_preview_loading(true);
    ui.set_preview_focus_label(format!("3D camera · {}…", action.label()).into());
    drop(ui);

    thread::spawn(move || {
        let rendered = match action {
            PreviewCameraAction::Orbit { dx, dy } => pick_map.orbit(dx, dy),
            PreviewCameraAction::Pan { dx, dy } => pick_map.pan(dx, dy),
            PreviewCameraAction::Dolly(steps) => pick_map.dolly(steps),
            PreviewCameraAction::Fit => pick_map.fit(),
        };

        if let Ok(rendered) = &rendered {
            if let Ok(mut guard) = state.lock() {
                guard.preview_pick_map = Some(rendered.pick_map.clone());
            }
        }

        let _ = weak.upgrade_in_event_loop(move |ui| match rendered {
            Ok(rendered) => {
                let buffer = SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(
                    &rendered.rgba,
                    rendered.width,
                    rendered.height,
                );
                ui.set_preview_image(Image::from_rgba8(buffer));
                ui.set_preview_available(true);
                ui.set_preview_loading(false);
                ui.set_preview_block_count(rendered.block_count as i32);
                ui.set_preview_focus_label(format!(
                    "3D camera · {} complete · LMB A · RMB B · LMB confirm · MMB look · wheel dolly",
                    action.label()
                ).into());
            }
            Err(error) => {
                ui.set_preview_loading(false);
                ui.set_preview_focus_label(format!("3D camera update failed: {error:#}").into());
                append_diagnostic(&ui, &format!("3D camera rerender failed: {error:#}"));
            }
        });
    });
}

fn block_list_request(ui: &MainWindow, world: &Path, state: &SharedState) -> Value {
    let (_, loader, mods_path) = selected_runtime_context(state, ui, world);
    json!({
        "command": "listBlocks",
        "worldPath": world,
        "modsPath": mods_path,
        "modLoader": loader,
        "minX": ui.get_min_x(), "minY": ui.get_min_y(), "minZ": ui.get_min_z(),
        "maxX": ui.get_max_x(), "maxY": ui.get_max_y(), "maxZ": ui.get_max_z()
    })
}

fn wire_cache_actions(ui: &MainWindow, engine: JavaEngine, state: SharedState, cache: RuntimeCacheManager) {
    let weak = ui.as_weak();
    let remove_cache = cache.clone();
    ui.on_remove_cache(move || {
        if remove_cache.is_running() { return; }
        let weak = weak.clone();
        let cache = remove_cache.clone();
        thread::spawn(move || {
            let confirmed = MessageDialog::new()
                .set_level(MessageLevel::Warning)
                .set_title("Remove all Minesport cache?")
                .set_description("This deletes regenerable runtime registries, heightmaps/tooling caches and compiled compatibility Bridge cache. Worlds, exports and your real mods folder are not touched.")
                .set_buttons(MessageButtons::YesNo)
                .show();
            if confirmed != MessageDialogResult::Yes { return; }
            let root = runtime::cache_root();
            let result = runtime::remove_generated_cache();
            if result.is_ok() { cache.invalidate(); }
            let _ = weak.upgrade_in_event_loop(move |ui| match result {
                Ok(()) => {
                    ui.set_runtime_cache_status("CLEARED · will rebuild when needed".into());
                    ui.set_task_title("CACHE REMOVED".into());
                    ui.set_task_detail(root.display().to_string().into());
                    append_diagnostic(&ui, &format!("Removed Minesport generated cache: {}", root.display()));
                }
                Err(error) => {
                    ui.set_task_title("CACHE CLEANUP FAILED".into());
                    ui.set_task_detail(error.to_string().into());
                    append_diagnostic(&ui, &format!("Cache cleanup failed: {error:#}"));
                }
            });
        });
    });

    let weak = ui.as_weak();
    let rebuild_cache = cache.clone();
    let rebuild_engine = engine.clone();
    let rebuild_state = state.clone();
    ui.on_rebuild_runtime_cache(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let world = PathBuf::from(ui.get_world_path().to_string());
        if !world.join("level.dat").is_file() { return; }
        let (version, loader, mods) = selected_runtime_context(&rebuild_state, &ui, &world);
        if loader != "fabric" {
            ui.set_task_title("RUNTIME CACHE".into());
            ui.set_task_detail("Full runtime registry currently requires a Fabric instance.".into());
            return;
        }
        if !bridge_compat::is_supported(&version) {
            ui.set_task_title("RUNTIME CACHE".into());
            ui.set_task_detail(format!("No embedded runtime compatibility recipe for Minecraft {version}.").into());
            return;
        }
        if !mods.is_dir() {
            ui.set_task_title("RUNTIME CACHE".into());
            ui.set_task_detail(format!("Mods folder is unavailable: {}", mods.display()).into());
            return;
        }
        if let Err(error) = start_runtime_cache_job(
            weak.clone(), rebuild_cache.clone(), rebuild_engine.clone(), rebuild_state.clone(), version, mods, true, true,
        ) {
            ui.set_task_title("RUNTIME CACHE FAILED".into());
            ui.set_task_detail(error.to_string().into());
        }
    });

    let weak = ui.as_weak();
    ui.on_cancel_runtime_cache(move || {
        if cache.cancel() {
            aux_windows::mark_runtime_cache_cancelling();
            if let Some(ui) = weak.upgrade() {
                ui.set_runtime_cache_status("CANCELLING…".into());
                ui.set_task_detail("Stopping disposable Minecraft registry worker…".into());
                append_diagnostic(&ui, "Runtime registry cancellation requested.");
            }
        }
    });
}

fn start_runtime_cache_job(
    weak: slint::Weak<MainWindow>,
    cache: RuntimeCacheManager,
    engine: JavaEngine,
    state: SharedState,
    version: String,
    mods_path: PathBuf,
    force: bool,
    foreground: bool,
) -> Result<bool> {
    let ui_weak = weak.clone();
    let completion_engine = engine.clone();
    let completion_state = state.clone();
    let window_version = version.clone();
    let window_cache = cache.clone();
    let started = cache.start(version.clone(), mods_path.clone(), force, move |event| match event {
        RuntimeCacheEvent::Progress(progress) => {
            let detail = progress.message.clone();
            let version = window_version.clone();
            let cancel_cache = window_cache.clone();
            let percent = (progress.percent.clamp(0, 100) as f32) / 100.0;
            let _ = ui_weak.upgrade_in_event_loop(move |ui| {
                ui.set_runtime_cache_busy(true);
                ui.set_runtime_cache_status(format!("PREPARING · {}% · {}", progress.percent, detail).into());
                aux_windows::show_runtime_cache(
                    &ui,
                    &version,
                    &detail,
                    percent,
                    move || { let _ = cancel_cache.cancel(); },
                );
                ui.set_task_active(false);
            });
        }
        RuntimeCacheEvent::Complete(result) => {
            let registry_path = result.as_ref().ok().map(|cache| cache.registry_path.clone());
            let error = result.as_ref().err().cloned();
            let reused = result.as_ref().ok().is_some_and(|cache| cache.reused);
            let fingerprint = result.as_ref().ok().map(|cache| cache.fingerprint.clone()).unwrap_or_default();
            let status = match &result {
                Ok(cache) => format!("READY · {} · {}", if cache.reused { "reused" } else { "fresh" }, short_hash(&cache.fingerprint)),
                Err(error) if error.to_ascii_lowercase().contains("cancel") => "CANCELLED".to_string(),
                Err(error) => format!("FAILED · {}", first_line(error)),
            };
            let ui_weak2 = ui_weak.clone();
            let ui_completion_state = completion_state.clone();
            let _ = ui_weak.upgrade_in_event_loop(move |ui| {
                aux_windows::close_runtime_cache(&ui);
                ui.set_runtime_cache_busy(false);
                ui.set_runtime_cache_status(status.into());
                ui.set_task_active(false);
                match &result {
                    Ok(cache_result) => {
                        if foreground && !has_pending_export(&ui_completion_state) {
                            ui.set_task_progress(1.0);
                            ui.set_task_title("RUNTIME REGISTRY READY".into());
                            ui.set_task_detail(cache_result.registry_path.display().to_string().into());
                        }
                        append_diagnostic(&ui, &format!("Full runtime registry ready: {} · fingerprint {}{}", cache_result.registry_path.display(), fingerprint, if reused { " · reused" } else { "" }));
                    }
                    Err(error) => {
                        if foreground && !has_pending_export(&ui_completion_state) {
                            ui.set_task_title("RUNTIME CACHE FAILED".into());
                            ui.set_task_detail(first_line(error).into());
                        }
                        append_diagnostic(&ui, &format!("[WARN] Runtime registry unavailable: {error}"));
                    }
                }
            });
            dispatch_pending_export(&ui_weak2, &completion_engine, &completion_state, registry_path, error);
        }
    })?;

    let _ = weak.upgrade_in_event_loop(move |ui| {
        ui.set_runtime_cache_busy(true);
        ui.set_runtime_cache_status(if force { "PREPARING · forced full-registry rebuild" } else { "PREPARING · full instance registry" }.into());
        ui.set_task_active(false);
        if foreground {
            ui.set_task_title("RUNTIME CACHE".into());
            ui.set_task_detail(format!("Minecraft {version} · full registered block/model registry").into());
        }
    });
    Ok(started)
}

fn dispatch_pending_export(
    weak: &slint::Weak<MainWindow>,
    engine: &JavaEngine,
    state: &SharedState,
    registry_path: Option<PathBuf>,
    cache_error: Option<String>,
) {
    let request = state.lock().ok().and_then(|mut guard| guard.pending_export.take());
    let Some(mut request) = request else { return; };
    if let Some(path) = registry_path {
        attach_registry(&mut request, &path);
    }
    let engine = engine.clone();
    let weak = weak.clone();
    thread::spawn(move || {
        let send_result = engine.send_value(request);
        let _ = weak.upgrade_in_event_loop(move |ui| {
            if let Some(error) = cache_error {
                append_diagnostic(&ui, &format!("Runtime cache failed; continuing export with static resolver fallback: {}", first_line(&error)));
            } else {
                append_diagnostic(&ui, "Runtime registry attached to queued export.");
            }
            match send_result {
                Ok(()) => {
                    ui.set_task_active(true);
                    ui.set_task_progress(0.02);
                    ui.set_task_title("EXPORT".into());
                    ui.set_task_detail("Runtime preparation complete · Java geometry export started".into());
                }
                Err(error) => {
                    ui.set_task_active(false);
                    ui.set_task_title("EXPORT FAILED".into());
                    ui.set_task_detail(error.to_string().into());
                }
            }
        });
    });
}

fn has_pending_export(state: &SharedState) -> bool {
    state.lock().map(|guard| guard.pending_export.is_some()).unwrap_or(false)
}

fn attach_registry(request: &mut Value, path: &Path) {
    if let Some(options) = request.get_mut("options").and_then(Value::as_object_mut) {
        options.insert("bridgeRegistry".to_string(), Value::String(path.display().to_string()));
    }
}

fn attach_exact_selection(ui: &MainWindow, state: &SharedState, request: &mut Value) -> Result<()> {
    let min = [ui.get_min_x(), ui.get_min_y(), ui.get_min_z()];
    let max = [ui.get_max_x(), ui.get_max_y(), ui.get_max_z()];
    let exact = {
        let mut guard = state.lock().map_err(|_| anyhow!("exact-selection state lock poisoned"))?;
        let Some(exact) = guard.exact_selection.clone() else { return Ok(()); };
        if ui.get_selection_mode() != 0 || !exact.matches_bounds(min, max) {
            guard.exact_selection = None;
            ui.set_preview_focus_label("Exact 3D selection cleared because selection bounds changed".into());
            append_diagnostic(ui, "Exact 3D selection cleared after bounds/mode changed");
            return Ok(());
        }
        exact
    };
    let path = selection::write_selection_file(&runtime::cache_root(), &exact)?;
    let options = request.get_mut("options").and_then(Value::as_object_mut)
        .ok_or_else(|| anyhow!("export request does not contain an options object"))?;
    options.insert("customSelectionFile".to_string(), Value::String(path.display().to_string()));
    append_diagnostic(ui, &format!("Exact custom selection attached: {} block(s) · {}", exact.coordinates.len(), path.display()));
    Ok(())
}

fn wire_asset_pickers(ui: &MainWindow, state: SharedState) {
    let weak = ui.as_weak();
    let resource_state = state.clone();
    ui.on_add_resource_pack(move || {
        let weak = weak.clone();
        let state = resource_state.clone();
        thread::spawn(move || {
            let picked = rfd::FileDialog::new().set_title("Add Minecraft resource pack (.zip)").add_filter("Minecraft resource pack", &["zip"]).pick_file();
            let Some(path) = picked else { return; };
            let added = state.lock().map(|mut guard| add_unique_path(&mut guard.resource_packs, path.clone())).unwrap_or(false);
            let _ = weak.upgrade_in_event_loop(move |ui| {
                refresh_asset_summaries(&ui, &state);
                let message = if added {
                    format!("Resource pack added: {}", path.display())
                } else {
                    "Resource pack already exists in the priority stack".to_string()
                };
                append_diagnostic(&ui, &message);
                persist_settings_snapshot(&ui, &state);
            });
        });
    });

    let weak = ui.as_weak();
    let resource_state = state.clone();
    ui.on_add_resource_pack_folder(move || {
        let weak = weak.clone();
        let state = resource_state.clone();
        thread::spawn(move || {
            let picked = rfd::FileDialog::new().set_title("Add Minecraft resource pack folder").pick_folder();
            let Some(path) = picked else { return; };
            let added = state.lock().map(|mut guard| add_unique_path(&mut guard.resource_packs, path.clone())).unwrap_or(false);
            let _ = weak.upgrade_in_event_loop(move |ui| {
                refresh_asset_summaries(&ui, &state);
                let message = if added {
                    format!("Resource pack folder added: {}", path.display())
                } else {
                    "Resource pack already exists in the priority stack".to_string()
                };
                append_diagnostic(&ui, &message);
                persist_settings_snapshot(&ui, &state);
            });
        });
    });

    let weak = ui.as_weak();
    let resource_state = state.clone();
    ui.on_move_resource_pack(move |delta| {
        let Some(ui) = weak.upgrade() else { return; };
        let index = ui.get_resource_pack_selected();
        if let Ok(mut guard) = resource_state.lock() {
            if move_path_entry(&mut guard.resource_packs, index, delta) {
                ui.set_resource_pack_selected((index + delta).max(0));
            }
        }
        refresh_asset_summaries(&ui, &resource_state);
        persist_settings_snapshot(&ui, &resource_state);
    });

    let weak = ui.as_weak();
    let resource_state = state.clone();
    ui.on_remove_resource_pack(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let index = ui.get_resource_pack_selected();
        if let Ok(mut guard) = resource_state.lock() {
            remove_path_entry(&mut guard.resource_packs, index);
        }
        refresh_asset_summaries(&ui, &resource_state);
        persist_settings_snapshot(&ui, &resource_state);
    });

    let weak = ui.as_weak();
    let data_state = state.clone();
    ui.on_add_data_pack(move || {
        let weak = weak.clone();
        let state = data_state.clone();
        thread::spawn(move || {
            let picked = rfd::FileDialog::new().set_title("Add Minecraft data pack folder").pick_folder();
            let Some(path) = picked else { return; };
            let added = state.lock().map(|mut guard| add_unique_path(&mut guard.data_packs, path.clone())).unwrap_or(false);
            let _ = weak.upgrade_in_event_loop(move |ui| {
                refresh_asset_summaries(&ui, &state);
                let message = if added {
                    format!("Data pack added: {}", path.display())
                } else {
                    "Data pack already exists in the priority stack".to_string()
                };
                append_diagnostic(&ui, &message);
                persist_settings_snapshot(&ui, &state);
            });
        });
    });

    let weak = ui.as_weak();
    let data_state = state.clone();
    ui.on_move_data_pack(move |delta| {
        let Some(ui) = weak.upgrade() else { return; };
        let index = ui.get_data_pack_selected();
        if let Ok(mut guard) = data_state.lock() {
            if move_path_entry(&mut guard.data_packs, index, delta) {
                ui.set_data_pack_selected((index + delta).max(0));
            }
        }
        refresh_asset_summaries(&ui, &data_state);
        persist_settings_snapshot(&ui, &data_state);
    });

    let weak = ui.as_weak();
    ui.on_remove_data_pack(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let index = ui.get_data_pack_selected();
        if let Ok(mut guard) = state.lock() {
            remove_path_entry(&mut guard.data_packs, index);
        }
        refresh_asset_summaries(&ui, &state);
        persist_settings_snapshot(&ui, &state);
    });
}

fn refresh_asset_summaries(ui: &MainWindow, state: &SharedState) {
    let (resource_packs, data_packs) = state.lock().map(|guard| (guard.resource_packs.clone(), guard.data_packs.clone())).unwrap_or_default();

    ui.set_resource_pack_summary(if resource_packs.is_empty() {
        "No resource-pack overrides".into()
    } else {
        format!("{} resource-pack override(s) · highest priority first", resource_packs.len()).into()
    });
    ui.set_data_pack_summary(if data_packs.is_empty() {
        "No data-pack overrides".into()
    } else {
        format!("{} data-pack override(s)", data_packs.len()).into()
    });

    ui.set_resource_pack_items(path_model(&resource_packs));
    ui.set_data_pack_items(path_model(&data_packs));
    ui.set_resource_pack_selected(clamp_selection(ui.get_resource_pack_selected(), resource_packs.len()));
    ui.set_data_pack_selected(clamp_selection(ui.get_data_pack_selected(), data_packs.len()));
}

fn path_model(paths: &[PathBuf]) -> ModelRc<SharedString> {
    let rows = paths.iter().enumerate().map(|(index, path)| {
        let kind = match fs::metadata(path) {
            Ok(metadata) if metadata.is_dir() => "FOLDER",
            Ok(_) if path.extension().and_then(|ext| ext.to_str()).is_some_and(|ext| ext.eq_ignore_ascii_case("zip")) => "ZIP",
            Ok(_) => "FILE",
            Err(_) => "MISSING",
        };
        let name = path.file_name().and_then(|value| value.to_str()).filter(|value| !value.is_empty()).unwrap_or_else(|| path.to_str().unwrap_or("path"));
        SharedString::from(format!("{} · {} · {}", index + 1, name, kind))
    }).collect::<Vec<_>>();
    ModelRc::new(VecModel::from(rows))
}

fn clamp_selection(index: i32, len: usize) -> i32 {
    if len == 0 { 0 } else { index.clamp(0, len.saturating_sub(1) as i32) }
}

fn add_unique_path(paths: &mut Vec<PathBuf>, path: PathBuf) -> bool {
    let cleaned = path.components().collect::<PathBuf>();
    if paths.iter().any(|existing| same_asset_path(existing, &cleaned)) {
        return false;
    }
    paths.push(cleaned);
    true
}

fn same_asset_path(left: &Path, right: &Path) -> bool {
    if cfg!(windows) {
        left.to_string_lossy().eq_ignore_ascii_case(&right.to_string_lossy())
    } else {
        left == right
    }
}

fn move_path_entry(paths: &mut [PathBuf], index: i32, delta: i32) -> bool {
    if index < 0 { return false; }
    let Some(target) = index.checked_add(delta) else { return false; };
    if target < 0 { return false; }
    let index = index as usize;
    let target = target as usize;
    if index >= paths.len() || target >= paths.len() { return false; }
    paths.swap(index, target);
    true
}

fn remove_path_entry(paths: &mut Vec<PathBuf>, index: i32) -> bool {
    if index < 0 || index as usize >= paths.len() { return false; }
    paths.remove(index as usize);
    true
}

fn wire_docs(ui: &MainWindow) {
    let weak = ui.as_weak();
    ui.on_open_docs(move || {
        let Some(ui) = weak.upgrade() else { return; };
        show_doc_page(&ui, 0);
        ui.set_docs_visible(true);
    });

    let weak = ui.as_weak();
    ui.on_docs_navigate(move |action| {
        let Some(ui) = weak.upgrade() else { return; };
        let current = ui.get_doc_page().max(1) as usize - 1;
        let next = match action { 0 => 0, -1 => current.saturating_sub(1), 1 => (current + 1).min(DOC_PAGES.len().saturating_sub(1)), 2 => DOC_PAGES.len().saturating_sub(1), _ => current };
        show_doc_page(&ui, next);
    });

    let weak = ui.as_weak();
    ui.on_docs_open_github(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let index = ui.get_doc_page().max(1) as usize - 1;
        if let Some(page) = DOC_PAGES.get(index) { let _ = open_external(page.github); }
    });

    let weak = ui.as_weak();
    ui.on_docs_open_video(move || {
        let Some(ui) = weak.upgrade() else { return; };
        let index = ui.get_doc_page().max(1) as usize - 1;
        if let Some(page) = DOC_PAGES.get(index) { if !page.video.is_empty() { let _ = open_external(page.video); } }
    });
}

fn wire_blender(ui: &MainWindow, state: SharedState) {
    refresh_blender_status(ui.as_weak(), true);

    let weak = ui.as_weak();
    let install_state = state.clone();
    ui.on_install_blender_translator(move || {
        start_blender_install(weak.clone(), install_state.clone(), false);
    });

    let weak = ui.as_weak();
    let prompt_install_state = state.clone();
    ui.on_blender_prompt_install(move || {
        start_blender_install(weak.clone(), prompt_install_state.clone(), true);
    });

    let weak = ui.as_weak();
    ui.on_blender_prompt_later(move || {
        let Some(ui) = weak.upgrade() else { return; };
        ui.set_blender_prompt_visible(false);
        ui.set_blender_translator_prompted(true);
        persist_settings_snapshot(&ui, &state);
        append_diagnostic(&ui, "Blender translator first-run prompt dismissed");
    });
}

fn refresh_blender_status(weak: slint::Weak<MainWindow>, allow_prompt: bool) {
    thread::spawn(move || {
        let status = blender::current_status();
        let _ = weak.upgrade_in_event_loop(move |ui| match status {
            Ok(status) => {
                ui.set_blender_translator_status(blender::status_text(status).into());
                ui.set_blender_translator_current(status.complete());
                if allow_prompt && status.detected > 0 && !ui.get_blender_translator_prompted() {
                    ui.set_blender_prompt_message(format!(
                        "Minesport found {} Blender 4.3+ profile(s).\n\nInstall the Minesport Dynamic Translator? It only translates Minesport metadata during import; it does not run a per-frame animation runtime.",
                        status.detected
                    ).into());
                    ui.set_blender_prompt_visible(true);
                }
            }
            Err(error) => {
                ui.set_blender_translator_current(false);
                ui.set_blender_translator_status(format!(
                    "Blender translator {}: ✕ status check failed",
                    blender::TRANSLATOR_VERSION
                ).into());
                append_diagnostic(&ui, &format!("Blender translator status check failed: {error:#}"));
            }
        });
    });
}

fn start_blender_install(weak: slint::Weak<MainWindow>, state: SharedState, from_prompt: bool) {
    let Some(ui) = weak.upgrade() else { return; };
    if from_prompt {
        ui.set_blender_prompt_visible(false);
        ui.set_blender_translator_prompted(true);
        persist_settings_snapshot(&ui, &state);
    }
    ui.set_task_active(true);
    ui.set_task_progress(0.1);
    ui.set_task_title("BLENDER ADD-ON".into());
    ui.set_task_detail(format!("Installing embedded Minesport translator {}…", blender::TRANSLATOR_VERSION).into());
    drop(ui);

    thread::spawn(move || {
        let result = blender::install_detected_profiles();
        let status = blender::current_status();
        let _ = weak.upgrade_in_event_loop(move |ui| {
            match result {
                Ok(report) => {
                    ui.set_task_active(false);
                    ui.set_task_progress(1.0);
                    ui.set_task_title("BLENDER ADD-ON INSTALLED".into());
                    ui.set_task_detail(format!("Updated {} Blender profile(s)", report.installed_profiles.len()).into());
                    for profile in report.installed_profiles {
                        append_diagnostic(&ui, &format!("Blender translator installed: {}", profile.display()));
                    }
                    if from_prompt {
                        ui.set_blender_export(true);
                        persist_settings_snapshot(&ui, &state);
                    }
                }
                Err(error) => {
                    ui.set_task_active(false);
                    ui.set_task_title("BLENDER INSTALL FAILED".into());
                    ui.set_task_detail(error.to_string().into());
                    append_diagnostic(&ui, &format!("Blender translator installation failed: {error:#}"));
                }
            }

            match status {
                Ok(status) => {
                    ui.set_blender_translator_status(blender::status_text(status).into());
                    ui.set_blender_translator_current(status.complete());
                }
                Err(error) => {
                    ui.set_blender_translator_current(false);
                    ui.set_blender_translator_status(format!(
                        "Blender translator {}: ✕ status check failed",
                        blender::TRANSLATOR_VERSION
                    ).into());
                    append_diagnostic(&ui, &format!("Blender translator status refresh failed: {error:#}"));
                }
            }
        });
    });
}

fn pump_engine_events(weak: slint::Weak<MainWindow>, events: Receiver<EngineEvent>, state: SharedState) {
    thread::spawn(move || {
        let mut pending_logs: Vec<String> = Vec::with_capacity(64);
        loop {
            match events.recv_timeout(Duration::from_millis(100)) {
                Ok(EngineEvent::Started { pid, process }) => pending_logs.push(format!("Started Minesport backend (PID {pid}) with {process}")),
                Ok(EngineEvent::Stderr(line)) => pending_logs.push(format!("[backend] {line}")),
                Ok(EngineEvent::ReadEnded(message)) => {
                    flush_logs(&weak, &mut pending_logs);
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        ui.set_engine_ready(false);
                        ui.set_engine_status("ENGINE STOPPED".into());
                        ui.set_task_active(false);
                        append_diagnostic(&ui, &message);
                    });
                    break;
                }
                Ok(EngineEvent::Response(response)) => {
                    if response.kind == "log" {
                        pending_logs.push(response.message);
                        if pending_logs.len() >= 96 { flush_logs(&weak, &mut pending_logs); }
                    } else {
                        flush_logs(&weak, &mut pending_logs);
                        apply_response(&weak, response, state.clone());
                    }
                }
                Err(RecvTimeoutError::Timeout) => flush_logs(&weak, &mut pending_logs),
                Err(RecvTimeoutError::Disconnected) => { flush_logs(&weak, &mut pending_logs); break; }
            }
        }
    });
}

fn apply_response(weak: &slint::Weak<MainWindow>, response: Response, state: SharedState) {
    if response.kind == "heightmap" {
        let decoded = decode_heightmap_payload(&response.image);
        let bounds = (response.min_x, response.min_z, response.max_x, response.max_z, response.scale);
        let selected_world = state.lock().ok().and_then(|guard| guard.selected_world.clone());
        let cache_note = match (&selected_world, &decoded) {
            (Some(world), Ok((_, png))) => match heightmap_cache::save(world, png, bounds.0, bounds.1, bounds.2, bounds.3, bounds.4) {
                Ok(()) => Some("2D map cached for the next launch".to_string()),
                Err(error) => Some(format!("[WARN] Could not cache 2D map: {error:#}")),
            },
            _ => None,
        };
        let _ = weak.clone().upgrade_in_event_loop(move |ui| {
            ui.set_map_loading(false);
            match decoded {
                Ok((raster, _)) => {
                    apply_map_raster(&ui, raster, bounds.0, bounds.1, bounds.2, bounds.3, bounds.4);
                    ui.set_task_title("MAP READY".into());
                    ui.set_task_detail(format!(
                        "X {}..{} · Z {}..{} · scale {}",
                        bounds.0,
                        bounds.2.saturating_sub(1),
                        bounds.1,
                        bounds.3.saturating_sub(1),
                        bounds.4,
                    ).into());
                    append_diagnostic(&ui, "IPC <- heightmap");
                    if let Some(note) = cache_note { append_diagnostic(&ui, &note); }
                }
                Err(error) => {
                    ui.set_map_available(false);
                    ui.set_task_title("MAP FAILED".into());
                    ui.set_task_detail(error.to_string().into());
                    append_diagnostic(&ui, &format!("Heightmap decode failed: {error:#}"));
                }
            }
        });
        return;
    }

    if response.kind == "blocksReady" {
        let purpose = state.lock().map(|guard| guard.block_request_purpose).unwrap_or_default();
        let path = PathBuf::from(response.file.clone());
        let count = response.count;
        let weak = weak.clone();
        match purpose {
            BlockRequestPurpose::Preflight => {
                thread::spawn(move || {
                    let summary = analyze_preflight(&path);
                    let _ = fs::remove_file(&path);
                    let _ = weak.upgrade_in_event_loop(move |ui| match summary {
                        Ok(summary) => {
                            ui.set_task_active(false);
                            ui.set_task_progress(1.0);
                            ui.set_task_title("PREFLIGHT READY".into());
                            ui.set_task_detail(format!("{count} solid blocks · {} unique block IDs", summary.unique_ids).into());
                            ui.set_preflight_summary(summary.compact.clone().into());
                            append_diagnostic(&ui, &summary.diagnostics);
                        }
                        Err(error) => {
                            ui.set_task_active(false);
                            ui.set_task_title("PREFLIGHT FAILED".into());
                            ui.set_task_detail(error.to_string().into());
                        }
                    });
                });
            }
            BlockRequestPurpose::Preview => {
                let preview_state = state.clone();
                thread::spawn(move || {
                    let rendered = preview::render_file(&path);
                    let _ = fs::remove_file(&path);
                    if let Ok(rendered) = &rendered {
                        if let Ok(mut guard) = preview_state.lock() {
                            guard.preview_pick_map = Some(rendered.pick_map.clone());
                        }
                    } else if let Ok(mut guard) = preview_state.lock() {
                        guard.preview_pick_map = None;
                    }
                    let _ = weak.upgrade_in_event_loop(move |ui| match rendered {
                        Ok(rendered) => {
                            let buffer = SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&rendered.rgba, rendered.width, rendered.height);
                            ui.set_preview_image(Image::from_rgba8(buffer));
                            ui.set_preview_available(true);
                            ui.set_preview_loading(false);
                            ui.set_preview_block_count(rendered.block_count as i32);
                            ui.set_preview_focus_label("LMB point A · RMB point B · LMB again confirms · MMB look · wheel dolly".into());
                            ui.set_task_active(false);
                            ui.set_task_progress(1.0);
                            ui.set_task_title("3D PREVIEW READY".into());
                            ui.set_task_detail(format!("{} blocks · {} rendered", rendered.block_count, rendered.rendered_count).into());
                            append_diagnostic(&ui, &format!("Rust preview renderer ready: {} blocks ({} rendered)", rendered.block_count, rendered.rendered_count));
                        }
                        Err(error) => {
                            ui.set_preview_loading(false);
                            ui.set_preview_available(false);
                            ui.set_task_active(false);
                            ui.set_task_title("3D PREVIEW FAILED".into());
                            ui.set_task_detail(error.to_string().into());
                        }
                    });
                });
            }
        }
        return;
    }

    let _ = weak.clone().upgrade_in_event_loop(move |ui| match response.kind.as_str() {
        "workerInfo" => append_diagnostic(&ui, &format!("[backend] {}", response.message)),
        "info" => append_diagnostic(&ui, &format!("Engine version: {}", response.version)),
        "pong" => {
            ui.set_engine_ready(true);
            ui.set_engine_status("ENGINE READY".into());
            ui.set_task_title("READY".into());
            ui.set_task_detail("Isolated Minesport backend + Java engine IPC online".into());
            append_diagnostic(&ui, "IPC <- pong");
        }
        "progress" => {
            ui.set_task_active(true);
            ui.set_task_progress((response.percent.clamp(0, 100) as f32) / 100.0);
            ui.set_task_detail(response.message.into());
        }
        "done" => {
            ui.set_task_active(false);
            ui.set_task_progress(1.0);
            ui.set_task_title("DONE · EXPORT COMPLETE".into());
            ui.set_task_detail(format!("{} · {} blocks · {} faces · {} vertices", response.output, response.block_count, response.quad_count, response.vertex_count).into());
            append_diagnostic(&ui, &format!("IPC <- done · {}", response.output));
        }
        "error" => {
            ui.set_task_active(false);
            if ui.get_map_loading() { ui.set_map_loading(false); }
            if ui.get_preview_loading() { ui.set_preview_loading(false); }
            ui.set_task_title("ENGINE OPERATION FAILED".into());
            ui.set_task_detail(response.message.clone().into());
            append_diagnostic(&ui, &format!("[ERROR] {}", response.message));
        }
        other => append_diagnostic(&ui, &format!("IPC <- {other}")),
    });
}

#[derive(Debug, Deserialize)]
struct PreflightBlock { id: String }

struct PreflightSummary {
    unique_ids: usize,
    compact: String,
    diagnostics: String,
}

fn analyze_preflight(path: &Path) -> Result<PreflightSummary> {
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let blocks: Vec<PreflightBlock> = serde_json::from_slice(&bytes).context("parse preflight block list")?;
    let mut counts: HashMap<String, usize> = HashMap::new();
    for block in &blocks { *counts.entry(block.id.clone()).or_default() += 1; }
    let mut common: Vec<_> = counts.iter().collect();
    common.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
    let top = common.iter().take(5).map(|(id, count)| format!("{id} × {count}")).collect::<Vec<_>>().join(" · ");
    let transparent = blocks.iter().filter(|block| looks_transparent(&block.id)).count();
    let shape_heavy = blocks.iter().filter(|block| looks_shape_heavy(&block.id)).count();
    let cube_like = blocks.iter().filter(|block| looks_cube_like(&block.id)).count();
    let total = blocks.len().max(1);
    let compact = format!("{} blocks · {} IDs · {} transparent/cutout · {} shape-heavy", blocks.len(), counts.len(), transparent, shape_heavy);
    let diagnostics = format!(
        "Preflight diagnostics:\nSolid blocks: {}\nUnique block states/types: {}\nGeometry upper bound before culling/FLATTER: ~{} faces · ~{} vertices\nMost common: {}\nCube-like IDs: {} ({:.1}%)\nTransparent/cutout-like IDs: {} ({:.1}%)\nShape-heavy IDs: {} ({:.1}%)\nExact faces saved are reported only after Java geometry compilation.",
        blocks.len(), counts.len(), blocks.len() * 6, blocks.len() * 24, if top.is_empty() { "—" } else { &top },
        cube_like, cube_like as f64 * 100.0 / total as f64,
        transparent, transparent as f64 * 100.0 / total as f64,
        shape_heavy, shape_heavy as f64 * 100.0 / total as f64,
    );
    Ok(PreflightSummary { unique_ids: counts.len(), compact, diagnostics })
}

fn looks_transparent(id: &str) -> bool {
    let value = id.to_ascii_lowercase();
    ["glass", "leaves", "water", "ice", "pane", "door", "trapdoor", "flower", "grass", "vine"].iter().any(|needle| value.contains(needle))
}

fn looks_shape_heavy(id: &str) -> bool {
    let value = id.to_ascii_lowercase();
    ["stair", "slab", "fence", "wall", "chair", "bench", "table", "chest", "rail", "bed", "lantern"].iter().any(|needle| value.contains(needle))
}

fn looks_cube_like(id: &str) -> bool {
    !looks_transparent(id) && !looks_shape_heavy(id)
}

struct DecodedRaster {
    rgba: Vec<u8>,
    width: u32,
    height: u32,
}

fn decode_png(bytes: &[u8]) -> Result<DecodedRaster> {
    let rgba = image::load_from_memory(bytes).context("decode heightmap PNG")?.into_rgba8();
    let width = rgba.width();
    let height = rgba.height();
    Ok(DecodedRaster { rgba: rgba.into_raw(), width, height })
}

fn decode_heightmap_payload(encoded: &str) -> Result<(DecodedRaster, Vec<u8>)> {
    if encoded.is_empty() { return Err(anyhow!("heightmap response did not contain PNG data")); }
    let bytes = BASE64.decode(encoded).context("decode heightmap base64")?;
    let raster = decode_png(&bytes)?;
    Ok((raster, bytes))
}

fn apply_map_raster(ui: &MainWindow, raster: DecodedRaster, min_x: i32, min_z: i32, max_x: i32, max_z: i32, scale: i32) {
    let buffer = SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&raster.rgba, raster.width, raster.height);
    ui.set_map_image(Image::from_rgba8(buffer));
    ui.set_map_loading(false);
    ui.set_map_available(true);
    ui.set_map_min_x(min_x);
    ui.set_map_min_z(min_z);
    ui.set_map_max_x(max_x);
    ui.set_map_max_z(max_z);
    ui.set_map_scale(scale);
    if ui.get_min_x() == -256 && ui.get_max_x() == 256 {
        ui.set_min_x(min_x);
        ui.set_max_x(max_x.saturating_sub(1));
        ui.set_min_z(min_z);
        ui.set_max_z(max_z.saturating_sub(1));
    }
}

fn flush_logs(weak: &slint::Weak<MainWindow>, logs: &mut Vec<String>) {
    if logs.is_empty() { return; }
    let batch = logs.join("\n");
    logs.clear();
    let _ = weak.clone().upgrade_in_event_loop(move |ui| append_diagnostic(&ui, &batch));
}

fn append_diagnostic(ui: &MainWindow, line: &str) {
    diagnostics::append(line);
    let current = ui.get_diagnostics().to_string();
    let mut combined = if current.is_empty() { line.to_string() } else { format!("{current}\n{line}") };
    const MAX_BYTES: usize = 64 * 1024;
    if combined.len() > MAX_BYTES {
        let mut start = combined.len() - MAX_BYTES;
        while !combined.is_char_boundary(start) { start += 1; }
        combined = format!("… older diagnostics trimmed …\n{}", &combined[start..]);
    }
    ui.set_diagnostics(combined.into());
    aux_windows::update_debug_console(ui);
}

fn add_bubble_fields(ui: &MainWindow, request: &mut Value) {
    if ui.get_selection_mode() != 1 { return; }
    let Some(object) = request.as_object_mut() else { return; };
    object.insert("centerX".to_string(), json!(ui.get_center_x()));
    object.insert("centerY".to_string(), json!(ui.get_center_y()));
    object.insert("centerZ".to_string(), json!(ui.get_center_z()));
    object.insert("radiusX".to_string(), json!(ui.get_radius_x().max(1)));
    object.insert("radiusY".to_string(), json!(ui.get_radius_y().max(1)));
    object.insert("radiusZ".to_string(), json!(ui.get_radius_z().max(1)));
}

fn asset_paths(state: &SharedState) -> (String, String) {
    let guard = state.lock().expect("asset state");
    let join = |paths: &[PathBuf]| paths.iter().map(|path| path.display().to_string()).collect::<Vec<_>>().join(";");
    (join(&guard.resource_packs), join(&guard.data_packs))
}

fn selected_runtime_context(state: &SharedState, ui: &MainWindow, world: &Path) -> (String, String, PathBuf) {
    if let Ok(guard) = state.lock() {
        if guard.selected_world.as_deref() == Some(world) {
            let version = guard.selected_version.clone().filter(|value| !value.trim().is_empty() && value != "?");
            let loader = guard.selected_loader.clone().filter(|value| !value.trim().is_empty());
            let mods = guard.selected_mods_path.clone();
            if let (Some(version), Some(loader), Some(mods)) = (version, loader, mods) {
                return (version, normalize_loader(&loader), mods);
            }
        }
    }
    (
        ui.get_minecraft_version().to_string(),
        normalize_loader(&ui.get_loader_type().to_string()),
        infer_mods_path(world),
    )
}

fn show_doc_page(ui: &MainWindow, index: usize) {
    let index = index.min(DOC_PAGES.len().saturating_sub(1));
    if let Some(page) = DOC_PAGES.get(index) {
        ui.set_doc_page((index + 1) as i32);
        ui.set_doc_total(DOC_PAGES.len() as i32);
        ui.set_doc_title(page.title.into());
        ui.set_doc_content(page.body.into());
        ui.set_doc_video_available(!page.video.is_empty());
    }
}

fn open_external(url: &str) -> Result<()> {
    if !url.starts_with("https://") { return Err(anyhow!("only https URLs may be opened")); }
    #[cfg(windows)] { Command::new("cmd").args(["/C", "start", "", url]).spawn().context("open URL")?; }
    #[cfg(target_os = "macos")] { Command::new("open").arg(url).spawn().context("open URL")?; }
    #[cfg(all(unix, not(target_os = "macos")))] { Command::new("xdg-open").arg(url).spawn().context("open URL")?; }
    Ok(())
}

fn bool_text(value: bool) -> &'static str { if value { "true" } else { "false" } }

fn normalize_loader(value: &str) -> String {
    let loader = value.trim().to_ascii_lowercase();
    if loader.is_empty() || loader == "—" { "fabric".to_string() } else { loader }
}

fn runtime_registry_supported(loader: &str, version: &str, mods_path: &Path) -> bool {
    loader.eq_ignore_ascii_case("fabric") && mods_path.is_dir() && bridge_compat::is_supported(version)
}

fn runtime_registry_unavailable_reason(loader: &str, version: &str, mods_path: &Path) -> String {
    if !loader.eq_ignore_ascii_case("fabric") {
        return format!("STATIC RESOLVER · full runtime registry is currently Fabric-only for {loader}");
    }
    if !mods_path.is_dir() {
        return format!("STATIC RESOLVER · mods folder unavailable: {}", mods_path.display());
    }
    if !bridge_compat::is_supported(version) {
        return format!("STATIC RESOLVER · no embedded runtime compatibility recipe for Minecraft {version}");
    }
    "STATIC RESOLVER · runtime registry unavailable".to_string()
}

fn output_directory(ui: &MainWindow) -> PathBuf {
    let selected = ui.get_output_path().to_string();
    if !selected.trim().is_empty() { return PathBuf::from(selected); }
    std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")).map(PathBuf::from).unwrap_or_else(std::env::temp_dir).join("Minesport_Exports")
}

fn infer_mods_path(world: &Path) -> PathBuf {
    world.parent().and_then(Path::parent).map(|minecraft| minecraft.join("mods")).unwrap_or_else(|| world.join("mods"))
}

fn detect_loader(world: &Path) -> String {
    let mods = infer_mods_path(world);
    if mods.is_dir() {
        if let Ok(entries) = fs::read_dir(&mods) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
                if name.contains("fabric") || name.contains("sodium") || name.contains("lithium") { return "Fabric".to_string(); }
                if name.contains("neoforge") { return "NeoForge".to_string(); }
                if name.contains("forge") { return "Forge".to_string(); }
                if name.contains("quilt") { return "Quilt".to_string(); }
            }
        }
    }
    "Fabric".to_string()
}

fn detect_minecraft_version(world: &Path) -> Option<String> {
    for component in world.components().rev() {
        let value = component.as_os_str().to_string_lossy();
        for token in value.split(|ch: char| !(ch.is_ascii_digit() || ch == '.')) {
            if looks_like_minecraft_version(token) { return Some(token.to_string()); }
        }
    }
    None
}

fn looks_like_minecraft_version(value: &str) -> bool {
    let parts: Vec<&str> = value.split('.').collect();
    if parts.len() < 2 || parts.len() > 3 || parts.iter().any(|part| part.is_empty() || !part.chars().all(|c| c.is_ascii_digit())) { return false; }
    parts[0] == "1" || parts[0].parse::<u32>().is_ok_and(|major| major >= 20)
}

fn sanitize_export_name(value: &str) -> String {
    let cleaned: String = value.trim().chars().map(|ch| match ch {
        '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
        ch if ch.is_control() => '_',
        _ => ch,
    }).collect();
    let cleaned = cleaned.trim_matches([' ', '.']);
    if cleaned.is_empty() { "Minesport_Export".to_string() } else { cleaned.to_string() }
}

fn short_hash(value: &str) -> &str { value.get(..12).unwrap_or(value) }
fn first_line(value: &str) -> &str { value.lines().next().unwrap_or(value) }

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn detects_instance_version_from_world_path() {
        let path = Path::new(r"C:\launcher\instances\1.21.10\minecraft\saves\test");
        assert_eq!(detect_minecraft_version(path).as_deref(), Some("1.21.10"));
    }

    #[test]
    fn sanitizes_windows_export_name() {
        assert_eq!(sanitize_export_name(" chest:test? "), "chest_test_");
    }

    #[test]
    fn next_export_path_skips_existing_suffixes() {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("minesport-export-path-{}-{stamp}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let base = root.join("scene.gltf");
        fs::write(&base, b"old").unwrap();
        fs::write(root.join("scene_2.gltf"), b"old").unwrap();
        assert_eq!(next_export_path(&base), root.join("scene_3.gltf"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn only_https_external_urls_are_accepted() {
        assert!(open_external("file:///tmp/nope").is_err());
    }

    #[test]
    fn preflight_classifiers_are_conservative() {
        assert!(looks_transparent("minecraft:oak_leaves"));
        assert!(looks_shape_heavy("minecraft:oak_stairs"));
        assert!(looks_cube_like("minecraft:stone"));
    }

    #[test]
    fn runtime_registry_support_follows_embedded_manifest_not_one_hardcoded_version() {
        let mods = Path::new("mods");
        assert!(bridge_compat::is_supported("1.19.4"));
        assert!(bridge_compat::is_supported("1.21.11"));
        assert!(bridge_compat::is_supported("26.2"));
        assert!(!bridge_compat::is_supported("1.5"));
        assert!(!runtime_registry_supported("forge", "1.21.10", mods));
    }

    #[test]
    fn asset_path_move_and_remove_preserve_priority() {
        let mut values = vec![PathBuf::from("A"), PathBuf::from("B"), PathBuf::from("C")];
        assert!(move_path_entry(&mut values, 1, -1));
        assert_eq!(values, vec![PathBuf::from("B"), PathBuf::from("A"), PathBuf::from("C")]);
        assert!(remove_path_entry(&mut values, 1));
        assert_eq!(values, vec![PathBuf::from("B"), PathBuf::from("C")]);
    }
}
