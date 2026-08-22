#![cfg_attr(windows, windows_subsystem = "windows")]

mod ipc;
mod runtime;

use anyhow::{Context, Result};
use ipc::{Engine, EngineEvent, Response};
use serde_json::json;
use slint::ComponentHandle;
use std::{
    fs,
    path::{Path, PathBuf},
    sync::mpsc::{Receiver, RecvTimeoutError},
    thread,
    time::Duration,
};

slint::include_modules!();

const VERSION: &str = "0.2.0";

fn main() -> Result<()> {
    if handle_cli() {
        return Ok(());
    }

    let ui = MainWindow::new().context("create Minesport Slint window")?;
    wire_file_pickers(&ui);

    let engine_path = runtime::materialize_engine()?;
    let (engine, events) = Engine::start(&engine_path)?;
    pump_engine_events(ui.as_weak(), events);
    wire_export(&ui, engine.clone());
    wire_cache_actions(&ui);

    append_diagnostic(&ui, &format!("Embedded engine: {}", engine_path.display()));
    if let Err(error) = engine.ping() {
        ui.set_engine_status("ENGINE ERROR".into());
        append_diagnostic(&ui, &format!("Could not ping Java engine: {error:#}"));
    }

    ui.run().context("run Minesport Slint event loop")?;
    engine.shutdown();
    Ok(())
}

fn handle_cli() -> bool {
    let mut args = std::env::args().skip(1);
    let Some(arg) = args.next() else { return false; };
    match arg.as_str() {
        "-h" | "--help" => {
            println!("Minesport {VERSION}\nRust + Slint desktop by Kastrick\n\nUsage:\n  minesport                 Open the desktop app\n  minesport --version       Print version\n  minesport --help          Show this help");
            true
        }
        "-V" | "--version" => {
            println!("Minesport {VERSION}");
            true
        }
        _ => false,
    }
}

fn wire_file_pickers(ui: &MainWindow) {
    let weak = ui.as_weak();
    ui.on_pick_world(move || {
        let weak = weak.clone();
        thread::spawn(move || {
            let picked = rfd::FileDialog::new()
                .set_title("Select a Minecraft world")
                .pick_folder();
            let Some(path) = picked else { return; };
            let level_dat = path.join("level.dat");
            let valid = level_dat.is_file();
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("Minecraft World")
                .to_string();
            let version = detect_minecraft_version(&path).unwrap_or_else(|| "1.21.10".to_string());
            let display = path.display().to_string();
            let _ = weak.upgrade_in_event_loop(move |ui| {
                if !valid {
                    append_diagnostic(&ui, &format!("Rejected world without level.dat: {display}"));
                    ui.set_task_title("Invalid world".into());
                    ui.set_task_detail("The selected folder does not contain level.dat".into());
                    return;
                }
                ui.set_world_path(display.clone().into());
                ui.set_world_name(name.into());
                ui.set_minecraft_version(version.clone().into());
                ui.set_loader_type("Fabric".into());
                ui.set_runtime_cache_status("NOT CACHED · runtime backend migration in progress".into());
                ui.set_task_title("World selected".into());
                ui.set_task_detail(format!("Minecraft {version} · Fabric").into());
                append_diagnostic(&ui, &format!("Selected world: {display} (Minecraft {version}, Fabric)"));
            });
        });
    });

    let weak = ui.as_weak();
    ui.on_pick_output(move || {
        let weak = weak.clone();
        thread::spawn(move || {
            let picked = rfd::FileDialog::new()
                .set_title("Choose Minesport export folder")
                .pick_folder();
            let Some(path) = picked else { return; };
            let display = path.display().to_string();
            let _ = weak.upgrade_in_event_loop(move |ui| {
                ui.set_output_path(display.clone().into());
                append_diagnostic(&ui, &format!("Export folder: {display}"));
            });
        });
    });
}

