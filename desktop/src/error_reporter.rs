use crate::diagnostics;
use anyhow::{Context, Result, anyhow};
use slint::ComponentHandle;
use std::{env, process::Command};

#[cfg(windows)]
use std::{thread, time::Duration};

const REPORTER_ARG: &str = "--error-reporter";

slint::slint! {
    import { Button } from "std-widgets.slint";

    export component ErrorReporterWindow inherits Window {
        title: "Minesport — Crash Reporter";
        preferred-width: 620px;
        preferred-height: 330px;
        background: #111514;

        in-out property <string> headline: "Minesport exited unexpectedly";
        in-out property <string> detail: "";
        in-out property <string> log-path: "";
        in-out property <string> operations-path: "";

        callback view-logs();
        callback relaunch();
        callback close-reporter();

        VerticalLayout {
            padding: 18px;
            spacing: 12px;

            Text {
                text: root.headline;
                color: #f1f5f2;
                font-size: 21px;
                font-weight: 700;
            }

            Text {
                text: root.detail;
                color: #c4cdc7;
                font-size: 13px;
                wrap: word-wrap;
            }

            Rectangle {
                height: 1px;
                background: #39423d;
            }

            Text {
                text: "DIAGNOSTICS";
                color: #8f9b94;
                font-size: 11px;
                font-weight: 700;
            }

            Text {
                text: "Human log: " + root.log-path;
                color: #aeb8b1;
                font-size: 12px;
                font-family: "monospace";
                wrap: word-wrap;
            }

            Text {
                text: "Operations: " + root.operations-path;
                color: #aeb8b1;
                font-size: 12px;
                font-family: "monospace";
                wrap: word-wrap;
            }

            Rectangle { vertical-stretch: 1; background: transparent; }

            HorizontalLayout {
                spacing: 9px;
                Button { text: "View logs"; clicked => { root.view-logs(); } }
                Rectangle { horizontal-stretch: 1; background: transparent; }
                Button { text: "Close"; clicked => { root.close-reporter(); } }
                Button { text: "Relaunch Minesport"; clicked => { root.relaunch(); } }
            }
        }
    }
}

/// Reporter mode must be handled before normal diagnostics/desktop startup so
/// the helper remains independent of the process it is supervising.
pub fn handle_mode() -> Result<bool> {
    let mut args = env::args().skip(1);
    let Some(first) = args.next() else { return Ok(false); };
    if first != REPORTER_ARG {
        return Ok(false);
    }

    let pid = args
        .next()
        .ok_or_else(|| anyhow!("error reporter parent PID is missing"))?
        .parse::<u32>()
        .context("parse error reporter parent PID")?;
    run_reporter(pid)?;
    Ok(true)
}

/// Only a normal no-argument desktop launch is supervised. Internal worker,
/// bridge CLI, Blender installer, and reporter invocations must never recurse.
pub fn should_supervise_current_invocation() -> bool {
    env::args_os().len() == 1
}

pub fn spawn_for_current_process() -> Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;

        let logger = diagnostics::Logger::new("REPORTER");
        let operation = logger.operation("ErrorReporterSupervisorSpawn");
        let executable = env::current_exe().context("resolve Minesport executable for crash reporter")?;
        let pid = std::process::id();
        let mut command = Command::new(&executable);
        command
            .arg(REPORTER_ARG)
            .arg(pid.to_string())
            .creation_flags(CREATE_NO_WINDOW);
        match command.spawn() {
            Ok(child) => {
                operation.success(
                    "standalone crash reporter started",
                    &[
                        ("reporter_pid", child.id().to_string()),
                        ("supervised_pid", pid.to_string()),
                        ("executable", executable.display().to_string()),
                    ],
                );
                Ok(())
            }
            Err(error) => {
                operation.failure(
                    "could not start standalone crash reporter",
                    &[("error", error.to_string())],
                );
                Err(error).context("start Minesport crash reporter")
            }
        }
    }

    #[cfg(not(windows))]
    {
        // Windows is the packaged desktop target today. Keeping this a clean
        // no-op preserves cross-platform Rust builds until equivalent native
        // parent-exit status APIs are added for macOS/Linux.
        Ok(())
    }
}

fn run_reporter(parent_pid: u32) -> Result<()> {
    let exit_code = wait_for_parent_exit(parent_pid)?;
    if exit_code == 0 {
        return Ok(());
    }

    let window = ErrorReporterWindow::new().context("create Minesport crash reporter window")?;
    window.set_headline(format_exit_headline(exit_code).into());
    window.set_detail(format_exit_detail(exit_code, parent_pid).into());
    window.set_log_path(diagnostics::log_path().display().to_string().into());
    window.set_operations_path(diagnostics::operations_path().display().to_string().into());

    window.on_view_logs(move || {
        let _ = open_logs();
    });

    let weak = window.as_weak();
    window.on_relaunch(move || {
        if let Ok(executable) = env::current_exe() {
            let _ = Command::new(executable).spawn();
        }
        if let Some(window) = weak.upgrade() {
            let _ = window.hide();
        }
    });

    let weak = window.as_weak();
    window.on_close_reporter(move || {
        if let Some(window) = weak.upgrade() {
            let _ = window.hide();
        }
    });

    window.run().context("run Minesport crash reporter window")?;
    Ok(())
}

