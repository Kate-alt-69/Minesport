#[allow(dead_code)]
#[path = "../runtime.rs"]
mod runtime;
#[allow(dead_code)]
#[path = "../diagnostics.rs"]
mod diagnostics;
#[allow(dead_code)]
#[path = "../ipc.rs"]
mod ipc;

use anyhow::{Result, bail};

const ENGINE_NAME: &str = "minesport-engine";
const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

fn main() -> Result<()> {
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
                    "version": ENGINE_VERSION,
                    "protocolVersion": ipc::ENGINE_PROTOCOL_VERSION,
                })
            );
            Ok(())
        }
        Some("-V" | "--version") => {
            println!("Minesport Engine {ENGINE_VERSION}");
            Ok(())
        }
        Some("--protocol-version") => {
            println!("{}", ipc::ENGINE_PROTOCOL_VERSION);
            Ok(())
        }
        Some("-h" | "--help") => {
            println!(
                "Minesport Engine {ENGINE_VERSION}\n\nUsage:\n  minesport-engine                 Run the IPC engine worker\n  minesport-engine --identity      Print machine-readable engine identity\n  minesport-engine --version       Print engine version\n  minesport-engine --protocol-version\n  minesport-engine --help"
            );
            Ok(())
        }
        None | Some("--engine-worker") => run_worker(),
        Some(other) => bail!("unknown minesport-engine option: {other}"),
    }
}

fn run_worker() -> Result<()> {
    let log = diagnostics::initialize()?;
    diagnostics::Logger::new("ENGINE").info(
        "EngineSidecarProcessStart",
        "Minesport engine sidecar started",
        &[
            ("version", ENGINE_VERSION.to_string()),
            ("protocol", ipc::ENGINE_PROTOCOL_VERSION.to_string()),
            ("diagnostics", log.display().to_string()),
        ],
    );
    let jar = runtime::materialize_engine()?;
    ipc::run_engine_worker(&jar)
}
