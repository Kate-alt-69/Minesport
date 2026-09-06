from pathlib import Path

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)

# --- runtime_worker.rs -------------------------------------------------------
path = Path("desktop/src/runtime_worker.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "use serde::Deserialize;\n",
    "use serde::Deserialize;\nuse sha2::{Digest, Sha256};\n",
    "runtime worker sha2 import",
)

scope_decl = r'''
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum RegistryScope {
    #[default]
    Full,
    Namespaces(Vec<String>),
}

impl RegistryScope {
    pub fn namespaces<I, S>(values: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let mut namespaces = values
            .into_iter()
            .filter_map(|value| normalize_namespace(value.as_ref()))
            .collect::<Vec<_>>();
        namespaces.push("minecraft".to_string());
        namespaces.sort();
        namespaces.dedup();
        Self::Namespaces(namespaces)
    }

    pub fn description(&self) -> String {
        match self {
            Self::Full => "full".to_string(),
            Self::Namespaces(namespaces) => format!("namespaces:{}", namespaces.join(",")),
        }
    }

    fn env_value(&self) -> Option<String> {
        match self {
            Self::Full => None,
            Self::Namespaces(namespaces) => Some(namespaces.join(",")),
        }
    }
}

fn normalize_namespace(value: &str) -> Option<String> {
    let value = value.trim().to_ascii_lowercase();
    if value.is_empty()
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'_' | b'-' | b'.'))
    {
        return None;
    }
    Some(value)
}

'''
text = replace_once(
    text,
    "#[derive(Debug, Clone)]\npub struct CacheResult {\n",
    scope_decl + "#[derive(Debug, Clone)]\npub struct CacheResult {\n",
    "runtime scope declaration",
)

old_header = r'''pub fn prepare_full_registry_cancellable_for_loader<F>(
    loader: &str,
    version: &str,
    mods_path: &Path,
    force: bool,
    cancel: Arc<AtomicBool>,
    mut progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
'''
new_header = r'''pub fn prepare_full_registry_cancellable_for_loader<F>(
    loader: &str,
    version: &str,
    mods_path: &Path,
    force: bool,
    cancel: Arc<AtomicBool>,
    progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
    prepare_registry_cancellable_for_loader(
        loader,
        version,
        mods_path,
        RegistryScope::Full,
        force,
        cancel,
        progress,
    )
}

pub fn prepare_registry_cancellable_for_loader<F>(
    loader: &str,
    version: &str,
    mods_path: &Path,
    scope: RegistryScope,
    force: bool,
    cancel: Arc<AtomicBool>,
    mut progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
'''
text = replace_once(text, old_header, new_header, "scoped worker entrypoint")

text = replace_once(
    text,
    "let fingerprint = loader_cache_fingerprint(family, &raw_fingerprint);",
    "let fingerprint = scoped_cache_fingerprint(family, &raw_fingerprint, &scope);",
    "scoped cache fingerprint call",
)

text = replace_once(
    text,
    '''            app_cds_archive.as_deref(),
            capture_port,
        )?''',
    '''            app_cds_archive.as_deref(),
            &scope,
            capture_port,
        )?''',
    "direct worker scope argument",
)
text = replace_once(
    text,
    "start_gradle_worker(family, &workspace, &log_path, &java_home, capture_port)?",
    "start_gradle_worker(family, &workspace, &log_path, &java_home, &scope, capture_port)?",
    "gradle worker scope argument",
)

fingerprint_fn = r'''
fn scoped_cache_fingerprint(family: BridgeFamily, raw: &str, scope: &RegistryScope) -> String {
    let base = loader_cache_fingerprint(family, raw);
    let RegistryScope::Namespaces(namespaces) = scope else {
        return base;
    };
    let mut digest = Sha256::new();
    digest.update(b"minesport-runtime-scope-v1\0");
    for namespace in namespaces {
        digest.update(namespace.as_bytes());
        digest.update([0]);
    }
    let digest = digest.finalize();
    format!("{base}-scope-{digest:x}")
}

'''
text = replace_once(
    text,
    "fn capture_has_stalled(\n",
    fingerprint_fn + "fn capture_has_stalled(\n",
    "scoped fingerprint helper",
)

