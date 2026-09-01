#[cfg(windows)]
use crate::engine_lease;
use crate::{diagnostics, runtime};
use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
    env, fs,
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, Receiver, Sender},
    },
    thread,
    time::{Duration, Instant},
};

const ENGINE_JAVA_MAJOR: u32 = 22;
pub const ENGINE_PROTOCOL_VERSION: u32 = 1;
const ENGINE_SIDECAR_MANIFEST_SCHEMA: u32 = 1;

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
    #[serde(default, rename = "operationId")]
    pub operation_id: String,
    #[serde(default, rename = "traceId")]
    pub trace_id: String,
    #[serde(default, rename = "clientWorldPath")]
    pub client_world_path: String,
    #[serde(default, rename = "clientPurpose")]
    pub client_purpose: String,
}

#[derive(Debug, Clone)]
pub enum EngineEvent {
    Started { pid: u32, process: String },
    Response(Response),
    Stderr(String),
    ReadEnded(String),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EngineSidecarManifest {
    schema: u32,
    version: String,
    protocol_version: u32,
    sha256: String,
    size: u64,
}

#[derive(Debug, Clone)]
struct BackendLaunch {
    executable: PathBuf,
    args: Vec<String>,
    mode: &'static str,
    engine_version: String,
}

impl BackendLaunch {
    fn resolve() -> Result<Self> {
        let desktop = env::current_exe().context("resolve current Minesport executable")?;

        #[cfg(windows)]
        {
            let sidecar = desktop
                .parent()
                .unwrap_or_else(|| Path::new("."))
                .join("minesport-engine.exe");
            if sidecar.is_file() {
                match validate_engine_sidecar(&sidecar) {
                    Ok(manifest) => {
                        diagnostics::Logger::new("IPC").child("UI").info(
                            "EngineSidecarAccepted",
                            "verified installed Minesport engine sidecar",
                            &[
                                ("executable", sidecar.display().to_string()),
                                ("version", manifest.version.clone()),
                                ("protocol", manifest.protocol_version.to_string()),
                            ],
                        );
                        return Ok(Self {
                            executable: sidecar,
                            args: Vec::new(),
                            mode: "sidecar",
                            engine_version: manifest.version,
                        });
                    }
                    Err(error) => {
                        diagnostics::Logger::new("IPC").child("UI").warn(
                            "EngineSidecarRejected",
                            "installed engine sidecar failed local verification; using embedded fallback worker",
                            &[
                                ("executable", sidecar.display().to_string()),
                                ("error", format!("{error:#}")),
                            ],
                        );
                    }
                }
            }
        }

        Ok(Self {
            executable: desktop,
            args: vec!["--engine-worker".to_string()],
            mode: "self-worker-fallback",
            engine_version: env!("CARGO_PKG_VERSION").to_string(),
        })
    }

