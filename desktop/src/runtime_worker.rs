use crate::{
    bridge_compat,
    bridge_family::{self, BridgeFamily},
    bridge_java, diagnostics, registry, runtime, toolchain,
};
use anyhow::{Context, Result, anyhow, bail};
use serde::Deserialize;
use std::{
    collections::BTreeMap,
    env,
    ffi::OsString,
    fs::{self, File},
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const GRADLEW_SH: &[u8] = include_bytes!("../../minesport-bridge-fabric/gradlew");
const GRADLEW_BAT: &[u8] = include_bytes!("../../minesport-bridge-fabric/gradlew.bat");
const GRADLE_WRAPPER_JAR: &[u8] =
    include_bytes!("../../minesport-bridge-fabric/gradle/wrapper/gradle-wrapper.jar");
const GRADLE_WRAPPER_PROPERTIES: &[u8] =
    include_bytes!("../../minesport-bridge-fabric/gradle/wrapper/gradle-wrapper.properties");

const MC_1_21_10: &str = "1.21.10";
const LOADER_1_21_10: &str = "0.18.5";
const FABRIC_API_1_21_10: &str = "0.138.4+1.21.10";
const LOOM_1_21_10: &str = "1.11.7";
const FABRIC_MOD_JSON_LIMIT: u64 = 1 << 20;
const DIRECT_LAUNCH_PROFILE_SCHEMA: u32 = 1;
const DIRECT_LAUNCH_RESOLVE_TIMEOUT: Duration = Duration::from_secs(5 * 60);
const CAPTURE_FIRST_PROGRESS_TIMEOUT: Duration = Duration::from_secs(4 * 60);
const CAPTURE_STALL_TIMEOUT: Duration = Duration::from_secs(2 * 60);

#[derive(Debug, Clone)]
pub struct CacheResult {
    pub fingerprint: String,
    pub registry_path: PathBuf,
    pub reused: bool,
}

#[derive(Debug, Clone)]
pub struct Progress {
    pub percent: i32,
    pub message: String,
}

struct WorkspacePlan {
    workspace: PathBuf,
    java: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DirectLaunchManifest {
    schema: u32,
    main_class: String,
    classpath: Vec<String>,
    #[serde(default)]
    jvm_args: Vec<String>,
    #[serde(default)]
    args: Vec<String>,
    working_dir: String,
    #[serde(default)]
    environment_overrides: BTreeMap<String, String>,
}

pub fn prepare_full_registry<F>(
    version: &str,
    mods_path: &Path,
    force: bool,
    progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
    prepare_full_registry_for_loader("fabric", version, mods_path, force, progress)
}

pub fn prepare_full_registry_for_loader<F>(
    loader: &str,
    version: &str,
    mods_path: &Path,
    force: bool,
    progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
    prepare_full_registry_cancellable_for_loader(
        loader,
        version,
        mods_path,
        force,
        Arc::new(AtomicBool::new(false)),
        progress,
    )
}

pub fn prepare_full_registry_cancellable<F>(
    version: &str,
    mods_path: &Path,
    force: bool,
    cancel: Arc<AtomicBool>,
    progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
    prepare_full_registry_cancellable_for_loader(
        "fabric", version, mods_path, force, cancel, progress,
    )
}

pub fn prepare_full_registry_cancellable_for_loader<F>(
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
    let family = BridgeFamily::parse(loader)
        .ok_or_else(|| anyhow!("unsupported Export Worker loader {loader:?}"))?;
    let version = bridge_compat::normalize_version(version)
        .ok_or_else(|| anyhow!("could not determine Minecraft version"))?;
    if !bridge_family::is_supported(family, &version) {
        bail!(
            "Minesport has no embedded {} compatibility recipe for Minecraft {version}",
            family.label()
        );
    }
    if !mods_path.is_dir() {
        bail!("mods folder is unavailable: {}", mods_path.display());
    }
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    progress(Progress {
        percent: 0,
        message: "Starting runtime cache…".into(),
    });
    let _cache_lease = runtime::acquire_generated_cache_lease()?;

    progress(Progress {
        percent: 1,
        message: "Checking mods…".into(),
    });
    let raw_fingerprint = registry::mods_fingerprint(mods_path)?;
    let fingerprint = loader_cache_fingerprint(family, &raw_fingerprint);
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    let cache_root = runtime::cache_root();
    let existing = registry::snapshot_path(&cache_root, &version, &fingerprint);
    if !force && registry::snapshot_exists(&cache_root, &version, &fingerprint) {
        progress(Progress {
            percent: 100,
            message: "Runtime ready".into(),
        });
        return Ok(CacheResult {
            fingerprint,
            registry_path: existing,
            reused: true,
        });
    }

    progress(Progress {
        percent: 4,
        message: "Preparing worker…".into(),
    });
    let plan = create_workspace(family, &version, mods_path, |update| {
        progress(Progress {
            percent: update.percent.clamp(4, 38),
            message: if update.detail.is_empty() {
                update.stage
            } else {
                format!("{} · {}", update.stage, update.detail)
            },
        });
    })?;
    let workspace = plan.workspace;
    let cleanup = WorkspaceCleanup(workspace.clone());
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    progress(Progress {
        percent: 40,
        message: "Checking JDK…".into(),
    });
    let java_home = toolchain::ensure_jdk_cancellable(plan.java, cancel.clone(), |update| {
        progress(Progress {
            percent: update.percent.clamp(40, 54),
            message: update.message,
        });
    })?;
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    let direct_launch = if family == BridgeFamily::Fabric && version == MC_1_21_10 {
        progress(Progress {
            percent: 54,
            message: "Preparing Minecraft launch…".into(),
        });
        Some(ensure_direct_launch_profile(
            &version,
            &java_home,
            cancel.as_ref(),
            |elapsed| {
                progress(Progress {
                    percent: 54,
                    message: format!(
                        "Preparing Minecraft launch… · {}s",
                        elapsed.as_secs()
                    ),
                });
            },
        )?)
    } else {
        None
    };
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    let (listen_tx, listen_rx) = mpsc::sync_channel::<Result<u16>>(1);
    let (capture_tx, capture_rx) = mpsc::sync_channel::<Result<PathBuf>>(1);
    let (capture_progress_tx, capture_progress_rx) = mpsc::channel::<(usize, usize)>();
    let capture_root = cache_root.clone();
    let capture_version = version.clone();
    let capture_fingerprint = fingerprint.clone();
    let capture_cancel = cancel.clone();
    let capture_handle = thread::spawn(move || {
        let mut announced = false;
        let result = registry::capture_once_cancellable(
            registry::EPHEMERAL_ADDRESS,
            &capture_root,
            &capture_version,
            &capture_fingerprint,
            capture_cancel,
            |notice| match notice {
                registry::CaptureNotice::Listening(address) if !announced => {
                    announced = true;
                    let port = address
                        .rsplit(':')
                        .next()
                        .and_then(|value| value.parse::<u16>().ok())
                        .ok_or_else(|| anyhow!("invalid runtime registry listener address {address}"));
                    let _ = listen_tx.send(port);
                }
                registry::CaptureNotice::Progress {
                    blocks,
                    total_blocks,
                } => {
                    let _ = capture_progress_tx.send((blocks, total_blocks));
                }
                _ => {}
            },
        );
        if !announced {
            let _ = listen_tx.send(Err(anyhow!("registry receiver stopped before listening")));
        }
        let _ = capture_tx.send(result);
    });
    let _capture_thread = CaptureThreadGuard::new(cancel.clone(), capture_handle);

    let capture_port = listen_rx
        .recv_timeout(Duration::from_secs(5))
        .context("wait for Rust runtime registry receiver")??;

    progress(Progress {
        percent: 55,
        message: "Starting Minecraft…".into(),
    });
    let log_path = workspace.join("runtime-worker.log");
    let app_cds_archive = direct_launch.as_ref().and_then(|manifest| {
        prepare_direct_launch_app_cds_archive(&version, &fingerprint, manifest)
    });
    let mut child = if let Some(manifest) = direct_launch.as_ref() {
        start_direct_worker(
            family,
            &workspace,
            &log_path,
            &java_home,
            manifest,
            app_cds_archive.as_deref(),
            capture_port,
        )?
    } else {
        start_gradle_worker(family, &workspace, &log_path, &java_home, capture_port)?
    };
    progress(Progress {
        percent: 62,
        message: "Loading runtime models…".into(),
    });

    let worker_started = Instant::now();
    let deadline = worker_started + Duration::from_secs(10 * 60);
    let mut last_captured_blocks = 0usize;
    let mut last_capture_progress = None::<Instant>;
    loop {
        if cancel.load(Ordering::Relaxed) {
            stop_child(&mut child);
            bail!("runtime cache cancelled");
        }

        while let Ok((blocks, total_blocks)) = capture_progress_rx.try_recv() {
            if blocks <= last_captured_blocks {
                continue;
            }
            last_captured_blocks = blocks;
            last_capture_progress = Some(Instant::now());
            let capture_percent = capture_progress_percent(blocks, total_blocks);
            let message = if total_blocks > 0 {
                format!("Loading runtime models · {blocks}/{total_blocks}")
            } else {
                format!("Loading runtime models · {blocks}")
            };
            progress(Progress {
                percent: capture_percent,
                message,
            });
        }

        match capture_rx.try_recv() {
            Ok(Ok(path)) => {
                progress(Progress {
                    percent: 96,
                    message: "Saving runtime cache…".into(),
                });
                finish_child_after_capture(&mut child, app_cds_archive.is_some());
                progress(Progress {
                    percent: 100,
                    message: "Runtime ready".into(),
                });
                drop(cleanup);
                return Ok(CacheResult {
                    fingerprint,
                    registry_path: path,
                    reused: false,
                });
            }
            Ok(Err(error)) => {
                stop_child(&mut child);
                return Err(runtime_worker_failure(
                    &workspace,
                    &version,
                    &log_path,
                    format!("{error:#}"),
                    60,
                ));
            }
            Err(mpsc::TryRecvError::Disconnected) => {
                stop_child(&mut child);
                return Err(runtime_worker_failure(
                    &workspace,
                    &version,
                    &log_path,
                    "runtime registry receiver terminated unexpectedly".into(),
                    60,
                ));
            }
            Err(mpsc::TryRecvError::Empty) => {}
        }

        if let Some(status) = child.try_wait().context("poll isolated Minecraft worker")? {
            match capture_rx.recv_timeout(Duration::from_secs(2)) {
                Ok(Ok(path)) => {
                    progress(Progress {
                        percent: 100,
                        message: "Runtime ready".into(),
                    });
                    return Ok(CacheResult {
                        fingerprint,
                        registry_path: path,
                        reused: false,
                    });
                }
                Ok(Err(error)) => {
                    return Err(runtime_worker_failure(
                        &workspace,
                        &version,
                        &log_path,
                        format!("{error:#}"),
                        80,
                    ));
                }
                Err(_) => {
                    return Err(runtime_worker_failure(
                        &workspace,
                        &version,
                        &log_path,
                        format!(
                            "runtime Minecraft worker exited with {status} before the full registry was received"
                        ),
                        80,
                    ));
                }
            }
        }

        let now = Instant::now();
        if capture_has_stalled(last_capture_progress, worker_started, now) {
            stop_child(&mut child);
            let reason = if last_capture_progress.is_none() {
                format!(
                    "runtime capture produced no model progress within {} seconds",
                    CAPTURE_FIRST_PROGRESS_TIMEOUT.as_secs()
                )
            } else {
                format!("runtime capture stalled after {last_captured_blocks} blocks")
            };
            return Err(runtime_worker_failure(
                &workspace,
                &version,
                &log_path,
                reason,
                80,
            ));
        }
        if now >= deadline {
            stop_child(&mut child);
            bail!("runtime cache timed out after 10 minutes");
        }
        thread::sleep(Duration::from_millis(150));
    }
}

fn loader_cache_fingerprint(family: BridgeFamily, raw: &str) -> String {
    if family == BridgeFamily::Fabric {
        raw.to_string()
    } else {
        format!("{}-{raw}", family.label().to_ascii_lowercase())
    }
}

fn capture_has_stalled(
    last_progress: Option<Instant>,
    worker_started: Instant,
    now: Instant,
) -> bool {
    match last_progress {
        Some(last) => now.duration_since(last) >= CAPTURE_STALL_TIMEOUT,
        None => now.duration_since(worker_started) >= CAPTURE_FIRST_PROGRESS_TIMEOUT,
    }
}

fn capture_progress_percent(blocks: usize, total_blocks: usize) -> i32 {
    if total_blocks == 0 {
        return (62 + (blocks / 64) as i32).clamp(63, 94);
    }
    let fraction = (blocks.min(total_blocks) as f64 / total_blocks as f64).clamp(0.0, 1.0);
    (62.0 + fraction * 32.0).round() as i32
}

fn create_workspace<F>(
    family: BridgeFamily,
    version: &str,
    mods_path: &Path,
    mut progress: F,
) -> Result<WorkspacePlan>
where
    F: FnMut(bridge_compat::CompatProgress),
{
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join("runtime-workers")
        .join(format!(
            "{}-{}-{}-{stamp}",
            family.label().to_ascii_lowercase(),
            safe(version),
            std::process::id()
        ));

    let fast_bundled_fabric = family == BridgeFamily::Fabric && version == MC_1_21_10;
    let java = if fast_bundled_fabric {
        create_fast_bundled_workspace(&workspace, version)?;
        21
    } else {
        let prepared = bridge_family::prepare_source(family, version, &workspace, &mut progress)?;
        if family == BridgeFamily::Fabric {
            bridge_java::tooling_java(
                prepared.java,
                prepared.variables.get("loom_version").map(String::as_str),
            )
        } else {
            prepared.java
        }
    };

    let run_dir = workspace.join("run");
    let run_mods = run_dir.join("mods");
    fs::create_dir_all(&run_mods)?;

    if fast_bundled_fabric {
        let bridge = runtime::materialize_bundled_bridge()?;
        let target = run_mods.join("minesport_export_worker-fabric-1.21.10.jar");
        link_or_copy(&bridge, &target)
            .with_context(|| format!("stage embedded Bridge {}", bridge.display()))?;
    }

    let count = copy_worker_mods(mods_path, &run_mods)?;
    progress(bridge_compat::CompatProgress {
        percent: 36,
        stage: "Preparing worker".into(),
        detail: format!("{count} mod JARs"),
    });

    if let Some(instance) = mods_path.parent() {
        let config = instance.join("config");
        if config.is_dir() {
            copy_directory(&config, &run_dir.join("config"))?;
        }
    }
    Ok(WorkspacePlan { workspace, java })
}

fn create_fast_bundled_workspace(workspace: &Path, version: &str) -> Result<()> {
    let wrapper_dir = workspace.join("gradle").join("wrapper");
    fs::create_dir_all(&wrapper_dir)?;
    write_file(&workspace.join("gradlew"), GRADLEW_SH)?;
    write_file(&workspace.join("gradlew.bat"), GRADLEW_BAT)?;
    write_file(&wrapper_dir.join("gradle-wrapper.jar"), GRADLE_WRAPPER_JAR)?;
    write_file(
        &wrapper_dir.join("gradle-wrapper.properties"),
        GRADLE_WRAPPER_PROPERTIES,
    )?;
    write_file(
        &workspace.join("settings.gradle"),
        SETTINGS_GRADLE.as_bytes(),
    )?;
    write_file(&workspace.join("build.gradle"), BUILD_GRADLE.as_bytes())?;
    write_file(
        &workspace.join("gradle.properties"),
        gradle_properties(version).as_bytes(),
    )?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(workspace.join("gradlew"), fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}

fn direct_launch_profile_dir(version: &str) -> PathBuf {
    runtime::cache_root()
        .join("bridge-build")
        .join("launch-profiles")
        .join(format!(
            "fabric-{}-loader-{}-api-{}-loom-{}-v{}",
            safe(version),
            safe(LOADER_1_21_10),
            safe(FABRIC_API_1_21_10),
            safe(LOOM_1_21_10),
            DIRECT_LAUNCH_PROFILE_SCHEMA
        ))
}

fn ensure_direct_launch_profile<F>(
    version: &str,
    java_home: &Path,
    cancel: &AtomicBool,
    mut on_wait: F,
) -> Result<DirectLaunchManifest>
where
    F: FnMut(Duration),
{
    let profile = direct_launch_profile_dir(version);
    let manifest_path = profile.join("minesport-launch.json");
    if let Ok(manifest) = load_direct_launch_manifest(&manifest_path) {
        return Ok(manifest);
    }
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled while preparing Minecraft launch");
    }

    create_fast_bundled_workspace(&profile, version)?;
    let log_path = profile.join("resolve-runtime.log");
    let stdout =
        File::create(&log_path).with_context(|| format!("create {}", log_path.display()))?;
    let stderr = stdout.try_clone()?;
    let java = java_home
        .join("bin")
        .join(if cfg!(windows) { "java.exe" } else { "java" });
    if !java.is_file() {
        bail!(
            "selected runtime JDK has no Java launcher: {}",
            java.display()
        );
    }
    let wrapper = profile
        .join("gradle")
        .join("wrapper")
        .join("gradle-wrapper.jar");
    if !wrapper.is_file() {
        bail!(
            "runtime launch profile has no embedded Gradle wrapper: {}",
            wrapper.display()
        );
    }

    let mut command = Command::new(&java);
    command
        .arg("-Xmx512m")
        .arg("-XX:MaxMetaspaceSize=384m")
        .arg("-XX:ActiveProcessorCount=2")
        .arg("-Dfile.encoding=UTF-8")
        .arg("-cp")
        .arg(&wrapper)
        .arg("org.gradle.wrapper.GradleWrapperMain")
        .args([
            "--no-daemon",
            "--console=plain",
            "--max-workers=1",
            "--no-watch-fs",
            "minesportResolveRuntime",
        ])
        .current_dir(&profile)
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));
    sanitize_java_environment(&mut command, java_home);
    command.env("GRADLE_USER_HOME", gradle_user_home());
    command.env("JAVA_TOOL_OPTIONS", "-XX:ActiveProcessorCount=2");
    hide_console_window(&mut command);

    let mut child = command.spawn().with_context(|| {
        format!(
            "start cached Fabric runtime launch-profile resolver with {}",
            java_home.display()
        )
    })?;
    let started = Instant::now();
    let mut last_reported_second = 0u64;
    let status = loop {
        if cancel.load(Ordering::Relaxed) {
            stop_child(&mut child);
            bail!("runtime cache cancelled while preparing Minecraft launch");
        }

        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) => {}
            Err(error) => {
                stop_child(&mut child);
                return Err(error).context("poll Fabric runtime launch-profile resolver");
            }
        }

        let elapsed = started.elapsed();
        if elapsed >= DIRECT_LAUNCH_RESOLVE_TIMEOUT {
            stop_child(&mut child);
            let tail = tail_file(&log_path, 80);
            if tail.is_empty() {
                bail!(
                    "runtime launch profile resolver timed out after {} seconds",
                    DIRECT_LAUNCH_RESOLVE_TIMEOUT.as_secs()
                );
            }
            bail!(
                "runtime launch profile resolver timed out after {} seconds\n{}",
                DIRECT_LAUNCH_RESOLVE_TIMEOUT.as_secs(),
                tail
            );
        }

        let elapsed_second = elapsed.as_secs();
        if elapsed_second > last_reported_second {
            last_reported_second = elapsed_second;
            on_wait(elapsed);
        }
        thread::sleep(Duration::from_millis(200));
    };

    if !status.success() {
        let tail = tail_file(&log_path, 80);
        if tail.is_empty() {
            bail!("runtime launch profile resolver exited with {status}");
        }
        bail!("runtime launch profile resolver exited with {status}\n{tail}");
    }

    load_direct_launch_manifest(&manifest_path).with_context(|| {
        format!(
            "load resolved runtime launch profile {}",
            manifest_path.display()
        )
    })
}

