use crate::{diagnostics, toolchain};
use anyhow::{Context, Result, bail};
use std::{env, io::Write, path::PathBuf};

pub const ENGINE_JAVA_MAJOR: u32 = 22;

/// Resolve or provision the Java runtime owned by the Minesport engine worker.
///
/// This runs during worker startup, before `ipc` launches Java. Setting
/// JAVA_HOME here makes the existing IPC resolver select the provisioned
/// runtime first without changing the GUI process or the user's permanent
/// environment. Startup progress is emitted as ordinary workerInfo IPC lines so
/// a first-run JDK download never looks like an unexplained frozen backend.
pub fn prepare_engine_java() -> Result<PathBuf> {
    let logger = diagnostics::Logger::new("ENGINE").child("JAVA");
    let home = toolchain::ensure_jdk(ENGINE_JAVA_MAJOR, |progress| {
        logger.debug(
            "EngineJavaProvisionProgress",
            &progress.message,
            &[("percent", progress.percent.to_string())],
        );
        emit_worker_info(progress.percent, &progress.message);
    })
    .with_context(|| format!("prepare Minesport-managed JDK {ENGINE_JAVA_MAJOR}"))?;

    let java = home.join("bin").join(java_executable_name());
    if !java.is_file() {
        bail!(
            "prepared JDK {ENGINE_JAVA_MAJOR} does not contain {}: {}",
            java_executable_name(),
            home.display()
        );
    }

    // SAFETY: callers invoke this during worker startup before spawning any
    // engine/runtime threads. The environment mutation is process-local and is
    // used only so ipc::resolve_java() prioritizes this exact JDK home.
    unsafe {
        env::set_var("JAVA_HOME", &home);
    }

    emit_worker_info(
        55,
        &format!("Engine Java {ENGINE_JAVA_MAJOR} ready · {}", home.display()),
    );
    logger.info(
        "EngineJavaRuntimePrepared",
        "Minesport engine Java runtime is ready",
        &[
            ("required_major", ENGINE_JAVA_MAJOR.to_string()),
            ("java_home", home.display().to_string()),
            ("java", java.display().to_string()),
        ],
    );
    Ok(java)
}

fn emit_worker_info(percent: i32, message: &str) {
    let record = serde_json::json!({
        "type": "workerInfo",
        "message": message,
        "percent": percent.clamp(0, 100),
    });
    let stdout = std::io::stdout();
    let mut output = stdout.lock();
    let _ = writeln!(output, "{record}");
    let _ = output.flush();
}

fn java_executable_name() -> &'static str {
    if cfg!(windows) { "java.exe" } else { "java" }
}
