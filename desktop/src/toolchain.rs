use crate::runtime;
use anyhow::{Context, Result, anyhow, bail};
use regex::Regex;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    env,
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command},
    sync::{Arc, atomic::{AtomicBool, Ordering}},
    thread,
    time::{Duration, Instant},
};

const DOWNLOAD_ATTEMPTS: usize = 3;
const NETWORK_READ_SLICE: Duration = Duration::from_secs(10);
const JDK_EXTRACTION_TIMEOUT: Duration = Duration::from_secs(5 * 60);


enum HttpAttemptError {
    Retryable(anyhow::Error),
    Permanent(anyhow::Error),
}

#[derive(Debug, Clone)]
pub struct ToolchainProgress {
    pub percent: i32,
    pub message: String,
}

pub fn ensure_jdk<F>(required: u32, progress: F) -> Result<PathBuf>
where
    F: FnMut(ToolchainProgress),
{
    ensure_jdk_cancellable(required, Arc::new(AtomicBool::new(false)), progress)
}

pub fn ensure_jdk_cancellable<F>(
    required: u32,
    cancel: Arc<AtomicBool>,
    mut progress: F,
) -> Result<PathBuf>
where
    F: FnMut(ToolchainProgress),
{
    check_cancelled(&cancel, "checking JDK")?;
    progress(ToolchainProgress { percent: 40, message: format!("Checking for JDK {required}…") });
    if let Some(home) = find_installed_jdk(required) {
        check_cancelled(&cancel, "checking installed JDK")?;
        progress(ToolchainProgress { percent: 44, message: format!("Using installed JDK {} · {}", javac_major(&javac_path(&home)), home.display()) });
        return Ok(home);
    }

    check_cancelled(&cancel, "checking JDK cache")?;
    progress(ToolchainProgress { percent: 44, message: format!("JDK {required} not installed · checking Minesport toolchain cache") });
    if let Some(home) = find_cached_jdk(required) {
        check_cancelled(&cancel, "checking cached JDK")?;
        progress(ToolchainProgress { percent: 47, message: format!("Using cached JDK {required} · {}", home.display()) });
        return Ok(home);
    }

    check_cancelled(&cancel, "preparing JDK download")?;
    progress(ToolchainProgress { percent: 47, message: format!("Downloading verified JDK {required}…") });
    let home = download_adoptium_jdk(required, cancel.clone(), &mut progress)?;
    check_cancelled(&cancel, "finishing JDK preparation")?;
    progress(ToolchainProgress { percent: 54, message: format!("JDK {required} ready · {}", home.display()) });
    Ok(home)
}

pub fn find_installed_jdk(required: u32) -> Option<PathBuf> {
    for key in ["JAVA_HOME", "JDK_HOME", "GRADLE_JAVA_HOME"] {
        if let Some(home) = env::var_os(key).map(PathBuf::from) {
            if valid_jdk_home(&home, required) { return Some(home); }
        }
    }

    if let Some(javac) = find_on_path(javac_name()) {
        if javac_major(&javac) >= required {
            if let Some(home) = javac.parent().and_then(Path::parent).map(Path::to_path_buf) {
                if valid_jdk_home(&home, required) { return Some(home); }
            }
        }
    }

    // Resolve launcher shims to the real java.home, but accept it only when
    // that home also contains javac and satisfies the requested major.
    if let Some(java) = find_on_path(java_name()) {
        let mut command = Command::new(java);
        command.args(["-XshowSettings:properties", "-version"]);
        hide_console_window(&mut command);
        if let Ok(Some(output)) = runtime::output_with_timeout(&mut command, Duration::from_secs(5)) {
            let text = String::from_utf8_lossy(&output.stderr);
            for line in text.lines() {
                let Some(value) = line.trim().strip_prefix("java.home =") else { continue; };
                let home = PathBuf::from(value.trim());
                if valid_jdk_home(&home, required) { return Some(home); }
            }
        }
    }
    None
}

fn find_cached_jdk(required: u32) -> Option<PathBuf> {
    let root = toolchain_root().join(format!("jdk-{required}")).join("runtime");
    find_jdk_home_under(&root, required)
}

