#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod aux_windows;
mod blender;
mod bridge_build;
mod bridge_cli;
mod bridge_compat;
mod diagnostics;
mod error_reporter;
mod heightmap_cache;
mod ipc;
mod launcher;
mod preview;
mod preview_picking;
#[allow(dead_code)]
mod registry;
mod runtime;
#[allow(dead_code)]
mod runtime_cache;
#[allow(dead_code)]
mod runtime_worker;
mod selection;
mod settings;
mod toolchain;
mod viewer_selection;
mod world_context;
mod world_picker;

// These are intentionally kept as typed migration anchors until the final
// Fyne viewer gestures are connected to Slint. Unlike `allow(dead_code)`, the
// compiler still has to type-check the exact production API we are about to
// consume, so signature drift cannot hide during the remaining viewer port.
const _: fn(
    &preview::PreviewPickMap,
    [i32; 3],
    [i32; 3],
) -> anyhow::Result<preview::RenderedPreview> = preview::PreviewPickMap::highlight_box;
const _: fn(&preview::PreviewPickMap) -> anyhow::Result<preview::RenderedPreview> =
    preview::PreviewPickMap::clear_highlight;
const _: fn([f32; 3], i32) -> Option<viewer_selection::BoxSelection> =
    viewer_selection::resize_point_b;

slint::include_modules!();

fn main() -> anyhow::Result<()> {
    // The reporter is deliberately handled before normal diagnostics startup.
    // It must stay a tiny independent process that can survive the Workbench.
    if error_reporter::handle_mode()? {
        return Ok(());
    }

    let log = diagnostics::initialize()?;
    diagnostics::append(&format!("Persistent diagnostics log: {}", log.display()));

    match bridge_cli::handle() {
        Ok(true) => {
            diagnostics::append("Minesport Rust CLI command completed cleanly");
            return Ok(());
        }
        Ok(false) => {}
        Err(error) => {
            diagnostics::append(&format!("Minesport Rust CLI command failed: {error:#}"));
            return Err(error);
        }
    }

    if error_reporter::should_supervise_current_invocation() {
        if let Err(error) = error_reporter::spawn_for_current_process() {
            diagnostics::Logger::new("REPORTER").warn(
                "ErrorReporterSupervisorUnavailable",
                "Minesport will continue without the standalone crash reporter",
                &[("error", format!("{error:#}"))],
            );
        }
    }

    let result = app::run();
    diagnostics::append(if result.is_ok() { "Minesport desktop exited cleanly" } else { "Minesport desktop exited with an error" });
    result
}
