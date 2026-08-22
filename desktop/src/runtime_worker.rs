use crate::{registry, runtime};
use anyhow::{Context, Result, anyhow, bail};
use std::{
    env,
    fs::{self, File},
    io::{BufRead, BufReader},
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
    let version = version.trim();
    if version != MC_1_21_10 {
        bail!("Rust fast runtime worker currently supports the canonical bundled Minecraft 1.21.10 path; compatibility-profile port for {version} is still in progress");
    }
    if !mods_path.is_dir() { bail!("mods folder is unavailable: {}", mods_path.display()); }
    if cancel.load(Ordering::Relaxed) { bail!("runtime model-cache generation cancelled"); }

    progress(Progress { percent: 1, message: "Verifying exact mod contents…".into() });
    let fingerprint = registry::mods_fingerprint(mods_path)?;
    if cancel.load(Ordering::Relaxed) { bail!("runtime model-cache generation cancelled"); }

    let cache_root = runtime::cache_root();
    let existing = registry::snapshot_path(&cache_root, version, &fingerprint);
    if !force && registry::snapshot_exists(&cache_root, version, &fingerprint) {
        progress(Progress { percent: 100, message: "Full runtime registry already ready".into() });
        return Ok(CacheResult { fingerprint, registry_path: existing, reused: true });
    }

    progress(Progress { percent: 5, message: "Preparing local isolated Minecraft worker…".into() });
    let workspace = create_workspace(version, mods_path)?;
    let cleanup = WorkspaceCleanup(workspace.clone());

    let (listen_tx, listen_rx) = mpsc::sync_channel::<Result<()>>(1);
    let (capture_tx, capture_rx) = mpsc::sync_channel::<Result<PathBuf>>(1);
    let capture_root = cache_root.clone();
    let capture_version = version.to_string();
    let capture_fingerprint = fingerprint.clone();
    thread::spawn(move || {
        let mut announced = false;
        let result = registry::capture_once(
            registry::DEFAULT_ADDRESS,
            &capture_root,
            &capture_version,
            &capture_fingerprint,
            |notice| {
                if matches!(notice, registry::CaptureNotice::Listening(_)) && !announced {
                    announced = true;
                    let _ = listen_tx.send(Ok(()));
                }
            },
        );
        if !announced { let _ = listen_tx.send(Err(anyhow!("registry receiver stopped before listening"))); }
        let _ = capture_tx.send(result);
    });

    listen_rx.recv_timeout(Duration::from_secs(5)).context("wait for Rust runtime registry receiver")??;

    progress(Progress { percent: 12, message: "Receiver ready · starting local Fabric/Loom client…".into() });
    let log_path = workspace.join("runtime-worker.log");
    let mut child = start_gradle_worker(&workspace, &log_path)?;
    progress(Progress { percent: 30, message: format!("Minecraft {version} worker started · loading full registered model registry") });

    let deadline = Instant::now() + Duration::from_secs(10 * 60);
    loop {
        if cancel.load(Ordering::Relaxed) {
            stop_child(&mut child);
            bail!("runtime model-cache generation cancelled");
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
                let tail = tail_file(&log_path, 60);
                if tail.is_empty() { return Err(error); }
                return Err(anyhow!("{error:#}\nRuntime worker tail:\n{tail}"));
            }
            Err(mpsc::TryRecvError::Disconnected) => {
                stop_child(&mut child);
                bail!("runtime registry receiver terminated unexpectedly");
            }
            Err(mpsc::TryRecvError::Empty) => {}
        }

        if let Some(status) = child.try_wait().context("poll isolated Minecraft worker")? {
            match capture_rx.recv_timeout(Duration::from_secs(2)) {
                Ok(Ok(path)) => {
                    progress(Progress { percent: 100, message: "Full registered Minecraft block/model registry ready".into() });
                    return Ok(CacheResult { fingerprint, registry_path: path, reused: false });
                }
                Ok(Err(error)) => return Err(error),
                Err(_) => {
                    let tail = tail_file(&log_path, 80);
                    bail!("runtime Minecraft worker exited with {status} before the full registry was received\n{tail}");
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

fn create_workspace(version: &str, mods_path: &Path) -> Result<PathBuf> {
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let workspace = runtime::cache_root().join("bridge-build").join("runtime-workers").join(format!("{}-{}-{stamp}", safe(version), std::process::id()));
    let wrapper_dir = workspace.join("gradle").join("wrapper");
    let run_mods = workspace.join("run").join("mods");
    fs::create_dir_all(&wrapper_dir)?;
    fs::create_dir_all(&run_mods)?;

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

    let bridge = runtime::materialize_bundled_bridge()?;
    fs::copy(&bridge, run_mods.join("minesport-bridge-0.2.0.jar"))
        .with_context(|| format!("copy embedded Bridge {}", bridge.display()))?;
    copy_worker_mods(mods_path, &run_mods)?;

    if let Some(instance) = mods_path.parent() {
        let config = instance.join("config");
        if config.is_dir() { copy_directory(&config, &workspace.join("run").join("config"))?; }
    }
    Ok(workspace)
}

fn start_gradle_worker(workspace: &Path, log_path: &Path) -> Result<Child> {
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
    sanitize_java_environment(&mut command);
    command.env("MINESPORT_BRIDGE_PORT", "25590");
    command.env("MINESPORT_BRIDGE_MODE", "all");
    command.env("MINESPORT_BRIDGE_WORKER", "1");
    command.env("GRADLE_USER_HOME", runtime::cache_root().join("gradle"));
    hide_console_window(&mut command);
    command.spawn().context("start isolated local Fabric/Loom runtime worker")
}

fn sanitize_java_environment(command: &mut Command) {
    for (key, _) in env::vars_os() {
        let name = key.to_string_lossy();
        if name.eq_ignore_ascii_case("JAVA_HOME") || name.eq_ignore_ascii_case("JDK_HOME") || name.eq_ignore_ascii_case("GRADLE_JAVA_HOME") {
            command.env_remove(key);
        }
    }

    // Never manufacture JAVA_HOME from Oracle's Windows javapath shim. Only
    // publish JAVA_HOME after proving the candidate contains a real JDK.
    if let Some(home) = resolve_jdk_home() {
        command.env("JAVA_HOME", &home);
        command.env("GRADLE_JAVA_HOME", &home);
        let current = env::var_os("PATH").unwrap_or_default();
        let mut paths = vec![home.join("bin")];
        paths.extend(env::split_paths(&current));
        if let Ok(joined) = env::join_paths(paths) { command.env("PATH", joined); }
    }
}

fn resolve_jdk_home() -> Option<PathBuf> {
    for key in ["JAVA_HOME", "JDK_HOME", "GRADLE_JAVA_HOME"] {
        if let Some(home) = env::var_os(key).map(PathBuf::from) {
            if valid_jdk_home(&home) { return Some(home); }
        }
    }

    if let Some(javac) = find_on_path(if cfg!(windows) { "javac.exe" } else { "javac" }) {
        if let Some(home) = javac.parent().and_then(Path::parent).map(Path::to_path_buf) {
            if valid_jdk_home(&home) { return Some(home); }
        }
    }

    // `java.home` resolves through launch shims to the actual runtime location,
    // unlike simply taking parent-of-parent of a PATH entry. Accept it only if
    // the resolved home also contains javac.
    if let Some(java) = find_on_path(if cfg!(windows) { "java.exe" } else { "java" }) {
        let output = Command::new(java).args(["-XshowSettings:properties", "-version"]).output().ok()?;
        let text = String::from_utf8_lossy(&output.stderr);
        for line in text.lines() {
            let trimmed = line.trim();
            let Some(value) = trimmed.strip_prefix("java.home =") else { continue; };
            let home = PathBuf::from(value.trim());
            if valid_jdk_home(&home) { return Some(home); }
        }
    }
    None
}

fn valid_jdk_home(home: &Path) -> bool {
    let bin = home.join("bin");
    bin.join(if cfg!(windows) { "java.exe" } else { "java" }).is_file()
        && bin.join(if cfg!(windows) { "javac.exe" } else { "javac" }).is_file()
}

fn find_on_path(executable: &str) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    env::split_paths(&path).map(|entry| entry.join(executable)).find(|candidate| candidate.is_file())
}

fn copy_worker_mods(source: &Path, target: &Path) -> Result<usize> {
    let mut count = 0;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        if !entry.file_type()?.is_file() { continue; }
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()).is_none_or(|value| !value.eq_ignore_ascii_case("jar")) { continue; }
        let lower = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if lower.starts_with("minesport-capture-bridge-") || lower.starts_with("crashassistant-") || lower.starts_with("crash-assistant-") { continue; }
        fs::copy(&path, target.join(entry.file_name())).with_context(|| format!("copy worker mod {}", path.display()))?;
        count += 1;
    }
    Ok(count)
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
        assert!(!valid_jdk_home(fake));
    }
}