    fn command(&self) -> Command {
        let mut command = Command::new(&self.executable);
        command
            .args(&self.args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        hide_console_window(&mut command);
        command
    }
}

fn validate_engine_sidecar(sidecar: &Path) -> Result<EngineSidecarManifest> {
    if !sidecar.is_file() {
        bail!("engine sidecar is unavailable: {}", sidecar.display());
    }
    let manifest_path = sidecar.with_file_name("minesport-engine.json");
    let manifest_bytes = fs::read(&manifest_path)
        .with_context(|| format!("read engine manifest {}", manifest_path.display()))?;
    let manifest: EngineSidecarManifest = serde_json::from_slice(&manifest_bytes)
        .with_context(|| format!("parse engine manifest {}", manifest_path.display()))?;
    if manifest.schema != ENGINE_SIDECAR_MANIFEST_SCHEMA {
        bail!(
            "unsupported engine manifest schema {}; expected {}",
            manifest.schema,
            ENGINE_SIDECAR_MANIFEST_SCHEMA
        );
    }
    if manifest.protocol_version != ENGINE_PROTOCOL_VERSION {
        bail!(
            "engine IPC protocol {} is incompatible with GUI protocol {}",
            manifest.protocol_version,
            ENGINE_PROTOCOL_VERSION
        );
    }
    if manifest.version.trim().is_empty() {
        bail!("engine manifest version is empty");
    }
    let metadata = fs::metadata(sidecar)
        .with_context(|| format!("inspect engine sidecar {}", sidecar.display()))?;
    if metadata.len() != manifest.size {
        bail!(
            "engine sidecar size mismatch: installed={} manifest={}",
            metadata.len(),
            manifest.size
        );
    }
    let expected = manifest.sha256.trim().to_ascii_lowercase();
    if expected.len() != 64 || !expected.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        bail!("engine manifest contains an invalid SHA-256 digest");
    }
    let actual = sha256_file(sidecar)?;
    if actual != expected {
        bail!("engine sidecar SHA-256 mismatch: installed={actual} manifest={expected}");
    }
    Ok(manifest)
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)
        .with_context(|| format!("open engine sidecar for hashing {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 1024 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .with_context(|| format!("hash engine sidecar {}", path.display()))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    let digest = hasher.finalize();
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(64);
    for byte in digest {
        encoded.push(HEX[(byte >> 4) as usize] as char);
        encoded.push(HEX[(byte & 0x0f) as usize] as char);
    }
    Ok(encoded)
}

struct EngineInner {
    stdin: Mutex<ChildStdin>,
    child: Mutex<Child>,
    events: Sender<EngineEvent>,
    launch: BackendLaunch,
    #[cfg(windows)]
    _engine_use_lease: engine_lease::Lease,
    generation: Arc<AtomicU64>,
    restarting: AtomicBool,
    shutting_down: AtomicBool,
}

#[derive(Debug, Clone, Default)]
struct RequestCorrelation {
    operation_id: String,
    trace_id: String,
    client_world_path: String,
    client_purpose: String,
}

/// UI-side handle to the isolated Minesport backend worker.
///
/// On Windows, installed builds prefer the independently replaceable
/// `minesport-engine.exe` sidecar after validating its manifest, protocol, size,
/// and SHA-256 digest. Development/legacy installs fall back to another copy of
/// Minesport.exe in `--engine-worker` mode so a missing sidecar never bricks the
/// desktop during the migration.
#[derive(Clone)]
pub struct Engine {
    inner: Arc<EngineInner>,
}

impl Engine {
    pub fn start() -> Result<(Self, Receiver<EngineEvent>)> {
        #[cfg(windows)]
        let engine_use_lease = engine_lease::acquire_engine_use_shared()
            .context("coordinate Minesport engine sidecar use")?;
        let logger = diagnostics::Logger::new("IPC").child("UI");
        let operation = logger.operation("IpcBackendWorkerStart");
        let launch = BackendLaunch::resolve()?;
        operation.event(
            "IpcBackendWorkerSpawn",
            "starting isolated Minesport backend worker",
            &[
                ("executable", launch.executable.display().to_string()),
                ("mode", launch.mode.to_string()),
                ("engine_version", launch.engine_version.clone()),
            ],
        );

        let mut command = launch.command();
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(error) => {
                operation.failure(
                    "failed to start Minesport backend worker",
                    &[
                        ("error", error.to_string()),
                        ("executable", launch.executable.display().to_string()),
                        ("mode", launch.mode.to_string()),
                    ],
                );
                return Err(error).with_context(|| {
                    format!(
                        "start Minesport backend worker {}",
                        launch.executable.display()
                    )
                });
            }
        };
        let pid = child.id();
        let stdin = child.stdin.take().context("open Minesport backend stdin")?;
        let stdout = child
            .stdout
            .take()
            .context("open Minesport backend stdout")?;
        let stderr = child
            .stderr
            .take()
            .context("open Minesport backend stderr")?;

        let (tx, rx) = mpsc::channel();
        let generation = Arc::new(AtomicU64::new(1));
        let _ = tx.send(EngineEvent::Started {
            pid,
            process: launch.executable.display().to_string(),
        });
        spawn_stdout_reader(stdout, tx.clone(), generation.clone(), 1);
        spawn_stderr_reader(stderr, tx.clone(), generation.clone(), 1);

        operation.success(
            "Minesport backend worker started",
            &[
                ("pid", pid.to_string()),
                ("executable", launch.executable.display().to_string()),
                ("mode", launch.mode.to_string()),
                ("engine_version", launch.engine_version.clone()),
            ],
        );
        Ok((
            Self {
                inner: Arc::new(EngineInner {
                    stdin: Mutex::new(stdin),
                    child: Mutex::new(child),
                    events: tx,
                    launch,
                    #[cfg(windows)]
                    _engine_use_lease: engine_use_lease,
                    generation,
                    restarting: AtomicBool::new(false),
                    shutting_down: AtomicBool::new(false),
                }),
            },
            rx,
        ))
    }

    pub fn restart(&self) -> Result<()> {
        if self.is_shutting_down() {
            bail!("backend shutdown is already in progress");
        }
        if self
            .inner
            .restarting
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Ok(());
        }
        let result = self.restart_inner();
        self.inner.restarting.store(false, Ordering::Release);
        result
    }

