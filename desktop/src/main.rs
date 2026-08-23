#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod blender;
mod bridge_compat;
mod diagnostics;
mod heightmap_cache;
mod ipc;
mod launcher;
mod preview;
mod registry;
mod runtime;
mod runtime_cache;
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
    let result = app::run();
    diagnostics::append(if result.is_ok() { "Minesport desktop exited cleanly" } else { "Minesport desktop exited with an error" });
    result
}
