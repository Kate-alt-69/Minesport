use crate::diagnostics;
use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    env, fs,
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{Arc, Mutex, mpsc::{self, Receiver, Sender}},
    thread,
    time::{Duration, Instant},
};

const ENGINE_JAVA_MAJOR: u32 = 22;

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Response {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub message: String,
    #[serde(default)]
    pub percent: i32,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub output: String,
    #[serde(default)]
    pub image: String,
    #[serde(default, rename = "minX")]
    pub min_x: i32,
    #[serde(default, rename = "minZ")]
    pub min_z: i32,
    #[serde(default, rename = "maxX")]
    pub max_x: i32,
    #[serde(default, rename = "maxZ")]
    pub max_z: i32,
    #[serde(default)]
    pub scale: i32,
    #[serde(default)]
    pub file: String,
    #[serde(default)]
    pub count: i32,
    #[serde(default, rename = "blockCount")]
    pub block_count: i32,
    #[serde(default, rename = "quadCount")]
    pub quad_count: i32,
    #[serde(default, rename = "vertexCount")]
    pub vertex_count: i32,
}

#[derive(Debug, Clone)]
pub enum EngineEvent {
    Started { pid: u32, process: String },
    Response(Response),
    Stderr(String),
    ReadEnded(String),
}

struct EngineInner {
    stdin: Mutex<ChildStdin>,
    child: Mutex<Child>,
}

/// UI-side handle to the isolated Minesport backend worker.
///
/// The Slint process never owns Java directly. It starts another copy of the
/// same Minesport executable in `--engine-worker` mode and speaks the existing
/// newline JSON protocol to that process. The worker owns the JVM lifecycle.
#[derive(Clone)]
pub struct Engine {
    inner: Arc<EngineInner>,
}

impl Engine {
    pub fn start() -> Result<(Self, Receiver<EngineEvent>)> {
        let executable = env::current_exe().context("resolve current Minesport executable")?;
        let mut command = Command::new(&executable);
        command
            .arg("--engine-worker")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        hide_console_window(&mut command);

        let mut child = command
            .spawn()
            .with_context(|| format!("start Minesport backend worker {}", executable.display()))?;
        let pid = child.id();
        diagnostics::append(&format!("Started Minesport backend worker (PID {pid}) with {}", executable.display()));
        let stdin = child.stdin.take().context("open Minesport backend stdin")?;
        let stdout = child.stdout.take().context("open Minesport backend stdout")?;
        let stderr = child.stderr.take().context("open Minesport backend stderr")?;

        let (tx, rx) = mpsc::channel();
        let _ = tx.send(EngineEvent::Started {
            pid,
            process: executable.display().to_string(),
        });
        spawn_stdout_reader(stdout, tx.clone());
        spawn_stderr_reader(stderr, tx);

        Ok((
            Self {
                inner: Arc::new(EngineInner {
                    stdin: Mutex::new(stdin),
                    child: Mutex::new(child),
                }),
            },
            rx,
        ))
    }

    pub fn send<T: Serialize>(&self, request: &T) -> Result<()> {
        let bytes = serde_json::to_vec(request).context("encode backend IPC request")?;
        if let Ok(value) = serde_json::from_slice::<Value>(&bytes) {
            let command = value.get("command").and_then(Value::as_str).unwrap_or("request");
            diagnostics::append(&format!("IPC -> {command}"));
        }
        let mut stdin = self.inner.stdin.lock().map_err(|_| anyhow!("Minesport backend stdin lock poisoned"))?;
        stdin.write_all(&bytes).context("write backend IPC request")?;
        stdin.write_all(b"\n").context("terminate backend IPC request")?;
        stdin.flush().context("flush backend IPC request")?;
        Ok(())
    }

    pub fn send_value(&self, mut request: Value) -> Result<()> {
        prepare_world_storage_payload(&mut request)?;
        self.send(&request)
    }

    pub fn ping(&self) -> Result<()> {
        self.send_value(serde_json::json!({ "command": "ping" }))
    }

    pub fn shutdown(&self) {
        diagnostics::append("Minesport backend shutdown requested");
        let _ = self.send_value(serde_json::json!({ "command": "quit" }));
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            let exited = self
                .inner
                .child
                .lock()
                .ok()
                .and_then(|mut child| child.try_wait().ok().flatten())
                .is_some();
            if exited {
                diagnostics::append("Minesport backend exited cleanly");
                return;
            }
            if Instant::now() >= deadline {
                if let Ok(mut child) = self.inner.child.lock() {
                    diagnostics::append("Minesport backend did not exit within 3s; terminating worker");
                    let _ = child.kill();
                    let _ = child.wait();
                }
                return;
            }
            thread::sleep(Duration::from_millis(25));
        }
    }
}