fn format_exit_headline(exit_code: u32) -> String {
    if exit_code <= i32::MAX as u32 {
        format!("Minesport exited unexpectedly — error code {exit_code}")
    } else {
        format!("Minesport exited unexpectedly — status 0x{exit_code:08X}")
    }
}

fn format_exit_detail(exit_code: u32, parent_pid: u32) -> String {
    let code_detail = if exit_code <= i32::MAX as u32 {
        exit_code.to_string()
    } else {
        format!("0x{exit_code:08X} ({})", exit_code as i32)
    };
    format!(
        "The Minesport desktop process (PID {parent_pid}) ended with status {code_detail}. The crash reporter is a separate process, so you can inspect the logs or relaunch Minesport even though the main Workbench has already exited."
    )
}

fn open_logs() -> Result<()> {
    #[cfg(windows)]
    {
        let log = diagnostics::log_path();
        Command::new("explorer.exe")
            .arg(format!("/select,{}", log.display()))
            .spawn()
            .context("open Minesport diagnostics folder")?;
        return Ok(());
    }
    #[cfg(target_os = "macos")]
    {
        Command::new("open")
            .arg(diagnostics::folder())
            .spawn()
            .context("open Minesport diagnostics folder")?;
        return Ok(());
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        Command::new("xdg-open")
            .arg(diagnostics::folder())
            .spawn()
            .context("open Minesport diagnostics folder")?;
        return Ok(());
    }
}

#[cfg(windows)]
fn wait_for_parent_exit(parent_pid: u32) -> Result<u32> {
    use std::ffi::c_void;

    type Handle = *mut c_void;
    const SYNCHRONIZE: u32 = 0x0010_0000;
    const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;
    const INFINITE: u32 = 0xFFFF_FFFF;
    const WAIT_OBJECT_0: u32 = 0;

    #[link(name = "kernel32")]
    unsafe extern "system" {
        #[link_name = "OpenProcess"]
        fn open_process(desired_access: u32, inherit_handle: i32, process_id: u32) -> Handle;
        #[link_name = "WaitForSingleObject"]
        fn wait_for_single_object(handle: Handle, milliseconds: u32) -> u32;
        #[link_name = "GetExitCodeProcess"]
        fn get_exit_code_process(process: Handle, exit_code: *mut u32) -> i32;
        #[link_name = "CloseHandle"]
        fn close_handle(object: Handle) -> i32;
    }

    let mut handle = std::ptr::null_mut();
    for _ in 0..50 {
        // SAFETY: OpenProcess receives a PID from our trusted parent command
        // line and returns an owned kernel handle or null. No borrowed memory.
        handle = unsafe {
            open_process(
                SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION,
                0,
                parent_pid,
            )
        };
        if !handle.is_null() {
            break;
        }
        thread::sleep(Duration::from_millis(20));
    }
    if handle.is_null() {
        return Err(std::io::Error::last_os_error()).context("open supervised Minesport process");
    }

    // SAFETY: `handle` is a valid process handle owned by this function.
    let wait_result = unsafe { wait_for_single_object(handle, INFINITE) };
    if wait_result != WAIT_OBJECT_0 {
        // SAFETY: close the valid handle before returning the wait failure.
        unsafe { close_handle(handle) };
        return Err(std::io::Error::last_os_error()).context("wait for supervised Minesport process");
    }

    let mut exit_code = 0u32;
    // SAFETY: both the process handle and output pointer are valid for this call.
    let ok = unsafe { get_exit_code_process(handle, &mut exit_code) };
    // SAFETY: this is the final use of the owned process handle.
    unsafe { close_handle(handle) };
    if ok == 0 {
        return Err(std::io::Error::last_os_error()).context("read Minesport process exit code");
    }
    Ok(exit_code)
}

#[cfg(not(windows))]
fn wait_for_parent_exit(_parent_pid: u32) -> Result<u32> {
    Ok(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exit_headline_prefers_plain_codes_and_hex_crash_statuses() {
        assert!(format_exit_headline(1).contains("error code 1"));
        assert!(format_exit_headline(0xC0000005).contains("0xC0000005"));
    }

    #[test]
    fn reporter_detail_includes_pid_and_status() {
        let detail = format_exit_detail(1, 4242);
        assert!(detail.contains("4242"));
        assert!(detail.contains("status 1"));
    }
}
