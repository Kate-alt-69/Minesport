#[allow(dead_code)]
#[path = "../bridge_compat.rs"]
mod bridge_compat;
#[allow(dead_code)]
#[path = "../runtime.rs"]
mod runtime;
#[allow(dead_code)]
#[path = "../toolchain.rs"]
mod toolchain;

use anyhow::{Context, Result, anyhow, bail};
use std::{
    env,
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};

fn main() -> Result<()> {
    let mut version = None;
    let mut output = None;
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-version" | "--version" => version = args.next(),
            "-output" | "--output" => output = args.next().map(PathBuf::from),
            "-h" | "--help" => {
                println!("Minesport Rust Bridge prepare helper\n\nUsage:\n  bridge-prepare --version <minecraft> --output <directory>");
                return Ok(());
            }
            other => bail!("unknown argument {other:?}"),
        }
    }

    let version = version.ok_or_else(|| anyhow!("--version is required"))?;
    let version = bridge_compat::normalize_version(&version).ok_or_else(|| anyhow!("invalid Minecraft version"))?;
    if !bridge_compat::is_supported(&version) {
        bail!("no embedded Minesport compatibility recipe for Minecraft {version}");
    }
    let output = output.ok_or_else(|| anyhow!("--output is required"))?;
    fs::create_dir_all(&output).with_context(|| format!("create {}", output.display()))?;

    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join("ci-recipes")
        .join(format!("{}-{}-{stamp}", safe(&version), std::process::id()));
    let _cleanup = Cleanup(workspace.clone());

    eprintln!("[Minesport] Preparing Rust compatibility recipe for Minecraft {version}");
    let prepared = bridge_compat::prepare_source(&version, &workspace, |progress| {
        let detail = if progress.detail.is_empty() { progress.stage } else { format!("{} · {}", progress.stage, progress.detail) };
        eprintln!("[{:>3}%] {detail}", progress.percent.clamp(0, 100));
    })?;

    let java_home = toolchain::ensure_jdk(prepared.java, |progress| {
        eprintln!("[{:>3}%] {}", progress.percent.clamp(0, 100), progress.message);
    })?;
    eprintln!("[Minesport] Compiling {} with JDK {} at {}", prepared.profile_id, prepared.java, java_home.display());

    let jar = compile_bridge(&prepared.workspace, &java_home)?;
    let destination = output.join(format!("minesport-bridge-{}.jar", safe(&version)));
    fs::copy(&jar, &destination)
        .with_context(|| format!("copy compiled Bridge {} to {}", jar.display(), destination.display()))?;
    if !destination.is_file() || fs::metadata(&destination)?.len() == 0 {
        bail!("compiled compatibility Bridge is missing or empty: {}", destination.display());
    }
    println!("{}", destination.display());
    Ok(())
}

fn compile_bridge(workspace: &Path, java_home: &Path) -> Result<PathBuf> {
    let mut command = if cfg!(windows) {
        let mut command = Command::new("cmd.exe");
        command.args(["/D", "/S", "/C", "gradlew.bat --no-daemon --stacktrace clean build"]);
        command
    } else {
        let wrapper = workspace.join("gradlew");
        #[cfg(unix)] {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(metadata) = fs::metadata(&wrapper) {
                let mut permissions = metadata.permissions();
                permissions.set_mode(0o755);
                let _ = fs::set_permissions(&wrapper, permissions);
            }
        }
        let mut command = Command::new(wrapper);
        command.args(["--no-daemon", "--stacktrace", "clean", "build"]);
        command
    };
    command.current_dir(workspace).stdout(Stdio::inherit()).stderr(Stdio::inherit());
    sanitize_java_environment(&mut command, java_home);
    command.env("GRADLE_USER_HOME", runtime::cache_root().join("gradle"));
    let status = command.status().context("launch Gradle compatibility Bridge build")?;
    if !status.success() {
        bail!("Gradle compatibility Bridge build failed with {status}");
    }

    let libs = workspace.join("build").join("libs");
    let mut candidates: Vec<PathBuf> = fs::read_dir(&libs)
        .with_context(|| format!("read {}", libs.display()))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path.extension().and_then(|value| value.to_str()).is_some_and(|value| value.eq_ignore_ascii_case("jar"))
                && path.file_name().and_then(|value| value.to_str()).is_some_and(|value| !value.to_ascii_lowercase().contains("sources"))
        })
        .collect();
    candidates.sort();
    candidates.pop().ok_or_else(|| anyhow!("Gradle reported success but no non-sources Bridge JAR was produced"))
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
    if let Ok(joined) = env::join_paths(paths) {
        command.env("PATH", joined);
    }
}

fn safe(value: &str) -> String {
    let result: String = value
        .chars()
        .map(|ch| if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') { ch } else { '_' })
        .collect();
    if result.is_empty() { "unknown".to_string() } else { result }
}

struct Cleanup(PathBuf);
impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