fn download_adoptium_jdk<F>(
    required: u32,
    cancel: Arc<AtomicBool>,
    progress: &mut F,
) -> Result<PathBuf>
where
    F: FnMut(ToolchainProgress),
{
    check_cancelled(&cancel, "resolving JDK package")?;
    let os_name = if cfg!(windows) { "windows" } else if cfg!(target_os = "macos") { "mac" } else if cfg!(target_os = "linux") { "linux" } else { bail!("automatic JDK download is unsupported on this operating system") };
    let arch = match env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "aarch64",
        other => bail!("automatic JDK download does not support architecture {other}"),
    };
    let endpoint = format!(
        "https://api.adoptium.net/v3/assets/latest/{required}/hotspot?architecture={arch}&image_type=jdk&jvm_impl=hotspot&os={os_name}&vendor=eclipse"
    );
    let metadata = http_get_bytes(
        &endpoint,
        8 * 1024 * 1024,
        Duration::from_secs(120),
        cancel.clone(),
    )?;
    check_cancelled(&cancel, "reading JDK package metadata")?;

    #[derive(Deserialize)]
    struct Asset { binary: Binary }
    #[derive(Deserialize)]
    struct Binary { package: Package }
    #[derive(Deserialize)]
    struct Package {
        link: String,
        #[serde(default)] checksum: String,
        #[serde(default)] name: String,
    }
    let assets: Vec<Asset> = serde_json::from_slice(&metadata).context("parse Adoptium JDK metadata")?;
    let package = assets.into_iter().next().ok_or_else(|| anyhow!("Adoptium did not return a JDK {required} package"))?.binary.package;
    if package.link.is_empty() { bail!("Adoptium JDK {required} package does not contain a download link"); }

    check_cancelled(&cancel, "preparing JDK cache")?;
    let root = toolchain_root().join(format!("jdk-{required}"));
    if root.exists() { fs::remove_dir_all(&root).with_context(|| format!("reset {}", root.display()))?; }
    fs::create_dir_all(&root)?;
    let archive_name = Path::new(&package.name).file_name().and_then(|value| value.to_str()).filter(|value| !value.is_empty())
        .unwrap_or(if cfg!(windows) { "jdk.zip" } else { "jdk.tar.gz" });
    let archive = root.join(archive_name);

    progress(ToolchainProgress { percent: 48, message: format!("Downloading Eclipse Temurin JDK {required}…") });
    download_file(&package.link, &archive, cancel.clone())?;
    check_cancelled(&cancel, "verifying JDK download")?;
    if !package.checksum.trim().is_empty() {
        verify_sha256_cancellable(&archive, package.checksum.trim(), &cancel)?;
    }
    check_cancelled(&cancel, "extracting JDK")?;
    progress(ToolchainProgress { percent: 51, message: format!("Verified JDK {required} · extracting…") });

    let extraction = root.join("runtime");
    fs::create_dir_all(&extraction)?;
    extract_archive_cancellable(&archive, &extraction, cancel.clone())?;
    check_cancelled(&cancel, "finishing JDK extraction")?;
    let _ = fs::remove_file(&archive);
    let home = find_jdk_home_under(&extraction, required)
        .ok_or_else(|| anyhow!("downloaded JDK {required} did not contain a compatible javac"))?;
    Ok(home)
}

fn download_file(url: &str, destination: &Path, cancel: Arc<AtomicBool>) -> Result<()> {
    if !url.starts_with("https://") { bail!("JDK download URL must use HTTPS"); }
    if let Some(parent) = destination.parent() { fs::create_dir_all(parent)?; }

    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(30))
        .timeout_read(NETWORK_READ_SLICE)
        .build();
    let mut last_error = None;
    for attempt in 1..=DOWNLOAD_ATTEMPTS {
        check_cancelled(&cancel, "downloading JDK")?;
        let _ = fs::remove_file(destination);
        match download_file_once(&agent, url, destination, &cancel) {
            Ok(()) => return Ok(()),
            Err(error) => {
                if cancel.load(Ordering::Relaxed) {
                    let _ = fs::remove_file(destination);
                    return Err(cancelled_error("downloading JDK"));
                }
                last_error = Some(error);
                if attempt < DOWNLOAD_ATTEMPTS {
                    sleep_cancellable(Duration::from_millis(500 * attempt as u64), &cancel, "retrying JDK download")?;
                }
            }
        }
    }
    let _ = fs::remove_file(destination);
    let error = last_error.unwrap_or_else(|| anyhow!("unknown download failure"));
    Err(anyhow!("download {url} failed after {DOWNLOAD_ATTEMPTS} attempts: {error:#}"))
}