/// Preserve the retired Go IPC behavior for Minecraft 26.1+ Overworld storage.
/// Java heightmap generation expects `<worldPath>/region`, while modern worlds
/// store the Overworld under `dimensions/minecraft/overworld/region`. Rewrite
/// only heightmap requests to the storage root that actually owns region files.
/// Export/listBlocks continue receiving the real world root because Java's
/// WorldCopier performs the same modern-first normalization for those commands.
fn prepare_world_storage_payload(request: &mut Value) -> Result<()> {
    if request.get("command").and_then(Value::as_str) != Some("heightmap") {
        return Ok(());
    }
    let Some(world_path) = request.get("worldPath").and_then(Value::as_str) else {
        return Ok(());
    };
    if world_path.trim().is_empty() {
        return Ok(());
    }

    let storage_root = resolve_overworld_storage_root(Path::new(world_path))?;
    let Some(object) = request.as_object_mut() else {
        return Ok(());
    };
    object.insert(
        "worldPath".to_string(),
        Value::String(storage_root.display().to_string()),
    );
    Ok(())
}

fn resolve_overworld_storage_root(world_path: &Path) -> Result<PathBuf> {
    let candidates = [
        world_path.join("dimensions").join("minecraft").join("overworld").join("region"),
        world_path.join("region"),
    ];
    for candidate in &candidates {
        if has_region_files(candidate) {
            return candidate
                .parent()
                .map(Path::to_path_buf)
                .ok_or_else(|| anyhow!("Overworld region path has no storage root: {}", candidate.display()));
        }
    }
    bail!(
        "no Overworld region files found; checked: {}, {}",
        candidates[0].display(),
        candidates[1].display()
    )
}

fn has_region_files(region_dir: &Path) -> bool {
    let Ok(entries) = fs::read_dir(region_dir) else { return false; };
    entries.flatten().any(|entry| {
        if !entry.file_type().is_ok_and(|kind| kind.is_file()) {
            return false;
        }
        entry
            .path()
            .extension()
            .and_then(|extension| extension.to_str())
            .is_some_and(|extension| extension.eq_ignore_ascii_case("mca") || extension.eq_ignore_ascii_case("mcr"))
    })
}

/// Headless mode used by the self-spawned Minesport backend process.
///
/// This process owns Java and transparently relays the existing Java newline
/// JSON protocol. Java stdout becomes worker stdout; Java stderr becomes worker
/// stderr; worker stdin is forwarded to Java stdin. The UI can therefore keep
/// using the established engine protocol while gaining a hard process boundary.
pub fn run_engine_worker(jar: &Path) -> Result<()> {
    if !jar.is_file() {
        return Err(anyhow!("embedded Java engine is unavailable: {}", jar.display()));
    }

    let java = resolve_java()?;
    diagnostics::append(&format!("Engine worker Java executable: {}", java.display()));
    let mut command = Command::new(&java);
    command
        .arg("-jar")
        .arg(jar)
        .arg("--ipc")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    hide_console_window(&mut command);

    let mut child = command
        .spawn()
        .with_context(|| format!("start Java engine with {}", java.display()))?;
    let java_pid = child.id();
    diagnostics::append(&format!("Java engine started (PID {java_pid}) with {}", java.display()));
    let mut java_stdin = child.stdin.take().context("open Java engine stdin")?;
    let java_stdout = child.stdout.take().context("open Java engine stdout")?;
    let java_stderr = child.stderr.take().context("open Java engine stderr")?;

    {
        let mut output = std::io::stdout().lock();
        let info = serde_json::json!({
            "type": "workerInfo",
            "message": format!("Java engine started (PID {java_pid}) with {}", java.display())
        });
        writeln!(output, "{info}").context("announce Java engine worker")?;
        output.flush().context("flush Java engine announcement")?;
    }

    let stdout_relay = thread::spawn(move || -> std::io::Result<()> {
        let reader = BufReader::new(java_stdout);
        let stdout = std::io::stdout();
        let mut output = stdout.lock();
        for line in reader.lines() {
            let line = line?;
            writeln!(output, "{line}")?;
            output.flush()?;
        }
        Ok(())
    });

    let stderr_relay = thread::spawn(move || -> std::io::Result<()> {
        let reader = BufReader::new(java_stderr);
        let stderr = std::io::stderr();
        let mut output = stderr.lock();
        for line in reader.lines() {
            let line = line?;
            writeln!(output, "{line}")?;
            output.flush()?;
        }
        Ok(())
    });

    // Do not join this thread: if Java crashes while the parent UI remains
    // alive, stdin may legitimately remain blocked. Returning from worker main
    // terminates the whole process and therefore this relay thread as well.
    thread::spawn(move || {
        let reader = BufReader::new(std::io::stdin());
        for line in reader.lines() {
            let Ok(line) = line else { break; };
            if java_stdin.write_all(line.as_bytes()).is_err() { break; }
            if java_stdin.write_all(b"\n").is_err() { break; }
            if java_stdin.flush().is_err() { break; }
        }
    });

    let status = child.wait().context("wait for Java engine")?;
    diagnostics::append(&format!("Java engine process exited with {status}"));
    let _ = stdout_relay.join();
    let _ = stderr_relay.join();
    if !status.success() {
        return Err(anyhow!("Java engine exited with status {status}"));
    }
    Ok(())
}

