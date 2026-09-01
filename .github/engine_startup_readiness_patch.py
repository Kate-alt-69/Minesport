from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


app = Path("desktop/src/app.rs")
replace_once(
    app,
    '''    append_diagnostic(\n        &ui,\n        "Backend boundary: Minesport.exe --engine-worker → embedded Java engine",\n    );\n''',
    '''    append_diagnostic(\n        &ui,\n        "Backend boundary: verified minesport-engine sidecar → embedded Java engine · Minesport.exe self-worker fallback available",\n    );\n''',
    "backend boundary diagnostic",
)
replace_once(
    app,
    '''    thread::spawn(move || {\n        if let Err(error) = ping_engine.ping() {\n            let _ = ping_weak.upgrade_in_event_loop(move |ui| {\n                ui.set_engine_status("ENGINE ERROR".into());\n                append_diagnostic(&ui, &format!("Could not ping Minesport backend: {error:#}"));\n            });\n        }\n    });\n''',
    '''    thread::spawn(move || {\n        if let Err(error) = ping_engine.ping_confirmed(Duration::from_secs(10)) {\n            let _ = ping_weak.upgrade_in_event_loop(move |ui| {\n                if !ui.get_engine_ready() {\n                    ui.set_engine_status("ENGINE ERROR".into());\n                    ui.set_task_title("ENGINE ERROR".into());\n                    ui.set_task_detail("Backend readiness check failed".into());\n                }\n                append_diagnostic(\n                    &ui,\n                    &format!("Minesport backend did not confirm startup readiness: {error:#}"),\n                );\n            });\n        }\n    });\n''',
    "initial confirmed backend readiness",
)