fn download_file_once(
    agent: &ureq::Agent,
    url: &str,
    destination: &Path,
    cancel: &Arc<AtomicBool>,
) -> Result<()> {
    check_cancelled(cancel, "starting JDK download")?;
    let response = agent.get(url).set("User-Agent", "Minesport-Rust-Toolchain/0.2.1").call()
        .map_err(|error| anyhow!("JDK download request failed: {error}"))?;
    let mut reader = response.into_reader();
    let mut output = File::create(destination).with_context(|| format!("create {}", destination.display()))?;
    let mut buffer = [0u8; 128 * 1024];
    loop {
        check_cancelled(cancel, "downloading JDK")?;
        let read = reader.read(&mut buffer).with_context(|| format!("download into {}", destination.display()))?;
        if read == 0 { break; }
        output.write_all(&buffer[..read]).with_context(|| format!("write {}", destination.display()))?;
    }
    output.flush()?;
    Ok(())
}

fn http_get_bytes(
    url: &str,
    limit: u64,
    timeout: Duration,
    cancel: Arc<AtomicBool>,
) -> Result<Vec<u8>> {
    if !url.starts_with("https://") { bail!("HTTP metadata URL must use HTTPS: {url}"); }
    let read_timeout = timeout.min(NETWORK_READ_SLICE);
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(read_timeout)
        .build();
    let deadline = Instant::now() + timeout;
    let mut last_error = None;
    for attempt in 1..=DOWNLOAD_ATTEMPTS {
        check_cancelled(&cancel, "fetching JDK metadata")?;
        if Instant::now() >= deadline {
            break;
        }
        match http_get_bytes_once(&agent, url, limit, &cancel) {
            Ok(data) => return Ok(data),
            Err(HttpAttemptError::Permanent(error)) => return Err(error),
            Err(HttpAttemptError::Retryable(error)) => {
                if cancel.load(Ordering::Relaxed) {
                    return Err(cancelled_error("fetching JDK metadata"));
                }
                last_error = Some(error);
                if attempt < DOWNLOAD_ATTEMPTS && Instant::now() < deadline {
                    sleep_cancellable(Duration::from_millis(400 * attempt as u64), &cancel, "retrying JDK metadata request")?;
                }
            }
        }
    }
    let error = last_error.unwrap_or_else(|| anyhow!("JDK metadata request timed out"));
    Err(anyhow!("HTTP request failed for {url} after {DOWNLOAD_ATTEMPTS} attempts: {error:#}"))
}

fn http_get_bytes_once(
    agent: &ureq::Agent,
    url: &str,
    limit: u64,
    cancel: &Arc<AtomicBool>,
) -> std::result::Result<Vec<u8>, HttpAttemptError> {
    if cancel.load(Ordering::Relaxed) {
        return Err(HttpAttemptError::Permanent(cancelled_error("fetching JDK metadata")));
    }
    let response = match agent
        .get(url)
        .set("User-Agent", "Minesport-Rust-Toolchain/0.2.1")
        .call()
    {
        Ok(response) => response,
        Err(ureq::Error::Status(code, _)) => {
            let error = anyhow!("HTTP {code} for {url}");
            if retryable_http_status(code) {
                return Err(HttpAttemptError::Retryable(error));
            }
            return Err(HttpAttemptError::Permanent(error));
        }
        Err(error) => {
            return Err(HttpAttemptError::Retryable(anyhow!("HTTP request failed for {url}: {error}")));
        }
    };
    let mut reader = response.into_reader();
    let mut data = Vec::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if cancel.load(Ordering::Relaxed) {
            return Err(HttpAttemptError::Permanent(cancelled_error("fetching JDK metadata")));
        }
        let read = match reader.read(&mut buffer) {
            Ok(read) => read,
            Err(error) => return Err(HttpAttemptError::Retryable(anyhow!("read HTTP response for {url}: {error}"))),
        };
        if read == 0 { break; }
        if data.len().saturating_add(read) as u64 > limit {
            return Err(HttpAttemptError::Permanent(anyhow!("HTTP response exceeded {limit} bytes: {url}")));
        }
        data.extend_from_slice(&buffer[..read]);
    }
    Ok(data)
}

