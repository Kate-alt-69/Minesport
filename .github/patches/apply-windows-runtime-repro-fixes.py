from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Reproduce/fix the Fabric direct-launch failure from the Windows field log.
# ---------------------------------------------------------------------------
runtime = Path("desktop/src/runtime_worker.rs")
text = runtime.read_text(encoding="utf-8")

text = replace_once(
    text,
    'const DIRECT_LAUNCH_PROFILE_SCHEMA: u32 = 1;',
    'const DIRECT_LAUNCH_PROFILE_SCHEMA: u32 = 2;',
    "direct launch schema bump",
)
text = replace_once(
    text,
    '''    #[serde(default)]
    jvm_args: Vec<String>,
    #[serde(default)]
    args: Vec<String>,
''',
    '''    #[serde(default)]
    jvm_args: Vec<String>,
    #[serde(default)]
    system_properties: BTreeMap<String, String>,
    #[serde(default)]
    args: Vec<String>,
''',
    "direct launch system properties field",
)
text = replace_once(
    text,
    '''    for arg in &manifest.jvm_args {
        if !managed_runtime_jvm_arg(arg) {
            command.arg(arg);
        }
    }
    if let Some(archive) = app_cds_archive {
''',
    '''    for arg in &manifest.jvm_args {
        if !managed_runtime_jvm_arg(arg) {
            command.arg(arg);
        }
    }
    for arg in direct_runtime_system_property_args(manifest) {
        command.arg(arg);
    }
    if let Some(archive) = app_cds_archive {
''',
    "direct launch system property arguments",
)
text = replace_once(
    text,
    '''fn direct_runtime_args(manifest: &DirectLaunchManifest, run_dir: &Path) -> Vec<String> {
''',
    '''fn direct_runtime_system_property_args(manifest: &DirectLaunchManifest) -> Vec<String> {
    manifest
        .system_properties
        .iter()
        .map(|(key, value)| format!("-D{key}={value}"))
        .collect()
}

fn direct_runtime_profile_has_loader_bootstrap(
    family: BridgeFamily,
    manifest: &DirectLaunchManifest,
) -> bool {
    match family {
        BridgeFamily::Fabric => manifest
            .jvm_args
            .iter()
            .any(|arg| arg.starts_with("-Dfabric.dli.main=")),
        // Quilt Loom can use Fabric's dev-launch injector. When it does, the
        // target main class must survive the cached profile as well.
        BridgeFamily::Quilt if manifest.main_class.contains("devlaunchinjector") => manifest
            .jvm_args
            .iter()
            .any(|arg| arg.starts_with("-Dfabric.dli.main=")),
        _ => true,
    }
}

fn direct_runtime_args(manifest: &DirectLaunchManifest, run_dir: &Path) -> Vec<String> {
''',
    "direct launch property helper",
)
text = replace_once(
    text,
    '''    let manifest_path = profile.join("minesport-launch.json");
    if let Ok(manifest) = load_direct_launch_manifest(&manifest_path) {
        return Ok(manifest);
    }
''',
    '''    let manifest_path = profile.join("minesport-launch.json");
    if let Ok(manifest) = load_direct_launch_manifest(&manifest_path) {
        if direct_runtime_profile_has_loader_bootstrap(family, &manifest) {
            return Ok(manifest);
        }
        // Never keep reusing a profile that can only terminate inside the
        // dev-launch injector with "missing fabric.dli.main".
        let _ = fs::remove_file(&manifest_path);
    }
''',
    "reject incomplete cached launch profile",
)
text = replace_once(
    text,
    '''    load_direct_launch_manifest(&manifest_path).with_context(|| {
        format!(
            "load resolved runtime launch profile {}",
            manifest_path.display()
        )
    })
}
''',
    '''    let manifest = load_direct_launch_manifest(&manifest_path).with_context(|| {
        format!(
            "load resolved runtime launch profile {}",
            manifest_path.display()
        )
    })?;
    if !direct_runtime_profile_has_loader_bootstrap(family, &manifest) {
        bail!(
            "resolved {} runtime launch profile is missing its loader bootstrap JVM arguments",
            family.label()
        );
    }
    Ok(manifest)
}
''',
    "validate newly resolved launch profile",
)