fn load_direct_launch_manifest(path: &Path) -> Result<DirectLaunchManifest> {
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let manifest: DirectLaunchManifest =
        serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))?;
    if manifest.schema != DIRECT_LAUNCH_PROFILE_SCHEMA {
        bail!(
            "runtime launch profile schema {} is not supported",
            manifest.schema
        );
    }
    if manifest.main_class.trim().is_empty() {
        bail!("runtime launch profile has no main class");
    }
    if manifest.classpath.is_empty() {
        bail!("runtime launch profile has an empty classpath");
    }
    if let Some(missing) = manifest
        .classpath
        .iter()
        .find(|entry| entry.ends_with(".jar") && !Path::new(entry.as_str()).is_file())
    {
        bail!("runtime launch profile references missing JAR {missing}");
    }
    Ok(manifest)
}

fn start_direct_worker(
    family: BridgeFamily,
    workspace: &Path,
    log_path: &Path,
    java_home: &Path,
    manifest: &DirectLaunchManifest,
    app_cds_archive: Option<&Path>,
    capture_port: u16,
) -> Result<Child> {
    let stdout =
        File::create(log_path).with_context(|| format!("create {}", log_path.display()))?;
    let stderr = stdout.try_clone()?;
    let java = java_home
        .join("bin")
        .join(if cfg!(windows) { "java.exe" } else { "java" });
    if !java.is_file() {
        bail!(
            "selected runtime JDK has no Java launcher: {}",
            java.display()
        );
    }

    let classpath = env::join_paths(manifest.classpath.iter().map(PathBuf::from))
        .context("join cached runtime classpath")?;
    let run_dir = workspace.join("run");
    let runtime_args = direct_runtime_args(manifest, &run_dir);

    let mut command = Command::new(&java);
    for arg in &manifest.jvm_args {
        if !managed_runtime_jvm_arg(arg) {
            command.arg(arg);
        }
    }
    if let Some(archive) = app_cds_archive {
        let mut archive_arg = OsString::from("-XX:SharedArchiveFile=");
        archive_arg.push(archive.as_os_str());
        command
            .arg("-Xshare:auto")
            .arg("-XX:+AutoCreateSharedArchive")
            .arg(archive_arg);
    }
    command
        .arg("-Xmx512m")
        .arg("-XX:MaxMetaspaceSize=384m")
        .arg("-XX:ActiveProcessorCount=2")
        .arg("-Dfile.encoding=UTF-8")
        .arg("-cp")
        .arg(classpath)
        .arg(&manifest.main_class)
        .args(runtime_args)
        .current_dir(&run_dir)
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));
    sanitize_java_environment(&mut command, java_home);
    for (key, value) in &manifest.environment_overrides {
        command.env(key, value);
    }
    command.env("MINESPORT_EXPORT_WORKER_PORT", capture_port.to_string());
    command.env("MINESPORT_EXPORT_WORKER_MODE", "all");
    command.env("MINESPORT_EXPORT_WORKER", "1");
    hide_console_window(&mut command);
    command.spawn().with_context(|| {
        format!(
            "start isolated {} runtime worker directly with {}",
            family.label(),
            java_home.display()
        )
    })
}

