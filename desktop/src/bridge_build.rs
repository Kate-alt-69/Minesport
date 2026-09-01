use crate::runtime;
use anyhow::{Context, Result, anyhow, bail};
use std::{
    env, fs,
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

    let status = command.status().with_context(|| {
        format!(
            "launch Gradle compatibility Export Worker build in {}",
            workspace.display()
        )
    })?;
    if !status.success() {
        bail!("Gradle compatibility Export Worker build failed with {status}");
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
    let expected_name =
        read_gradle_property(&workspace.join("gradle.properties"), "archives_base_name")
            .filter(|value| !value.trim().is_empty())
            .map(|value| format!("{}.jar", value.trim()));

    let mut candidates = Vec::new();
    for entry in fs::read_dir(&libs)
        .with_context(|| format!("read Export Worker build output {}", libs.display()))?
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
        let name = entry.file_name().to_string_lossy().to_string();
        let lower = name.to_ascii_lowercase();
        if lower.ends_with("-sources.jar")
            || lower.ends_with("-javadoc.jar")
            || lower.contains("-dev")
        {
            continue;
        }
        if expected_name
            .as_ref()
            .is_some_and(|expected| name == *expected)
        {
            return Ok(path);
        }
        candidates.push(path);
    }

    // Compatibility recipes may intentionally change Gradle/Loom versions.
    // Older Loom/Gradle combinations can append project.version even when the
    // canonical 1.21.10 build suppresses archiveVersion. The caller installs
    // the returned JAR under Minesport's canonical cache filename, so when
    // Gradle produced exactly one usable runtime JAR it is unambiguous and safe
    // to accept that artifact instead of failing solely on its source filename.
    match candidates.as_slice() {
        [only] => Ok(only.clone()),
        [] => {
            if let Some(expected) = expected_name {
                Err(anyhow!(
                    "Gradle reported success but expected Export Worker artifact {expected} was not produced in {} and no fallback runtime JAR was found",
                    libs.display()
                ))
            } else {
                Err(anyhow!(
                    "Gradle reported success but no non-sources Export Worker JAR was produced in {}",
                    libs.display()
                ))
            }
        }
        _ => {
            let names = candidates
                .iter()
                .map(|path| {
                    path.file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .to_string()
                })
                .collect::<Vec<_>>()
                .join(", ");
            if let Some(expected) = expected_name {
                Err(anyhow!(
                    "Gradle did not produce exact Export Worker artifact {expected} in {} and produced multiple fallback candidates: {names}",
                    libs.display()
                ))
            } else {
                Err(anyhow!(
                    "Gradle produced multiple candidate Export Worker JARs in {} and no exact archives_base_name was available: {names}",
                    libs.display()
                ))
            }
        }
    }
}

fn read_gradle_property(path: &Path, key: &str) -> Option<String> {
    let text = fs::read_to_string(path).ok()?;
    text.lines().find_map(|line| {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            return None;
        }
        let (candidate, value) = line.split_once('=')?;
        (candidate.trim() == key).then(|| value.trim().to_string())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_workspace(label: &str) -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        env::temp_dir().join(format!(
            "minesport-bridge-build-{label}-{}-{stamp}",
            std::process::id()
        ))
    }

    #[test]
    fn gradle_property_parser_reads_exact_key() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = env::temp_dir().join(format!(
            "minesport-gradle-props-{}-{stamp}",
            std::process::id()
        ));
        fs::write(
            &path,
            "foo=bar\narchives_base_name=minesport_export_worker-fabric-1.20.1\n",
        )
        .unwrap();
        assert_eq!(
            read_gradle_property(&path, "archives_base_name").as_deref(),
            Some("minesport_export_worker-fabric-1.20.1")
        );
        let _ = fs::remove_file(path);
    }

    #[test]
    fn artifact_discovery_accepts_single_version_suffixed_runtime_jar() {
        let workspace = temp_workspace("version-suffix");
        let libs = workspace.join("build").join("libs");
        fs::create_dir_all(&libs).unwrap();
        fs::write(
            workspace.join("gradle.properties"),
            "archives_base_name=minesport_export_worker-fabric-1.19\n",
        )
        .unwrap();
        let actual = libs.join("minesport_export_worker-fabric-1.19-0.2.1.jar");
        fs::write(&actual, b"runtime").unwrap();
        fs::write(
            libs.join("minesport_export_worker-fabric-1.19-0.2.1-sources.jar"),
            b"sources",
        )
        .unwrap();

        assert_eq!(find_built_bridge(&workspace).unwrap(), actual);
        let _ = fs::remove_dir_all(workspace);
    }

    #[test]
    fn artifact_discovery_prefers_exact_name_over_other_runtime_jars() {
        let workspace = temp_workspace("exact-name");
        let libs = workspace.join("build").join("libs");
        fs::create_dir_all(&libs).unwrap();
        fs::write(
            workspace.join("gradle.properties"),
            "archives_base_name=minesport_export_worker-fabric-1.20.1\n",
        )
        .unwrap();
        let exact = libs.join("minesport_export_worker-fabric-1.20.1.jar");
        fs::write(&exact, b"runtime").unwrap();
        fs::write(
            libs.join("minesport_export_worker-fabric-1.20.1-legacy.jar"),
            b"legacy",
        )
        .unwrap();

        assert_eq!(find_built_bridge(&workspace).unwrap(), exact);
        let _ = fs::remove_dir_all(workspace);
    }
}
