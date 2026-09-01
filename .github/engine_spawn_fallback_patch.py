from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


update = Path("desktop/src/engine_update.rs")
replace_once(
    update,
    '            discard_previous_engine(&executable);\n            clear_staged_update(&stage);\n',
    '            // Keep one previous engine generation after a successful update.\n            // Hash/protocol validation proves the files are intact, but the new\n            // sidecar has not completed a real process/IPC startup yet. The next\n            // installer replacement may rotate this rollback generation.\n            clear_staged_update(&stage);\n',
    "preserve previous engine generation",
)
replace_once(
    update,
    '''fn discard_previous_engine(desktop_executable: &Path) {\n    let Some(root) = desktop_executable.parent() else {\n        return;\n    };\n    let _ = fs::remove_file(root.join("minesport-engine.exe.prev"));\n    let _ = fs::remove_file(root.join("minesport-engine.json.prev"));\n}\n\n''',
    '',
    "remove eager previous-engine deletion helper",
)

ipc = Path("desktop/src/ipc.rs")
replace_once(
    ipc,
    'pub const ENGINE_PROTOCOL_VERSION: u32 = 1;\nconst ENGINE_SIDECAR_MANIFEST_SCHEMA: u32 = 1;\n',
    'pub const ENGINE_PROTOCOL_VERSION: u32 = 1;\nconst ENGINE_SIDECAR_MANIFEST_SCHEMA: u32 = 1;\nconst EMBEDDED_ENGINE_VERSION_RAW: &str = include_str!("../../engine/VERSION");\n',
    "embedded engine version constant",
)
replace_once(
    ipc,
    '''impl BackendLaunch {\n    fn resolve() -> Result<Self> {\n''',
    '''impl BackendLaunch {\n    fn self_worker(desktop: PathBuf) -> Self {\n        Self {\n            executable: desktop,\n            args: vec!["--engine-worker".to_string()],\n            mode: "self-worker-fallback",\n            engine_version: EMBEDDED_ENGINE_VERSION_RAW.trim().to_string(),\n        }\n    }\n\n    fn resolve() -> Result<Self> {\n''',
    "BackendLaunch self-worker helper",
)
replace_once(
    ipc,
    '''        Ok(Self {\n            executable: desktop,\n            args: vec!["--engine-worker".to_string()],\n            mode: "self-worker-fallback",\n            engine_version: env!("CARGO_PKG_VERSION").to_string(),\n        })\n''',
    '        Ok(Self::self_worker(desktop))\n',
    "BackendLaunch fallback identity",
)
replace_once(
    ipc,
    '        let launch = BackendLaunch::resolve()?;\n',
    '        let mut launch = BackendLaunch::resolve()?;\n',
    "mutable resolved backend launch",
)
old_spawn = '''        let mut command = launch.command();\n        let mut child = match command.spawn() {\n            Ok(child) => child,\n            Err(error) => {\n                operation.failure(\n                    "failed to start Minesport backend worker",\n                    &[\n                        ("error", error.to_string()),\n                        ("executable", launch.executable.display().to_string()),\n                        ("mode", launch.mode.to_string()),\n                    ],\n                );\n                return Err(error).with_context(|| {\n                    format!(\n                        "start Minesport backend worker {}",\n                        launch.executable.display()\n                    )\n                });\n            }\n        };\n'''
new_spawn = '''        let mut command = launch.command();\n        let mut child = match command.spawn() {\n            Ok(child) => child,\n            Err(sidecar_error) if launch.mode == "sidecar" => {\n                logger.warn(\n                    "EngineSidecarSpawnFailedFallback",\n                    "verified engine sidecar could not be launched; using embedded fallback worker",\n                    &[\n                        ("error", sidecar_error.to_string()),\n                        ("executable", launch.executable.display().to_string()),\n                        ("engine_version", launch.engine_version.clone()),\n                    ],\n                );\n                let fallback = BackendLaunch::self_worker(\n                    env::current_exe().context("resolve Minesport executable for backend fallback")?,\n                );\n                operation.event(\n                    "IpcBackendWorkerFallbackSpawn",\n                    "starting embedded Minesport backend fallback after sidecar launch failure",\n                    &[\n                        ("executable", fallback.executable.display().to_string()),\n                        ("mode", fallback.mode.to_string()),\n                        ("engine_version", fallback.engine_version.clone()),\n                    ],\n                );\n                let mut fallback_command = fallback.command();\n                match fallback_command.spawn() {\n                    Ok(child) => {\n                        launch = fallback;\n                        child\n                    }\n                    Err(fallback_error) => {\n                        operation.failure(\n                            "failed to start both the verified sidecar and embedded fallback worker",\n                            &[\n                                ("sidecar_error", sidecar_error.to_string()),\n                                ("fallback_error", fallback_error.to_string()),\n                                ("fallback_executable", fallback.executable.display().to_string()),\n                            ],\n                        );\n                        return Err(fallback_error).with_context(|| {\n                            format!(\n                                "start embedded Minesport backend fallback {} after sidecar error: {sidecar_error}",\n                                fallback.executable.display()\n                            )\n                        });\n                    }\n                }\n            }\n            Err(error) => {\n                operation.failure(\n                    "failed to start Minesport backend worker",\n                    &[\n                        ("error", error.to_string()),\n                        ("executable", launch.executable.display().to_string()),\n                        ("mode", launch.mode.to_string()),\n                    ],\n                );\n                return Err(error).with_context(|| {\n                    format!(\n                        "start Minesport backend worker {}",\n                        launch.executable.display()\n                    )\n                });\n            }\n        };\n'''
replace_once(ipc, old_spawn, new_spawn, "sidecar spawn fallback")
