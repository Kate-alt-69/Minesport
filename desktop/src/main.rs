#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod blender;
mod bridge_build;
mod bridge_cli;
mod bridge_compat;
mod diagnostics;
mod heightmap_cache;
mod ipc;
mod launcher;
mod preview;
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
mod world_context;
mod world_picker;

slint::include_modules!();

fn main() -> anyhow::Result<()> {
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

    let result = app::run();
    diagnostics::append(if result.is_ok() { "Minesport desktop exited cleanly" } else { "Minesport desktop exited with an error" });
    result
}