text = replace_once(
    text,
    '''    manifest: &DirectLaunchManifest,
    app_cds_archive: Option<&Path>,
    capture_port: u16,
) -> Result<Child> {''',
    '''    manifest: &DirectLaunchManifest,
    app_cds_archive: Option<&Path>,
    scope: &RegistryScope,
    capture_port: u16,
) -> Result<Child> {''',
    "direct launcher signature",
)
text = replace_once(
    text,
    '''    log_path: &Path,
    java_home: &Path,
    capture_port: u16,
) -> Result<Child> {''',
    '''    log_path: &Path,
    java_home: &Path,
    scope: &RegistryScope,
    capture_port: u16,
) -> Result<Child> {''',
    "gradle launcher signature",
)

text = replace_once(
    text,
    '''    command.env("MINESPORT_EXPORT_WORKER_PORT", capture_port.to_string());
    command.env("MINESPORT_EXPORT_WORKER_MODE", "all");
    command.env("MINESPORT_EXPORT_WORKER", "1");
    hide_console_window(&mut command);''',
    '''    command.env("MINESPORT_EXPORT_WORKER_PORT", capture_port.to_string());
    command.env("MINESPORT_EXPORT_WORKER_MODE", "all");
    apply_worker_scope_env(&mut command, scope);
    command.env("MINESPORT_EXPORT_WORKER", "1");
    hide_console_window(&mut command);''',
    "direct launcher scope env",
)
text = replace_once(
    text,
    '''    command.env("MINESPORT_EXPORT_WORKER_PORT", capture_port.to_string());
    command.env("MINESPORT_EXPORT_WORKER_MODE", "all");
    command.env("MINESPORT_EXPORT_WORKER", "1");
    // Inherited by JavaExec/runClient children''',
    '''    command.env("MINESPORT_EXPORT_WORKER_PORT", capture_port.to_string());
    command.env("MINESPORT_EXPORT_WORKER_MODE", "all");
    apply_worker_scope_env(&mut command, scope);
    command.env("MINESPORT_EXPORT_WORKER", "1");
    // Inherited by JavaExec/runClient children''',
    "gradle launcher scope env",
)

scope_env_helper = r'''
fn apply_worker_scope_env(command: &mut Command, scope: &RegistryScope) {
    // Never inherit a caller-provided scope into a full capture.
    command.env_remove("MINESPORT_EXPORT_WORKER_NS");
    if let Some(namespaces) = scope.env_value() {
        command.env("MINESPORT_EXPORT_WORKER_NS", namespaces);
    }
}

'''
text = replace_once(
    text,
    "fn managed_runtime_jvm_arg(arg: &str) -> bool {\n",
    scope_env_helper + "fn managed_runtime_jvm_arg(arg: &str) -> bool {\n",
    "worker scope env helper",
)

