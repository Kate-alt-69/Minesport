use crate::{MainWindow, diagnostics};
use slint::{CloseRequestResponse, ComponentHandle};
use std::{
    cell::RefCell,
    io::{self, Write},
    process::{Command, Stdio},
};

slint::slint! {
    import { Button, ProgressIndicator, ScrollView, Spinner } from "std-widgets.slint";

    export component DebugConsoleWindow inherits Window {
        title: "Minesport — Debug Console";
        preferred-width: 760px;
        preferred-height: 440px;
        min-width: 560px;
        min-height: 320px;
        background: #101313;

        in-out property <string> diagnostics: "";
        in-out property <string> log-path: "";
        in-out property <string> engine-status: "STARTING";
        in-out property <bool> engine-ready: false;

        callback copy-all();
        callback clear-all();
        callback open-log-folder();

        VerticalLayout {
            spacing: 0px;
            Rectangle {
                height: 50px;
                background: #1b1e1c;
                border-width: 1px;
                border-color: #343a36;
                HorizontalLayout {
                    padding-left: 12px;
                    padding-right: 10px;
                    spacing: 8px;
                    Button { text: "Copy all"; clicked => { root.copy-all(); } }
                    Button { text: "Clear"; clicked => { root.clear-all(); } }
                    Button { text: "Open log folder"; clicked => { root.open-log-folder(); } }
                    Rectangle { horizontal-stretch: 1; background: transparent; }
                    Text {
                        text: root.engine-status;
                        color: root.engine-ready ? #8fc69b : #d39191;
                        font-size: 12px;
                        font-weight: 700;
                        vertical-alignment: center;
                    }
                }
            }
            Rectangle {
                height: 34px;
                background: #151817;
                border-width: 1px;
                border-color: #2d3230;
                Text {
                    x: 12px;
                    width: parent.width - 24px;
                    height: parent.height;
                    text: root.log-path;
                    color: #929c96;
                    font-size: 11px;
                    font-family: "monospace";
                    vertical-alignment: center;
                    overflow: elide;
                }
            }
            ScrollView {
                Text {
                    x: 12px;
                    y: 10px;
                    width: parent.width - 24px;
                    text: root.diagnostics;
                    color: #c7d0ca;
                    font-size: 12px;
                    font-family: "monospace";
                    wrap: word-wrap;
                }
            }
        }
    }

    export component RuntimeCacheWindow inherits Window {
        title: "Minesport — Preparing runtime models";
        preferred-width: 520px;
        preferred-height: 190px;
        min-width: 460px;
        min-height: 180px;
        background: #171b18;

        in-out property <string> stage: "Preparing Minecraft runtime models…";
        in-out property <string> detail: "Exact current mod set";
        in-out property <float> progress: 0.0;
        in-out property <bool> cancelling: false;
        callback cancel();

        VerticalLayout {
            padding: 16px;
            spacing: 10px;
            HorizontalLayout {
                spacing: 10px;
                Spinner { width: 24px; height: 24px; indeterminate: true; }
                Text {
                    horizontal-stretch: 1;
                    text: root.stage;
                    color: #edf1ee;
                    font-size: 14px;
                    font-weight: 700;
                    vertical-alignment: center;
                    overflow: elide;
                }
                Text {
                    text: round(max(0.0, min(1.0, root.progress)) * 100) + "%";
                    color: #cdd5cf;
                    font-size: 12px;
                    font-family: "monospace";
                    font-weight: 700;
                    vertical-alignment: center;
                }
            }
            Text {
                text: root.detail;
                color: #aeb7b1;
                font-size: 12px;
                wrap: word-wrap;
            }
            ProgressIndicator { progress: max(0.0, min(1.0, root.progress)); }
            Rectangle { height: 1px; background: #343a36; }
            HorizontalLayout {
                spacing: 8px;
                Text {
                    horizontal-stretch: 1;
                    text: "Caching Minecraft's registered baked block models. Your real world and mods folder are never modified.";
                    color: #939d97;
                    font-size: 11px;
                    wrap: word-wrap;
                    vertical-alignment: center;
                }
                Button {
                    text: root.cancelling ? "Cancelling…" : "Cancel";
                    enabled: !root.cancelling;
                    clicked => { root.cancelling = true; root.cancel(); }
                }
            }
        }
    }
}

thread_local! {
    static DEBUG_WINDOW: RefCell<Option<DebugConsoleWindow>> = const { RefCell::new(None) };
    static RUNTIME_WINDOW: RefCell<Option<RuntimeCacheWindow>> = const { RefCell::new(None) };
}

pub fn show_debug_console<C, L>(main: &MainWindow, on_closed: C, on_clear: L)
where
    C: Fn() + 'static,
    L: Fn() + 'static,
{
    DEBUG_WINDOW.with(|slot| {
        let mut slot = slot.borrow_mut();
        if slot.is_none() {
            let Ok(window) = DebugConsoleWindow::new() else {
                diagnostics::append("Could not create Slint debug console window");
                return;
            };

            let weak = window.as_weak();
            window.on_copy_all(move || {
                let Some(window) = weak.upgrade() else { return; };
                if let Err(error) = copy_to_clipboard(&window.get_diagnostics().to_string()) {
                    diagnostics::append(&format!("Could not copy debug console text: {error}"));
                }
            });

            let weak = window.as_weak();
            window.on_clear_all(move || {
                if let Some(window) = weak.upgrade() {
                    window.set_diagnostics("".into());
                }
                on_clear();
            });

            window.on_open_log_folder(move || {
                if let Err(error) = open_log_folder() {
                    diagnostics::append(&format!("Could not open diagnostics folder: {error}"));
                }
            });

            window.window().on_close_requested(move || {
                on_closed();
                CloseRequestResponse::HideWindow
            });
            *slot = Some(window);
        }

        if let Some(window) = slot.as_ref() {
            sync_debug_values(window, main);
            if let Err(error) = window.show() {
                diagnostics::append(&format!("Could not show debug console window: {error}"));
            }
        }
    });
}

pub fn update_debug_console(main: &MainWindow) {
    DEBUG_WINDOW.with(|slot| {
        if let Some(window) = slot.borrow().as_ref() {
            sync_debug_values(window, main);
        }
    });
}

pub fn hide_debug_console() {
    DEBUG_WINDOW.with(|slot| {
        if let Some(window) = slot.borrow_mut().take() {
            let _ = window.hide();
        }
    });
}

fn sync_debug_values(window: &DebugConsoleWindow, main: &MainWindow) {
    window.set_diagnostics(main.get_diagnostics());
    window.set_engine_status(main.get_engine_status());
    window.set_engine_ready(main.get_engine_ready());
    window.set_log_path(diagnostics::log_path().display().to_string().into());
}

pub fn show_runtime_cache<C>(main: &MainWindow, version: &str, stage: &str, progress: f32, on_cancel: C)
where
    C: Fn() + 'static,
{
    RUNTIME_WINDOW.with(|slot| {
        let mut slot = slot.borrow_mut();
        if slot.is_none() {
            let Ok(window) = RuntimeCacheWindow::new() else {
                diagnostics::append("Could not create Slint runtime-cache window");
                return;
            };

            let weak = window.as_weak();
            window.on_cancel(move || {
                if let Some(window) = weak.upgrade() {
                    window.set_cancelling(true);
                    window.set_stage("Cancelling runtime-model cache…".into());
                    window.set_detail("Stopping the disposable Minecraft worker safely.".into());
                }
                on_cancel();
            });

            window.window().on_close_requested({
                let weak = window.as_weak();
                move || {
                    if let Some(window) = weak.upgrade() {
                        window.set_cancelling(true);
                        window.set_stage("Cancelling runtime-model cache…".into());
                        window.set_detail("Stopping the disposable Minecraft worker safely.".into());
                        window.invoke_cancel();
                    }
                    CloseRequestResponse::KeepWindowShown
                }
            });
            *slot = Some(window);
        }

        if let Some(window) = slot.as_ref() {
            window.set_cancelling(false);
            window.set_stage(stage.into());
            window.set_detail(format!("Minecraft {version} · exact current mod set").into());
            window.set_progress(progress.clamp(0.0, 1.0));
            let _ = main.hide();
            if let Err(error) = window.show() {
                diagnostics::append(&format!("Could not show runtime-cache window: {error}"));
                let _ = main.show();
            }
        }
    });
}

pub fn update_runtime_cache(version: &str, stage: &str, progress: f32) {
    RUNTIME_WINDOW.with(|slot| {
        if let Some(window) = slot.borrow().as_ref() {
            window.set_stage(stage.into());
            window.set_detail(format!("Minecraft {version} · exact current mod set").into());
            window.set_progress(progress.clamp(0.0, 1.0));
        }
    });
}

pub fn mark_runtime_cache_cancelling() {
    RUNTIME_WINDOW.with(|slot| {
        if let Some(window) = slot.borrow().as_ref() {
            window.set_cancelling(true);
            window.set_stage("Cancelling runtime-model cache…".into());
            window.set_detail("Stopping the disposable Minecraft worker safely.".into());
        }
    });
}

pub fn close_runtime_cache(main: &MainWindow) {
    RUNTIME_WINDOW.with(|slot| {
        if let Some(window) = slot.borrow_mut().take() {
            let _ = window.hide();
        }
    });
    if let Err(error) = main.show() {
        diagnostics::append(&format!("Could not restore Minesport workbench: {error}"));
    }
}

fn copy_to_clipboard(text: &str) -> io::Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let mut command = Command::new("clip.exe");
        command.stdin(Stdio::piped()).creation_flags(CREATE_NO_WINDOW);
        let mut child = command.spawn()?;
        if let Some(stdin) = child.stdin.as_mut() {
            stdin.write_all(text.as_bytes())?;
        }
        child.wait()?;
        return Ok(());
    }

    #[cfg(target_os = "macos")]
    {
        let mut child = Command::new("pbcopy").stdin(Stdio::piped()).spawn()?;
        if let Some(stdin) = child.stdin.as_mut() {
            stdin.write_all(text.as_bytes())?;
        }
        child.wait()?;
        return Ok(());
    }

    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let mut child = Command::new("wl-copy").stdin(Stdio::piped()).spawn()?;
        if let Some(stdin) = child.stdin.as_mut() {
            stdin.write_all(text.as_bytes())?;
        }
        child.wait()?;
        return Ok(());
    }
}

fn open_log_folder() -> io::Result<()> {
    let folder = diagnostics::folder();
    #[cfg(windows)]
    {
        Command::new("explorer.exe").arg(folder).spawn()?;
        return Ok(());
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open").arg(folder).spawn()?;
        return Ok(());
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        Command::new("xdg-open").arg(folder).spawn()?;
        return Ok(());
    }
}
