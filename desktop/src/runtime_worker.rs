use crate::{bridge_compat, diagnostics, registry, runtime, toolchain};
use anyhow::{Context, Result, anyhow, bail};
use serde::Deserialize;
use std::{
    env,
    fs::{self, File},
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{Arc, atomic::{AtomicBool, Ordering}, mpsc},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const GRADLEW_SH: &[u8] = include_bytes!("../../bridge/gradlew");
const GRADLEW_BAT: &[u8] = include_bytes!("../../bridge/gradlew.bat");
const GRADLE_WRAPPER_JAR: &[u8] = include_bytes!("../../bridge/gradle/wrapper/gradle-wrapper.jar");
const GRADLE_WRAPPER_PROPERTIES: &[u8] = include_bytes!("../../bridge/gradle/wrapper/gradle-wrapper.properties");

const MC_1_21_10: &str = "1.21.10";
const LOADER_1_21_10: &str = "0.18.5";
const FABRIC_API_1_21_10: &str = "0.138.4+1.21.10";
const LOOM_1_21_10: &str = "1.11.7";
const FABRIC_MOD_JSON_LIMIT: u64 = 1 << 20;

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

pub fn prepare_full_registry<F>(version: &str, mods_path: &Path, force: bool, progress: F) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
    prepare_full_registry_cancellable(version, mods_path, force, Arc::new(AtomicBool::new(false)), progress)
}

