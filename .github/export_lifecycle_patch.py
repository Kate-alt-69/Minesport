from pathlib import Path

PATH = Path("desktop/src/app.rs")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''        let format_index = ui.get_export_format_index();
        if let Ok(mut guard) = state.lock() {
            guard.export_format_index = format_index;
        }
        if format_index == 2 {
            cache.cancel();
            ui.set_runtime_cache_status("NOT REQUIRED FOR LITEMATICA".into());
        }
''',
    '''        let format_index = ui.get_export_format_index();
        let cancelled_pending_export = if let Ok(mut guard) = state.lock() {
            guard.export_format_index = format_index;
            if format_index == 2 {
                guard.pending_export.take().is_some()
            } else {
                false
            }
        } else {
            false
        };
        if format_index == 2 {
            cache.cancel();
            ui.set_runtime_cache_status("NOT REQUIRED FOR LITEMATICA".into());
            if cancelled_pending_export {
                ui.set_task_title("EXPORT CANCELLED".into());
                ui.set_task_detail("Cancelled".into());
            }
        }
''',
    "format switch cancellation",
)

replace_once(
    '        if !ui.get_engine_ready() || ui.get_task_active() { return; }',
    '        if !ui.get_engine_ready() || ui.get_task_active() || has_pending_export(&state) { return; }',
    "duplicate queued export guard",
)

replace_once(
    '''    let weak = ui.as_weak();
    ui.on_cancel_runtime_cache(move || {
        if cache.cancel() {
            aux_windows::mark_runtime_cache_cancelling();
            if let Some(ui) = weak.upgrade() {
                ui.set_runtime_cache_status("CANCELLING…".into());
                ui.set_task_detail("Cancelling…".into());
                append_diagnostic(&ui, "Runtime registry cancellation requested.");
            }
        }
    });
''',
    '''    let weak = ui.as_weak();
    let cancel_state = state.clone();
    ui.on_cancel_runtime_cache(move || {
        let cancelled_pending_export = cancel_pending_export(&cancel_state);
        let cancelled_cache = cache.cancel();
        if cancelled_cache || cancelled_pending_export {
            if cancelled_cache {
                aux_windows::mark_runtime_cache_cancelling();
            }
            if let Some(ui) = weak.upgrade() {
                ui.set_runtime_cache_status(
                    if cancelled_cache { "CANCELLING…" } else { "CANCELLED" }.into()
                );
                ui.set_task_title(
                    if cancelled_pending_export { "EXPORT CANCELLED" } else { "RUNTIME CANCELLED" }.into()
                );
                ui.set_task_detail("Cancelling…".into());
                append_diagnostic(&ui, "Runtime registry cancellation requested.");
            }
        }
    });
''',
    "runtime cancel handler",
)

replace_once(
    '''                    Err(error) => {
                        if foreground && !has_pending_export(&ui_completion_state) {
                            ui.set_task_title("RUNTIME CACHE FAILED".into());
                            ui.set_task_detail(first_line(error).into());
                        }
                        append_diagnostic(&ui, &format!("[WARN] Runtime registry unavailable: {error}"));
                    }
''',
    '''                    Err(error) => {
                        let cancelled = error.to_ascii_lowercase().contains("cancel");
                        if foreground && !has_pending_export(&ui_completion_state) {
                            if cancelled {
                                ui.set_task_title("RUNTIME CANCELLED".into());
                                ui.set_task_detail("Cancelled".into());
                            } else {
                                ui.set_task_title("RUNTIME CACHE FAILED".into());
                                ui.set_task_detail(first_line(error).into());
                            }
                        }
                        if cancelled {
                            append_diagnostic(&ui, "Runtime registry cancelled.");
                        } else {
                            append_diagnostic(&ui, &format!("[WARN] Runtime registry unavailable: {error}"));
                        }
                    }
''',
    "runtime completion cancellation state",
)

replace_once(
    '''fn has_pending_export(state: &SharedState) -> bool {
    state.lock().map(|guard| guard.pending_export.is_some()).unwrap_or(false)
}
''',
    '''fn cancel_pending_export(state: &SharedState) -> bool {
    state.lock()
        .map(|mut guard| guard.pending_export.take().is_some())
        .unwrap_or(false)
}

fn has_pending_export(state: &SharedState) -> bool {
    state.lock().map(|guard| guard.pending_export.is_some()).unwrap_or(false)
}
''',
    "pending export helper",
)

text = text.replace('"EXPORT WAIT FAILED".into()', '"EXPORT FAILED".into()')

replace_once(
    '''    #[test]
    fn asset_path_move_and_remove_preserve_priority() {
''',
    '''    #[test]
    fn cancelling_pending_export_is_terminal() {
        let state: SharedState = Arc::new(Mutex::new(AppState::default()));
        state.lock().unwrap().pending_export = Some(json!({ "command": "export" }));
        assert!(has_pending_export(&state));
        assert!(cancel_pending_export(&state));
        assert!(!has_pending_export(&state));
        assert!(!cancel_pending_export(&state));
    }

    #[test]
    fn asset_path_move_and_remove_preserve_priority() {
''',
    "pending export cancellation test",
)

for required in (
    "fn cancel_pending_export",
    "cancelled_pending_export",
    "cancelling_pending_export_is_terminal",
    "has_pending_export(&state) { return; }",
):
    if required not in text:
        raise SystemExit(f"missing lifecycle invariant: {required}")

if "EXPORT WAIT FAILED" in text:
    raise SystemExit("legacy EXPORT WAIT FAILED status remains")

PATH.write_text(text, encoding="utf-8")
print("Applied queued-export cancellation hardening")
