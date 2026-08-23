use crate::{bridge_compat, launcher, runtime, toolchain};
use anyhow::{Context, Result, anyhow, bail};
use std::{
    collections::BTreeSet,
    env,
    fs,
    path::{Path, PathBuf},
    process::Command,
};

const VERSION: &str = "0.2.0";

pub fn handle() -> Result<bool> {
    let args = env::args().skip(1).collect::<Vec<_>>();
    match args.as_slice() {
        [flag, version] if flag == "--build-bridge" => {
            let jar = ensure_bridge(version)?;
            println!("Bridge ready: {}", jar.display());
            Ok(true)
        }
        [flag] if flag == "--build-bridge" => {
            bail!("usage: minesport --build-bridge <minecraft-version>")
        }
        [flag, ..] if flag == "--build-bridge" => {
            bail!("usage: minesport --build-bridge <minecraft-version>")
        }
        [flag] if flag == "--build-bridges-detected" => {
            build_detected_bridges()?;
            Ok(true)
        }
        [flag, ..] if flag == "--build-bridges-detected" => {
            bail!("usage: minesport --build-bridges-detected")
        }
        [flag] if flag == "-h" || flag == "--help" => {
            print_help();
            Ok(true)
        }
        _ => Ok(false),
    }
}

fn ensure_bridge(raw_version: &str) -> Result<PathBuf> {
    let version = bridge_compat::normalize_version(raw_version)
        .ok_or_else(|| anyhow!("could not determine Minecraft version from {raw_version:?}"))?;
    if !bridge_compat::is_supported(&version) {
        bail!("Minesport has no embedded Fabric compatibility recipe for Minecraft {version}");
    }

    let manifest = bridge_compat::manifest()?;
    if bridge_compat::is_bundled_compatible(&version)? {
        let jar = runtime::materialize_bundled_bridge()?;
        println!(
            "Minecraft {version} uses bundled Bridge {} · {}",
            manifest.base.bundled_jar,
            jar.display()
        );
        return Ok(jar);
    }

    let destination = compiled_bridge_path(&version);
    if destination.is_file() {
        println!("Minecraft {version} · cached Bridge reused");
        return Ok(destination);
    }

    println!(
        "Compatibility source: {} @ {} · {}",
        display_or(&manifest.repository, "embedded canonical source"),
        display_or(&manifest.git_ref, "embedded ref"),
        display_or(&manifest.base.source_root, "bridge")
    );

    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join("cli")
        .join(safe_version(&version));
    let prepared = bridge_compat::prepare_source(&version, &workspace, |update| {
        print_progress(update.percent, &update.stage, &update.detail);
    })?;

    println!(
        "Prepared profile {} for Minecraft {} · {} variable(s) · {}",
        prepared.profile_id,
        prepared.version,
        prepared.variables.len(),
        prepared.workspace.display()
    );

    let java_home = toolchain::ensure_jdk(prepared.java, |update| {
        print_progress(update.percent, "JDK", &update.message);
    })?;
    run_gradle_build(&workspace, &java_home)?;

    let built = find_built_bridge(&workspace)?;
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create compiled Bridge cache {}", parent.display()))?;
    }
    let temporary = destination.with_extension("jar.tmp");
    let _ = fs::remove_file(&temporary);
    fs::copy(&built, &temporary)
        .with_context(|| format!("stage built Bridge {}", built.display()))?;
    let _ = fs::remove_file(&destination);
    fs::rename(&temporary, &destination)
        .with_context(|| format!("install compiled Bridge {}", destination.display()))?;

    println!("[100%] Bridge compiled · {}", destination.display());
    Ok(destination)
}

fn build_detected_bridges() -> Result<()> {
    let mut versions = BTreeSet::new();
    for discovered in launcher::discover_all() {
        for instance in launcher::discover_instances(&discovered) {
            if instance.loader != launcher::ModLoader::Fabric {
                continue;
            }
            let Some(version) = bridge_compat::normalize_version(&instance.version) else {
                continue;
            };
            println!(
                "Detected {} · {} · Minecraft {}",
                instance.launcher.label(),
                instance.name,
                version
            );
            versions.insert(version);
        }
    }

    if versions.is_empty() {
        println!("No Fabric Minecraft installations with a known version were detected.");
        return Ok(());
    }

    let mut failures = Vec::new();
    for version in versions {
        println!("\nMinecraft {version}");
        if let Err(error) = ensure_bridge(&version) {
            eprintln!("Bridge preparation failed for {version}: {error:#}");
            failures.push(format!("{version}: {error}"));
        }
    }

    if failures.is_empty() {
        Ok(())
    } else {
        bail!(
            "{} Bridge build(s) failed: {}",
            failures.len(),
            failures.join("; ")
        )
    }
}

fn run_gradle_build(workspace: &Path, java_home: &Path) -> Result<()> {
    let mut command = if cfg!(windows) {
        let mut command = Command::new("cmd.exe");
        command.args([
            "/D",
            "/S",
            "/C",
            "gradlew.bat --no-daemon --console=plain build",
        ]);
        command
    } else {
        let mut command = Command::new(workspace.join("gradlew"));
        command.args(["--no-daemon", "--console=plain", "build"]);
        command
    };

    command.current_dir(workspace);
    sanitize_java_environment(&mut command, java_home);
    command.env("GRADLE_USER_HOME", runtime::cache_root().join("gradle"));

    println!("Building compatibility Bridge with {}", java_home.display());
    let status = command
        .status()
        .with_context(|| format!("launch Gradle Bridge build in {}", workspace.display()))?;
    if !status.success() {
        bail!("Gradle Bridge build failed with {status}");
    }
    Ok(())
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
        if path.extension().and_then(|value| value.to_str()) != Some("jar") {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if name.ends_with("-sources.jar") || name.ends_with("-javadoc.jar") || name.contains("-dev") {
            continue;
        }
        candidates.push(path);
    }
    candidates.sort();
    candidates
        .into_iter()
        .next()
        .ok_or_else(|| anyhow!("Gradle build completed but no Bridge JAR was found in {}", libs.display()))
}

fn compiled_bridge_path(version: &str) -> PathBuf {
    runtime::cache_root()
        .join("bridge-build")
        .join("compiled")
        .join(safe_version(version))
        .join("minesport-bridge-0.2.0.jar")
}

fn safe_version(value: &str) -> String {
    let safe = value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '_'
            }
        })
        .collect::<String>();
    if safe.is_empty() {
        "unknown".to_string()
    } else {
        safe
    }
}

fn print_progress(percent: i32, stage: &str, detail: &str) {
    if detail.trim().is_empty() {
        println!("[{:3}%] {stage}", percent.clamp(0, 100));
    } else {
        println!("[{:3}%] {stage} · {detail}", percent.clamp(0, 100));
    }
}

fn display_or<'a>(value: &'a str, fallback: &'a str) -> &'a str {
    if value.trim().is_empty() {
        fallback
    } else {
        value
    }
}

fn print_help() {
    println!(
        "Minesport {VERSION}\nRust + Slint desktop by Kastrick\n\nUsage:\n  minesport                            Open the desktop app\n  minesport --build-bridge VERSION     Prepare/cache the Fabric Bridge for VERSION\n  minesport --build-bridges-detected   Prepare Bridges for detected Fabric instances\n  minesport --install-blender-translator\n                                       Install/repair the bundled Blender translator\n  minesport --version                  Print version\n  minesport --help                     Show this help\n\nMinecraft 1.21.9 and 1.21.10 use the bundled 1.21.10 Bridge. Other supported\nversions are generated from the canonical source and compiled only when needed."
    );
}
