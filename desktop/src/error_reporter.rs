use crate::diagnostics;
use anyhow::{Context, Result, anyhow};
use slint::ComponentHandle;
use std::{
    env,
    fs::File,
    io::{Read, Seek, SeekFrom},
    process::Command,
};

#[cfg(windows)]
use std::{thread, time::Duration};

const REPORTER_ARG: &str = "--error-reporter";
const OPERATION_TAIL_BYTES: u64 = 64 * 1024;
const HUMAN_LOG_TAIL_BYTES: u64 = 48 * 1024;
const HUMAN_LOG_TAIL_LINES: usize = 30;

slint::slint! {
    import { Button, ScrollView } from "std-widgets.slint";

    export component ErrorReporterWindow inherits Window {
        title: "Minesport — Crash Reporter";
        preferred-width: 720px;
        preferred-height: 560px;
        min-width: 600px;
        min-height: 430px;
        background: #111514;

        in-out property <string> headline: "Minesport exited unexpectedly";
        in-out property <string> detail: "";
        in-out property <string> last-operation: "";
        in-out property <string> recent-log: "";
        in-out property <string> log-path: "";
        in-out property <string> operations-path: "";

        callback view-logs();
        callback relaunch();
        callback close-reporter();

        VerticalLayout {
            padding: 18px;
            spacing: 10px;

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
                text: "LAST OPERATION";
                color: #8f9b94;
                font-size: 11px;
                font-weight: 700;
            }

            Text {
                text: root.last-operation;
                color: #d5ddd8;
                font-size: 12px;
                font-family: "monospace";
                wrap: word-wrap;
            }

            Text {
                text: "RECENT LOG";
                color: #8f9b94;
                font-size: 11px;
                font-weight: 700;
            }

            Rectangle {
                vertical-stretch: 1;
                min-height: 150px;
                background: #0d100f;
                border-width: 1px;
                border-color: #343b37;
                recent-scroll := ScrollView {
                    x: 1px;
                    y: 1px;
                    width: parent.width - 2px;
                    height: parent.height - 2px;
                    horizontal-scrollbar-policy: as-needed;
                    vertical-scrollbar-policy: as-needed;
                    VerticalLayout {
                        padding: 10px;
                        Text {
                            text: root.recent-log;
                            color: #c5cec8;
                            font-size: 11px;
                            font-family: "monospace";
                            wrap: no-wrap;
                        }
                    }
                }
            }

            Text {
                text: "DIAGNOSTICS";
                color: #8f9b94;
                font-size: 11px;
                font-weight: 700;
            }

            Text {
                text: root.log-path;
                color: #929c96;
                font-size: 11px;
                font-family: "monospace";
                overflow: elide;
            }

            HorizontalLayout {
                spacing: 9px;
                Button { text: "Open full log"; clicked => { root.view-logs(); } }
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
    window.set_last_operation(
        latest_operation_summary()
            .unwrap_or_else(|| "No complete structured operation record was available.".to_string())
            .into(),
    );
    window.set_recent_log(
        latest_human_log_tail()
            .unwrap_or_else(|| "No recent human diagnostics were available.".to_string())
            .into(),
    );
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

fn latest_operation_summary() -> Option<String> {
    let text = read_file_tail(diagnostics::operations_path(), OPERATION_TAIL_BYTES)?;
    for line in text.lines().rev().filter(|line| !line.trim().is_empty()) {
        let Ok(record) = serde_json::from_str::<serde_json::Value>(line) else { continue; };
        let Some(operation_id) = record.get("operation_id").and_then(serde_json::Value::as_str) else { continue; };
        let trace = record.get("trace_id").and_then(serde_json::Value::as_str).unwrap_or("unknown");
        let event = record.get("event").and_then(serde_json::Value::as_str).unwrap_or("unknown event");
        let level = record.get("level").and_then(serde_json::Value::as_str).unwrap_or("?");
        let message = record.get("message").and_then(serde_json::Value::as_str).unwrap_or_default();
        return Some(format!(
            "{level} · operation={operation_id} · trace={trace}\n{event}: {message}"
        ));
    }
    None
}

fn latest_human_log_tail() -> Option<String> {
    let text = read_file_tail(diagnostics::log_path(), HUMAN_LOG_TAIL_BYTES)?;
    let mut lines = text
        .lines()
        .rev()
        .take(HUMAN_LOG_TAIL_LINES)
        .map(str::to_string)
        .collect::<Vec<_>>();
    lines.reverse();
    if lines.is_empty() { None } else { Some(lines.join("\n")) }
}

fn read_file_tail(path: std::path::PathBuf, maximum_bytes: u64) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let length = file.metadata().ok()?.len();
    let start = length.saturating_sub(maximum_bytes);
    file.seek(SeekFrom::Start(start)).ok()?;

    let mut bytes = Vec::with_capacity((length - start).min(maximum_bytes) as usize);
    file.read_to_end(&mut bytes).ok()?;
    let text = String::from_utf8_lossy(&bytes);
    if start > 0 {
        Some(text.split_once('\n').map(|(_, tail)| tail).unwrap_or_default().to_string())
    } else {
        Some(text.into_owned())
    }
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

    let wait_result = unsafe { wait_for_single_object(handle, INFINITE) };
    if wait_result != WAIT_OBJECT_0 {
        unsafe { close_handle(handle) };
        return Err(std::io::Error::last_os_error()).context("wait for supervised Minesport process");
    }

    let mut exit_code = 0u32;
    let ok = unsafe { get_exit_code_process(handle, &mut exit_code) };
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