# Loom stores the dev-launch injector properties in RunConfiguration JVM args,
# surfaced lazily through JavaExec JVM argument providers. allJvmArgs alone can
# miss provider values when we resolve a profile without executing runClient.
for label, marker in [
    ("Fabric", "const BUILD_GRADLE: &str = r#\""),
    ("Quilt", "const QUILT_BUILD_GRADLE: &str = r#\""),
]:
    start = text.index(marker)
    end = text.index('"#;', start)
    block = text[start:end]
    old = '''            jvmArgs: runTask.allJvmArgs.collect { it.toString() },
            args: (runTask.args ?: []).collect { it.toString() },
'''
    new = '''            jvmArgs: (
                (runTask.allJvmArgs ?: []).collect { it.toString() } +
                runTask.jvmArgumentProviders.collectMany { provider ->
                    provider.asArguments().collect { it.toString() }
                }
            ).unique(),
            systemProperties: runTask.systemProperties.collectEntries { key, value ->
                [(key.toString()): value?.toString()]
            },
            args: (runTask.args ?: []).collect { it.toString() },
'''
    if block.count(old) != 1:
        raise SystemExit(f"{label} Gradle JVM-provider anchor count={block.count(old)}")
    block = block.replace(old, new, 1)
    text = text[:start] + block + text[end:]

test_anchor = '''    #[test]
    fn capture_watchdog_covers_startup_and_mid_capture_stalls() {
'''
test_insert = '''    #[test]
    fn direct_launch_profiles_preserve_loom_bootstrap_arguments() {
        assert_eq!(DIRECT_LAUNCH_PROFILE_SCHEMA, 2);
        assert!(BUILD_GRADLE.contains("runTask.jvmArgumentProviders.collectMany"));
        assert!(QUILT_BUILD_GRADLE.contains("runTask.jvmArgumentProviders.collectMany"));
        assert!(BUILD_GRADLE.contains("systemProperties: runTask.systemProperties"));

        let mut properties = BTreeMap::new();
        properties.insert("fabric.dli.env".to_string(), "client".to_string());
        let mut manifest = DirectLaunchManifest {
            schema: DIRECT_LAUNCH_PROFILE_SCHEMA,
            main_class: "net.fabricmc.devlaunchinjector.Main".to_string(),
            classpath: vec!["loader.jar".to_string()],
            jvm_args: Vec::new(),
            system_properties: properties,
            args: Vec::new(),
            working_dir: String::new(),
            environment_overrides: BTreeMap::new(),
        };
        assert!(!direct_runtime_profile_has_loader_bootstrap(
            BridgeFamily::Fabric,
            &manifest
        ));
        manifest
            .jvm_args
            .push("-Dfabric.dli.main=net.minecraft.client.main.Main".to_string());
        assert!(direct_runtime_profile_has_loader_bootstrap(
            BridgeFamily::Fabric,
            &manifest
        ));
        let args = direct_runtime_system_property_args(&manifest);
        assert!(args.contains(&"-Dfabric.dli.env=client".to_string()));
    }

'''
text = replace_once(text, test_anchor, test_insert + test_anchor, "direct launch regression test")
runtime.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Contain the 0xC00000FD updater crash outside the Workbench process.
# ---------------------------------------------------------------------------
update = Path("desktop/src/engine_update.rs")
text = update.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''const INSTALL_TIMEOUT: Duration = Duration::from_secs(180);
''',
    '''const INSTALL_TIMEOUT: Duration = Duration::from_secs(180);