fn spawn_stdout_reader(stdout: impl std::io::Read + Send + 'static, tx: Sender<EngineEvent>) {
    thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            match line {
                Ok(line) if !line.trim().is_empty() => {
                    let response = serde_json::from_str::<Response>(&line).unwrap_or_else(|_| Response {
                        kind: "log".to_string(),
                        message: line,
                        ..Response::default()
                    });
                    match response.kind.as_str() {
                        "log" => diagnostics::append(&response.message),
                        "progress" => diagnostics::append(&format!("IPC <- progress {}% · {}", response.percent, response.message)),
                        "done" => diagnostics::append(&format!("IPC <- done · {} · {} blocks · {} faces · {} vertices", response.output, response.block_count, response.quad_count, response.vertex_count)),
                        "error" => diagnostics::append(&format!("IPC <- error · {}", response.message)),
                        kind => diagnostics::append(&format!("IPC <- {kind}")),
                    }
                    if tx.send(EngineEvent::Response(response)).is_err() {
                        return;
                    }
                }
                Ok(_) => {}
                Err(error) => {
                    let message = format!("Minesport backend IPC read failed: {error}");
                    diagnostics::append(&message);
                    let _ = tx.send(EngineEvent::ReadEnded(message));
                    return;
                }
            }
        }
        diagnostics::append("Minesport backend output closed");
        let _ = tx.send(EngineEvent::ReadEnded("Minesport backend output closed".to_string()));
    });
}

fn spawn_stderr_reader(stderr: impl std::io::Read + Send + 'static, tx: Sender<EngineEvent>) {
    thread::spawn(move || {
        for line in BufReader::new(stderr).lines() {
            match line {
                Ok(line) if !line.trim().is_empty() => {
                    diagnostics::append(&format!("[backend] {line}"));
                    if tx.send(EngineEvent::Stderr(line)).is_err() {
                        return;
                    }
                }
                Ok(_) => {}
                Err(error) => {
                    let message = format!("backend stderr read failed: {error}");
                    diagnostics::append(&message);
                    let _ = tx.send(EngineEvent::Stderr(message));
                    return;
                }
            }
        }
    });
}

fn resolve_java() -> Result<PathBuf> {
    let mut candidates = Vec::new();

    if let Some(home) = env::var_os("JAVA_HOME") {
        push_java_candidate(&mut candidates, PathBuf::from(home).join("bin").join(java_executable_name()));
    }
    if let Some(path_java) = find_on_path(java_executable_name()) {
        push_java_candidate(&mut candidates, path_java);
    }

    // Preserve the retired Go wrapper's launcher-Java fallback. Some Windows
    // machines have no system Java because the Minecraft launcher owns it.
    if cfg!(windows) {
        if let Some(appdata) = env::var_os("APPDATA").map(PathBuf::from) {
            for runtime_name in ["java-runtime-delta", "java-runtime-gamma"] {
                push_java_candidate(
                    &mut candidates,
                    appdata.join("FreesmLauncher").join("java").join(runtime_name).join("bin").join("java.exe"),
                );
            }
        }
        if let Some(program_files) = env::var_os("ProgramFiles").map(PathBuf::from) {
            for base in [program_files.join("Java"), program_files.join("Eclipse Adoptium")] {
                let Ok(entries) = fs::read_dir(base) else { continue; };
                let mut homes = entries
                    .flatten()
                    .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_dir()))
                    .map(|entry| entry.path())
                    .collect::<Vec<_>>();
                homes.sort_by(|left, right| right.file_name().cmp(&left.file_name()));
                for home in homes {
                    push_java_candidate(&mut candidates, home.join("bin").join("java.exe"));
                }
            }
        }
    }

    for candidate in candidates {
        let major = java_major(&candidate);
        if major >= ENGINE_JAVA_MAJOR {
            return Ok(candidate);
        }
        diagnostics::append(&format!(
            "Ignoring Java candidate {} because major version {} is below required {}",
            candidate.display(), major, ENGINE_JAVA_MAJOR
        ));
    }

    bail!(
        "Java {ENGINE_JAVA_MAJOR}+ is required for the Minesport engine; checked JAVA_HOME, PATH, launcher Java, and standard JDK folders"
    )
}