    fn restart_inner(&self) -> Result<()> {
        if self.is_shutting_down() {
            bail!("backend shutdown is already in progress");
        }
        let logger = diagnostics::Logger::new("IPC").child("UI");
        let operation = logger.operation("IpcBackendWorkerRestart");
        let reader_generation = self.inner.generation.fetch_add(1, Ordering::AcqRel) + 1;

        if let Ok(mut child) = self.inner.child.lock() {
            match child.try_wait() {
                Ok(Some(_)) => {}
                Ok(None) => {
                    let _ = runtime::terminate_process_tree(&mut child, Duration::from_secs(2));
                }
                Err(error) => logger.warn(
                    "IpcBackendWorkerRestartOldStateUnknown",
                    "could not query previous backend before restart",
                    &[("error", error.to_string())],
                ),
            }
        }

        let launch = self.inner.launch.clone();
        operation.event(
            "IpcBackendWorkerRespawn",
            "restarting isolated Minesport backend worker",
            &[
                ("executable", launch.executable.display().to_string()),
                ("mode", launch.mode.to_string()),
                ("engine_version", launch.engine_version.clone()),
                ("generation", reader_generation.to_string()),
            ],
        );
        let mut command = launch.command();

        let mut child = command.spawn().with_context(|| {
            format!(
                "restart Minesport backend worker {}",
                launch.executable.display()
            )
        })?;
        let pid = child.id();
        let stdin = child
            .stdin
            .take()
            .context("open restarted Minesport backend stdin")?;
        let stdout = child
            .stdout
            .take()
            .context("open restarted Minesport backend stdout")?;
        let stderr = child
            .stderr
            .take()
            .context("open restarted Minesport backend stderr")?;

        {
            let mut current = self
                .inner
                .stdin
                .lock()
                .map_err(|_| anyhow!("Minesport backend stdin lock poisoned during restart"))?;
            *current = stdin;
        }
        {
            let mut current = self
                .inner
                .child
                .lock()
                .map_err(|_| anyhow!("Minesport backend child lock poisoned during restart"))?;
            *current = child;
        }

        let tx = self.inner.events.clone();
        let _ = tx.send(EngineEvent::Started {
            pid,
            process: launch.executable.display().to_string(),
        });
        spawn_stdout_reader(
            stdout,
            tx.clone(),
            self.inner.generation.clone(),
            reader_generation,
        );
        spawn_stderr_reader(stderr, tx, self.inner.generation.clone(), reader_generation);
        operation.success(
            "Minesport backend worker restarted",
            &[
                ("pid", pid.to_string()),
                ("executable", launch.executable.display().to_string()),
                ("mode", launch.mode.to_string()),
                ("engine_version", launch.engine_version),
                ("generation", reader_generation.to_string()),
            ],
        );
        Ok(())
    }

    pub fn is_shutting_down(&self) -> bool {
        self.inner.shutting_down.load(Ordering::Acquire)
    }

    pub fn send<T: Serialize>(&self, request: &T) -> Result<()> {
        let mut value = serde_json::to_value(request).context("encode backend IPC request")?;
        let command = value
            .get("command")
            .and_then(Value::as_str)
            .unwrap_or("request")
            .to_string();
        let world = value
            .get("worldPath")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let output = value
            .get("outputPath")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();

        let logger = diagnostics::Logger::new("IPC").child("TX");
        let mut operation = logger
            .operation(request_operation_id(&command))
            .field("command", &command);
        if !world.is_empty() {
            operation = operation.field("world", &world);
        }
        if !output.is_empty() {
            operation = operation.field("output", &output);
        }

        if let Some(object) = value.as_object_mut() {
            object.insert(
                "operationId".to_string(),
                Value::String(operation.operation_id().to_string()),
            );
            object.insert(
                "traceId".to_string(),
                Value::String(operation.trace_id().to_string()),
            );
        }
        let bytes =
            serde_json::to_vec(&value).context("serialize correlated backend IPC request")?;
        operation = operation.field("bytes", bytes.len());
        operation.event("IpcRequestWrite", "writing backend IPC request", &[]);

        let result = (|| -> Result<()> {
            let mut stdin = self
                .inner
                .stdin
                .lock()
                .map_err(|_| anyhow!("Minesport backend stdin lock poisoned"))?;
            stdin
                .write_all(&bytes)
                .context("write backend IPC request")?;
            stdin
                .write_all(b"\n")
                .context("terminate backend IPC request")?;
            stdin.flush().context("flush backend IPC request")?;
            Ok(())
        })();

        match result {
            Ok(()) => {
                operation.success("backend IPC request dispatched", &[]);
                Ok(())
            }
            Err(error) => {
                operation.failure(
                    "backend IPC request dispatch failed",
                    &[("error", format!("{error:#}"))],
                );
                Err(error)
            }
        }
    }