fn managed_runtime_jvm_arg(arg: &str) -> bool {
    arg.starts_with("-Xmx")
        || arg.starts_with("-Xms")
        || arg.starts_with("-XX:MaxMetaspaceSize=")
        || arg.starts_with("-XX:ActiveProcessorCount=")
        || arg.starts_with("-Dfile.encoding=")
        || arg.starts_with("-Xshare:")
        || arg.starts_with("-XX:SharedArchiveFile=")
        || arg.starts_with("-XX:ArchiveClassesAtExit=")
        || matches!(
            arg,
            "-XX:+AutoCreateSharedArchive"
                | "-XX:-AutoCreateSharedArchive"
                | "-XX:+RecordDynamicDumpInfo"
                | "-XX:-RecordDynamicDumpInfo"
        )
}

fn direct_runtime_args(manifest: &DirectLaunchManifest, run_dir: &Path) -> Vec<String> {
    let from = manifest.working_dir.as_str();
    let to = run_dir.to_string_lossy();
    manifest
        .args
        .iter()
        .map(|arg| {
            if from.is_empty() {
                arg.clone()
            } else {
                arg.replace(from, to.as_ref())
            }
        })
        .collect()
}

fn direct_launch_app_cds_eligible(manifest: &DirectLaunchManifest) -> bool {
    if manifest
        .jvm_args
        .iter()
        .any(|arg| app_cds_incompatible_jvm_arg(arg))
    {
        return false;
    }
    manifest
        .classpath
        .iter()
        .all(|entry| app_cds_classpath_entry_compatible(Path::new(entry)))
}

