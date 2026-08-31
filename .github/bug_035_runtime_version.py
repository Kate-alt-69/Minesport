from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


diagnostics_path = Path("desktop/src/diagnostics.rs")
diagnostics = diagnostics_path.read_text(encoding="utf-8")
diagnostics = replace_once(
    diagnostics,
    '''        "Minesport 0.2.0 Rust/Slint session started",''',
    '''        format!("Minesport {} Rust/Slint session started", env!("CARGO_PKG_VERSION")),''',
    "diagnostics package version",
)
diagnostics_path.write_text(diagnostics, encoding="utf-8")

app_path = Path("desktop/src/app.rs")
app = app_path.read_text(encoding="utf-8")
app = replace_once(
    app,
    '''const VERSION: &str = "0.2.1";''',
    '''const VERSION: &str = env!("CARGO_PKG_VERSION");''',
    "app package version",
)
app = replace_once(
    app,
    '''    append_diagnostic(&ui, "3D selection: camera-derived voxel DDA + Fyne point A / point B box workflow");''',
    '''    append_diagnostic(&ui, "3D selection: Rust voxel picking + point A / point B box workflow");''',
    "stale Fyne diagnostic",
)
app_path.write_text(app, encoding="utf-8")

compat_path = Path("desktop/src/bridge_compat.rs")
compat = compat_path.read_text(encoding="utf-8")
compat = replace_once(
    compat,
    '''    set_property(
        &safe_join(workspace, Path::new("gradle.properties"))?,
        "mod_version",
        "0.2.1",
    )?;''',
    '''    set_property(
        &safe_join(workspace, Path::new("gradle.properties"))?,
        "mod_version",
        env!("CARGO_PKG_VERSION"),
    )?;''',
    "compatibility worker package version",
)
compat_path.write_text(compat, encoding="utf-8")

main_path = Path("engine/src/main/java/dev/kastrick/minesport/Main.java")
main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    '''        // IPC mode — launched by Go wrapper''',
    '''        // IPC mode — launched by Minesport's Rust backend worker''',
    "stale Java IPC owner comment",
)
main_path.write_text(main, encoding="utf-8")

print("BUG-035: runtime diagnostics and compatibility metadata now follow the package version")
