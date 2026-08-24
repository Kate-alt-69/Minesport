#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod aux_windows;
mod blender;
mod bridge_build;
mod bridge_cli;
mod bridge_compat;
#[allow(dead_code)]
mod bridge_family;
mod bridge_java;
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
const _: fn(&preview::PreviewPickMap) -> [f32; 3] = preview::PreviewPickMap::look_direction;
const _: fn([f32; 3], i32) -> Option<viewer_selection::BoxSelection> =
    viewer_selection::resize_point_b;

// Loader-family runtime wiring is landing incrementally in the Slint app.
// Keep every embedded loader materializer type-checked in normal builds until
// the UI/runtime path consumes all four directly.
const _: [fn() -> anyhow::Result<std::path::PathBuf>; 3] = [
    runtime::materialize_bundled_forge_bridge,
    runtime::materialize_bundled_neoforge_bridge,
    runtime::materialize_bundled_quilt_bridge,
];

slint::include_modules!();

fn main() -> anyhow::Result<()> {
    // The reporter is deliberately handled before normal diagnostics startup.
    // It must stay a tiny independent process that can survive the Workbench.
    if error_reporter::handle_mode()? {
        return Ok(());
    }

    let log = diagnostics::initialize()?;
    diagnostics::append(&format!("Persistent diagnostics log: {}", log.display()));
    install_panic_hook();

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

    let desktop_operation = diagnostics::Logger::new("DESKTOP")
        .operation("DesktopApplicationRunLifecycle");
    let result = app::run();
    match &result {
        Ok(()) => desktop_operation.success("Minesport desktop exited cleanly", &[]),
        Err(error) => desktop_operation.failure(
            "Minesport desktop exited with an error",
            &[("error", format!("{error:#}"))],
        ),
    }
    result
}

fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |panic_info| {
        let payload = panic_info
            .payload()
            .downcast_ref::<&str>()
            .map(|value| (*value).to_string())
            .or_else(|| panic_info.payload().downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "non-string panic payload".to_string());
        let location = panic_info
            .location()
            .map(|location| format!("{}:{}:{}", location.file(), location.line(), location.column()))
            .unwrap_or_else(|| "unknown".to_string());
        let thread_name = std::thread::current()
            .name()
            .unwrap_or("unnamed")
            .to_string();

        diagnostics::Logger::new("PANIC")
            .operation("DesktopUnhandledRustPanic")
            .field("thread", thread_name)
            .field("location", location)
            .failure(
                "unhandled Rust panic reached the process panic hook",
                &[("payload", payload)],
            );
        previous(panic_info);
    }));
}