fn app_cds_incompatible_jvm_arg(arg: &str) -> bool {
    matches!(
        arg,
        "--upgrade-module-path" | "--patch-module" | "--limit-modules" | "--module-path" | "-p"
    ) || arg.starts_with("--upgrade-module-path=")
        || arg.starts_with("--patch-module=")
        || arg.starts_with("--limit-modules=")
        || arg.starts_with("--module-path=")
        || arg.starts_with("-Xbootclasspath/a:")
}

fn app_cds_classpath_entry_compatible(path: &Path) -> bool {
    if path.is_file() {
        return true;
    }
    if !path.is_dir() {
        return false;
    }
    fs::read_dir(path)
        .ok()
        .is_some_and(|mut entries| entries.next().is_none())
}

fn direct_launch_app_cds_archive_path(version: &str, fingerprint: &str) -> PathBuf {
    direct_launch_profile_dir(version)
        .join("cds")
        .join(format!("{}.jsa", safe(fingerprint)))
}

fn prepare_direct_launch_app_cds_archive(
    version: &str,
    fingerprint: &str,
    manifest: &DirectLaunchManifest,
) -> Option<PathBuf> {
    if !direct_launch_app_cds_eligible(manifest) {
        return None;
    }
    let archive = direct_launch_app_cds_archive_path(version, fingerprint);
    fs::create_dir_all(archive.parent()?).ok()?;
    Some(archive)
}

