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
    '''    pub fn is_shutting_down(&self) -> bool {\n        self.inner.shutting_down.load(Ordering::Acquire)\n    }\n\n    pub fn send<T: Serialize>(&self, request: &T) -> Result<()> {\n''',
    '''    pub fn activate_embedded_fallback(&self, reason: &str) -> Result<bool> {\n        let mut launch = self\n            .inner\n            .launch\n            .lock()\n            .map_err(|_| anyhow!("Minesport backend launch lock poisoned while activating fallback"))?;\n        if launch.mode != "sidecar" {\n            return Ok(false);\n        }\n\n        let previous_executable = launch.executable.display().to_string();\n        let previous_version = launch.engine_version.clone();\n        let fallback = BackendLaunch::self_worker(\n            env::current_exe().context("resolve Minesport executable for health fallback")?,\n        );\n        diagnostics::Logger::new("IPC").child("UI").warn(\n            "EngineSidecarHealthFailedFallback",\n            "verified engine sidecar failed runtime health; embedded fallback selected for this session",\n            &[\n                ("reason", reason.to_string()),\n                ("sidecar", previous_executable),\n                ("sidecar_version", previous_version),\n                ("fallback", fallback.executable.display().to_string()),\n                ("fallback_version", fallback.engine_version.clone()),\n            ],\n        );\n        *launch = fallback;\n        Ok(true)\n    }\n\n    pub fn is_shutting_down(&self) -> bool {\n        self.inner.shutting_down.load(Ordering::Acquire)\n    }\n\n    pub fn send<T: Serialize>(&self, request: &T) -> Result<()> {\n''',
    "session health fallback method",
)

app = Path("desktop/src/app.rs")
replace_once(
    app,
    '''    thread::spawn(move || {\n        if let Err(error) = ping_engine.ping_confirmed(Duration::from_secs(10)) {\n            let _ = ping_weak.upgrade_in_event_loop(move |ui| {\n                if !ui.get_engine_ready() {\n                    ui.set_engine_status("ENGINE ERROR".into());\n                    ui.set_task_title("ENGINE ERROR".into());\n                    ui.set_task_detail("Backend readiness check failed".into());\n                }\n                append_diagnostic(\n                    &ui,\n                    &format!("Minesport backend did not confirm startup readiness: {error:#}"),\n                );\n            });\n        }\n    });\n''',
    '''    thread::spawn(move || {\n        let mut readiness = ping_engine.ping_confirmed(Duration::from_secs(10));\n        if let Err(error) = &readiness {\n            let reason = format!("initial readiness failed: {error:#}");\n            match ping_engine.activate_embedded_fallback(&reason) {\n                Ok(true) => {\n                    readiness = ping_engine\n                        .restart()\n                        .and_then(|_| ping_engine.ping_confirmed(Duration::from_secs(10)));\n                }\n                Ok(false) => {}\n                Err(fallback_error) => {\n                    readiness = Err(fallback_error.context(reason));\n                }\n            }\n        }\n        if let Err(error) = readiness {\n            let _ = ping_weak.upgrade_in_event_loop(move |ui| {\n                if !ui.get_engine_ready() {\n                    ui.set_engine_status("ENGINE ERROR".into());\n                    ui.set_task_title("ENGINE ERROR".into());\n                    ui.set_task_detail("Backend readiness check failed".into());\n                }\n                append_diagnostic(\n                    &ui,\n                    &format!("Minesport backend did not confirm startup readiness: {error:#}"),\n                );\n            });\n        }\n    });\n''',
    "startup health fallback",
)
replace_once(
    app,
    '''                                Err(error) => {\n                                    last_error =\n                                        format!("restart readiness check failed: {error:#}");\n                                }\n''',
    '''                                Err(error) => {\n                                    last_error =\n                                        format!("restart readiness check failed: {error:#}");\n                                    let reason = last_error.clone();\n                                    match engine.activate_embedded_fallback(&reason) {\n                                        Ok(true) => pending_logs.push(\n                                            "Engine sidecar failed confirmed health; embedded fallback selected for the next recovery attempt".to_string(),\n                                        ),\n                                        Ok(false) => {}\n                                        Err(fallback_error) => {\n                                            last_error = format!(\n                                                "{last_error} · could not activate embedded fallback: {fallback_error:#}"\n                                            );\n                                        }\n                                    }\n                                }\n''',
    "restart health fallback",
)