fn wire_export(ui: &MainWindow, engine: Engine) {
    let weak = ui.as_weak();
    ui.on_export_requested(move || {
        let Some(ui) = weak.upgrade() else { return; };
        if !ui.get_engine_ready() || ui.get_task_active() {
            return;
        }

        let world = PathBuf::from(ui.get_world_path().to_string());
        if !world.join("level.dat").is_file() {
            ui.set_task_title("Export blocked".into());
            ui.set_task_detail("Select a valid Minecraft world first".into());
            return;
        }

        let output_dir = output_directory(&ui);
        if let Err(error) = fs::create_dir_all(&output_dir) {
            ui.set_task_title("Export failed".into());
            ui.set_task_detail(error.to_string().into());
            return;
        }
        let format = ui.get_export_format().to_string().to_ascii_lowercase();
        let extension = if format == "obj" { "obj" } else { "gltf" };
        let name = sanitize_export_name(&ui.get_export_name().to_string());
        let output = output_dir.join(format!("{name}.{extension}"));
        let export_mode = match ui.get_export_mode().as_str() {
            "Individual blocks" => "individual",
            "Merged" => "merged",
            _ => "grouped",
        };
        let mods_path = infer_mods_path(&world);
        let version = ui.get_minecraft_version().to_string();
        let loader = ui.get_loader_type().to_string();
        let optimize = ui.get_optimize();

        let request = json!({
            "command": "export",
            "worldPath": world,
            "modsPath": mods_path,
            "modLoader": loader,
            "outputPath": output,
            "format": format,
            "minX": ui.get_min_x(),
            "minY": ui.get_min_y(),
            "minZ": ui.get_min_z(),
            "maxX": ui.get_max_x(),
            "maxY": ui.get_max_y(),
            "maxZ": ui.get_max_z(),
            "exportMode": export_mode,
            "options": {
                "minecraftVersion": version,
                "modLoader": "fabric",
                "optimize": if optimize { "true" } else { "false" },
                "faceCulling": if optimize { "true" } else { "false" }
            }
        });

        ui.set_task_active(true);
        ui.set_task_progress(0.01);
        ui.set_task_title("EXPORT".into());
        ui.set_task_detail(format!("Preparing {}", output.display()).into());
        append_diagnostic(&ui, &format!("IPC -> export · {}", output.display()));

        if let Err(error) = engine.send_value(request) {
            ui.set_task_active(false);
            ui.set_task_title("Export failed".into());
            ui.set_task_detail(error.to_string().into());
            append_diagnostic(&ui, &format!("Export request failed: {error:#}"));
        }
    });
}

fn wire_cache_actions(ui: &MainWindow) {
    let weak = ui.as_weak();
    ui.on_remove_cache(move || {
        let weak = weak.clone();
        thread::spawn(move || {
            let root = runtime::cache_root();
            let result = if root.exists() { fs::remove_dir_all(&root) } else { Ok(()) };
            let _ = weak.upgrade_in_event_loop(move |ui| match result {
                Ok(()) => {
                    ui.set_runtime_cache_status("CLEARED".into());
                    ui.set_task_title("Cache removed".into());
                    ui.set_task_detail(root.display().to_string().into());
                    append_diagnostic(&ui, &format!("Removed Minesport cache root: {}", root.display()));
                }
                Err(error) => {
                    ui.set_task_title("Cache cleanup failed".into());
                    ui.set_task_detail(error.to_string().into());
                    append_diagnostic(&ui, &format!("Cache cleanup failed: {error}"));
                }
            });
        });
    });

    let weak = ui.as_weak();
    ui.on_rebuild_runtime_cache(move || {
        if let Some(ui) = weak.upgrade() {
            ui.set_task_title("Runtime registry".into());
            ui.set_task_detail("Bridge registry backend is being migrated from the headless Go service next".into());
            append_diagnostic(&ui, "Runtime-registry UI is ready; backend handoff is the next migration stage.");
        }
    });
}

