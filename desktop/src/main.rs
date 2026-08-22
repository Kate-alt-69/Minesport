#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod blender;
mod bridge_compat;
mod ipc;
mod launcher;
mod preview;
mod registry;
mod runtime;
mod runtime_cache;
mod runtime_worker;
mod settings;
mod toolchain;

slint::include_modules!();

fn main() -> anyhow::Result<()> {
    app::run()
}