fn start_gradle_worker(
    family: BridgeFamily,
    workspace: &Path,
    log_path: &Path,
    java_home: &Path,
    capture_port: u16,
) -> Result<Child> {
    let stdout =
        File::create(log_path).with_context(|| format!("create {}", log_path.display()))?;
    let stderr = stdout.try_clone()?;
    let java = java_home
        .join("bin")
        .join(if cfg!(windows) { "java.exe" } else { "java" });
    if !java.is_file() {
        bail!(
            "selected runtime JDK has no Java launcher: {}",
            java.display()
        );
    }
    let wrapper = workspace
        .join("gradle")
        .join("wrapper")
        .join("gradle-wrapper.jar");
    if !wrapper.is_file() {
        bail!(
            "runtime worker has no embedded Gradle wrapper: {}",
            wrapper.display()
        );
    }

    let mut command = Command::new(&java);
    command
        .arg("-Xmx512m")
        .arg("-XX:MaxMetaspaceSize=384m")
        .arg("-XX:ActiveProcessorCount=2")
        .arg("-Dfile.encoding=UTF-8")
        .arg("-cp")
        .arg(&wrapper)
        .arg("org.gradle.wrapper.GradleWrapperMain")
        .args([
            "--no-daemon",
            "--console=plain",
            "--max-workers=1",
            "--no-watch-fs",
            "runClient",
        ]);
    command
        .current_dir(workspace)
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));
    sanitize_java_environment(&mut command, java_home);
    command.env("MINESPORT_EXPORT_WORKER_PORT", capture_port.to_string());
    command.env("MINESPORT_EXPORT_WORKER_MODE", "all");
    command.env("MINESPORT_EXPORT_WORKER", "1");
    // Inherited by JavaExec/runClient children as well as the wrapper JVM. It
    // reduces JVM/GC/common-pool thread fan-out without changing game/model data.
    command.env("JAVA_TOOL_OPTIONS", "-XX:ActiveProcessorCount=2");
    command.env("GRADLE_USER_HOME", gradle_user_home());
    hide_console_window(&mut command);
    command.spawn().with_context(|| {
        format!(
            "start isolated {} runtime worker with {}",
            family.label(),
            java_home.display()
        )
    })
}

