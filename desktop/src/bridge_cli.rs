use crate::{bridge_build, bridge_compat, launcher, runtime, toolchain};
use anyhow::{Context, Result, anyhow, bail};
use std::{collections::BTreeSet, env, fs, path::PathBuf};

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
    let required_java = bridge_compat::required_java(&version)?;

    let manifest = bridge_compat::manifest()?;
    if bridge_compat::is_bundled_compatible(&version)? {
        let jar = runtime::materialize_bundled_bridge()?;
        println!(
            "Minecraft {version} uses bundled Bridge {} · Java {} · {}",
            manifest.base.bundled_jar,
            required_java,
            jar.display()
        );
        return Ok(jar);
    }

    let destination = compiled_bridge_path(&version);
    if destination.is_file() {
        println!("Minecraft {version} · Java {required_java} · cached Bridge reused");
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
    if prepared.java != required_java {
        bail!(
            "compatibility Java requirement drift for Minecraft {version}: manifest requires {required_java}, prepared profile {} requested {}",
            prepared.profile_id,
            prepared.java
        );
    }

    println!(
        "Prepared profile {} for Minecraft {} · Java {} · {} variable(s) · {}",
        prepared.profile_id,
        prepared.version,
        required_java,
        prepared.variables.len(),
        prepared.workspace.display()
    );

    let java_home = toolchain::ensure_jdk(required_java, |update| {
        print_progress(update.percent, "JDK", &update.message);
    })?;
    println!("Building compatibility Bridge with {}", java_home.display());
    let built = bridge_build::compile_bridge(&workspace, &java_home, true)?;

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
