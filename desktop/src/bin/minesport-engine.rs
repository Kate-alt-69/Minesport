#[allow(dead_code)]
#[path = "../runtime.rs"]
mod runtime;
#[allow(dead_code)]
#[path = "../diagnostics.rs"]
mod diagnostics;
#[allow(dead_code)]
#[path = "../toolchain.rs"]
mod toolchain;
#[allow(dead_code)]
#[path = "../engine_java.rs"]
mod engine_java;
#[allow(dead_code)]
#[cfg_attr(not(windows), allow(unused_imports))]
#[path = "../ipc.rs"]
mod ipc;

use anyhow::{Result, bail};

const ENGINE_NAME: &str = "minesport-engine";
// The sidecar owns an independent version from the desktop package. Gradle
// already trims engine/VERSION; the native identity must use the same rule so
// a normal trailing newline cannot create two different engine versions.
const ENGINE_VERSION_RAW: &str = include_str!("../../../engine/VERSION");

fn engine_version() -> &'static str {
    ENGINE_VERSION_RAW.trim()
}

fn main() -> Result<()> {
    let engine_version = engine_version();
    if engine_version.is_empty() {
        bail!("engine/VERSION must not be empty");
    }

    let mut args = std::env::args().skip(1);
    let mode = args.next();
    if args.next().is_some() {
        bail!("minesport-engine accepts at most one command-line option");
    }

    match mode.as_deref() {
        Some("--identity") => {
            println!(
                "{}",
                serde_json::json!({
                    "name": ENGINE_NAME,
                    "version": engine_version,
                    "protocolVersion": ipc::ENGINE_PROTOCOL_VERSION,
                })
            );
            Ok(())
        }
        Some("-V" | "--version") => {
            println!("Minesport Engine {engine_version}");
            Ok(())
        }
        Some("--protocol-version") => {
            println!("{}", ipc::ENGINE_PROTOCOL_VERSION);
            Ok(())
        }
        Some("-h" | "--help") => {
            println!(
                "Minesport Engine {engine_version}\n\nUsage:\n  minesport-engine                 Run the IPC engine worker\n  minesport-engine --identity      Print machine-readable engine identity\n  minesport-engine --version       Print engine version\n  minesport-engine --protocol-version\n  minesport-engine --help"
            );
            Ok(())
        }
        None | Some("--engine-worker") => run_worker(),
        Some(other) => bail!("unknown minesport-engine option: {other}"),
    }
}

fn run_worker() -> Result<()> {
    let log = diagnostics::initialize()?;
    let java = engine_java::prepare_engine_java()?;
    diagnostics::Logger::new("ENGINE").info(
        "EngineSidecarProcessStart",
        "Minesport engine sidecar started",
        &[
            ("version", engine_version().to_string()),
            ("protocol", ipc::ENGINE_PROTOCOL_VERSION.to_string()),
            ("java", java.display().to_string()),
            ("diagnostics", log.display().to_string()),
        ],
    );
    let jar = runtime::materialize_engine()?;
    ipc::run_engine_worker(&jar)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_version_matches_gradle_whitespace_rules() {
        assert_eq!(" 0.2.10\r\n".trim(), "0.2.10");
        assert_eq!(engine_version(), ENGINE_VERSION_RAW.trim());
    }
}