    pub fn send_value(&self, mut request: Value) -> Result<()> {
        preserve_client_world_path(&mut request);
        prepare_world_storage_payload(&mut request)?;
        self.send(&request)
    }

    pub fn ping(&self) -> Result<()> {
        self.send_value(serde_json::json!({ "command": "ping" }))
    }

    pub fn shutdown(&self) {
        self.inner.shutting_down.store(true, Ordering::Release);
        let logger = diagnostics::Logger::new("IPC").child("UI");
        let operation = logger.operation("IpcBackendWorkerShutdown");
        operation.event(
            "IpcBackendWorkerQuitRequested",
            "requesting backend shutdown",
            &[],
        );
        let _ = self.send_value(serde_json::json!({ "command": "quit" }));
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            let exited = self
                .inner
                .child
                .lock()
                .ok()
                .and_then(|mut child| child.try_wait().ok().flatten());
            if let Some(status) = exited {
                operation.success(
                    "Minesport backend exited",
                    &[("status", status.to_string())],
                );
                return;
            }
            if Instant::now() >= deadline {
                if let Ok(mut child) = self.inner.child.lock() {
                    logger.warn(
                        "IpcBackendWorkerShutdownTimeout",
                        "backend did not exit within 3 seconds; terminating worker tree",
                        &[("pid", child.id().to_string())],
                    );
                    let status =
                        runtime::terminate_process_tree(&mut child, Duration::from_secs(5))
                            .map(|status| status.to_string())
                            .unwrap_or_else(|| "not reaped before deadline".to_string());
                    operation.failure(
                        "backend required forced process-tree termination",
                        &[("status", status)],
                    );
                } else {
                    operation.failure("backend shutdown state lock failed", &[]);
                }
                return;
            }
            thread::sleep(Duration::from_millis(25));
        }
    }
}

fn request_operation_id(command: &str) -> &'static str {
    match command {
        "export" => "IpcRequestDispatchExport",
        "heightmap" => "IpcRequestDispatchHeightmap",
        "listBlocks" => "IpcRequestDispatchBlockList",
        "ping" => "IpcRequestPingBackend",
        "quit" => "IpcRequestQuitBackend",
        _ => "IpcRequestDispatchUnknownCommand",
    }
}

fn preserve_client_world_path(request: &mut Value) {
    let Some(world_path) = request
        .get("worldPath")
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_string)
    else {
        return;
    };
    let Some(object) = request.as_object_mut() else {
        return;
    };
    object
        .entry("clientWorldPath".to_string())
        .or_insert(Value::String(world_path));
}

fn correlation_from_request_line(line: &str) -> RequestCorrelation {
    let Ok(value) = serde_json::from_str::<Value>(line) else {
        return RequestCorrelation::default();
    };
    RequestCorrelation {
        operation_id: value
            .get("operationId")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        trace_id: value
            .get("traceId")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        client_world_path: value
            .get("clientWorldPath")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        client_purpose: value
            .get("clientPurpose")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
    }
}

fn annotate_java_response(line: &str, correlation: &RequestCorrelation) -> (String, bool) {
    let Ok(mut value) = serde_json::from_str::<Value>(line) else {
        return (line.to_string(), false);
    };
    let kind = value
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    if let Some(object) = value.as_object_mut() {
        if !correlation.operation_id.is_empty() {
            object.insert(
                "operationId".to_string(),
                Value::String(correlation.operation_id.clone()),
            );
        }
        if !correlation.trace_id.is_empty() {
            object.insert(
                "traceId".to_string(),
                Value::String(correlation.trace_id.clone()),
            );
        }
        if !correlation.client_world_path.is_empty() {
            object.insert(
                "clientWorldPath".to_string(),
                Value::String(correlation.client_world_path.clone()),
            );
        }
        if !correlation.client_purpose.is_empty() {
            object.insert(
                "clientPurpose".to_string(),
                Value::String(correlation.client_purpose.clone()),
            );
        }
    }
    let terminal = matches!(
        kind.as_str(),
        "pong" | "done" | "error" | "heightmap" | "blocksReady"
    );
    let encoded = serde_json::to_string(&value).unwrap_or_else(|_| line.to_string());
    (encoded, terminal)
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
    diagnostics::Logger::new("IPC").child("WORLD").debug(
        "IpcHeightmapStorageRewrite",
        "resolved Overworld storage root",
        &[
            ("world", world_path.to_string()),
            ("storage_root", storage_root.display().to_string()),
        ],
    );
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
        world_path
            .join("dimensions")
            .join("minecraft")
            .join("overworld")
            .join("region"),
        world_path.join("region"),
    ];
    for candidate in &candidates {
        if has_region_files(candidate) {
            return candidate.parent().map(Path::to_path_buf).ok_or_else(|| {
                anyhow!(
                    "Overworld region path has no storage root: {}",
                    candidate.display()
                )
            });
        }
    }
    bail!(
        "no Overworld region files found; checked: {}, {}",
        candidates[0].display(),
        candidates[1].display()
    )
}

