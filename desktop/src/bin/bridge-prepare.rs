#[allow(dead_code)]
#[path = "../bridge_build.rs"]
mod bridge_build;
#[allow(dead_code)]
#[path = "../bridge_compat.rs"]
mod bridge_compat;
#[allow(dead_code)]
#[path = "../bridge_java.rs"]
mod bridge_java;
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
    time::{SystemTime, UNIX_EPOCH},
};

fn main() -> Result<()> {
    let mut version = None;
    let mut output = None;
    let mut source_only = false;
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-version" | "--version" => version = args.next(),
            "-output" | "--output" => output = args.next().map(PathBuf::from),
            "-source-only" | "--source-only" => source_only = true,
            "-h" | "--help" => {
                println!(
                    "Minesport Rust Bridge prepare helper\n\nUsage:\n  bridge-prepare --version <minecraft> [--output <directory>] [--source-only]\n\nOptions:\n  --version <minecraft>   Minecraft version to prepare\n  --output <directory>    Optional directory for the compiled Bridge JAR\n  --source-only           Prepare patched source and print its workspace without compiling"
                );
                return Ok(());
            }
            other => bail!("unknown argument {other:?}"),
        }
    }

    let version = version.ok_or_else(|| anyhow!("--version is required"))?;
    let version = bridge_compat::normalize_version(&version)
        .ok_or_else(|| anyhow!("invalid Minecraft version"))?;
    if !bridge_compat::is_supported(&version) {
        bail!("no embedded Minesport compatibility recipe for Minecraft {version}");
    }

    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let workspace = runtime::cache_root()
        .join("bridge-build")
        .join(if source_only { "prepared-sources" } else { "ci-recipes" })
        .join(format!("{}-{}-{stamp}", safe(&version), std::process::id()));

    eprintln!("[Minesport] Preparing Rust compatibility recipe for Minecraft {version}");
    let prepared = bridge_compat::prepare_source(&version, &workspace, |progress| {
        let detail = if progress.detail.is_empty() {
            progress.stage
        } else {
            format!("{} · {}", progress.stage, progress.detail)
        };
        eprintln!("[{:>3}%] {detail}", progress.percent.clamp(0, 100));
    })?;

    // Match the retired Go helper: source-only mode intentionally leaves the
    // prepared workspace on disk so a developer or CI job can inspect/use it.
    if source_only {
        println!("{}", prepared.workspace.display());
        return Ok(());
    }

    let _cleanup = Cleanup(workspace);
    let build_java = bridge_java::tooling_java(
        prepared.java,
        prepared.variables.get("loom_version").map(String::as_str),
    );
    let java_home = toolchain::ensure_jdk(build_java, |progress| {
        eprintln!(
            "[{:>3}%] {}",
            progress.percent.clamp(0, 100),
            progress.message
        );
    })?;
    eprintln!(
        "[Minesport] Compiling {} targeting Java {} with build JDK {} at {}",
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
            .join(safe(&version))
    });
    fs::create_dir_all(&destination_dir)
        .with_context(|| format!("create {}", destination_dir.display()))?;
    let destination = destination_dir.join(format!("minesport-bridge-{}.jar", safe(&version)));
    copy_file(&jar, &destination)?;
    println!("{}", destination.display());
    Ok(())
}

fn copy_file(source: &Path, destination: &Path) -> Result<()> {
    fs::copy(source, destination).with_context(|| {
        format!(
            "copy compiled Bridge {} to {}",
            source.display(),
            destination.display()
        )
    })?;
    if !destination.is_file() || fs::metadata(destination)?.len() == 0 {
        bail!(
            "compiled compatibility Bridge is missing or empty: {}",
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
