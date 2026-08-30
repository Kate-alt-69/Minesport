#[allow(dead_code)]
#[path = "../bridge_build.rs"]
mod bridge_build;
#[allow(dead_code)]
#[path = "../bridge_compat.rs"]
mod bridge_compat;
#[allow(dead_code)]
#[path = "../bridge_family.rs"]
mod bridge_family;
#[allow(dead_code)]
#[path = "../bridge_java.rs"]
mod bridge_java;
#[allow(dead_code)]
#[path = "../launcher.rs"]
mod launcher;
#[allow(dead_code)]
#[path = "../runtime.rs"]
mod runtime;
#[allow(dead_code)]
#[path = "../toolchain.rs"]
mod toolchain;

use anyhow::{Context, Result, anyhow, bail};
use bridge_family::BridgeFamily;
use std::{
    env,
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

fn main() -> Result<()> {
    let mut version = None;
    let mut loader = "fabric".to_string();
    let mut output = None;
    let mut source_only = false;
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-version" | "--version" => version = args.next(),
            "-loader" | "--loader" => loader = args.next().ok_or_else(|| anyhow!("--loader requires a value"))?,
            "-output" | "--output" => output = args.next().map(PathBuf::from),
            "-source-only" | "--source-only" => source_only = true,
            "-h" | "--help" => {
                println!(
                    "Minesport Rust Export Worker prepare helper\n\nUsage:\n  bridge-prepare --version <minecraft> [--loader fabric|forge|neoforge|quilt] [--output <directory>] [--source-only]\n\nOptions:\n  --version <minecraft>   Minecraft version to prepare\n  --loader <loader>       Export Worker loader (default: fabric)\n  --output <directory>    Optional directory for the compiled Export Worker JAR\n  --source-only           Prepare patched source and print its workspace without compiling"
                );
                return Ok(());
            }
            other => bail!("unknown argument {other:?}"),
        }
    }

    let family = BridgeFamily::parse(&loader)
        .ok_or_else(|| anyhow!("unsupported Export Worker loader {loader:?}"))?;
    let version = version.ok_or_else(|| anyhow!("--version is required"))?;
    let version = bridge_compat::normalize_version(&version)
        .ok_or_else(|| anyhow!("invalid Minecraft version"))?;
    if !bridge_family::is_supported(family, &version) {
        bail!(
            "no embedded {} compatibility recipe for Minecraft {version}",
            family.label()
        );
    }

    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join(if source_only { "prepared-sources" } else { "ci-recipes" })
        .join(format!(
            "{}-{}-{}-{stamp}",
            family.label().to_ascii_lowercase(),
            safe(&version),
            std::process::id()
        ));

    eprintln!(
        "[Minesport] Preparing {} compatibility recipe for Minecraft {version}",
        family.label()
    );
    let prepared = bridge_family::prepare_source(family, &version, &workspace, |progress| {
        let detail = if progress.detail.is_empty() {
            progress.stage
        } else {
            format!("{} · {}", progress.stage, progress.detail)
        };
        eprintln!("[{:>3}%] {detail}", progress.percent.clamp(0, 100));
    })?;

    if source_only {
        println!("{}", prepared.workspace.display());
        return Ok(());
    }

    let _cleanup = Cleanup(workspace);
    let build_java = if family == BridgeFamily::Fabric {
        bridge_java::tooling_java(
            prepared.java,
            prepared.variables.get("loom_version").map(String::as_str),
        )
    } else {
        prepared.java
    };
    let java_home = toolchain::ensure_jdk(build_java, |progress| {
        eprintln!(
            "[{:>3}%] {}",
            progress.percent.clamp(0, 100),
            progress.message
        );
    })?;
    eprintln!(
        "[Minesport] Compiling {} {} targeting Java {} with build JDK {} at {}",
        family.label(),
        prepared.profile_id,
        prepared.java,
        build_java,
        java_home.display()
    );

    let jar = bridge_build::compile_bridge(&prepared.workspace, &java_home, true)?;
    let destination_dir = output.unwrap_or_else(|| {
        runtime::cache_root()
            .join("bridge-build")
            .join("compiled")
            .join(family.label().to_ascii_lowercase())
            .join(safe(&version))
    });
    fs::create_dir_all(&destination_dir)
        .with_context(|| format!("create {}", destination_dir.display()))?;
    let destination = destination_dir.join(output_name(family, &version));
    copy_file(&jar, &destination)?;
    println!("{}", destination.display());
    Ok(())
}

fn output_name(family: BridgeFamily, version: &str) -> String {
    // bridge-prepare is the compatibility/CI publication helper. Its output is
    // consumed by workflows, installers and artifact downloaders under the
    // public `minesport-bridge-<loader>-<version>.jar` contract. The Gradle
    // project may internally call the module an `export_worker`; do not leak
    // that implementation filename into the published artifact name.
    format!(
        "minesport-bridge-{}-{}.jar",
        family.label().to_ascii_lowercase(),
        safe(version)
    )
}

fn copy_file(source: &Path, destination: &Path) -> Result<()> {
    fs::copy(source, destination).with_context(|| {
        format!(
            "copy compiled Export Worker {} to {}",
            source.display(),
            destination.display()
        )
    })?;
    if !destination.is_file() || fs::metadata(destination)?.len() == 0 {
        bail!(
            "compiled compatibility Export Worker is missing or empty: {}",
            destination.display()
        );
    }
    Ok(())
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
        "unknown".to_string()
    } else {
        result
    }
}

struct Cleanup(PathBuf);
impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fabric_recipe_filename_uses_public_bridge_contract() {
        assert_eq!(
            output_name(BridgeFamily::Fabric, "1.21.5"),
            "minesport-bridge-fabric-1.21.5.jar"
        );
    }

    #[test]
    fn loader_recipe_filename_uses_public_bridge_contract() {
        assert_eq!(
            output_name(BridgeFamily::Forge, "1.21.5"),
            "minesport-bridge-forge-1.21.5.jar"
        );
        assert_eq!(
            output_name(BridgeFamily::NeoForge, "1.21.7"),
            "minesport-bridge-neoforge-1.21.7.jar"
        );
        assert_eq!(
            output_name(BridgeFamily::Quilt, "1.21.6"),
            "minesport-bridge-quilt-1.21.6.jar"
        );
    }
}