fn pump_engine_events(weak: slint::Weak<MainWindow>, events: Receiver<EngineEvent>) {
    thread::spawn(move || {
        let mut pending_logs: Vec<String> = Vec::with_capacity(64);
        loop {
            match events.recv_timeout(Duration::from_millis(100)) {
                Ok(EngineEvent::Started { pid, java }) => pending_logs.push(format!("Started Java engine (PID {pid}) with {java}")),
                Ok(EngineEvent::Stderr(line)) => pending_logs.push(format!("[java] {line}")),
                Ok(EngineEvent::ReadEnded(message)) => {
                    flush_logs(&weak, &mut pending_logs);
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        ui.set_engine_ready(false);
                        ui.set_engine_status("ENGINE STOPPED".into());
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
                        apply_response(&weak, response);
                    }
                }
                Err(RecvTimeoutError::Timeout) => flush_logs(&weak, &mut pending_logs),
                Err(RecvTimeoutError::Disconnected) => {
                    flush_logs(&weak, &mut pending_logs);
                    break;
                }
            }
        }
    });
}

fn apply_response(weak: &slint::Weak<MainWindow>, response: Response) {
    let _ = weak.clone().upgrade_in_event_loop(move |ui| match response.kind.as_str() {
        "info" => append_diagnostic(&ui, &format!("Engine version: {}", response.version)),
        "pong" => {
            ui.set_engine_ready(true);
            ui.set_engine_status("ENGINE READY".into());
            ui.set_task_title("Ready".into());
            ui.set_task_detail("Java engine IPC online".into());
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
            ui.set_task_title("Export complete".into());
            ui.set_task_detail(format!("{} · {} blocks · {} faces · {} vertices", response.output, response.block_count, response.quad_count, response.vertex_count).into());
            append_diagnostic(&ui, &format!("IPC <- done · {}", response.output));
        }
        "error" => {
            ui.set_task_active(false);
            ui.set_task_title("Engine operation failed".into());
            ui.set_task_detail(response.message.clone().into());
            append_diagnostic(&ui, &format!("[ERROR] {}", response.message));
        }
        other => append_diagnostic(&ui, &format!("IPC <- {other}")),
    });
}

fn flush_logs(weak: &slint::Weak<MainWindow>, logs: &mut Vec<String>) {
    if logs.is_empty() { return; }
    let batch = logs.join("\n");
    logs.clear();
    let _ = weak.clone().upgrade_in_event_loop(move |ui| append_diagnostic(&ui, &batch));
}

fn append_diagnostic(ui: &MainWindow, line: &str) {
    let current = ui.get_diagnostics().to_string();
    let mut combined = if current.is_empty() { line.to_string() } else { format!("{current}\n{line}") };
    const MAX_BYTES: usize = 48 * 1024;
    if combined.len() > MAX_BYTES {
        let mut start = combined.len() - MAX_BYTES;
        while !combined.is_char_boundary(start) { start += 1; }
        combined = format!("… older diagnostics trimmed …\n{}", &combined[start..]);
    }
    ui.set_diagnostics(combined.into());
}

fn output_directory(ui: &MainWindow) -> PathBuf {
    let selected = ui.get_output_path().to_string();
    if !selected.trim().is_empty() { return PathBuf::from(selected); }
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("Minesport_Exports")
}

fn infer_mods_path(world: &Path) -> PathBuf {
    world.parent().and_then(Path::parent).map(|minecraft| minecraft.join("mods")).unwrap_or_else(|| world.join("mods"))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_instance_version_from_world_path() {
        let path = Path::new(r"C:\launcher\instances\1.21.10\minecraft\saves\test");
        assert_eq!(detect_minecraft_version(path).as_deref(), Some("1.21.10"));
    }

    #[test]
    fn sanitizes_windows_export_name() {
        assert_eq!(sanitize_export_name(" chest:test? "), "chest_test_");
    }
}