fn gradle_user_home() -> PathBuf {
    if let Some(path) = env::var_os("GRADLE_USER_HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_dir())
    {
        return path;
    }
    for home_var in ["USERPROFILE", "HOME"] {
        if let Some(home) = env::var_os(home_var).map(PathBuf::from) {
            let candidate = home.join(".gradle");
            if candidate.is_dir() {
                return candidate;
            }
        }
    }
    runtime::cache_root().join("gradle")
}

fn sanitize_java_environment(command: &mut Command, java_home: &Path) {
    for (key, _) in env::vars_os() {
        let name = key.to_string_lossy();
        if name.eq_ignore_ascii_case("JAVA_HOME")
            || name.eq_ignore_ascii_case("JDK_HOME")
            || name.eq_ignore_ascii_case("GRADLE_JAVA_HOME")
            || name.eq_ignore_ascii_case("JAVA_OPTS")
            || name.eq_ignore_ascii_case("GRADLE_OPTS")
            || name.eq_ignore_ascii_case("JAVA_TOOL_OPTIONS")
            || name.eq_ignore_ascii_case("_JAVA_OPTIONS")
            || name.eq_ignore_ascii_case("JDK_JAVA_OPTIONS")
        {
            command.env_remove(key);
        }
    }
    command.env("JAVA_HOME", java_home);
    command.env("GRADLE_JAVA_HOME", java_home);
    let current = env::var_os("PATH").unwrap_or_default();
    let mut paths = vec![java_home.join("bin")];
    paths.extend(env::split_paths(&current));
    if let Ok(joined) = env::join_paths(paths) {
        command.env("PATH", joined);
    }
}

fn copy_worker_mods(source: &Path, target: &Path) -> Result<usize> {
    let mut count = 0;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        if !entry.file_type()?.is_file() {
            continue;
        }
        let path = entry.path();
        if path
            .extension()
            .and_then(|value| value.to_str())
            .is_none_or(|value| !value.eq_ignore_ascii_case("jar"))
        {
            continue;
        }
        let filename = entry.file_name().to_string_lossy().to_string();
        let lower = filename.to_ascii_lowercase();
        if lower.starts_with("minesport-bridge-") || lower.starts_with("minesport-capture-bridge-")
        {
            continue;
        }
        if should_skip_runtime_worker_mod(&path, &filename) {
            continue;
        }
        let destination = target.join(entry.file_name());
        link_or_copy(&path, &destination)
            .with_context(|| format!("stage worker mod {}", path.display()))?;
        count += 1;
    }
    Ok(count)
}

fn link_or_copy(source: &Path, target: &Path) -> Result<()> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    let _ = fs::remove_file(target);
    if fs::hard_link(source, target).is_ok() {
        return Ok(());
    }
    fs::copy(source, target)
        .with_context(|| format!("copy {} to {}", source.display(), target.display()))?;
    Ok(())
}

fn should_skip_runtime_worker_mod(jar_path: &Path, filename: &str) -> bool {
    let lower = filename.to_ascii_lowercase();
    if lower.starts_with("crashassistant-") || lower.starts_with("crash-assistant-") {
        return true;
    }
    runtime_worker_fabric_mod_id(jar_path)
        .is_some_and(|id| id.eq_ignore_ascii_case("crash_assistant"))
}

fn runtime_worker_fabric_mod_id(jar_path: &Path) -> Option<String> {
    let file = File::open(jar_path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut entry = archive.by_name("fabric.mod.json").ok()?;
    let mut bytes = Vec::new();
    entry
        .by_ref()
        .take(FABRIC_MOD_JSON_LIMIT + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() as u64 > FABRIC_MOD_JSON_LIMIT {
        return None;
    }

    #[derive(Deserialize)]
    struct FabricMetadata {
        #[serde(default)]
        id: String,
    }
    let metadata: FabricMetadata = serde_json::from_slice(&bytes).ok()?;
    let id = metadata.id.trim();
    if id.is_empty() {
        None
    } else {
        Some(id.to_string())
    }
}

fn copy_directory(source: &Path, target: &Path) -> Result<()> {
    fs::create_dir_all(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let destination = target.join(entry.file_name());
        if kind.is_symlink() {
            continue;
        }
        if kind.is_dir() {
            copy_directory(&entry.path(), &destination)?;
        } else if kind.is_file() {
            fs::copy(entry.path(), destination)?;
        }
    }
    Ok(())
}

fn write_file(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, bytes).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

fn finish_child_after_capture(child: &mut Child, allow_graceful_exit: bool) {
    if allow_graceful_exit {
        let deadline = Instant::now() + Duration::from_secs(12);
        loop {
            match child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) => {}
                Err(_) => break,
            }
            if Instant::now() >= deadline {
                break;
            }
            thread::sleep(Duration::from_millis(50));
        }
    }
    stop_child(child);
}