fn retryable_http_status(code: u16) -> bool {
    matches!(code, 408 | 425 | 429) || (500..=599).contains(&code)
}

#[cfg(test)]
fn verify_sha256(path: &Path, expected: &str) -> Result<()> {
    verify_sha256_cancellable(path, expected, &Arc::new(AtomicBool::new(false)))
}

fn verify_sha256_cancellable(path: &Path, expected: &str, cancel: &Arc<AtomicBool>) -> Result<()> {
    let mut file = File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 128 * 1024];
    loop {
        check_cancelled(cancel, "verifying JDK checksum")?;
        let read = file.read(&mut buffer)?;
        if read == 0 { break; }
        digest.update(&buffer[..read]);
    }
    let actual = format!("{:x}", digest.finalize());
    if !actual.eq_ignore_ascii_case(expected) {
        bail!("JDK checksum mismatch: expected {expected}, got {actual}");
    }
    Ok(())
}

fn extract_archive_cancellable(
    archive: &Path,
    destination: &Path,
    cancel: Arc<AtomicBool>,
) -> Result<()> {
    check_cancelled(&cancel, "starting JDK extraction")?;
    let mut command = if cfg!(windows) {
        let mut command = Command::new("powershell.exe");
        command
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command"])
            .arg("param($archive,$dest) Expand-Archive -LiteralPath $archive -DestinationPath $dest -Force")
            .arg(archive)
            .arg(destination);
        hide_console_window(&mut command);
        command
    } else {
        let mut command = Command::new("tar");
        command.arg("-xzf").arg(archive).arg("-C").arg(destination);
        command
    };

    let mut child = command.spawn().with_context(|| {
        if cfg!(windows) {
            "launch PowerShell Expand-Archive for JDK"
        } else {
            "launch tar for downloaded JDK"
        }
    })?;
    let started = Instant::now();
    loop {
        if cancel.load(Ordering::Relaxed) {
            terminate_child(&mut child);
            return Err(cancelled_error("extracting JDK"));
        }
        if started.elapsed() >= JDK_EXTRACTION_TIMEOUT {
            terminate_child(&mut child);
            bail!("JDK extraction timed out after {} seconds", JDK_EXTRACTION_TIMEOUT.as_secs());
        }
        match child.try_wait() {
            Ok(Some(status)) => {
                if status.success() {
                    return Ok(());
                }
                if cfg!(windows) {
                    bail!("PowerShell failed to extract downloaded JDK: {status}");
                }
                bail!("tar failed to extract downloaded JDK: {status}");
            }
            Ok(None) => thread::sleep(Duration::from_millis(100)),
            Err(error) => return Err(error).context("poll JDK extraction process"),
        }
    }
}

fn terminate_child(child: &mut Child) {
    let _ = runtime::terminate_process_tree(child, Duration::from_secs(3));
}

fn check_cancelled(cancel: &Arc<AtomicBool>, stage: &str) -> Result<()> {
    if cancel.load(Ordering::Relaxed) {
        return Err(cancelled_error(stage));
    }
    Ok(())
}

fn cancelled_error(stage: &str) -> anyhow::Error {
    anyhow!("JDK preparation cancelled while {stage}")
}

fn sleep_cancellable(duration: Duration, cancel: &Arc<AtomicBool>, stage: &str) -> Result<()> {
    let deadline = Instant::now() + duration;
    while Instant::now() < deadline {
        check_cancelled(cancel, stage)?;
        thread::sleep(Duration::from_millis(50).min(deadline.saturating_duration_since(Instant::now())));
    }
    Ok(())
}

