use crate::runtime;
use anyhow::{Context, Result, anyhow, bail};
use std::{
    env,
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

pub fn compile_bridge(workspace: &Path, java_home: &Path, clean: bool) -> Result<PathBuf> {
    let mut command = if cfg!(windows) {
        let mut command = Command::new("cmd.exe");
        let task = if clean {
            "gradlew.bat --no-daemon --stacktrace clean build"
        } else {
            "gradlew.bat --no-daemon --stacktrace build"
        };
        command.args(["/D", "/S", "/C", task]);
        command
    } else {
        let wrapper = workspace.join("gradlew");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(metadata) = fs::metadata(&wrapper) {
                let mut permissions = metadata.permissions();
                permissions.set_mode(0o755);
                let _ = fs::set_permissions(&wrapper, permissions);
            }
        }
        let mut command = Command::new(wrapper);
        command.args(["--no-daemon", "--stacktrace"]);
        if clean {
            command.arg("clean");
        }
        command.arg("build");
        command
    };

    command
        .current_dir(workspace)
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());
    sanitize_java_environment(&mut command, java_home);
    command.env("GRADLE_USER_HOME", runtime::cache_root().join("gradle"));

    let status = command
        .status()
        .with_context(|| format!("launch Gradle compatibility Bridge build in {}", workspace.display()))?;
    if !status.success() {
        bail!("Gradle compatibility Bridge build failed with {status}");
    }

    find_built_bridge(workspace)
}

fn sanitize_java_environment(command: &mut Command, java_home: &Path) {
    for (key, _) in env::vars_os() {
        let name = key.to_string_lossy();
        if name.eq_ignore_ascii_case("JAVA_HOME")
            || name.eq_ignore_ascii_case("JDK_HOME")
            || name.eq_ignore_ascii_case("GRADLE_JAVA_HOME")
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

fn find_built_bridge(workspace: &Path) -> Result<PathBuf> {
    let libs = workspace.join("build").join("libs");
    let preferred = libs.join("minesport-bridge-0.2.0.jar");
    if preferred.is_file() {
        return Ok(preferred);
    }

    let mut candidates = Vec::new();
    for entry in fs::read_dir(&libs)
        .with_context(|| format!("read Bridge build output {}", libs.display()))?
    {
        let entry = entry?;
        if !entry.file_type()?.is_file() {
            continue;
        }
        let path = entry.path();
        if !path
            .extension()
            .and_then(|value| value.to_str())
            .is_some_and(|value| value.eq_ignore_ascii_case("jar"))
        {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if name.ends_with("-sources.jar")
            || name.ends_with("-javadoc.jar")
            || name.contains("-dev")
        {
            continue;
        }
        candidates.push(path);
    }
    candidates.sort();
    candidates
        .into_iter()
        .next()
        .ok_or_else(|| anyhow!("Gradle reported success but no non-sources Bridge JAR was produced in {}", libs.display()))
}
