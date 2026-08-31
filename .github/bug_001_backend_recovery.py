from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


ipc_path = Path("desktop/src/ipc.rs")
ipc = ipc_path.read_text(encoding="utf-8")

ipc = replace_once(
    ipc,
    '''    sync::{Arc, Mutex, mpsc::{self, Receiver, Sender}},''',
    '''    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
    },''',
    "ipc atomic imports",
)

ipc = replace_once(
    ipc,
    '''struct EngineInner {
    stdin: Mutex<ChildStdin>,
    child: Mutex<Child>,
}''',
    '''struct EngineInner {
    stdin: Mutex<ChildStdin>,
    child: Mutex<Child>,
    events: Sender<EngineEvent>,
    executable: PathBuf,
    restarting: AtomicBool,
    shutting_down: AtomicBool,
}''',
    "restartable engine state",
)

ipc = replace_once(
    ipc,
    '''        spawn_stdout_reader(stdout, tx.clone());
        spawn_stderr_reader(stderr, tx);''',
    '''        spawn_stdout_reader(stdout, tx.clone());
        spawn_stderr_reader(stderr, tx.clone());''',
    "retain engine event sender",
)

ipc = replace_once(
    ipc,
    '''                inner: Arc::new(EngineInner {
                    stdin: Mutex::new(stdin),
                    child: Mutex::new(child),
                }),''',
    '''                inner: Arc::new(EngineInner {
                    stdin: Mutex::new(stdin),
                    child: Mutex::new(child),
                    events: tx,
                    executable,
                    restarting: AtomicBool::new(false),
                    shutting_down: AtomicBool::new(false),
                }),''',
    "engine restart metadata",
)

ipc = replace_once(
    ipc,
    '''    pub fn send<T: Serialize>(&self, request: &T) -> Result<()> {''',
    '''    pub fn restart(&self) -> Result<()> {
        if self.is_shutting_down() {
            bail!("backend shutdown is already in progress");
        }
        if self.inner.restarting.compare_exchange(
            false,
            true,
            Ordering::AcqRel,
            Ordering::Acquire,
        ).is_err() {
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

        let executable = self.inner.executable.clone();
        operation.event(
            "IpcBackendWorkerRespawn",
            "restarting isolated Minesport backend worker",
            &[("executable", executable.display().to_string())],
        );
        let mut command = Command::new(&executable);
        command
            .arg("--engine-worker")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        hide_console_window(&mut command);

        let mut child = command
            .spawn()
            .with_context(|| format!("restart Minesport backend worker {}", executable.display()))?;
        let pid = child.id();
        let stdin = child.stdin.take().context("open restarted Minesport backend stdin")?;
        let stdout = child.stdout.take().context("open restarted Minesport backend stdout")?;
        let stderr = child.stderr.take().context("open restarted Minesport backend stderr")?;

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
            process: executable.display().to_string(),
        });
        spawn_stdout_reader(stdout, tx.clone());
        spawn_stderr_reader(stderr, tx);
        operation.success(
            "Minesport backend worker restarted",
            &[("pid", pid.to_string()), ("executable", executable.display().to_string())],
        );
        Ok(())
    }

    pub fn is_shutting_down(&self) -> bool {
        self.inner.shutting_down.load(Ordering::Acquire)
    }

    pub fn send<T: Serialize>(&self, request: &T) -> Result<()> {''',
    "engine restart implementation",
)

ipc = replace_once(
    ipc,
    '''    pub fn shutdown(&self) {
        let logger = diagnostics::Logger::new("IPC").child("UI");''',
    '''    pub fn shutdown(&self) {
        self.inner.shutting_down.store(true, Ordering::Release);
        let logger = diagnostics::Logger::new("IPC").child("UI");''',
    "suppress restart during shutdown",
)

ipc_path.write_text(ipc, encoding="utf-8")


app_path = Path("desktop/src/app.rs")
app = app_path.read_text(encoding="utf-8")

app = replace_once(
    app,
    '''const VERSION: &str = "0.2.1";''',
    '''const VERSION: &str = "0.2.1";
const BACKEND_RESTART_ATTEMPTS: usize = 3;''',
    "backend restart attempt constant",
)