fn has_region_files(region_dir: &Path) -> bool {
    let Ok(entries) = fs::read_dir(region_dir) else {
        return false;
    };
    entries.flatten().any(|entry| {
        if !entry.file_type().is_ok_and(|kind| kind.is_file()) {
            return false;
        }
        entry
            .path()
            .extension()
            .and_then(|extension| extension.to_str())
            .is_some_and(|extension| {
                extension.eq_ignore_ascii_case("mca") || extension.eq_ignore_ascii_case("mcr")
            })
    })
}

/// Headless mode used by the self-spawned Minesport backend process.
///
/// This process owns Java and transparently relays the existing Java newline
/// JSON protocol. It also preserves the UI request's operation/trace IDs and
/// client routing metadata on Java responses, keeping observability and stale
/// response protection out of the Java engine.
pub fn run_engine_worker(jar: &Path) -> Result<()> {
    let logger = diagnostics::Logger::new("IPC").child("WORKER");
    let operation = logger
        .operation("JavaEngineWorkerLifecycle")
        .field("jar", jar.display());
    if !jar.is_file() {
        operation.failure(
            "embedded Java engine is unavailable",
            &[("jar", jar.display().to_string())],
        );
        return Err(anyhow!(
            "embedded Java engine is unavailable: {}",
            jar.display()
        ));
    }

    let java = match resolve_java() {
        Ok(java) => java,
        Err(error) => {
            operation.failure(
                "could not resolve Java runtime",
                &[("error", format!("{error:#}"))],
            );
            return Err(error);
        }
    };
    let java_major = java_major(&java);
    operation.event(
        "JavaEngineRuntimeResolved",
        "resolved Java runtime for embedded engine",
        &[
            ("java", java.display().to_string()),
            ("major", java_major.to_string()),
        ],
    );
    let mut command = Command::new(&java);
    command
        .arg("-jar")
        .arg(jar)
        .arg("--ipc")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    hide_console_window(&mut command);

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            operation.failure(
                "failed to start Java engine",
                &[
                    ("java", java.display().to_string()),
                    ("error", error.to_string()),
                ],
            );
            return Err(error)
                .with_context(|| format!("start Java engine with {}", java.display()));
        }
    };
    let java_pid = child.id();
    operation.event(
        "JavaEngineProcessStarted",
        "Java engine process started",
        &[
            ("pid", java_pid.to_string()),
            ("java", java.display().to_string()),
        ],
    );
    let mut java_stdin = child.stdin.take().context("open Java engine stdin")?;
    let java_stdout = child.stdout.take().context("open Java engine stdout")?;
    let java_stderr = child.stderr.take().context("open Java engine stderr")?;
    let correlations = Arc::new(Mutex::new(VecDeque::<RequestCorrelation>::new()));

    {
        let mut output = std::io::stdout().lock();
        let info = serde_json::json!({
            "type": "workerInfo",
            "message": format!("Java engine started (PID {java_pid}) with {}", java.display())
        });
        writeln!(output, "{info}").context("announce Java engine worker")?;
        output.flush().context("flush Java engine announcement")?;
    }

    let stdout_correlations = correlations.clone();
    let stdout_relay = thread::spawn(move || -> std::io::Result<()> {
        let reader = BufReader::new(java_stdout);
        let stdout = std::io::stdout();
        let mut output = stdout.lock();
        for line in reader.lines() {
            let line = line?;
            let correlation = stdout_correlations
                .lock()
                .ok()
                .and_then(|queue| queue.front().cloned())
                .unwrap_or_default();
            let (line, terminal) = annotate_java_response(&line, &correlation);
            writeln!(output, "{line}")?;
            output.flush()?;
            if terminal {
                if let Ok(mut queue) = stdout_correlations.lock() {
                    let _ = queue.pop_front();
                }
            }
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

    let stdin_correlations = correlations;
    thread::spawn(move || {
        let reader = BufReader::new(std::io::stdin());
        for line in reader.lines() {
            let Ok(line) = line else {
                break;
            };
            if let Ok(mut queue) = stdin_correlations.lock() {
                queue.push_back(correlation_from_request_line(&line));
            }
            if java_stdin.write_all(line.as_bytes()).is_err() {
                break;
            }
            if java_stdin.write_all(b"\n").is_err() {
                break;
            }
            if java_stdin.flush().is_err() {
                break;
            }
        }
    });

    let status = child.wait().context("wait for Java engine")?;
    let _ = stdout_relay.join();
    let _ = stderr_relay.join();
    if !status.success() {
        operation.failure(
            "Java engine process exited unsuccessfully",
            &[
                ("status", status.to_string()),
                ("pid", java_pid.to_string()),
            ],
        );
        return Err(anyhow!("Java engine exited with status {status}"));
    }
    operation.success(
        "Java engine process exited cleanly",
        &[
            ("status", status.to_string()),
            ("pid", java_pid.to_string()),
        ],
    );
    Ok(())
}

fn reader_generation_is_current(generation: &AtomicU64, reader_generation: u64) -> bool {
    generation.load(Ordering::Acquire) == reader_generation
}

fn spawn_stdout_reader(
    stdout: impl std::io::Read + Send + 'static,
    tx: Sender<EngineEvent>,
    generation: Arc<AtomicU64>,
    reader_generation: u64,
) {
    thread::spawn(move || {
        let logger = diagnostics::Logger::new("IPC").child("RX");
        let reader = BufReader::new(stdout);
        let mut last_progress: Option<(String, String, i32, String)> = None;
        for line in reader.lines() {
            if !reader_generation_is_current(&generation, reader_generation) {
                return;
            }
            match line {
                Ok(line) if !line.trim().is_empty() => {
                    let response =
                        serde_json::from_str::<Response>(&line).unwrap_or_else(|_| Response {
                            kind: "log".to_string(),
                            message: line,
                            ..Response::default()
                        });
                    match response.kind.as_str() {
                        "log" => diagnostics::append_correlated(
                            &response.message,
                            &response.operation_id,
                            &response.trace_id,
                        ),
                        "progress" => {
                            let percent = response.percent.clamp(0, 100);
                            let progress_key = (
                                response.operation_id.clone(),
                                response.trace_id.clone(),
                                percent,
                                response.message.clone(),
                            );
                            if last_progress.as_ref() != Some(&progress_key) {
                                logger.correlated(
                                    diagnostics::Level::Info,
                                    "IpcResponseProgress",
                                    &response.operation_id,
                                    &response.trace_id,
                                    &response.message,
                                    &[("percent", percent.to_string())],
                                );
                                last_progress = Some(progress_key);
                            }
                        }
                        "done" => {
                            last_progress = None;
                            logger.correlated(
                                diagnostics::Level::Info,
                                "IpcResponseDone",
                                &response.operation_id,
                                &response.trace_id,
                                "engine operation completed",
                                &[
                                    ("output", response.output.clone()),
                                    ("blocks", response.block_count.to_string()),
                                    ("faces", response.quad_count.to_string()),
                                    ("vertices", response.vertex_count.to_string()),
                                ],
                            );
                        }
                        "error" => {
                            last_progress = None;
                            logger.correlated(
                                diagnostics::Level::Error,
                                "IpcResponseError",
                                &response.operation_id,
                                &response.trace_id,
                                &response.message,
                                &[],
                            );
                        }
                        kind => logger.correlated(
                            diagnostics::Level::Debug,
                            "IpcResponseReceived",
                            &response.operation_id,
                            &response.trace_id,
                            "backend response received",
                            &[("type", kind.to_string())],
                        ),
                    }
                    if !reader_generation_is_current(&generation, reader_generation) {
                        return;
                    }
                    if tx.send(EngineEvent::Response(response)).is_err() {
                        logger.warn("IpcResponseConsumerClosed", "UI event receiver closed", &[]);
                        return;
                    }
                }
                Ok(_) => {}
                Err(error) => {
                    if !reader_generation_is_current(&generation, reader_generation) {
                        return;
                    }
                    let message = format!("Minesport backend IPC read failed: {error}");
                    logger.error(
                        "IpcResponseReadFailed",
                        &message,
                        &[("error", error.to_string())],
                    );
                    let _ = tx.send(EngineEvent::ReadEnded(message));
                    return;
                }
            }
        }
        if !reader_generation_is_current(&generation, reader_generation) {
            return;
        }
        logger.info(
            "IpcResponseStreamClosed",
            "Minesport backend output closed",
            &[],
        );
        let _ = tx.send(EngineEvent::ReadEnded(
            "Minesport backend output closed".to_string(),
        ));
    });
}

fn spawn_stderr_reader(
    stderr: impl std::io::Read + Send + 'static,
    tx: Sender<EngineEvent>,
    generation: Arc<AtomicU64>,
    reader_generation: u64,
) {
    thread::spawn(move || {
        let logger = diagnostics::Logger::new("IPC").child("STDERR");
        for line in BufReader::new(stderr).lines() {
            if !reader_generation_is_current(&generation, reader_generation) {
                return;
            }
            match line {
                Ok(line) if !line.trim().is_empty() => {
                    logger.warn("IpcBackendStderrLine", &line, &[]);
                    if !reader_generation_is_current(&generation, reader_generation) {
                        return;
                    }
                    if tx.send(EngineEvent::Stderr(line)).is_err() {
                        return;
                    }
                }
                Ok(_) => {}
                Err(error) => {
                    if !reader_generation_is_current(&generation, reader_generation) {
                        return;
                    }
                    let message = format!("backend stderr read failed: {error}");
                    logger.error(
                        "IpcBackendStderrReadFailed",
                        &message,
                        &[("error", error.to_string())],
                    );
                    let _ = tx.send(EngineEvent::Stderr(message));
                    return;
                }
            }
        }
    });
}

fn resolve_java() -> Result<PathBuf> {
    let logger = diagnostics::Logger::new("IPC").child("JAVA");
    let mut candidates = Vec::new();

    if let Some(home) = env::var_os("JAVA_HOME") {
        push_java_candidate(
            &mut candidates,
            PathBuf::from(home).join("bin").join(java_executable_name()),
        );
    }
    if let Some(path_java) = find_on_path(java_executable_name()) {
        push_java_candidate(&mut candidates, path_java);
    }

    if cfg!(windows) {
        if let Some(appdata) = env::var_os("APPDATA").map(PathBuf::from) {
            for runtime_name in ["java-runtime-delta", "java-runtime-gamma"] {
                push_java_candidate(
                    &mut candidates,
                    appdata
                        .join("FreesmLauncher")
                        .join("java")
                        .join(runtime_name)
                        .join("bin")
                        .join("java.exe"),
                );
            }
        }
        if let Some(program_files) = env::var_os("ProgramFiles").map(PathBuf::from) {
            for base in [
                program_files.join("Java"),
                program_files.join("Eclipse Adoptium"),
            ] {
                let Ok(entries) = fs::read_dir(base) else {
                    continue;
                };
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

    logger.debug(
        "JavaRuntimeCandidatesEvaluate",
        "evaluating Java runtime candidates",
        &[("count", candidates.len().to_string())],
    );
    for candidate in candidates {
        let major = java_major(&candidate);
        if major >= ENGINE_JAVA_MAJOR {
            logger.info(
                "JavaRuntimeSelected",
                "selected Java runtime",
                &[
                    ("path", candidate.display().to_string()),
                    ("major", major.to_string()),
                ],
            );
            return Ok(candidate);
        }
        logger.warn(
            "JavaRuntimeRejectedTooOld",
            "Java runtime is below the engine requirement",
            &[
                ("path", candidate.display().to_string()),
                ("major", major.to_string()),
                ("required", ENGINE_JAVA_MAJOR.to_string()),
            ],
        );
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
            existing
                .to_string_lossy()
                .eq_ignore_ascii_case(&candidate.to_string_lossy())
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
    let Ok(Some(output)) = runtime::output_with_timeout(&mut command, Duration::from_secs(5))
    else {
        return 0;
    };
    let text = format!(
        "{} {}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    parse_java_major(&text).unwrap_or(0)
}

fn parse_java_major(text: &str) -> Option<u32> {
    let quoted = text.split('"').nth(1);
    let version = quoted.or_else(|| {
        text.split_whitespace()
            .find(|token| token.chars().next().is_some_and(|ch| ch.is_ascii_digit()))
    })?;
    let mut parts = version.split('.');
    let first = parts
        .next()?
        .trim_matches(|ch: char| !ch.is_ascii_digit())
        .parse::<u32>()
        .ok()?;
    if first == 1 {
        parts
            .next()?
            .trim_matches(|ch: char| !ch.is_ascii_digit())
            .parse::<u32>()
            .ok()
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
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-ipc-{name}-{}-{stamp}",
            std::process::id()
        ));
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
        assert_eq!(
            resolve_overworld_storage_root(&world).unwrap(),
            world.join("dimensions/minecraft/overworld")
        );
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
    fn heightmap_payload_is_rewritten_but_client_world_identity_is_preserved() {
        let world = temp_world("payload");
        let modern = world.join("dimensions/minecraft/overworld/region");
        fs::create_dir_all(&modern).unwrap();
        fs::write(modern.join("r.0.0.mca"), b"region").unwrap();
        let mut heightmap = serde_json::json!({ "command": "heightmap", "worldPath": world });
        preserve_client_world_path(&mut heightmap);
        prepare_world_storage_payload(&mut heightmap).unwrap();
        assert_eq!(
            heightmap
                .get("worldPath")
                .and_then(Value::as_str)
                .map(PathBuf::from),
            Some(world.join("dimensions/minecraft/overworld"))
        );
        assert_eq!(
            heightmap
                .get("clientWorldPath")
                .and_then(Value::as_str)
                .map(PathBuf::from),
            Some(world.clone())
        );
        let mut export = serde_json::json!({ "command": "export", "worldPath": world });
        preserve_client_world_path(&mut export);
        prepare_world_storage_payload(&mut export).unwrap();
        assert_eq!(
            export
                .get("worldPath")
                .and_then(Value::as_str)
                .map(PathBuf::from),
            Some(world.clone())
        );
        assert_eq!(
            export
                .get("clientWorldPath")
                .and_then(Value::as_str)
                .map(PathBuf::from),
            Some(world.clone())
        );
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn java_major_parser_handles_legacy_and_modern_version_strings() {
        assert_eq!(parse_java_major("java version \"1.8.0_401\""), Some(8));
        assert_eq!(
            parse_java_major("openjdk version \"17.0.12\" 2024-07-16"),
            Some(17)
        );
        assert_eq!(
            parse_java_major("openjdk version \"22.0.2\" 2024-07-16"),
            Some(22)
        );
        assert_eq!(parse_java_major("openjdk 25.0.1"), Some(25));
    }

    #[test]
    fn ipc_command_operation_ids_are_hardcoded_and_searchable() {
        assert_eq!(request_operation_id("export"), "IpcRequestDispatchExport");
        assert_eq!(
            request_operation_id("heightmap"),
            "IpcRequestDispatchHeightmap"
        );
        assert_eq!(
            request_operation_id("listBlocks"),
            "IpcRequestDispatchBlockList"
        );
        assert_eq!(request_operation_id("ping"), "IpcRequestPingBackend");
        assert_eq!(request_operation_id("quit"), "IpcRequestQuitBackend");
        assert_eq!(
            request_operation_id("mystery"),
            "IpcRequestDispatchUnknownCommand"
        );
    }

    #[test]
    fn request_correlation_is_read_from_json() {
        let request = serde_json::json!({
            "command": "listBlocks",
            "operationId": "IpcRequestDispatchBlockList",
            "traceId": "123-000004",
            "clientWorldPath": "C:/world-a",
            "clientPurpose": "preview"
        })
        .to_string();
        let correlation = correlation_from_request_line(&request);
        assert_eq!(correlation.operation_id, "IpcRequestDispatchBlockList");
        assert_eq!(correlation.trace_id, "123-000004");
        assert_eq!(correlation.client_world_path, "C:/world-a");
        assert_eq!(correlation.client_purpose, "preview");
    }

    #[test]
    fn java_terminal_response_is_correlated_and_marks_queue_completion() {
        let correlation = RequestCorrelation {
            operation_id: "IpcRequestDispatchExport".to_string(),
            trace_id: "123-000004".to_string(),
            client_world_path: "C:/world-a".to_string(),
            client_purpose: "export".to_string(),
        };
        let response = serde_json::json!({ "type": "done", "output": "test.obj" }).to_string();
        let (line, terminal) = annotate_java_response(&response, &correlation);
        let value: Value = serde_json::from_str(&line).unwrap();
        assert!(terminal);
        assert_eq!(
            value.get("operationId").and_then(Value::as_str),
            Some("IpcRequestDispatchExport")
        );
        assert_eq!(
            value.get("traceId").and_then(Value::as_str),
            Some("123-000004")
        );
        assert_eq!(
            value.get("clientWorldPath").and_then(Value::as_str),
            Some("C:/world-a")
        );
        assert_eq!(
            value.get("clientPurpose").and_then(Value::as_str),
            Some("export")
        );
    }

    #[test]
    fn java_progress_response_keeps_request_open() {
        let correlation = RequestCorrelation {
            operation_id: "IpcRequestDispatchExport".to_string(),
            trace_id: "123-000004".to_string(),
            ..RequestCorrelation::default()
        };
        let response = serde_json::json!({
            "type": "progress", "percent": 62, "message": "Building geometry"
        })
        .to_string();
        let (_, terminal) = annotate_java_response(&response, &correlation);
        assert!(!terminal);
    }

    #[test]
    fn stale_backend_reader_generations_are_rejected() {
        let generation = AtomicU64::new(7);
        assert!(reader_generation_is_current(&generation, 7));
        assert!(!reader_generation_is_current(&generation, 6));
        generation.store(8, Ordering::Release);
        assert!(!reader_generation_is_current(&generation, 7));
        assert!(reader_generation_is_current(&generation, 8));
    }
}