pub fn prepare_full_registry_cancellable<F>(
    version: &str,
    mods_path: &Path,
    force: bool,
    cancel: Arc<AtomicBool>,
    mut progress: F,
) -> Result<CacheResult>
where
    F: FnMut(Progress) + Send,
{
    let version = bridge_compat::normalize_version(version)
        .ok_or_else(|| anyhow!("could not determine Minecraft version"))?;
    if !bridge_compat::is_supported(&version) {
        bail!("Minesport has no embedded Fabric compatibility recipe for Minecraft {version}");
    }
    if !mods_path.is_dir() { bail!("mods folder is unavailable: {}", mods_path.display()); }
    if cancel.load(Ordering::Relaxed) { bail!("runtime model-cache generation cancelled"); }

    // Keep generated cache/toolchain/workspace paths alive for the entire
    // operation. Destructive cleanup takes the write side of the same lease.
    let _cache_lease = runtime::acquire_generated_cache_lease()?;

    progress(Progress { percent: 1, message: "Verifying exact mod contents…".into() });
    let fingerprint = registry::mods_fingerprint(mods_path)?;
    if cancel.load(Ordering::Relaxed) { bail!("runtime model-cache generation cancelled"); }

    let cache_root = runtime::cache_root();
    let existing = registry::snapshot_path(&cache_root, &version, &fingerprint);
    if !force && registry::snapshot_exists(&cache_root, &version, &fingerprint) {
        progress(Progress { percent: 100, message: "Full runtime registry already ready".into() });
        return Ok(CacheResult { fingerprint, registry_path: existing, reused: true });
    }

    progress(Progress { percent: 4, message: "Preparing local isolated Minecraft worker…".into() });
    let plan = create_workspace(&version, mods_path, |update| {
        progress(Progress {
            percent: update.percent.clamp(4, 38),
            message: if update.detail.is_empty() { update.stage } else { format!("{} · {}", update.stage, update.detail) },
        });
    })?;
    let workspace = plan.workspace;
    let cleanup = WorkspaceCleanup(workspace.clone());
    if cancel.load(Ordering::Relaxed) { bail!("runtime model-cache generation cancelled"); }

    progress(Progress { percent: 40, message: format!("Preparing JDK {} for Minecraft {version}…", plan.java) });
    let java_home = toolchain::ensure_jdk(plan.java, |update| {
        progress(Progress { percent: update.percent.clamp(40, 54), message: update.message });
    })?;
    if cancel.load(Ordering::Relaxed) { bail!("runtime model-cache generation cancelled"); }

    let (listen_tx, listen_rx) = mpsc::sync_channel::<Result<()>>(1);
    let (capture_tx, capture_rx) = mpsc::sync_channel::<Result<PathBuf>>(1);
    let (capture_progress_tx, capture_progress_rx) = mpsc::channel::<usize>();
    let capture_root = cache_root.clone();
    let capture_version = version.clone();
    let capture_fingerprint = fingerprint.clone();
    thread::spawn(move || {
        let mut announced = false;
        let result = registry::capture_once(
            registry::DEFAULT_ADDRESS,
            &capture_root,
            &capture_version,
            &capture_fingerprint,
            |notice| match notice {
                registry::CaptureNotice::Listening(_) if !announced => {
                    announced = true;
                    let _ = listen_tx.send(Ok(()));
                }
                registry::CaptureNotice::Progress { blocks } => {
                    let _ = capture_progress_tx.send(blocks);
                }
                _ => {}
            },
        );
        if !announced { let _ = listen_tx.send(Err(anyhow!("registry receiver stopped before listening"))); }
        let _ = capture_tx.send(result);
    });

    listen_rx.recv_timeout(Duration::from_secs(5)).context("wait for Rust runtime registry receiver")??;

    progress(Progress { percent: 55, message: format!("Receiver ready · starting Minecraft {version} with JDK {}", plan.java) });
    let log_path = workspace.join("runtime-worker.log");
    let mut child = start_gradle_worker(&workspace, &log_path, &java_home)?;
    progress(Progress { percent: 62, message: format!("Minecraft {version} worker started · loading baked model registry") });

    let deadline = Instant::now() + Duration::from_secs(10 * 60);
    let mut last_captured_blocks = 0usize;
    loop {
        if cancel.load(Ordering::Relaxed) {
            stop_child(&mut child);
            bail!("runtime model-cache generation cancelled");
        }

        while let Ok(blocks) = capture_progress_rx.try_recv() {
            if blocks <= last_captured_blocks { continue; }
            last_captured_blocks = blocks;
            // The wire protocol currently reports completed block count but not
            // total progress. Keep the bar moving through the capture segment
            // without pretending the heuristic is an exact percentage.
            let capture_percent = (62 + (blocks / 64) as i32).clamp(63, 94);
            progress(Progress {
                percent: capture_percent,
                message: format!("Receiving baked model registry · {blocks} block types captured"),
            });
        }

        match capture_rx.try_recv() {
            Ok(Ok(path)) => {
                progress(Progress { percent: 96, message: "Full registry received · stopping disposable Minecraft client…".into() });
                stop_child(&mut child);
                progress(Progress { percent: 100, message: "Full registered Minecraft block/model registry ready".into() });
                drop(cleanup);
                return Ok(CacheResult { fingerprint, registry_path: path, reused: false });
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
                    progress(Progress { percent: 100, message: "Full registered Minecraft block/model registry ready".into() });
                    return Ok(CacheResult { fingerprint, registry_path: path, reused: false });
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
                        format!("runtime Minecraft worker exited with {status} before the full registry was received"),
                        80,
                    ));
                }
            }
        }

        if Instant::now() >= deadline {
            stop_child(&mut child);
            bail!("runtime model-cache generation timed out after 10 minutes");
        }
        thread::sleep(Duration::from_millis(150));
    }
}

fn create_workspace<F>(version: &str, mods_path: &Path, mut progress: F) -> Result<WorkspacePlan>
where
    F: FnMut(bridge_compat::CompatProgress),
{
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join("runtime-workers")
        .join(format!("{}-{}-{stamp}", safe(version), std::process::id()));

    let java = if version == MC_1_21_10 {
        create_fast_bundled_workspace(&workspace, version)?;
        21
    } else {
        let prepared = bridge_compat::prepare_source(version, &workspace, &mut progress)?;
        prepared.java
    };

    let run_dir = workspace.join("run");
    let run_mods = run_dir.join("mods");
    fs::create_dir_all(&run_mods)?;

    // The canonical 1.21.10 fast workspace intentionally has no Bridge source
    // project. Its already-built Bridge is loaded as a local run mod instead.
    if version == MC_1_21_10 {
        let bridge = runtime::materialize_bundled_bridge()?;
        fs::copy(&bridge, run_mods.join("minesport-bridge-0.2.0.jar"))
            .with_context(|| format!("copy embedded Bridge {}", bridge.display()))?;
    }

    let count = copy_worker_mods(mods_path, &run_mods)?;
    progress(bridge_compat::CompatProgress {
        percent: 36,
        stage: "Preparing worker".into(),
        detail: format!("Copied {count} installed mod JAR(s) into disposable runtime"),
    });

    if let Some(instance) = mods_path.parent() {
        let config = instance.join("config");
        if config.is_dir() { copy_directory(&config, &run_dir.join("config"))?; }
    }
    Ok(WorkspacePlan { workspace, java })
}

