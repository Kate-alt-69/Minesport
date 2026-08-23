use crate::runtime;
use anyhow::{Context, Result};
use serde_json::{Map, Value, json};
use std::{
    collections::VecDeque,
    fmt::Display,
    fs::{self, File, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::{Mutex, OnceLock, atomic::{AtomicU64, Ordering}},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

static LOG_FILE: OnceLock<Mutex<File>> = OnceLock::new();
static OPERATIONS_FILE: OnceLock<Mutex<File>> = OnceLock::new();
static DISPLAY_LOG: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();
static OPERATION_SEQUENCE: AtomicU64 = AtomicU64::new(1);
const MAX_DISPLAY_LINES: usize = 4096;

#[derive(Debug, Clone, Copy)]
pub enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

impl Level {
    fn as_str(self) -> &'static str {
        match self {
            Self::Debug => "DEBUG",
            Self::Info => "INFO",
            Self::Warn => "WARN",
            Self::Error => "ERROR",
        }
    }
}

/// RBE-style named logger with Minesport-specific operational events.
///
/// Human-readable lines keep module/event names visible while the matching
/// JSONL record preserves fields as structured data for diagnostics tooling.
#[derive(Debug, Clone)]
pub struct Logger {
    module: String,
}

impl Logger {
    pub fn new(module: impl Into<String>) -> Self {
        Self { module: module.into().to_uppercase() }
    }

    pub fn child(&self, sub_module: impl Display) -> Self {
        Self { module: format!("{}:{}", self.module, sub_module) }
    }

    pub fn debug(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit(Level::Debug, &self.module, event, None, &message.to_string(), fields);
    }

    pub fn info(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit(Level::Info, &self.module, event, None, &message.to_string(), fields);
    }

    pub fn warn(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit(Level::Warn, &self.module, event, None, &message.to_string(), fields);
    }

    pub fn error(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit(Level::Error, &self.module, event, None, &message.to_string(), fields);
    }

    pub fn operation(&self, name: impl Into<String>) -> Operation {
        Operation::new(self.clone(), name.into())
    }
}

/// One end-to-end operation. Start/finish records share an operation ID and
/// completion records include duration and outcome. Dropped unfinished spans
/// are recorded as abandoned, which makes early returns/crashes visible.
#[derive(Debug)]
pub struct Operation {
    logger: Logger,
    id: String,
    name: String,
    started: Instant,
    base_fields: Vec<(String, String)>,
    finished: bool,
}

impl Operation {
    fn new(logger: Logger, name: String) -> Self {
        let sequence = OPERATION_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let id = format!("{}-{sequence:06}", std::process::id());
        emit(
            Level::Info,
            &logger.module,
            "operation.start",
            Some(&id),
            &name,
            &[("operation", name.clone())],
        );
        Self {
            logger,
            id,
            name,
            started: Instant::now(),
            base_fields: Vec::new(),
            finished: false,
        }
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn field(mut self, key: impl Into<String>, value: impl Display) -> Self {
        self.base_fields.push((key.into(), value.to_string()));
        self
    }

    pub fn event(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        let combined = self.combined_fields(fields);
        emit_owned(
            Level::Info,
            &self.logger.module,
            event,
            Some(&self.id),
            &message.to_string(),
            &combined,
        );
    }

    pub fn warn(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        let combined = self.combined_fields(fields);
        emit_owned(
            Level::Warn,
            &self.logger.module,
            event,
            Some(&self.id),
            &message.to_string(),
            &combined,
        );
    }

    pub fn success(mut self, message: impl Display, fields: &[(&str, String)]) {
        self.finish(Level::Info, "success", message.to_string(), fields);
    }

    pub fn failure(mut self, message: impl Display, fields: &[(&str, String)]) {
        self.finish(Level::Error, "failure", message.to_string(), fields);
    }

    pub fn cancelled(mut self, message: impl Display, fields: &[(&str, String)]) {
        self.finish(Level::Warn, "cancelled", message.to_string(), fields);
    }

    fn finish(&mut self, level: Level, outcome: &str, message: String, fields: &[(&str, String)]) {
        if self.finished {
            return;
        }
        self.finished = true;
        let mut combined = self.combined_fields(fields);
        combined.push(("operation".to_string(), self.name.clone()));
        combined.push(("outcome".to_string(), outcome.to_string()));
        combined.push(("duration_ms".to_string(), elapsed_millis(self.started.elapsed()).to_string()));
        emit_owned(
            level,
            &self.logger.module,
            "operation.finish",
            Some(&self.id),
            &message,
            &combined,
        );
    }

    fn combined_fields(&self, fields: &[(&str, String)]) -> Vec<(String, String)> {
        let mut combined = self.base_fields.clone();
        combined.extend(fields.iter().map(|(key, value)| ((*key).to_string(), value.clone())));
        combined
    }
}

impl Drop for Operation {
    fn drop(&mut self) {
        if self.finished {
            return;
        }
        self.finished = true;
        let mut fields = self.base_fields.clone();
        fields.push(("operation".to_string(), self.name.clone()));
        fields.push(("outcome".to_string(), "abandoned".to_string()));
        fields.push(("duration_ms".to_string(), elapsed_millis(self.started.elapsed()).to_string()));
        emit_owned(
            Level::Warn,
            &self.logger.module,
            "operation.finish",
            Some(&self.id),
            "operation left scope without an explicit outcome",
            &fields,
        );
    }
}

pub fn folder() -> PathBuf {
    runtime::data_root().join("diagnostics")
}

pub fn log_path() -> PathBuf {
    folder().join("minesport.log")
}

pub fn operations_path() -> PathBuf {
    folder().join("minesport.operations.jsonl")
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

    let operations = operations_path();
    let operations_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&operations)
        .with_context(|| format!("open operational diagnostics {}", operations.display()))?;
    let _ = OPERATIONS_FILE.set(Mutex::new(operations_file));
    let _ = display_log();

    Logger::new("DESKTOP").info(
        "session.start",
        "Minesport 0.2.0 Rust/Slint session started",
        &[
            ("pid", std::process::id().to_string()),
            ("operations_log", operations.display().to_string()),
        ],
    );
    Ok(path)
}

/// Compatibility entry point for call sites not yet migrated to a named logger.
pub fn append(message: &str) {
    Logger::new("APP").info("message", message, &[]);
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

fn emit(
    level: Level,
    module: &str,
    event: &str,
    operation_id: Option<&str>,
    message: &str,
    fields: &[(&str, String)],
) {
    let owned = fields.iter().map(|(key, value)| ((*key).to_string(), value.clone())).collect::<Vec<_>>();
    emit_owned(level, module, event, operation_id, message, &owned);
}

fn emit_owned(
    level: Level,
    module: &str,
    event: &str,
    operation_id: Option<&str>,
    message: &str,
    fields: &[(String, String)],
) {
    let now = SystemTime::now();
    let epoch_ms = now.duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let display_stamp = local_clock_stamp(now);
    let repaired = repair_common_mojibake(message);
    let rendered_fields = fields
        .iter()
        .map(|(key, value)| format!("{key}={}", quote_human_value(&repair_common_mojibake(value))))
        .collect::<Vec<_>>();
    let operation_text = operation_id.map(|id| format!(" op={id}")).unwrap_or_default();
    let suffix = if rendered_fields.is_empty() {
        String::new()
    } else {
        format!(" {}", rendered_fields.join(" "))
    };
    let human = format!(
        "{} [{}] [{}] {}{}: {}{}",
        level.as_str(),
        module,
        event,
        operation_text,
        "",
        repaired,
        suffix,
    );

    if let Some(lock) = LOG_FILE.get() {
        if let Ok(mut file) = lock.lock() {
            let _ = writeln!(file, "{epoch_ms}  {human}");
            let _ = file.flush();
        }
    }

    if let Some(lock) = OPERATIONS_FILE.get() {
        if let Ok(mut file) = lock.lock() {
            let mut json_fields = Map::new();
            for (key, value) in fields {
                json_fields.insert(key.clone(), Value::String(repair_common_mojibake(value)));
            }
            let record = json!({
                "timestamp_ms": epoch_ms,
                "local_time": display_stamp,
                "level": level.as_str(),
                "module": module,
                "event": event,
                "operation_id": operation_id,
                "message": repaired,
                "fields": json_fields,
                "pid": std::process::id(),
            });
            if serde_json::to_writer(&mut *file, &record).is_ok() {
                let _ = writeln!(file);
                let _ = file.flush();
            }
        }
    }

    if let Ok(mut lines) = display_log().lock() {
        lines.push_back(format!("[{display_stamp}] {human}"));
        while lines.len() > MAX_DISPLAY_LINES {
            lines.pop_front();
        }
    }
}

fn quote_human_value(value: &str) -> String {
    if value.chars().all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '_' | '-' | '.' | ':' | '/' | '\\')) {
        value.to_string()
    } else {
        format!("{:?}", value)
    }
}

fn elapsed_millis(duration: Duration) -> u128 {
    duration.as_millis()
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
        assert!(operations_path().to_string_lossy().ends_with("minesport.operations.jsonl"));
    }

    #[test]
    fn repairs_known_windows_mojibake_sequences() {
        assert_eq!(
            repair_common_mojibake("IPC ΓåÆ Java ┬╖ 3 ├ù 4 ΓÇª"),
            "IPC → Java · 3 × 4 …"
        );
    }

    #[test]
    fn human_values_quote_spaces_but_not_paths() {
        assert_eq!(quote_human_value("runtime.cache"), "runtime.cache");
        assert_eq!(quote_human_value(r"C:\\Temp\\world"), r"C:\\Temp\\world");
        assert_eq!(quote_human_value("hello world"), "\"hello world\"");
    }
}
