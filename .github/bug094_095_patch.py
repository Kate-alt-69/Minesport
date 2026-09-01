from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"{label} moved; refusing automatic patch")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


ipc = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
replace_once(
    ipc,
    '    public static void run() {\n        send("info", json -> json.addProperty("version", "0.2.1"));\n',
    '    static String engineVersion() {\n'
    '        String version = IpcMode.class.getPackage().getImplementationVersion();\n'
    '        return version == null || version.isBlank() ? "dev" : version.trim();\n'
    '    }\n\n'
    '    public static void run() {\n'
    '        send("info", json -> json.addProperty("version", engineVersion()));\n',
    "IpcMode.java version anchor",
)

build = Path("desktop/build.rs")
replace_once(
    build,
    '''fn find_engine_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_ENGINE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    let root = repo_root().join("engine").join("build").join("libs");
    let entries = fs::read_dir(root).ok()?;
    let mut candidates: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path
                    .extension()
                    .and_then(|ext| ext.to_str())
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| {
                        name.starts_with("minesport-engine-") && !name.contains("sources")
                    })
        })
        .collect();
    candidates.sort();
    candidates.pop()
}
''',
    '''fn find_engine_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_ENGINE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    let version_path = repo_root().join("engine").join("VERSION");
    let raw = fs::read_to_string(version_path).ok()?;
    let version = raw.trim();
    if version.is_empty()
        || !version.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'+' | b'_')
        })
    {
        return None;
    }

    let exact = repo_root()
        .join("engine")
        .join("build")
        .join("libs")
        .join(format!("minesport-engine-{version}.jar"));
    exact.is_file().then_some(exact)
}
''',
    "desktop/build.rs find_engine_jar anchor",
)
replace_once(
    build,
    '    println!("cargo:rerun-if-changed={}", engine_libs.display());\n',
    '    println!("cargo:rerun-if-changed={}", engine_libs.display());\n'
    '    println!(\n'
    '        "cargo:rerun-if-changed={}",\n'
    '        root.join("engine").join("VERSION").display()\n'
    '    );\n',
    "desktop/build.rs rerun anchor",
)

cache = Path("desktop/src/runtime_cache.rs")
replace_once(
    cache,
    'use std::{\n    path::{Path, PathBuf},\n',
    'use std::{\n    panic::{AssertUnwindSafe, catch_unwind},\n    path::{Path, PathBuf},\n',
    "runtime_cache.rs import anchor",
)
replace_once(
    cache,
    '''fn notify_listeners(listeners: &[Listener], event: &RuntimeCacheEvent) {
    for listener in listeners {
        if let Ok(callback) = listener.lock() {
            callback(event.clone());
        }
    }
}
''',
    '''fn notify_listeners(listeners: &[Listener], event: &RuntimeCacheEvent) {
    for listener in listeners {
        if let Ok(callback) = listener.lock() {
            if catch_unwind(AssertUnwindSafe(|| callback(event.clone()))).is_err() {
                diagnostics::Logger::new("RUNTIME")
                    .child("REGISTRY")
                    .warn(
                        "RuntimeRegistryListenerPanicked",
                        "runtime registry listener panicked; continuing worker lifecycle",
                        &[],
                    );
            }
        }
    }
}
''',
    "runtime_cache.rs notify_listeners anchor",
)
replace_once(
    cache,
    '    #[test]\n    fn emit_releases_manager_lock_before_listener_runs() {\n',
    '''    #[test]
    fn panicking_listener_does_not_abort_following_listeners() {
        let observed = Arc::new(AtomicBool::new(false));
        let observed_listener = observed.clone();
        let listeners = vec![
            wrap_listener(|_| panic!("listener failure")),
            wrap_listener(move |_| {
                observed_listener.store(true, Ordering::Relaxed);
            }),
        ];
        notify_listeners(
            &listeners,
            &RuntimeCacheEvent::Progress(Progress {
                percent: 50,
                message: "test".into(),
            }),
        );
        assert!(observed.load(Ordering::Relaxed));
    }

    #[test]
    fn emit_releases_manager_lock_before_listener_runs() {
''',
    "runtime_cache.rs test anchor",
)