fn create_fast_bundled_workspace(workspace: &Path, version: &str) -> Result<()> {
    let wrapper_dir = workspace.join("gradle").join("wrapper");
    fs::create_dir_all(&wrapper_dir)?;
    write_file(&workspace.join("gradlew"), GRADLEW_SH)?;
    write_file(&workspace.join("gradlew.bat"), GRADLEW_BAT)?;
    write_file(&wrapper_dir.join("gradle-wrapper.jar"), GRADLE_WRAPPER_JAR)?;
    write_file(&wrapper_dir.join("gradle-wrapper.properties"), GRADLE_WRAPPER_PROPERTIES)?;
    write_file(&workspace.join("settings.gradle"), SETTINGS_GRADLE.as_bytes())?;
    write_file(&workspace.join("build.gradle"), BUILD_GRADLE.as_bytes())?;
    write_file(&workspace.join("gradle.properties"), gradle_properties(version).as_bytes())?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(workspace.join("gradlew"), fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}

fn start_gradle_worker(workspace: &Path, log_path: &Path, java_home: &Path) -> Result<Child> {
    let stdout = File::create(log_path).with_context(|| format!("create {}", log_path.display()))?;
    let stderr = stdout.try_clone()?;
    let mut command = if cfg!(windows) {
        let mut command = Command::new("cmd.exe");
        command.args(["/D", "/S", "/C", "gradlew.bat --no-daemon --console=plain runClient"]);
        command
    } else {
        let mut command = Command::new(workspace.join("gradlew"));
        command.args(["--no-daemon", "--console=plain", "runClient"]);
        command
    };
    command.current_dir(workspace).stdout(Stdio::from(stdout)).stderr(Stdio::from(stderr));
    sanitize_java_environment(&mut command, java_home);
    command.env("MINESPORT_BRIDGE_PORT", "25590");
    command.env("MINESPORT_BRIDGE_MODE", "all");
    command.env("MINESPORT_BRIDGE_WORKER", "1");
    command.env("GRADLE_USER_HOME", runtime::cache_root().join("gradle"));
    hide_console_window(&mut command);
    command.spawn().with_context(|| format!("start isolated Fabric/Loom runtime worker with {}", java_home.display()))
}

fn sanitize_java_environment(command: &mut Command, java_home: &Path) {
    for (key, _) in env::vars_os() {
        let name = key.to_string_lossy();
        if name.eq_ignore_ascii_case("JAVA_HOME") || name.eq_ignore_ascii_case("JDK_HOME") || name.eq_ignore_ascii_case("GRADLE_JAVA_HOME") {
            command.env_remove(key);
        }
    }
    command.env("JAVA_HOME", java_home);
    command.env("GRADLE_JAVA_HOME", java_home);
    let current = env::var_os("PATH").unwrap_or_default();
    let mut paths = vec![java_home.join("bin")];
    paths.extend(env::split_paths(&current));
    if let Ok(joined) = env::join_paths(paths) { command.env("PATH", joined); }
}

fn copy_worker_mods(source: &Path, target: &Path) -> Result<usize> {
    let mut count = 0;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        if !entry.file_type()?.is_file() { continue; }
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()).is_none_or(|value| !value.eq_ignore_ascii_case("jar")) { continue; }
        let filename = entry.file_name().to_string_lossy().to_string();
        let lower = filename.to_ascii_lowercase();
        if lower.starts_with("minesport-bridge-") || lower.starts_with("minesport-capture-bridge-") { continue; }
        if should_skip_runtime_worker_mod(&path, &filename) { continue; }
        fs::copy(&path, target.join(entry.file_name())).with_context(|| format!("copy worker mod {}", path.display()))?;
        count += 1;
    }
    Ok(count)
}

// Crash Assistant treats Minesport's intentionally short-lived registry worker
// like a crashed normal client and may spawn its own UI. It contributes nothing
// to block/model registration, so identify it by Fabric mod ID first, with the
// filename check retained as a fallback for malformed/unreadable JARs.
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
    if id.is_empty() { None } else { Some(id.to_string()) }
}

