use crate::{bridge_build, bridge_compat, bridge_family::{self, BridgeFamily}, bridge_java, launcher, runtime, toolchain};
use anyhow::{Context, Result, anyhow, bail};
use std::{collections::BTreeSet, env, fs, path::PathBuf};

const VERSION: &str = "0.2.0";

pub fn handle() -> Result<bool> {
    let args = env::args().skip(1).collect::<Vec<_>>();
    match args.as_slice() {
        [flag, version] if flag == "--build-bridge" => {
            let jar = ensure_bridge(BridgeFamily::Fabric, version)?;
            println!("Bridge ready: {}", jar.display());
            Ok(true)
        }
        [flag, loader_flag, loader, version] if flag == "--build-bridge" && loader_flag == "--loader" => {
            let family = BridgeFamily::parse(loader)
                .ok_or_else(|| anyhow!("unsupported Bridge loader {loader:?}; expected fabric, forge, neoforge or quilt"))?;
            let jar = ensure_bridge(family, version)?;
            println!("{} Bridge ready: {}", family.label(), jar.display());
            Ok(true)
        }
        [flag, ..] if flag == "--build-bridge" => {
            bail!("usage: minesport --build-bridge [--loader fabric|forge|neoforge|quilt] <minecraft-version>")
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

fn ensure_bridge(family: BridgeFamily, raw_version: &str) -> Result<PathBuf> {
    let version = bridge_compat::normalize_version(raw_version)
        .ok_or_else(|| anyhow!("could not determine Minecraft version from {raw_version:?}"))?;
    if !bridge_family::is_supported(family, &version) {
        bail!("Minesport has no embedded {} compatibility recipe for Minecraft {version}", family.label());
    }

    if family == BridgeFamily::Fabric && bridge_compat::is_bundled_compatible(&version)? {
        let required_java = bridge_compat::required_java(&version)?;
        let manifest = bridge_compat::manifest()?;
        let jar = runtime::materialize_bundled_bridge()?;
        println!(
            "Minecraft {version} uses bundled Fabric Bridge {} · Java {} · {}",
            manifest.base.bundled_jar,
            required_java,
            jar.display()
        );
        return Ok(jar);
    }

    let destination = compiled_bridge_path(family, &version);
    if destination.is_file() {
        println!("{} · Minecraft {version} · cached Bridge reused", family.label());
        return Ok(destination);
    }

    if family == BridgeFamily::Fabric {
        let manifest = bridge_compat::manifest()?;
        println!(
            "Compatibility source: {} @ {} · {}",
            display_or(&manifest.repository, "embedded canonical source"),
            display_or(&manifest.git_ref, "embedded ref"),
            display_or(&manifest.base.source_root, "bridge")
        );
    } else {
        println!("Compatibility source: embedded canonical {} 1.21.10 Bridge", family.label());
    }

    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join("cli")
        .join(family.label().to_ascii_lowercase())
        .join(safe_version(&version));
    let prepared = bridge_family::prepare_source(family, &version, &workspace, |update| {
        print_progress(update.percent, &update.stage, &update.detail);
    })?;
    let target_java = prepared.java;
    let build_java = build_java_for(family, target_java, &prepared.variables);

    println!(
        "Prepared {} profile {} for Minecraft {} · target Java {} · build JDK {} · {} variable(s) · {}",
        family.label(),
        prepared.profile_id,
        prepared.version,
        target_java,
        build_java,
        prepared.variables.len(),
        prepared.workspace.display()
    );

    let java_home = toolchain::ensure_jdk(build_java, |update| {
        print_progress(update.percent, "JDK", &update.message);
    })?;
    println!("Building {} compatibility Bridge with {}", family.label(), java_home.display());
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

    println!("[100%] {} Bridge compiled · {}", family.label(), destination.display());
    Ok(destination)
}

fn build_java_for(family: BridgeFamily, target_java: u32, variables: &std::collections::HashMap<String, String>) -> u32 {
    if family == BridgeFamily::Fabric {
        return bridge_java::tooling_java(
            target_java,
            variables.get("loom_version").map(String::as_str),
        );
    }
    variables
        .get("build_java")
        .and_then(|value| value.parse::<u32>().ok())
        .unwrap_or(target_java)
}

fn build_detected_bridges() -> Result<()> {
    let mut targets = BTreeSet::new();
    for discovered in launcher::discover_all() {
        for instance in launcher::discover_instances(&discovered) {
            let Some(family) = BridgeFamily::from_mod_loader(instance.loader) else {
                continue;
            };
            let Some(version) = bridge_compat::normalize_version(&instance.version) else {
                continue;
            };
            println!(
                "Detected {} · {} · {} · Minecraft {}",
                instance.launcher.label(),
                instance.name,
                family.label(),
                version
            );
            targets.insert((family.label().to_ascii_lowercase(), version));
        }
    }

    if targets.is_empty() {
        println!("No supported mod-loader Minecraft installations with a known version were detected.");
        return Ok(());
    }

    let mut failures = Vec::new();
    for (loader, version) in targets {
        let Some(family) = BridgeFamily::parse(&loader) else {
            continue;
        };
        println!("\n{} · Minecraft {version}", family.label());
        if let Err(error) = ensure_bridge(family, &version) {
            eprintln!("{} Bridge preparation failed for {version}: {error:#}", family.label());
            failures.push(format!("{} {version}: {error}", family.label()));
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

fn compiled_bridge_path(family: BridgeFamily, version: &str) -> PathBuf {
    runtime::cache_root()
        .join("bridge-build")
        .join("compiled")
        .join(family.label().to_ascii_lowercase())
        .join(safe_version(version))
        .join(format!("minesport-bridge-{}-0.2.0.jar", family.label().to_ascii_lowercase()))
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
        "Minesport {VERSION}\nRust + Slint desktop by Kastrick\n\nUsage:\n  minesport                            Open the desktop app\n  minesport --build-bridge VERSION     Prepare/cache the Fabric Bridge for VERSION\n  minesport --build-bridge --loader LOADER VERSION\n                                       Prepare/cache Fabric, Forge, NeoForge or Quilt Bridge\n  minesport --build-bridges-detected   Prepare Bridges for detected mod-loader instances\n  minesport --install-blender-translator\n                                       Install/repair the bundled Blender translator\n  minesport --version                  Print version\n  minesport --help                     Show this help\n\nEach loader family owns a canonical Minecraft 1.21.10 Bridge. Older supported\nversions are generated from that family baseline by embedded patch recipes."
    );
}
