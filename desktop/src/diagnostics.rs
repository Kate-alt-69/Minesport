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
static TRACE_SEQUENCE: AtomicU64 = AtomicU64::new(1);
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
/// `operation_id` values are deliberately hardcoded string literals at the
/// operation call site. That makes a log ID directly searchable in source.
/// A generated `trace_id` is separate and identifies one runtime invocation.
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
        emit_operational(Level::Debug, &self.module, event, None, None, &message.to_string(), fields);
    }

    pub fn info(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit_operational(Level::Info, &self.module, event, None, None, &message.to_string(), fields);
    }

    pub fn warn(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit_operational(Level::Warn, &self.module, event, None, None, &message.to_string(), fields);
    }

    pub fn error(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        emit_operational(Level::Error, &self.module, event, None, None, &message.to_string(), fields);
    }

    /// Emit a structured event that belongs to an operation/trace created in
    /// another process. This is how Java IPC responses keep the Rust request's
    /// hardcoded operation ID all the way back into the desktop JSONL log.
    pub fn correlated(
        &self,
        level: Level,
        event: &str,
        operation_id: &str,
        trace_id: &str,
        message: impl Display,
        fields: &[(&str, String)],
    ) {
        emit_operational(
            level,
            &self.module,
            event,
            non_empty(operation_id),
            non_empty(trace_id),
            &message.to_string(),
            fields,
        );
    }

    /// Start one traced operation.
    ///
    /// Keep `operation_id` as a literal at the call site, for example:
    /// `logger.operation("ExportProgressResolveTextureFile")`.
    pub fn operation(&self, operation_id: &'static str) -> Operation {
        Operation::new(self.clone(), operation_id)
    }
}

#[derive(Debug)]
pub struct Operation {
    logger: Logger,
    operation_id: &'static str,
    trace_id: String,
    started: Instant,
    base_fields: Vec<(String, String)>,
    finished: bool,
}

impl Operation {
    fn new(logger: Logger, operation_id: &'static str) -> Self {
        let sequence = TRACE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let trace_id = format!("{}-{sequence:06}", std::process::id());
        emit_operational(
            Level::Info,
            &logger.module,
            "OperationStart",
            Some(operation_id),
            Some(&trace_id),
            "operation started",
            &[],
        );
        Self {
            logger,
            operation_id,
            trace_id,
            started: Instant::now(),
            base_fields: Vec::new(),
            finished: false,
        }
    }

