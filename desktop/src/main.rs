#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod blender;
mod ipc;
mod launcher;
mod preview;
mod registry;
mod runtime;
mod runtime_cache;
mod runtime_worker;
mod settings;

slint::include_modules!();

fn main() -> anyhow::Result<()> {
    app::run()
}