fn push_java_candidate(candidates: &mut Vec<PathBuf>, candidate: PathBuf) {
    if !candidate.is_file() {
        return;
    }
    let duplicate = candidates.iter().any(|existing| {
        if cfg!(windows) {
            existing.to_string_lossy().eq_ignore_ascii_case(&candidate.to_string_lossy())
        } else {
            existing == &candidate
        }
    });
    if !duplicate {
        candidates.push(candidate);
    }
}

fn find_on_path(executable: &str) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    env::split_paths(&path)
        .map(|folder| folder.join(executable))
        .find(|candidate| candidate.is_file())
}

fn java_major(java: &Path) -> u32 {
    let mut command = Command::new(java);
    command.arg("-version");
    hide_console_window(&mut command);
    let Ok(output) = command.output() else { return 0; };
    let text = format!("{} {}", String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
    parse_java_major(&text).unwrap_or(0)
}

fn parse_java_major(text: &str) -> Option<u32> {
    let quoted = text.split('"').nth(1);
    let version = quoted.or_else(|| {
        text.split_whitespace().find(|token| token.chars().next().is_some_and(|ch| ch.is_ascii_digit()))
    })?;
    let mut parts = version.split('.');
    let first = parts.next()?.trim_matches(|ch: char| !ch.is_ascii_digit()).parse::<u32>().ok()?;
    if first == 1 {
        parts.next()?.trim_matches(|ch: char| !ch.is_ascii_digit()).parse::<u32>().ok()
    } else {
        Some(first)
    }
}

fn java_executable_name() -> &'static str {
    if cfg!(windows) { "java.exe" } else { "java" }
}

#[cfg(windows)]
fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_console_window(_command: &mut Command) {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_world(name: &str) -> PathBuf {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = env::temp_dir().join(format!("minesport-ipc-{name}-{}-{stamp}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        root
    }

    #[test]
    fn modern_overworld_storage_wins_over_stale_legacy_region() {
        let world = temp_world("modern-overworld");
        let modern = world.join("dimensions/minecraft/overworld/region");
        let legacy = world.join("region");
        fs::create_dir_all(&modern).unwrap();
        fs::create_dir_all(&legacy).unwrap();
        fs::write(modern.join("r.0.0.mca"), b"modern").unwrap();
        fs::write(legacy.join("r.1.1.mca"), b"legacy").unwrap();

        assert_eq!(resolve_overworld_storage_root(&world).unwrap(), world.join("dimensions/minecraft/overworld"));
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn legacy_overworld_storage_remains_supported() {
        let world = temp_world("legacy-overworld");
        let legacy = world.join("region");
        fs::create_dir_all(&legacy).unwrap();
        fs::write(legacy.join("r.0.0.mcr"), b"legacy").unwrap();

        assert_eq!(resolve_overworld_storage_root(&world).unwrap(), world);
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn heightmap_payload_is_rewritten_but_export_payload_is_not() {
        let world = temp_world("payload");
        let modern = world.join("dimensions/minecraft/overworld/region");
        fs::create_dir_all(&modern).unwrap();
        fs::write(modern.join("r.0.0.mca"), b"region").unwrap();

        let mut heightmap = serde_json::json!({ "command": "heightmap", "worldPath": world });
        prepare_world_storage_payload(&mut heightmap).unwrap();
        assert_eq!(
            heightmap.get("worldPath").and_then(Value::as_str).map(PathBuf::from),
            Some(world.join("dimensions/minecraft/overworld"))
        );

        let mut export = serde_json::json!({ "command": "export", "worldPath": world });
        prepare_world_storage_payload(&mut export).unwrap();
        assert_eq!(
            export.get("worldPath").and_then(Value::as_str).map(PathBuf::from),
            Some(world.clone())
        );
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn java_major_parser_handles_legacy_and_modern_version_strings() {
        assert_eq!(parse_java_major("java version \"1.8.0_401\""), Some(8));
        assert_eq!(parse_java_major("openjdk version \"17.0.12\" 2024-07-16"), Some(17));
        assert_eq!(parse_java_major("openjdk version \"22.0.2\" 2024-07-16"), Some(22));
        assert_eq!(parse_java_major("openjdk 25.0.1"), Some(25));
    }
}