fn stop_child(child: &mut Child) {
    let _ = runtime::terminate_process_tree(child, Duration::from_secs(5));
}

struct CaptureThreadGuard {
    cancel: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl CaptureThreadGuard {
    fn new(cancel: Arc<AtomicBool>, handle: thread::JoinHandle<()>) -> Self {
        Self {
            cancel,
            handle: Some(handle),
        }
    }
}

impl Drop for CaptureThreadGuard {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn runtime_worker_failure(
    workspace: &Path,
    version: &str,
    log_path: &Path,
    message: String,
    tail_lines: usize,
) -> anyhow::Error {
    let tail = tail_file(log_path, tail_lines);
    let diagnostics = preserve_runtime_worker_diagnostics(workspace, version);
    let mut detail = message;
    if let Some(path) = diagnostics {
        detail.push_str(&format!("\nDiagnostics preserved at: {}", path.display()));
    }
    if !tail.is_empty() {
        detail.push_str("\nRuntime worker tail:\n");
        detail.push_str(&tail);
    }
    anyhow!(detail)
}

fn preserve_runtime_worker_diagnostics(workspace: &Path, version: &str) -> Option<PathBuf> {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()?
        .as_millis();
    let destination = diagnostics::folder().join("runtime-workers").join(format!(
        "{}-{}-{stamp}",
        safe(version),
        std::process::id()
    ));
    fs::create_dir_all(&destination).ok()?;
    let files = [
        (workspace.join("runtime-worker.log"), "runtime-worker.log"),
        (
            workspace.join("run").join("logs").join("latest.log"),
            "minecraft-latest.log",
        ),
    ];
    let mut copied = false;
    for (source, name) in files {
        if !source.is_file() {
            continue;
        }
        if fs::copy(&source, destination.join(name)).is_ok() {
            copied = true;
        }
    }
    if copied {
        Some(destination)
    } else {
        let _ = fs::remove_dir_all(&destination);
        None
    }
}

fn tail_file(path: &Path, lines: usize) -> String {
    let Ok(file) = File::open(path) else {
        return String::new();
    };
    let values: Vec<String> = BufReader::new(file).lines().map_while(Result::ok).collect();
    values
        .into_iter()
        .rev()
        .take(lines)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect::<Vec<_>>()
        .join("\n")
}

fn safe(value: &str) -> String {
    let result: String = value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '_'
            }
        })
        .collect();
    if result.is_empty() {
        "unknown".into()
    } else {
        result
    }
}