fn copy_directory(source: &Path, target: &Path) -> Result<()> {
    fs::create_dir_all(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let destination = target.join(entry.file_name());
        if kind.is_symlink() { continue; }
        if kind.is_dir() { copy_directory(&entry.path(), &destination)?; }
        else if kind.is_file() { fs::copy(entry.path(), destination)?; }
    }
    Ok(())
}

fn write_file(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    fs::write(path, bytes).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

fn stop_child(child: &mut Child) {
    if child.try_wait().ok().flatten().is_none() {
        let _ = child.kill();
        let _ = child.wait();
    }
}

fn runtime_worker_failure(workspace: &Path, version: &str, log_path: &Path, message: String, tail_lines: usize) -> anyhow::Error {
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
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).ok()?.as_millis();
    let destination = diagnostics::folder()
        .join("runtime-workers")
        .join(format!("{}-{}-{stamp}", safe(version), std::process::id()));
    fs::create_dir_all(&destination).ok()?;

    let files = [
        (workspace.join("runtime-worker.log"), "runtime-worker.log"),
        (workspace.join("run").join("logs").join("latest.log"), "minecraft-latest.log"),
    ];
    let mut copied = false;
    for (source, name) in files {
        if !source.is_file() { continue; }
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
    let Ok(file) = File::open(path) else { return String::new(); };
    let values: Vec<String> = BufReader::new(file).lines().map_while(Result::ok).collect();
    values.into_iter().rev().take(lines).collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join("\n")
}

fn safe(value: &str) -> String {
    let result: String = value.chars().map(|ch| if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') { ch } else { '_' }).collect();
    if result.is_empty() { "unknown".into() } else { result }
}

struct WorkspaceCleanup(PathBuf);
impl Drop for WorkspaceCleanup {
    fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); }
}

const SETTINGS_GRADLE: &str = r#"pluginManagement {
    repositories {
        maven { url 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}
rootProject.name = 'minesport-runtime-worker'
"#;

const BUILD_GRADLE: &str = r#"plugins {
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
"#;

fn gradle_properties(version: &str) -> String {
    format!("minecraft_version={version}\nloader_version={LOADER_1_21_10}\nfabric_version={FABRIC_API_1_21_10}\norg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\norg.gradle.parallel=false\n")
}

#[cfg(windows)]
fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
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
    fn fake_oracle_javapath_parent_is_not_a_jdk() {
        let fake = Path::new(r"C:\Program Files\Common Files\Oracle\Java");
        assert!(!toolchain::valid_jdk_home(fake, 21));
    }

    #[test]
    fn manifest_supported_versions_are_accepted_by_worker_front_door() {
        assert!(bridge_compat::is_supported("1.19.4"));
        assert!(bridge_compat::is_supported("1.21.11"));
        assert!(bridge_compat::is_supported("26.2"));
    }

    #[test]
    fn worker_diagnostics_path_is_durable_not_cache_owned() {
        let path = diagnostics::folder().join("runtime-workers");
        assert!(path.starts_with(runtime::data_root()));
        assert!(!path.starts_with(runtime::cache_root()));
    }

    #[test]
    fn renamed_crash_assistant_is_skipped_by_fabric_mod_id() {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = env::temp_dir().join(format!("minesport-crash-assistant-id-{}-{stamp}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let jar = root.join("totally-normal-mod.jar");
        let file = File::create(&jar).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        writer.start_file("fabric.mod.json", options).unwrap();
        writer.write_all(br#"{"schemaVersion":1,"id":"crash_assistant","version":"1.0.0"}"#).unwrap();
        writer.finish().unwrap();

        assert_eq!(runtime_worker_fabric_mod_id(&jar).as_deref(), Some("crash_assistant"));
        assert!(should_skip_runtime_worker_mod(&jar, "totally-normal-mod.jar"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn crash_assistant_filename_fallback_survives_unreadable_jar() {
        assert!(should_skip_runtime_worker_mod(Path::new("missing.jar"), "CrashAssistant-fabric-26.2.jar"));
        assert!(should_skip_runtime_worker_mod(Path::new("missing.jar"), "crash-assistant-26.2.jar"));
    }
}