scope_tests = r'''
    #[test]
    fn scoped_registry_identity_is_stable_and_separate_from_full() {
        let raw = "0123456789abcdef";
        let full = scoped_cache_fingerprint(BridgeFamily::Fabric, raw, &RegistryScope::Full);
        let a = RegistryScope::namespaces(["create", "minecraft", "create"]);
        let b = RegistryScope::namespaces(["minecraft", "create"]);
        let c = RegistryScope::namespaces(["minecraft", "mekanism"]);
        assert_eq!(full, raw);
        assert_eq!(
            scoped_cache_fingerprint(BridgeFamily::Fabric, raw, &a),
            scoped_cache_fingerprint(BridgeFamily::Fabric, raw, &b)
        );
        assert_ne!(
            scoped_cache_fingerprint(BridgeFamily::Fabric, raw, &a),
            scoped_cache_fingerprint(BridgeFamily::Fabric, raw, &c)
        );
        assert_ne!(scoped_cache_fingerprint(BridgeFamily::Fabric, raw, &a), full);
    }

    #[test]
    fn namespace_scope_normalizes_and_always_keeps_vanilla() {
        let scope = RegistryScope::namespaces([" Create ", "create", "bad:namespace", "mekanism"]);
        assert_eq!(
            scope,
            RegistryScope::Namespaces(vec![
                "create".to_string(),
                "mekanism".to_string(),
                "minecraft".to_string(),
            ])
        );
        assert_eq!(
            scope.env_value().as_deref(),
            Some("create,mekanism,minecraft")
        );
        assert_eq!(RegistryScope::Full.env_value(), None);
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn worker_diagnostics_path_is_durable_not_cache_owned() {\n",
    scope_tests + "    #[test]\n    fn worker_diagnostics_path_is_durable_not_cache_owned() {\n",
    "runtime worker scope tests",
)
path.write_text(text, encoding="utf-8")

# --- runtime_cache.rs --------------------------------------------------------
path = Path("desktop/src/runtime_cache.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "runtime_worker::{self, CacheResult, Progress},",
    "runtime_worker::{self, CacheResult, Progress, RegistryScope},",
    "runtime cache scope import",
)
text = replace_once(
    text,
    "    ready_path: PathBuf,\n",
    "    ready_path: PathBuf,\n    scope: RegistryScope,\n",
    "runtime cache state scope",
)

old = r'''    pub fn start_for_loader<F>(
        &self,
        version: String,
        loader: String,
        mods_path: PathBuf,
        force: bool,
        listener: F,
    ) -> Result<bool>
    where
        F: Fn(RuntimeCacheEvent) + Send + 'static,
    {
        let loader = normalize_loader(&loader);
'''
new = r'''    pub fn start_for_loader<F>(
        &self,
        version: String,
        loader: String,
        mods_path: PathBuf,
        force: bool,
        listener: F,
    ) -> Result<bool>
    where
        F: Fn(RuntimeCacheEvent) + Send + 'static,
    {
        self.start_for_loader_scope(
            version,
            loader,
            mods_path,
            RegistryScope::Full,
            force,
            listener,
        )
    }

    pub fn start_for_loader_scope<F>(
        &self,
        version: String,
        loader: String,
        mods_path: PathBuf,
        scope: RegistryScope,
        force: bool,
        listener: F,
    ) -> Result<bool>
    where
        F: Fn(RuntimeCacheEvent) + Send + 'static,
    {
        let loader = normalize_loader(&loader);
'''
text = replace_once(text, old, new, "runtime cache scoped start")

old_running = r'''        if state.running {
            if state.version != version
                || !state.loader.eq_ignore_ascii_case(&loader)
                || !same_path(&state.mods_path, &mods_path)
            {
'''
new_running = r'''        let same_instance = state.version == version
            && state.loader.eq_ignore_ascii_case(&loader)
            && same_path(&state.mods_path, &mods_path);
        if state.running {
            if !same_instance || !scope_satisfies(&state.scope, &scope) {
'''
text = replace_once(text, old_running, new_running, "runtime cache running compatibility")

text = replace_once(
    text,
    '''                        ("running_loader", state.loader.clone()),
                        ("running_mods", state.mods_path.display().to_string()),
                    ],''',
    '''                        ("running_loader", state.loader.clone()),
                        ("running_mods", state.mods_path.display().to_string()),
                        ("requested_scope", scope.description()),
                        ("running_scope", state.scope.description()),
                    ],''',
    "runtime cache rejected scope diagnostics",
)

text = replace_once(
    text,
    '''        let same_identity = state.version == version
            && state.loader.eq_ignore_ascii_case(&loader)
            && same_path(&state.mods_path, &mods_path);
        if !same_identity || force {''',
    '''        let same_identity = same_instance && scope_satisfies(&state.scope, &scope);
        if !same_identity || force {''',
    "runtime cache ready identity",
)
text = replace_once(
    text,
    '''            .field("loader", &loader)
            .field("mods_path", mods_path.display())
            .field("force", force);''',
    '''            .field("loader", &loader)
            .field("mods_path", mods_path.display())
            .field("scope", scope.description())
            .field("force", force);''',
    "runtime cache operation scope",
)
text = replace_once(
    text,
    "        state.mods_path = mods_path.clone();\n        state.cancel = Some(cancel.clone());",
    "        state.mods_path = mods_path.clone();\n        state.scope = scope.clone();\n        state.cancel = Some(cancel.clone());",
    "runtime cache remember scope",
)
text = replace_once(
    text,
    '''            let result = runtime_worker::prepare_full_registry_cancellable_for_loader(
                &loader,
                &version,
                &mods_path,
                force,''',
    '''            let result = runtime_worker::prepare_registry_cancellable_for_loader(
                &loader,
                &version,
                &mods_path,
                scope,
                force,''',
    "runtime cache scoped worker call",
)

old_running_method = r'''    pub fn is_running_for_loader(&self, version: &str, loader: &str, mods_path: &Path) -> bool {
        let loader = normalize_loader(loader);
        self.state
            .lock()
            .map(|state| {
                state.running
                    && state.version == version
                    && state.loader.eq_ignore_ascii_case(&loader)
                    && same_path(&state.mods_path, mods_path)
            })
            .unwrap_or(false)
    }
'''
new_running_method = r'''    pub fn is_running_for_loader(&self, version: &str, loader: &str, mods_path: &Path) -> bool {
        self.is_running_for_scope(version, loader, mods_path, &RegistryScope::Full)
    }

    pub fn is_running_for_scope(
        &self,
        version: &str,
        loader: &str,
        mods_path: &Path,
        scope: &RegistryScope,
    ) -> bool {
        let loader = normalize_loader(loader);
        self.state
            .lock()
            .map(|state| {
                state.running
                    && state.version == version
                    && state.loader.eq_ignore_ascii_case(&loader)
                    && same_path(&state.mods_path, mods_path)
                    && scope_satisfies(&state.scope, scope)
            })
            .unwrap_or(false)
    }
'''
text = replace_once(text, old_running_method, new_running_method, "runtime cache scoped running lookup")

old_ready = r'''    pub fn ready_path_for_loader(
        &self,
        version: &str,
        loader: &str,
        mods_path: &Path,
    ) -> Option<PathBuf> {
        let loader = normalize_loader(loader);
        let state = self.state.lock().ok()?;
        if state.running
            || state.version != version
            || !state.loader.eq_ignore_ascii_case(&loader)
            || !same_path(&state.mods_path, mods_path)
            || state.ready_path.as_os_str().is_empty()
            || !state.ready_path.is_file()
        {
            return None;
        }
        Some(state.ready_path.clone())
    }
'''
new_ready = r'''    pub fn ready_path_for_loader(
        &self,
        version: &str,
        loader: &str,
        mods_path: &Path,
    ) -> Option<PathBuf> {
        self.ready_path_for_scope(version, loader, mods_path, &RegistryScope::Full)
    }

    pub fn ready_path_for_scope(
        &self,
        version: &str,
        loader: &str,
        mods_path: &Path,
        scope: &RegistryScope,
    ) -> Option<PathBuf> {
        let loader = normalize_loader(loader);
        let state = self.state.lock().ok()?;
        if state.running
            || state.version != version
            || !state.loader.eq_ignore_ascii_case(&loader)
            || !same_path(&state.mods_path, mods_path)
            || !scope_satisfies(&state.scope, scope)
            || state.ready_path.as_os_str().is_empty()
            || !state.ready_path.is_file()
        {
            return None;
        }
        Some(state.ready_path.clone())
    }
'''
text = replace_once(text, old_ready, new_ready, "runtime cache scoped ready lookup")

scope_satisfies = r'''
fn scope_satisfies(available: &RegistryScope, requested: &RegistryScope) -> bool {
    matches!(available, RegistryScope::Full) || available == requested
}

'''
text = replace_once(
    text,
    "fn normalize_loader(value: &str) -> String {\n",
    scope_satisfies + "fn normalize_loader(value: &str) -> String {\n",
    "runtime cache scope compatibility helper",
)

cache_tests = r'''
    #[test]
    fn full_scope_satisfies_every_scoped_request_but_not_the_reverse() {
        let create = RegistryScope::namespaces(["create"]);
        let mekanism = RegistryScope::namespaces(["mekanism"]);
        assert!(scope_satisfies(&RegistryScope::Full, &RegistryScope::Full));
        assert!(scope_satisfies(&RegistryScope::Full, &create));
        assert!(scope_satisfies(&create, &create));
        assert!(!scope_satisfies(&create, &RegistryScope::Full));
        assert!(!scope_satisfies(&create, &mekanism));
    }

    #[test]
    fn scoped_ready_registry_never_masquerades_as_full_or_another_scope() {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "minesport-runtime-scope-ready-{}-{stamp}",
            std::process::id()
        ));
        std::fs::create_dir_all(&root).unwrap();
        let registry = root.join("registry.data");
        std::fs::write(&registry, b"ready").unwrap();
        let manager = RuntimeCacheManager::default();
        let create = RegistryScope::namespaces(["create"]);
        {
            let mut state = manager.state.lock().unwrap();
            state.version = "1.21.10".into();
            state.loader = "fabric".into();
            state.mods_path = PathBuf::from("mods");
            state.scope = create.clone();
            state.fingerprint = "scoped".into();
            state.ready_path = registry.clone();
        }
        assert_eq!(
            manager.ready_path_for_scope("1.21.10", "fabric", Path::new("mods"), &create),
            Some(registry)
        );
        assert!(
            manager
                .ready_path_for_loader("1.21.10", "fabric", Path::new("mods"))
                .is_none()
        );
        assert!(
            manager
                .ready_path_for_scope(
                    "1.21.10",
                    "fabric",
                    Path::new("mods"),
                    &RegistryScope::namespaces(["mekanism"]),
                )
                .is_none()
        );
        let _ = std::fs::remove_dir_all(root);
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn listener_error_is_single_line_but_operation_error_can_stay_detailed() {\n",
    cache_tests + "    #[test]\n    fn listener_error_is_single_line_but_operation_error_can_stay_detailed() {\n",
    "runtime cache scope tests",
)
path.write_text(text, encoding="utf-8")

# --- app.rs ----------------------------------------------------------------
path = Path("desktop/src/app.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "runtime_cache::{RuntimeCacheEvent, RuntimeCacheManager},\n    selection,",
    "runtime_cache::{RuntimeCacheEvent, RuntimeCacheManager},\n    runtime_worker::RegistryScope,\n    selection,",
    "app scope import",
)
text = replace_once(
    text,
    "collections::HashMap,",
    "collections::{BTreeSet, HashMap},",
    "app btree set import",
)
text = replace_once(
    text,
    "pump_engine_events(ui.as_weak(), events, state.clone(), engine.clone());",
    "pump_engine_events(\n        ui.as_weak(),\n        events,\n        state.clone(),\n        cache.clone(),\n        engine.clone(),\n    );",
    "app pump cache wiring",
)

old_export = r'''        if runtime_registry_supported(&loader, &version, &mods_path) {
            let queued = if let Ok(mut guard) = state.lock() {
                guard.pending_export = Some(request);
                true
            } else {
                false
            };
            if !queued {
                ui.set_task_title("EXPORT FAILED".into());
                ui.set_task_detail("Export state is unavailable".into());
                append_diagnostic(&ui, "Export could not be queued because the export state lock is unavailable.");
                return;
            }
            ui.set_task_active(false);
            ui.set_task_progress(0.01);
            ui.set_task_title("WAITING FOR RUNTIME".into());
            ui.set_task_detail("Export will start when the runtime registry is ready".into());
            append_diagnostic(&ui, &format!("Export queued for runtime registry · {}", output.display()));

            if !cache.is_running_for_loader(&version, &loader, &mods_path) {
                if let Err(error) = start_runtime_cache_job(
                    weak.clone(), cache.clone(), engine.clone(), state.clone(), version, loader, mods_path, false, true,
                ) {
                    append_diagnostic(&ui, &format!("Runtime registry could not start: {error:#}"));
                    dispatch_pending_export(&weak, &engine, &state, None, Some(error.to_string()));
                }
            }
            return;
        }
'''
new_export = r'''        if runtime_registry_supported(&loader, &version, &mods_path) {
            let preflight = runtime_preflight_request(&request);
            let queued = if let Ok(mut guard) = state.lock() {
                guard.pending_export = Some(request);
                true
            } else {
                false
            };
            if !queued {
                ui.set_task_title("EXPORT FAILED".into());
                ui.set_task_detail("Export state is unavailable".into());
                append_diagnostic(&ui, "Export could not be queued because the export state lock is unavailable.");
                return;
            }
            ui.set_task_active(false);
            ui.set_task_progress(0.01);
            ui.set_task_title("WAITING FOR RUNTIME".into());
            ui.set_task_detail("Scanning selected block namespaces…".into());
            append_diagnostic(&ui, &format!("Export queued · scanning selected namespaces before runtime launch · {}", output.display()));

            if cache.is_running_for_loader(&version, &loader, &mods_path) {
                append_diagnostic(&ui, "A full runtime registry is already preparing; queued export will reuse it.");
                return;
            }
            if let Err(error) = engine.send_value(preflight) {
                append_diagnostic(&ui, &format!("Namespace preflight could not start; falling back to full runtime capture: {error:#}"));
                if let Err(start_error) = start_runtime_cache_job(
                    weak.clone(),
                    cache.clone(),
                    engine.clone(),
                    state.clone(),
                    version,
                    loader,
                    mods_path,
                    RegistryScope::Full,
                    false,
                    true,
                ) {
                    dispatch_pending_export(
                        &weak,
                        &engine,
                        &state,
                        None,
                        Some(start_error.to_string()),
                    );
                }
            }
            return;
        }
'''
text = replace_once(text, old_export, new_export, "export namespace preflight")

text = replace_once(
    text,
    '''            version,
            loader,
            mods,
            true,
            true,
        )''',
    '''            version,
            loader,
            mods,
            RegistryScope::Full,
            true,
            true,
        )''',
    "manual runtime rebuild full scope",
)

text = replace_once(
    text,
    '''    mods_path: PathBuf,
    force: bool,
    foreground: bool,
) -> Result<bool> {''',
    '''    mods_path: PathBuf,
    scope: RegistryScope,
    force: bool,
    foreground: bool,
) -> Result<bool> {''',
    "runtime cache job scope signature",
)
text = replace_once(
    text,
    "let started = cache.start_for_loader(version.clone(), loader.clone(), mods_path.clone(), force, move |event| match event {",
    "let scope_for_log = scope.clone();\n    let started = cache.start_for_loader_scope(version.clone(), loader.clone(), mods_path.clone(), scope, force, move |event| match event {",
    "runtime cache job scoped manager call",
)
text = replace_once(
    text,
    '''append_diagnostic(&ui, &format!("Full runtime registry ready: {} · fingerprint {}{}", cache_result.registry_path.display(), fingerprint, if reused { " · reused" } else { "" }));''',
    '''append_diagnostic(&ui, &format!("Runtime registry ready: {} · {} · fingerprint {}{}", cache_result.registry_path.display(), scope_for_log.description(), fingerprint, if reused { " · reused" } else { "" }));''',
    "runtime cache scoped completion diagnostic",
)

text = replace_once(
    text,
    '''fn pump_engine_events(
    weak: slint::Weak<MainWindow>,
    events: Receiver<EngineEvent>,
    state: SharedState,
    engine: JavaEngine,
) {''',
    '''fn pump_engine_events(
    weak: slint::Weak<MainWindow>,
    events: Receiver<EngineEvent>,
    state: SharedState,
    cache: RuntimeCacheManager,
    engine: JavaEngine,
) {''',
    "engine event pump cache parameter",
)
text = replace_once(
    text,
    "apply_response(&weak, response, state.clone());",
    "apply_response(\n                            &weak,\n                            response,\n                            state.clone(),\n                            cache.clone(),\n                            engine.clone(),\n                        );",
    "response cache engine wiring",
)
text = replace_once(
    text,
    "fn apply_response(weak: &slint::Weak<MainWindow>, response: Response, state: SharedState) {",
    "fn apply_response(\n    weak: &slint::Weak<MainWindow>,\n    response: Response,\n    state: SharedState,\n    cache: RuntimeCacheManager,\n    engine: JavaEngine,\n) {",
    "response handler scoped parameters",
)

runtime_branch = r'''
            "runtime-preflight" => {
                let preflight_state = state.clone();
                let preflight_cache = cache.clone();
                let preflight_engine = engine.clone();
                thread::spawn(move || {
                    let namespaces = read_runtime_namespaces_from_path(&path);
                    let _ = fs::remove_file(&path);
                    continue_pending_export_after_namespace_scan(
                        weak,
                        preflight_cache,
                        preflight_engine,
                        preflight_state,
                        namespaces,
                    );
                });
            }
'''
text = replace_once(
    text,
    '''            "preview" => {
                let preview_state = state.clone();''',
    runtime_branch + '''            "preview" => {
                let preview_state = state.clone();''',
    "runtime preflight response branch",
)

error_branch = r'''
    if response.kind == "error" && response.client_purpose == "runtime-preflight" {
        if response_world_matches_current(&response, &state) {
            let error = anyhow!(response.message.clone());
            continue_pending_export_after_namespace_scan(
                weak.clone(),
                cache,
                engine,
                state,
                Err(error),
            );
        }
        return;
    }

'''
text = replace_once(
    text,
    "    if response_has_stale_world_target(&response, &state) {\n",
    error_branch + "    if response_has_stale_world_target(&response, &state) {\n",
    "runtime preflight error fallback",
)

helpers = r'''
fn runtime_preflight_request(export: &Value) -> Value {
    let mut request = export.clone();
    if let Some(object) = request.as_object_mut() {
        object.insert("command".to_string(), json!("listBlocks"));
        object.insert("clientPurpose".to_string(), json!("runtime-preflight"));
        for key in ["outputPath", "format", "exportMode", "options"] {
            object.remove(key);
        }
    }
    request
}

fn read_runtime_namespaces_from_path(path: &Path) -> Result<RegistryScope> {
    let file = fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    read_runtime_namespaces(std::io::BufReader::new(file))
}

struct RuntimeNamespaceVisitor;

impl<'de> serde::de::Visitor<'de> for RuntimeNamespaceVisitor {
    type Value = RegistryScope;

    fn expecting(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("a JSON array of block IDs for runtime namespace scoping")
    }

    fn visit_seq<A>(self, mut sequence: A) -> std::result::Result<Self::Value, A::Error>
    where
        A: serde::de::SeqAccess<'de>,
    {
        let mut namespaces = BTreeSet::new();
        namespaces.insert("minecraft".to_string());
        while let Some(block) = sequence.next_element::<PreflightBlock>()? {
            let namespace = block
                .id
                .split_once(':')
                .map(|(namespace, _)| namespace)
                .unwrap_or("minecraft")
                .trim()
                .to_ascii_lowercase();
            if namespace.is_empty()
                || !namespace.bytes().all(|byte| {
                    byte.is_ascii_lowercase()
                        || byte.is_ascii_digit()
                        || matches!(byte, b'_' | b'-' | b'.')
                })
            {
                return Err(serde::de::Error::custom(format!(
                    "invalid block namespace in {:?}",
                    block.id
                )));
            }
            namespaces.insert(namespace);
        }
        Ok(RegistryScope::namespaces(namespaces))
    }
}

fn read_runtime_namespaces<R: std::io::Read>(reader: R) -> Result<RegistryScope> {
    let mut deserializer = serde_json::Deserializer::from_reader(reader);
    serde::de::Deserializer::deserialize_seq(&mut deserializer, RuntimeNamespaceVisitor)
        .context("parse runtime namespace block list")
}

fn pending_runtime_context(state: &SharedState) -> Option<(String, String, PathBuf)> {
    let guard = state.lock().ok()?;
    guard.pending_export.as_ref()?;
    Some((
        guard.selected_version.clone()?,
        guard.selected_loader.clone()?,
        guard.selected_mods_path.clone()?,
    ))
}

fn continue_pending_export_after_namespace_scan(
    weak: slint::Weak<MainWindow>,
    cache: RuntimeCacheManager,
    engine: JavaEngine,
    state: SharedState,
    scope_result: Result<RegistryScope>,
) {
    let Some((version, loader, mods_path)) = pending_runtime_context(&state) else {
        return;
    };
    let scope = match scope_result {
        Ok(scope) => scope,
        Err(error) => {
            let detail = format!(
                "Namespace scan failed; falling back to full runtime capture: {error:#}"
            );
            let weak_log = weak.clone();
            let _ = weak_log.upgrade_in_event_loop(move |ui| append_diagnostic(&ui, &detail));
            RegistryScope::Full
        }
    };

    if let Some(path) = cache.ready_path_for_scope(&version, &loader, &mods_path, &scope) {
        dispatch_pending_export(&weak, &engine, &state, Some(path), None);
        return;
    }
    if cache.is_running_for_scope(&version, &loader, &mods_path, &scope) {
        return;
    }

    let weak_log = weak.clone();
    let description = scope.description();
    let _ = weak_log.upgrade_in_event_loop(move |ui| {
        ui.set_task_detail(format!("Preparing runtime · {description}").into());
        append_diagnostic(&ui, &format!("Runtime capture scope selected · {description}"));
    });
    if let Err(error) = start_runtime_cache_job(
        weak.clone(),
        cache,
        engine.clone(),
        state.clone(),
        version,
        loader,
        mods_path,
        scope,
        false,
        true,
    ) {
        dispatch_pending_export(&weak, &engine, &state, None, Some(error.to_string()));
    }
}

'''
text = replace_once(
    text,
    "#[derive(Debug, Deserialize)]\nstruct PreflightBlock {\n",
    helpers + "#[derive(Debug, Deserialize)]\nstruct PreflightBlock {\n",
    "runtime namespace helpers",
)

app_tests = r'''
    #[test]
    fn runtime_namespace_scan_is_sorted_deduped_and_keeps_vanilla() {
        let payload = br#"[
            {"id":"create:shaft"},
            {"id":"minecraft:stone"},
            {"id":"Create:cogwheel"},
            {"id":"mekanism:steel_casing"}
        ]"#;
        let scope = read_runtime_namespaces(std::io::Cursor::new(payload)).unwrap();
        assert_eq!(
            scope,
            RegistryScope::Namespaces(vec![
                "create".to_string(),
                "mekanism".to_string(),
                "minecraft".to_string(),
            ])
        );
    }

    #[test]
    fn malformed_namespace_fails_closed_to_full_capture_path() {
        let payload = br#"[{"id":"bad namespace:block"}]"#;
        assert!(read_runtime_namespaces(std::io::Cursor::new(payload)).is_err());
    }

    #[test]
    fn runtime_preflight_preserves_selection_but_removes_export_only_fields() {
        let export = json!({
            "command": "export",
            "clientPurpose": "export",
            "worldPath": "world",
            "modsPath": "mods",
            "modLoader": "fabric",
            "outputPath": "out.gltf",
            "format": "gltf",
            "exportMode": "grouped",
            "options": {"optimize":"true"},
            "minX": -8,
            "maxX": 8,
            "centerX": 1,
            "radiusX": 4,
            "exactSelection": {"version":1,"indices":[1,2,3]}
        });
        let request = runtime_preflight_request(&export);
        assert_eq!(request["command"], "listBlocks");
        assert_eq!(request["clientPurpose"], "runtime-preflight");
        assert_eq!(request["centerX"], 1);
        assert_eq!(request["exactSelection"]["indices"][2], 3);
        assert!(request.get("outputPath").is_none());
        assert!(request.get("format").is_none());
        assert!(request.get("exportMode").is_none());
        assert!(request.get("options").is_none());
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn asset_path_move_and_remove_preserve_priority() {\n",
    app_tests + "    #[test]\n    fn asset_path_move_and_remove_preserve_priority() {\n",
    "app runtime scope tests",
)

path.write_text(text, encoding="utf-8")