fn find_jdk_home_under(root: &Path, required: u32) -> Option<PathBuf> {
    if valid_jdk_home(root, required) { return Some(root.to_path_buf()); }
    let mut stack = vec![root.to_path_buf()];
    while let Some(current) = stack.pop() {
        let Ok(entries) = fs::read_dir(&current) else {
            continue;
        };
        for entry in entries.filter_map(Result::ok) {
            let Ok(file_type) = entry.file_type() else {
                continue;
            };
            if !file_type.is_dir() { continue; }
            let path = entry.path();
            if valid_jdk_home(&path, required) { return Some(path); }
            stack.push(path);
        }
    }
    None
}

pub fn valid_jdk_home(home: &Path, required: u32) -> bool {
    let java = home.join("bin").join(java_name());
    let javac = home.join("bin").join(javac_name());
    java.is_file() && javac.is_file() && javac_major(&javac) >= required
}

fn javac_path(home: &Path) -> PathBuf { home.join("bin").join(javac_name()) }

pub fn javac_major(javac: &Path) -> u32 {
    let mut command = Command::new(javac);
    command.arg("-version");
    hide_console_window(&mut command);
    let output = match runtime::output_with_timeout(&mut command, Duration::from_secs(5)) {
        Ok(Some(output)) => output,
        _ => return 0,
    };
    let text = format!("{} {}", String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr));
    let rx = Regex::new(r"(?i)javac\s+([0-9]+)").expect("javac version regex");
    rx.captures(&text).and_then(|capture| capture.get(1)).and_then(|value| value.as_str().parse().ok()).unwrap_or(0)
}

fn find_on_path(executable: &str) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    env::split_paths(&path).map(|entry| entry.join(executable)).find(|candidate| candidate.is_file())
}

fn java_name() -> &'static str { if cfg!(windows) { "java.exe" } else { "java" } }
fn javac_name() -> &'static str { if cfg!(windows) { "javac.exe" } else { "javac" } }
fn toolchain_root() -> PathBuf { runtime::cache_root().join("toolchains") }

#[cfg(windows)]
fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_console_window(_command: &mut Command) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fake_oracle_java_directory_is_not_a_jdk() {
        assert!(!valid_jdk_home(Path::new(r"C:\Program Files\Common Files\Oracle\Java"), 21));
    }

    #[test]
    fn toolchain_cache_is_inside_minesport_cache() {
        let value = toolchain_root().to_string_lossy().to_ascii_lowercase();
        assert!(value.contains("minesport"));
        assert!(value.contains("toolchains"));
    }

    #[test]
    fn metadata_retry_statuses_match_legacy_transport() {
        for code in [408, 425, 429, 500, 503, 599] {
            assert!(retryable_http_status(code), "{code}");
        }
        for code in [400, 401, 403, 404, 409, 422] {
            assert!(!retryable_http_status(code), "{code}");
        }
    }

    #[test]
    fn metadata_download_attempt_count_matches_legacy_hardening() {
        assert_eq!(DOWNLOAD_ATTEMPTS, 3);
    }

    #[test]
    fn pre_cancelled_jdk_request_stops_before_detection_or_network() {
        let cancel = Arc::new(AtomicBool::new(true));
        let error = ensure_jdk_cancellable(999, cancel, |_| {}).unwrap_err().to_string();
        assert!(error.to_ascii_lowercase().contains("cancel"));
    }

    #[test]
    fn extraction_has_a_hard_timeout() {
        assert!(JDK_EXTRACTION_TIMEOUT <= Duration::from_secs(5 * 60));
        assert!(NETWORK_READ_SLICE <= Duration::from_secs(10));
    }

    #[test]
    fn legacy_checksum_wrapper_still_verifies_files() {
        let root = std::env::temp_dir().join(format!("minesport-sha-test-{}", std::process::id()));
        fs::write(&root, b"abc").unwrap();
        assert!(verify_sha256(
            &root,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        ).is_ok());
        let _ = fs::remove_file(root);
    }
}
