from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


ipc = Path("desktop/src/ipc.rs")
replace_once(
    ipc,
    '''    events: Sender<EngineEvent>,\n    launch: BackendLaunch,\n    #[cfg(windows)]\n''',
    '''    events: Sender<EngineEvent>,\n    launch: Mutex<BackendLaunch>,\n    pong_count: Arc<AtomicU64>,\n    #[cfg(windows)]\n''',
    "EngineInner launch/pong state",
)
replace_once(
    ipc,
    '''        let (tx, rx) = mpsc::channel();\n        let generation = Arc::new(AtomicU64::new(1));\n''',
    '''        let (tx, rx) = mpsc::channel();\n        let generation = Arc::new(AtomicU64::new(1));\n        let pong_count = Arc::new(AtomicU64::new(0));\n''',
    "start pong counter",
)
replace_once(
    ipc,
    '''        spawn_stdout_reader(stdout, tx.clone(), generation.clone(), 1);\n''',
    '''        spawn_stdout_reader(\n            stdout,\n            tx.clone(),\n            generation.clone(),\n            pong_count.clone(),\n            1,\n        );\n''',
    "start stdout reader pong counter",
)
replace_once(
    ipc,
    '''                    events: tx,\n                    launch,\n                    #[cfg(windows)]\n''',
    '''                    events: tx,\n                    launch: Mutex::new(launch),\n                    pong_count,\n                    #[cfg(windows)]\n''',
    "EngineInner start initialization",
)
replace_once(
    ipc,
    '''        let launch = self.inner.launch.clone();\n        operation.event(\n''',
    '''        let mut launch = self\n            .inner\n            .launch\n            .lock()\n            .map_err(|_| anyhow!("Minesport backend launch lock poisoned during restart"))?\n            .clone();\n        operation.event(\n''',
    "restart launch clone",
)
old_restart_spawn = '''        let mut command = launch.command();\n\n        let mut child = command.spawn().with_context(|| {\n            format!(\n                "restart Minesport backend worker {}",\n                launch.executable.display()\n            )\n        })?;\n'''
new_restart_spawn = '''        let mut command = launch.command();\n\n        let mut child = match command.spawn() {\n            Ok(child) => child,\n            Err(sidecar_error) if launch.mode == "sidecar" => {\n                logger.warn(\n                    "EngineSidecarRespawnFailedFallback",\n                    "engine sidecar could not be restarted; using embedded fallback worker",\n                    &[\n                        ("error", sidecar_error.to_string()),\n                        ("executable", launch.executable.display().to_string()),\n                        ("engine_version", launch.engine_version.clone()),\n                    ],\n                );\n                let fallback = BackendLaunch::self_worker(\n                    env::current_exe()\n                        .context("resolve Minesport executable for restart fallback")?,\n                );\n                let mut fallback_command = fallback.command();\n                match fallback_command.spawn() {\n                    Ok(child) => {\n                        launch = fallback;\n                        child\n                    }\n                    Err(fallback_error) => {\n                        operation.failure(\n                            "failed to restart both the sidecar and embedded fallback worker",\n                            &[\n                                ("sidecar_error", sidecar_error.to_string()),\n                                ("fallback_error", fallback_error.to_string()),\n                                ("fallback_executable", fallback.executable.display().to_string()),\n                            ],\n                        );\n                        return Err(fallback_error).with_context(|| {\n                            format!(\n                                "restart embedded Minesport backend fallback {} after sidecar error: {sidecar_error}",\n                                fallback.executable.display()\n                            )\n                        });\n                    }\n                }\n            }\n            Err(error) => {\n                return Err(error).with_context(|| {\n                    format!(\n                        "restart Minesport backend worker {}",\n                        launch.executable.display()\n                    )\n                });\n            }\n        };\n'''
replace_once(ipc, old_restart_spawn, new_restart_spawn, "restart sidecar fallback")
replace_once(
    ipc,
    '''        {\n            let mut current = self\n                .inner\n                .child\n                .lock()\n                .map_err(|_| anyhow!("Minesport backend child lock poisoned during restart"))?;\n            *current = child;\n        }\n\n        let tx = self.inner.events.clone();\n''',
    '''        {\n            let mut current = self\n                .inner\n                .child\n                .lock()\n                .map_err(|_| anyhow!("Minesport backend child lock poisoned during restart"))?;\n            *current = child;\n        }\n        {\n            let mut current = self\n                .inner\n                .launch\n                .lock()\n                .map_err(|_| anyhow!("Minesport backend launch lock poisoned during restart"))?;\n            *current = launch.clone();\n        }\n\n        let tx = self.inner.events.clone();\n''',
    "remember restart fallback launch",
)
replace_once(
    ipc,
    '''        spawn_stdout_reader(\n            stdout,\n            tx.clone(),\n            self.inner.generation.clone(),\n            reader_generation,\n        );\n''',
    '''        spawn_stdout_reader(\n            stdout,\n            tx.clone(),\n            self.inner.generation.clone(),\n            self.inner.pong_count.clone(),\n            reader_generation,\n        );\n''',
    "restart stdout reader pong counter",
)
replace_once(
    ipc,
    '''    pub fn ping(&self) -> Result<()> {\n        self.send_value(serde_json::json!({ "command": "ping" }))\n    }\n\n    pub fn shutdown(&self) {\n''',
    '''    pub fn ping(&self) -> Result<()> {\n        self.send_value(serde_json::json!({ "command": "ping" }))\n    }\n\n    pub fn ping_confirmed(&self, timeout: Duration) -> Result<()> {\n        let generation = self.inner.generation.load(Ordering::Acquire);\n        let observed = self.inner.pong_count.load(Ordering::Acquire);\n        self.ping()?;\n        let deadline = Instant::now() + timeout;\n        loop {\n            if self.inner.generation.load(Ordering::Acquire) != generation {\n                bail!("backend generation changed while waiting for ping response");\n            }\n            if self.inner.pong_count.load(Ordering::Acquire) > observed {\n                return Ok(());\n            }\n            if let Ok(mut child) = self.inner.child.lock() {\n                if let Ok(Some(status)) = child.try_wait() {\n                    bail!("backend exited with status {status} before answering ping");\n                }\n            }\n            if Instant::now() >= deadline {\n                bail!(\n                    "backend did not answer ping within {} ms",\n                    timeout.as_millis()\n                );\n            }\n            thread::sleep(Duration::from_millis(25));\n        }\n    }\n\n    pub fn shutdown(&self) {\n''',
    "confirmed ping method",
)
replace_once(
    ipc,
    '''fn spawn_stdout_reader(\n    stdout: impl std::io::Read + Send + 'static,\n    tx: Sender<EngineEvent>,\n    generation: Arc<AtomicU64>,\n    reader_generation: u64,\n) {\n''',
    '''fn spawn_stdout_reader(\n    stdout: impl std::io::Read + Send + 'static,\n    tx: Sender<EngineEvent>,\n    generation: Arc<AtomicU64>,\n    pong_count: Arc<AtomicU64>,\n    reader_generation: u64,\n) {\n''',
    "stdout reader pong parameter",
)
replace_once(
    ipc,
    '''                    match response.kind.as_str() {\n                        "log" => diagnostics::append_correlated(\n''',
    '''                    if response.kind == "pong" {\n                        pong_count.fetch_add(1, Ordering::AcqRel);\n                    }\n                    match response.kind.as_str() {\n                        "log" => diagnostics::append_correlated(\n''',
    "record pong response",
)

app = Path("desktop/src/app.rs")
replace_once(
    app,
    '''                        match engine.restart() {\n                            Ok(()) => match engine.ping() {\n''',
    '''                        match engine.restart() {\n                            Ok(()) => match engine.ping_confirmed(Duration::from_secs(10)) {\n''',
    "recovery confirmed ping",
)
replace_once(
    app,
    '                                    last_error = format!("restart ping failed: {error:#}");\n',
    '                                    last_error = format!("restart readiness check failed: {error:#}");\n',
    "recovery readiness error wording",
)