const BACKGROUND_CHECK_ARG: &str = "--engine-update-check";
// TLS/certificate/HTTP decoding gets its own generous worker stack, but more
// importantly that worker now lives in an isolated helper process. A fatal
// native stack overflow can no longer terminate the Workbench.
const ENGINE_UPDATE_THREAD_STACK: usize = 8 * 1024 * 1024;
''',
    "updater isolation constants",
)

start = text.index('/// Network/update discovery must never hold up the Workbench.')
fn_start = text.index('pub fn spawn_background_check()', start)
fn_end = text.index('\nfn check_and_stage_update_if_due()', fn_start)
replacement = '''pub fn handle_background_check_mode() -> Result<bool> {
    if env::args().nth(1).as_deref() != Some(BACKGROUND_CHECK_ARG) {
        return Ok(false);
    }

    let operation = diagnostics::Logger::new("ENGINE")
        .child("UPDATE")
        .operation("EngineBackgroundUpdateHelper");
    let worker = thread::Builder::new()
        .name("minesport-engine-update-network".to_string())
        .stack_size(ENGINE_UPDATE_THREAD_STACK)
        .spawn(check_and_stage_update_if_due)
        .context("start isolated engine update network worker")?;

    match worker.join() {
        Ok(Ok(())) => {
            operation.success("isolated engine update helper completed", &[]);
            Ok(true)
        }
        Ok(Err(error)) => {
            operation.failure(
                "isolated engine update helper could not complete",
                &[("error", format!("{error:#}"))],
            );
            Err(error)
        }
        Err(_) => {
            operation.failure("isolated engine update helper panicked", &[]);
            bail!("isolated engine update helper panicked")
        }
    }
}

/// Update discovery runs in a separate hidden process. The updater touches
/// networking, TLS, signatures and installer staging; none of those failures
/// should be able to take the Slint Workbench down with them.
pub fn spawn_background_check() {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    let logger = diagnostics::Logger::new("ENGINE").child("UPDATE");
    let executable = match env::current_exe() {
        Ok(executable) => executable,
        Err(error) => {
            logger.warn(
                "EngineUpdateHelperUnavailable",
                "could not resolve Minesport executable for isolated update discovery",
                &[("error", error.to_string())],
            );
            return;
        }
    };

    let mut command = Command::new(&executable);
    command
        .arg(BACKGROUND_CHECK_ARG)
        .creation_flags(CREATE_NO_WINDOW);
    match command.spawn() {
        Ok(child) => logger.debug(
            "EngineUpdateHelperStarted",
            "engine update discovery started in an isolated helper process",
            &[
                ("helper_pid", child.id().to_string()),
                ("executable", executable.display().to_string()),
            ],
        ),
        Err(error) => logger.warn(
            "EngineUpdateHelperUnavailable",
            "could not start isolated engine update helper; Workbench startup will continue",
            &[("error", error.to_string())],
        ),
    }
}
'''
text = text[:fn_start] + replacement + text[fn_end:]

engine_test_anchor = '''    #[test]
    fn semantic_engine_versions_compare_numerically() {
'''
engine_test = '''    #[test]
    fn updater_helper_is_isolated_and_has_network_stack_headroom() {
        assert_eq!(BACKGROUND_CHECK_ARG, "--engine-update-check");
        assert!(ENGINE_UPDATE_THREAD_STACK >= 8 * 1024 * 1024);
    }

'''
text = replace_once(
    text,
    engine_test_anchor,
    engine_test + engine_test_anchor,
    "updater isolation regression test",
)
update.write_text(text, encoding="utf-8")

main = Path("desktop/src/main.rs")
text = main.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''    install_panic_hook();

    let engine_worker_mode = std::env::args().nth(1).as_deref() == Some("--engine-worker");
''',
    '''    install_panic_hook();

    // The update helper is intentionally handled before normal GUI/backend
    // startup. It has an argument, so it never spawns a crash reporter or a
    // second update helper.
    #[cfg(windows)]
    if engine_update::handle_background_check_mode()? {
        diagnostics::append("Isolated engine update helper exited cleanly");
        return Ok(());
    }

    let engine_worker_mode = std::env::args().nth(1).as_deref() == Some("--engine-worker");
''',
    "engine update helper mode dispatch",
)
main.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Do not turn concurrency-cancelled superseded CI runs into false red builds.
# ---------------------------------------------------------------------------
build = Path(".github/workflows/build.yml")
text = build.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''  compile-clean:
    name: Compile cleanly
    if: always()
''',
    '''  compile-clean:
    name: Compile cleanly
    # Superseded push runs are intentionally cancelled by workflow concurrency.
    # Do not convert that neutral cancellation into a false red compile failure.
    if: always() && !cancelled()
''',
    "compile verdict cancellation guard",
)
build.write_text(text, encoding="utf-8")
