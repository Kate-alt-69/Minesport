use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    env,
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{Arc, Mutex, mpsc::{self, Receiver, Sender}},
    thread,
    time::{Duration, Instant},
};

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
        let mut stdin = self.inner.stdin.lock().map_err(|_| anyhow!("Minesport backend stdin lock poisoned"))?;
        stdin.write_all(&bytes).context("write backend IPC request")?;
        stdin.write_all(b"\n").context("terminate backend IPC request")?;
        stdin.flush().context("flush backend IPC request")?;
        Ok(())
    }

    pub fn send_value(&self, request: Value) -> Result<()> {
        self.send(&request)
    }

    pub fn ping(&self) -> Result<()> {
        self.send_value(serde_json::json!({ "command": "ping" }))
    }

    pub fn shutdown(&self) {
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
                return;
            }
            if Instant::now() >= deadline {
                if let Ok(mut child) = self.inner.child.lock() {
                    let _ = child.kill();
                    let _ = child.wait();
                }
                return;
            }
            thread::sleep(Duration::from_millis(25));
        }
    }
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

    let java = resolve_java();
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
                    if tx.send(EngineEvent::Response(response)).is_err() {
                        return;
                    }
                }
                Ok(_) => {}
                Err(error) => {
                    let _ = tx.send(EngineEvent::ReadEnded(format!("Minesport backend IPC read failed: {error}")));
                    return;
                }
            }
        }
        let _ = tx.send(EngineEvent::ReadEnded("Minesport backend output closed".to_string()));
    });
}

fn spawn_stderr_reader(stderr: impl std::io::Read + Send + 'static, tx: Sender<EngineEvent>) {
    thread::spawn(move || {
        for line in BufReader::new(stderr).lines() {
            match line {
                Ok(line) if !line.trim().is_empty() => {
                    if tx.send(EngineEvent::Stderr(line)).is_err() {
                        return;
                    }
                }
                Ok(_) => {}
                Err(error) => {
                    let _ = tx.send(EngineEvent::Stderr(format!("backend stderr read failed: {error}")));
                    return;
                }
            }
        }
    });
}

fn resolve_java() -> PathBuf {
    if let Some(home) = env::var_os("JAVA_HOME") {
        let candidate = PathBuf::from(home).join("bin").join(java_executable_name());
        if candidate.is_file() {
            return candidate;
        }
    }
    PathBuf::from(java_executable_name())
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