struct WorkspaceCleanup(PathBuf);
impl Drop for WorkspaceCleanup {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

const SETTINGS_GRADLE: &str = r#"pluginManagement {
    repositories {
        maven { url 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}
rootProject.name = 'minesport-runtime-worker'
"#;

const BUILD_GRADLE: &str = r#"import groovy.json.JsonOutput

plugins {
    id 'fabric-loom' version '1.11.7'
    id 'java'
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    maven { url 'https://maven.fabricmc.net/' }
    mavenCentral()
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
}

tasks.register('minesportResolveRuntime') {
    dependsOn {
        def runTask = tasks.named('runClient').get()
        runTask.taskDependencies.getDependencies(runTask)
    }
    doLast {
        def runTask = tasks.named('runClient').get()
        def inherited = System.getenv()
        def environmentOverrides = runTask.environment
            .findAll { key, value -> inherited[key] != value?.toString() }
            .collectEntries { key, value -> [(key.toString()): value?.toString()] }
        def payload = [
            schema: 1,
            mainClass: runTask.mainClass.get(),
            classpath: runTask.classpath.files.collect { it.absolutePath },
            jvmArgs: runTask.allJvmArgs.collect { it.toString() },
            args: (runTask.args ?: []).collect { it.toString() },
            workingDir: runTask.workingDir.absolutePath,
            environmentOverrides: environmentOverrides,
        ]
        file('minesport-launch.json').text = JsonOutput.toJson(payload)
    }
}
"#;

fn gradle_properties(version: &str) -> String {
    format!(
        "minecraft_version={version}\nloader_version={LOADER_1_21_10}\nfabric_version={FABRIC_API_1_21_10}\norg.gradle.daemon=false\norg.gradle.parallel=false\norg.gradle.workers.max=1\norg.gradle.vfs.watch=false\n"
    )
}

#[cfg(windows)]
fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS);
}
#[cfg(not(windows))]
fn hide_console_window(_command: &mut Command) {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

    #[test]
    fn fast_worker_versions_are_pinned() {
        assert_eq!(MC_1_21_10, "1.21.10");
        assert_eq!(LOADER_1_21_10, "0.18.5");
        assert_eq!(FABRIC_API_1_21_10, "0.138.4+1.21.10");
        assert_eq!(LOOM_1_21_10, "1.11.7");
    }

    #[test]
    fn workspace_uses_embedded_gradle_wrapper() {
        assert!(!GRADLEW_BAT.is_empty());
        assert!(!GRADLE_WRAPPER_JAR.is_empty());
    }

    #[test]
    fn runtime_gradle_properties_do_not_reserve_a_huge_build_heap() {
        let properties = gradle_properties(MC_1_21_10);
        assert!(!properties.contains("org.gradle.jvmargs=-Xmx1536m"));
        assert!(properties.contains("org.gradle.daemon=false"));
        assert!(properties.contains("org.gradle.workers.max=1"));
        assert!(properties.contains("org.gradle.vfs.watch=false"));
    }

    #[test]
    fn capture_watchdog_covers_startup_and_mid_capture_stalls() {
        let now = Instant::now();
        assert!(!capture_has_stalled(
            None,
            now - CAPTURE_FIRST_PROGRESS_TIMEOUT + Duration::from_millis(1),
            now,
        ));
        assert!(capture_has_stalled(
            None,
            now - CAPTURE_FIRST_PROGRESS_TIMEOUT,
            now,
        ));
        assert!(!capture_has_stalled(
            Some(now - CAPTURE_STALL_TIMEOUT + Duration::from_millis(1)),
            now - CAPTURE_FIRST_PROGRESS_TIMEOUT,
            now,
        ));
        assert!(capture_has_stalled(
            Some(now - CAPTURE_STALL_TIMEOUT),
            now - CAPTURE_FIRST_PROGRESS_TIMEOUT,
            now,
        ));
    }

    #[test]
    fn exact_capture_progress_maps_to_runtime_segment() {
        assert_eq!(capture_progress_percent(0, 1000), 62);
        assert_eq!(capture_progress_percent(500, 1000), 78);
        assert_eq!(capture_progress_percent(1000, 1000), 94);
        assert_eq!(capture_progress_percent(1200, 1000), 94);
    }

    #[test]
    fn direct_launch_keeps_loom_flags_but_owns_resource_caps() {
        assert!(managed_runtime_jvm_arg("-Xmx2G"));
        assert!(managed_runtime_jvm_arg("-XX:ActiveProcessorCount=8"));
        assert!(managed_runtime_jvm_arg("-Xshare:off"));
        assert!(managed_runtime_jvm_arg("-XX:+AutoCreateSharedArchive"));
        assert!(managed_runtime_jvm_arg("-XX:SharedArchiveFile=old.jsa"));
        assert!(!managed_runtime_jvm_arg("-Dfabric.dli.env=client"));
    }

    #[test]
    fn direct_launch_retargets_only_the_game_run_directory() {
        let manifest = DirectLaunchManifest {
            schema: DIRECT_LAUNCH_PROFILE_SCHEMA,
            main_class: "example.Main".into(),
            classpath: vec!["example.jar".into()],
            jvm_args: vec![],
            args: vec![
                "--gameDir".into(),
                "/profile/run".into(),
                "--assetsDir".into(),
                "/cache/assets".into(),
            ],
            working_dir: "/profile/run".into(),
            environment_overrides: BTreeMap::new(),
        };
        assert_eq!(
            direct_runtime_args(&manifest, Path::new("/worker/run")),
            vec!["--gameDir", "/worker/run", "--assetsDir", "/cache/assets",]
        );
    }

    #[test]
    fn fake_oracle_javapath_parent_is_not_a_jdk() {
        let fake = Path::new(r"C:\Program Files\Common Files\Oracle\Java");
        assert!(!toolchain::valid_jdk_home(fake, 21));
    }

    #[test]
    fn manifest_supported_versions_are_accepted_by_worker_front_door() {
        assert!(bridge_family::is_supported(BridgeFamily::Fabric, "1.19.4"));
        assert!(bridge_family::is_supported(BridgeFamily::Fabric, "1.21.11"));
        assert!(bridge_family::is_supported(BridgeFamily::Fabric, "26.2"));
        assert!(bridge_family::is_supported(BridgeFamily::Forge, "1.21.10"));
        assert!(bridge_family::is_supported(
            BridgeFamily::NeoForge,
            "1.21.9"
        ));
        assert!(bridge_family::is_supported(BridgeFamily::Quilt, "1.21.8"));
    }

    #[test]
    fn loader_family_separates_non_fabric_cache_identity() {
        let raw = "0123456789abcdef";
        assert_eq!(loader_cache_fingerprint(BridgeFamily::Fabric, raw), raw);
        assert_eq!(
            loader_cache_fingerprint(BridgeFamily::Forge, raw),
            "forge-0123456789abcdef"
        );
        assert_eq!(
            loader_cache_fingerprint(BridgeFamily::NeoForge, raw),
            "neoforge-0123456789abcdef"
        );
        assert_eq!(
            loader_cache_fingerprint(BridgeFamily::Quilt, raw),
            "quilt-0123456789abcdef"
        );
    }

    #[test]
    fn worker_diagnostics_path_is_durable_not_cache_owned() {
        let path = diagnostics::folder().join("runtime-workers");
        assert!(path.starts_with(runtime::data_root()));
        assert!(!path.starts_with(runtime::cache_root()));
    }

    #[test]
    fn renamed_crash_assistant_is_skipped_by_fabric_mod_id() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-crash-assistant-id-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let jar = root.join("totally-normal-mod.jar");
        let file = File::create(&jar).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        writer.start_file("fabric.mod.json", options).unwrap();
        writer
            .write_all(br#"{"schemaVersion":1,"id":"crash_assistant","version":"1.0.0"}"#)
            .unwrap();
        writer.finish().unwrap();

        assert_eq!(
            runtime_worker_fabric_mod_id(&jar).as_deref(),
            Some("crash_assistant")
        );
        assert!(should_skip_runtime_worker_mod(
            &jar,
            "totally-normal-mod.jar"
        ));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn crash_assistant_filename_fallback_survives_unreadable_jar() {
        assert!(should_skip_runtime_worker_mod(
            Path::new("missing.jar"),
            "CrashAssistant-fabric-26.2.jar"
        ));
        assert!(should_skip_runtime_worker_mod(
            Path::new("missing.jar"),
            "crash-assistant-26.2.jar"
        ));
    }
}