app = replace_once(
    app,
    '''    pump_engine_events(ui.as_weak(), events, state.clone());''',
    '''    pump_engine_events(ui.as_weak(), events, state.clone(), engine.clone());''',
    "event pump owns restartable engine",
)

app = replace_once(
    app,
    '''fn pump_engine_events(weak: slint::Weak<MainWindow>, events: Receiver<EngineEvent>, state: SharedState) {''',
    '''fn backend_restart_allowed(shutting_down: bool, attempts_used: usize) -> bool {
    !shutting_down && attempts_used < BACKEND_RESTART_ATTEMPTS
}

fn pump_engine_events(
    weak: slint::Weak<MainWindow>,
    events: Receiver<EngineEvent>,
    state: SharedState,
    engine: JavaEngine,
) {''',
    "restart-aware event pump",
)

app = replace_once(
    app,
    '''                Ok(EngineEvent::ReadEnded(message)) => {
                    flush_logs(&weak, &mut pending_logs);
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        ui.set_engine_ready(false);
                        ui.set_engine_status("ENGINE STOPPED".into());
                        ui.set_task_active(false);
                        append_diagnostic(&ui, &message);
                    });
                    break;
                }''',
    '''                Ok(EngineEvent::ReadEnded(message)) => {
                    flush_logs(&weak, &mut pending_logs);
                    let first_message = message.clone();
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        ui.set_engine_ready(false);
                        ui.set_engine_status("ENGINE RESTARTING".into());
                        ui.set_task_completing(false);
                        ui.set_task_active(false);
                        if ui.get_map_loading() { ui.set_map_loading(false); }
                        if ui.get_preview_loading() { ui.set_preview_loading(false); }
                        ui.set_task_title("ENGINE RESTARTING".into());
                        ui.set_task_detail("Previous engine operation was interrupted".into());
                        append_diagnostic(&ui, &first_message);
                    });

                    let mut recovered = false;
                    let mut last_error = String::new();
                    for attempt in 0..BACKEND_RESTART_ATTEMPTS {
                        if !backend_restart_allowed(engine.is_shutting_down(), attempt) {
                            break;
                        }
                        if attempt > 0 {
                            thread::sleep(Duration::from_millis(250 * attempt as u64));
                        }
                        match engine.restart() {
                            Ok(()) => match engine.ping() {
                                Ok(()) => {
                                    pending_logs.push(format!(
                                        "Minesport backend recovery dispatched on attempt {}",
                                        attempt + 1
                                    ));
                                    recovered = true;
                                    break;
                                }
                                Err(error) => {
                                    last_error = format!("restart ping failed: {error:#}");
                                }
                            },
                            Err(error) => {
                                last_error = format!("restart attempt {} failed: {error:#}", attempt + 1);
                            }
                        }
                    }
                    if recovered {
                        continue;
                    }
                    let detail = if last_error.is_empty() {
                        message
                    } else {
                        format!("{message} · automatic recovery failed: {last_error}")
                    };
                    let _ = weak.upgrade_in_event_loop(move |ui| {
                        ui.set_engine_ready(false);
                        ui.set_engine_status("ENGINE STOPPED".into());
                        ui.set_task_completing(false);
                        ui.set_task_active(false);
                        append_diagnostic(&ui, &detail);
                    });
                    break;
                }''',
    "recover backend after read end",
)

app = replace_once(
    app,
    '''    fn block_list_request_carries_its_own_action_identity() {''',
    '''    fn backend_restart_policy_stops_after_three_attempts_or_shutdown() {
        assert!(backend_restart_allowed(false, 0));
        assert!(backend_restart_allowed(false, 2));
        assert!(!backend_restart_allowed(false, 3));
        assert!(!backend_restart_allowed(true, 0));
    }

    #[test]
    fn block_list_request_carries_its_own_action_identity() {''',
    "backend restart policy regression test",
)

app_path.write_text(app, encoding="utf-8")
print("BUG-001: backend IPC death now recovers through the existing shared Engine handle")