    pub fn operation_id(&self) -> &'static str {
        self.operation_id
    }

    pub fn trace_id(&self) -> &str {
        &self.trace_id
    }

    pub fn field(mut self, key: impl Into<String>, value: impl Display) -> Self {
        self.base_fields.push((key.into(), value.to_string()));
        self
    }

    pub fn event(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        let combined = self.combined_fields(fields);
        emit_operational_owned(
            Level::Info,
            &self.logger.module,
            event,
            Some(self.operation_id),
            Some(&self.trace_id),
            &message.to_string(),
            &combined,
        );
    }

    pub fn warn(&self, event: &str, message: impl Display, fields: &[(&str, String)]) {
        let combined = self.combined_fields(fields);
        emit_operational_owned(
            Level::Warn,
            &self.logger.module,
            event,
            Some(self.operation_id),
            Some(&self.trace_id),
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
        combined.push(("outcome".to_string(), outcome.to_string()));
        combined.push(("duration_ms".to_string(), elapsed_millis(self.started.elapsed()).to_string()));
        emit_operational_owned(
            level,
            &self.logger.module,
            "OperationFinish",
            Some(self.operation_id),
            Some(&self.trace_id),
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
        fields.push(("outcome".to_string(), "abandoned".to_string()));
        fields.push(("duration_ms".to_string(), elapsed_millis(self.started.elapsed()).to_string()));
        emit_operational_owned(
            Level::Warn,
            &self.logger.module,
            "OperationFinish",
            Some(self.operation_id),
            Some(&self.trace_id),
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
        "DesktopSessionStart",
        format!("Minesport {} Rust/Slint session started", env!("CARGO_PKG_VERSION")),
        &[
            ("pid", std::process::id().to_string()),
            ("operations_log", operations.display().to_string()),
        ],
    );
    Ok(path)
}

/// Compatibility/raw log entry for verbose backend text and call sites not yet
/// migrated to structured operations. Raw entries stay out of operations JSONL.
pub fn append(message: &str) {
    emit_human(Level::Info, "APP", "RawMessage", None, None, message, &[]);
}

/// Raw cross-process log text with correlation preserved in the human log and
/// Debug Console, but deliberately excluded from operations JSONL.
pub fn append_correlated(message: &str, operation_id: &str, trace_id: &str) {
    emit_human(
        Level::Info,
        "IPC:JAVA",
        "RawMessage",
        non_empty(operation_id),
        non_empty(trace_id),
        message,
        &[],
    );
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

fn emit_operational(
    level: Level,
    module: &str,
    event: &str,
    operation_id: Option<&str>,
    trace_id: Option<&str>,
    message: &str,
    fields: &[(&str, String)],
) {
    let owned = fields.iter().map(|(key, value)| ((*key).to_string(), value.clone())).collect::<Vec<_>>();
    emit_operational_owned(level, module, event, operation_id, trace_id, message, &owned);
}

fn emit_operational_owned(
    level: Level,
    module: &str,
    event: &str,
    operation_id: Option<&str>,
    trace_id: Option<&str>,
    message: &str,
    fields: &[(String, String)],
) {
    let (epoch_ms, display_stamp, repaired, human) =
        emit_human(level, module, event, operation_id, trace_id, message, fields);

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
                "trace_id": trace_id,
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

    let _ = human;
}

fn emit_human(
    level: Level,
    module: &str,
    event: &str,
    operation_id: Option<&str>,
    trace_id: Option<&str>,
    message: &str,
    fields: &[(String, String)],
) -> (u128, String, String, String) {
    let now = SystemTime::now();
    let epoch_ms = now.duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let display_stamp = local_clock_stamp(now);
    let repaired = repair_common_mojibake(message);
    let rendered_fields = fields
        .iter()
        .map(|(key, value)| format!("{key}={}", quote_human_value(&repair_common_mojibake(value))))
        .collect::<Vec<_>>();
    let operation_text = operation_id.map(|id| format!(" operation={id}")).unwrap_or_default();
    let trace_text = trace_id.map(|id| format!(" trace={id}")).unwrap_or_default();
    let suffix = if rendered_fields.is_empty() {
        String::new()
    } else {
        format!(" {}", rendered_fields.join(" "))
    };
    let human = format!(
        "{} [{}] [{}]{}{}: {}{}",
        level.as_str(),
        module,
        event,
        operation_text,
        trace_text,
        repaired,
        suffix,
    );

    if let Some(lock) = LOG_FILE.get() {
        if let Ok(mut file) = lock.lock() {
            let _ = writeln!(file, "{epoch_ms}  {human}");
            let _ = file.flush();
        }
    }

    if let Ok(mut lines) = display_log().lock() {
        lines.push_back(format!("[{display_stamp}] {human}"));
        while lines.len() > MAX_DISPLAY_LINES {
            lines.pop_front();
        }
    }

    (epoch_ms, display_stamp, repaired, human)
}

fn non_empty(value: &str) -> Option<&str> {
    if value.trim().is_empty() { None } else { Some(value) }
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

    #[test]
    fn operation_ids_are_distinct_from_runtime_trace_ids() {
        let operation = Logger::new("TEST").operation("TestHardcodedOperationId");
        assert_eq!(operation.operation_id(), "TestHardcodedOperationId");
        assert!(operation.trace_id().starts_with(&format!("{}-", std::process::id())));
    }

    #[test]
    fn empty_correlation_values_are_omitted() {
        assert_eq!(non_empty(""), None);
        assert_eq!(non_empty("   "), None);
        assert_eq!(non_empty("Trace"), Some("Trace"));
    }
}
