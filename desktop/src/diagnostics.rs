use crate::runtime;
use anyhow::{Context, Result};
use std::{
    collections::VecDeque,
    fs::{self, File, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::{Mutex, OnceLock},
    time::{SystemTime, UNIX_EPOCH},
};

static LOG_FILE: OnceLock<Mutex<File>> = OnceLock::new();
static DISPLAY_LOG: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();
const MAX_DISPLAY_LINES: usize = 4096;

pub fn folder() -> PathBuf {
    runtime::data_root().join("diagnostics")
}

pub fn log_path() -> PathBuf {
    folder().join("minesport.log")
}

fn display_log() -> &'static Mutex<VecDeque<String>> {
    DISPLAY_LOG.get_or_init(|| Mutex::new(VecDeque::with_capacity(512)))
}

pub fn initialize() -> Result<PathBuf> {
    let folder = folder();
    fs::create_dir_all(&folder).with_context(|| format!("create diagnostics directory {}", folder.display()))?;
    let path = log_path();
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .with_context(|| format!("open diagnostics log {}", path.display()))?;
    let _ = LOG_FILE.set(Mutex::new(file));
    let _ = display_log();
    append("--- Minesport 0.2.0 Rust/Slint session started ---");
    Ok(path)
}

pub fn append(message: &str) {
    let now = SystemTime::now();
    let epoch_ms = now.duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let display_stamp = local_clock_stamp(now);
    let repaired = repair_common_mojibake(message);

    if let Some(lock) = LOG_FILE.get() {
        if let Ok(mut file) = lock.lock() {
            for line in repaired.lines() {
                let _ = writeln!(file, "{epoch_ms}  {line}");
            }
            let _ = file.flush();
        }
    }

    if let Ok(mut lines) = display_log().lock() {
        for line in repaired.lines() {
            lines.push_back(format!("[{display_stamp}] {line}"));
            while lines.len() > MAX_DISPLAY_LINES {
                lines.pop_front();
            }
        }
    }
}

pub fn display_text() -> String {
    display_log()
        .lock()
        .map(|lines| lines.iter().map(String::as_str).collect::<Vec<_>>().join("\n"))
        .unwrap_or_default()
}

pub fn clear_display() {
    if let Ok(mut lines) = display_log().lock() {
        lines.clear();
    }
}

fn repair_common_mojibake(value: &str) -> String {
    value
        .replace("ΓåÆ", "→")
        .replace("ΓåÉ", "←")
        .replace("ΓÇ║", "›")
        .replace("┬╖", "·")
        .replace("├ù", "×")
        .replace("ΓÇö", "—")
        .replace("ΓÇª", "…")
}

#[cfg(windows)]
fn local_clock_stamp(_now: SystemTime) -> String {
    #[repr(C)]
    #[derive(Default)]
    struct SystemTimeFields {
        year: u16,
        month: u16,
        day_of_week: u16,
        day: u16,
        hour: u16,
        minute: u16,
        second: u16,
        milliseconds: u16,
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        #[link_name = "GetLocalTime"]
        fn get_local_time(system_time: *mut SystemTimeFields);
    }

    let mut local = SystemTimeFields::default();
    // SAFETY: GetLocalTime writes one complete SYSTEMTIME-compatible struct to
    // the valid pointer supplied here and retains no reference to it.
    unsafe { get_local_time(&mut local) };
    let _ = (local.year, local.month, local.day_of_week, local.day);
    format!(
        "{:02}:{:02}:{:02}.{:03}",
        local.hour, local.minute, local.second, local.milliseconds
    )
}

#[cfg(not(windows))]
fn local_clock_stamp(now: SystemTime) -> String {
    // `std` does not expose the host timezone offset cross-platform. Keep the
    // fallback explicit rather than pretending UTC is local time.
    let total_ms = now.duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let day_ms = total_ms % 86_400_000;
    let hour = day_ms / 3_600_000;
    let minute = (day_ms / 60_000) % 60;
    let second = (day_ms / 1_000) % 60;
    let millis = day_ms % 1_000;
    format!("UTC {hour:02}:{minute:02}:{second:02}.{millis:03}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagnostics_path_is_namespaced() {
        let path = log_path().to_string_lossy().to_ascii_lowercase();
        assert!(path.contains("minesport"));
        assert!(path.ends_with("minesport.log"));
    }

    #[test]
    fn repairs_known_windows_mojibake_sequences() {
        assert_eq!(
            repair_common_mojibake("IPC ΓåÆ Java ┬╖ 3 ├ù 4 ΓÇª"),
            "IPC → Java · 3 × 4 …"
        );
    }
}
