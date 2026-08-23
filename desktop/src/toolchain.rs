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
    process::Command,
    time::Duration,
};

const DOWNLOAD_ATTEMPTS: usize = 3;

#[derive(Debug, Clone)]
pub struct ToolchainProgress {
    pub percent: i32,
    pub message: String,
}

pub fn ensure_jdk<F>(required: u32, mut progress: F) -> Result<PathBuf>
where
    F: FnMut(ToolchainProgress),
{
    progress(ToolchainProgress { percent: 40, message: format!("Checking for JDK {required}…") });
    if let Some(home) = find_installed_jdk(required) {
        progress(ToolchainProgress { percent: 44, message: format!("Using installed JDK {} · {}", javac_major(&javac_path(&home)), home.display()) });
        return Ok(home);
    }

    progress(ToolchainProgress { percent: 44, message: format!("JDK {required} not installed · checking Minesport toolchain cache") });
    if let Some(home) = find_cached_jdk(required) {
        progress(ToolchainProgress { percent: 47, message: format!("Using cached JDK {required} · {}", home.display()) });
        return Ok(home);
    }

    progress(ToolchainProgress { percent: 47, message: format!("Downloading verified JDK {required}…") });
    let home = download_adoptium_jdk(required, &mut progress)?;
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
        if let Ok(output) = command.output() {
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

fn download_adoptium_jdk<F>(required: u32, progress: &mut F) -> Result<PathBuf>
where
    F: FnMut(ToolchainProgress),
{
    let os_name = if cfg!(windows) { "windows" } else if cfg!(target_os = "macos") { "mac" } else if cfg!(target_os = "linux") { "linux" } else { bail!("automatic JDK download is unsupported on this operating system") };
    let arch = match env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "aarch64",
        other => bail!("automatic JDK download does not support architecture {other}"),
    };
    let endpoint = format!(
        "https://api.adoptium.net/v3/assets/latest/{required}/hotspot?architecture={arch}&image_type=jdk&jvm_impl=hotspot&os={os_name}&vendor=eclipse"
    );
    let metadata = http_get_bytes(&endpoint, 8 * 1024 * 1024, Duration::from_secs(120))?;

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

    let root = toolchain_root().join(format!("jdk-{required}"));
    if root.exists() { fs::remove_dir_all(&root).with_context(|| format!("reset {}", root.display()))?; }
    fs::create_dir_all(&root)?;
    let archive_name = Path::new(&package.name).file_name().and_then(|value| value.to_str()).filter(|value| !value.is_empty())
        .unwrap_or(if cfg!(windows) { "jdk.zip" } else { "jdk.tar.gz" });
    let archive = root.join(archive_name);

    progress(ToolchainProgress { percent: 48, message: format!("Downloading Eclipse Temurin JDK {required}…") });
    download_file(&package.link, &archive)?;
    if !package.checksum.trim().is_empty() {
        verify_sha256(&archive, package.checksum.trim())?;
    }
    progress(ToolchainProgress { percent: 51, message: format!("Verified JDK {required} · extracting…") });

    let extraction = root.join("runtime");
    fs::create_dir_all(&extraction)?;
    extract_archive(&archive, &extraction)?;
    let _ = fs::remove_file(&archive);
    let home = find_jdk_home_under(&extraction, required)
        .ok_or_else(|| anyhow!("downloaded JDK {required} did not contain a compatible javac"))?;
    Ok(home)
}

fn download_file(url: &str, destination: &Path) -> Result<()> {
    if !url.starts_with("https://") { bail!("JDK download URL must use HTTPS"); }
    if let Some(parent) = destination.parent() { fs::create_dir_all(parent)?; }

    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(30))
        .timeout_read(Duration::from_secs(180))
        .build();
    let mut last_error = None;
    for attempt in 1..=DOWNLOAD_ATTEMPTS {
        let _ = fs::remove_file(destination);
        match download_file_once(&agent, url, destination) {
            Ok(()) => return Ok(()),
            Err(error) => {
                last_error = Some(error);
                if attempt < DOWNLOAD_ATTEMPTS { std::thread::sleep(Duration::from_millis(500 * attempt as u64)); }
            }
        }
    }
    let _ = fs::remove_file(destination);
    let error = last_error.unwrap_or_else(|| anyhow!("unknown download failure"));
    Err(anyhow!("download {url} failed after {DOWNLOAD_ATTEMPTS} attempts: {error:#}"))
}

fn download_file_once(agent: &ureq::Agent, url: &str, destination: &Path) -> Result<()> {
    let response = agent.get(url).set("User-Agent", "Minesport-Rust-Toolchain/0.2.0").call()
        .map_err(|error| anyhow!("JDK download request failed: {error}"))?;
    let mut reader = response.into_reader();
    let mut output = File::create(destination).with_context(|| format!("create {}", destination.display()))?;
    std::io::copy(&mut reader, &mut output).with_context(|| format!("download into {}", destination.display()))?;
    output.flush()?;
    Ok(())
}

fn http_get_bytes(url: &str, limit: u64, timeout: Duration) -> Result<Vec<u8>> {
    if !url.starts_with("https://") { bail!("HTTP metadata URL must use HTTPS: {url}"); }
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(timeout)
        .build();
    let mut last_error = None;
    for attempt in 1..=DOWNLOAD_ATTEMPTS {
        match http_get_bytes_once(&agent, url, limit) {
            Ok(data) => return Ok(data),
            Err(error) => {
                last_error = Some(error);
                if attempt < DOWNLOAD_ATTEMPTS {
                    std::thread::sleep(Duration::from_millis(400 * attempt as u64));
                }
            }
        }
    }
    let error = last_error.unwrap_or_else(|| anyhow!("unknown HTTP failure"));
    Err(anyhow!("HTTP request failed for {url} after {DOWNLOAD_ATTEMPTS} attempts: {error:#}"))
}

fn http_get_bytes_once(agent: &ureq::Agent, url: &str, limit: u64) -> Result<Vec<u8>> {
    let response = agent
        .get(url)
        .set("User-Agent", "Minesport-Rust-Toolchain/0.2.0")
        .call()
        .map_err(|error| anyhow!("HTTP request failed for {url}: {error}"))?;
    let mut reader = response.into_reader().take(limit + 1);
    let mut data = Vec::new();
    reader.read_to_end(&mut data)?;
    if data.len() as u64 > limit { bail!("HTTP response exceeded {limit} bytes: {url}"); }
    Ok(data)
}

fn verify_sha256(path: &Path, expected: &str) -> Result<()> {
    let mut file = File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 128 * 1024];
    loop {
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

fn extract_archive(archive: &Path, destination: &Path) -> Result<()> {
    if cfg!(windows) {
        let mut command = Command::new("powershell.exe");
        command
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command"])
            .arg("param($archive,$dest) Expand-Archive -LiteralPath $archive -DestinationPath $dest -Force")
            .arg(archive)
            .arg(destination);
        hide_console_window(&mut command);
        let status = command.status().context("launch PowerShell Expand-Archive for JDK")?;
        if !status.success() { bail!("PowerShell failed to extract downloaded JDK: {status}"); }
        return Ok(());
    }

    let status = Command::new("tar")
        .arg("-xzf").arg(archive)
        .arg("-C").arg(destination)
        .status().context("launch tar for downloaded JDK")?;
    if !status.success() { bail!("tar failed to extract downloaded JDK: {status}"); }
    Ok(())
}

fn find_jdk_home_under(root: &Path, required: u32) -> Option<PathBuf> {
    if valid_jdk_home(root, required) { return Some(root.to_path_buf()); }
    let mut stack = vec![root.to_path_buf()];
    while let Some(current) = stack.pop() {
        let entries = fs::read_dir(&current).ok()?;
        for entry in entries.flatten() {
            if !entry.file_type().ok()?.is_dir() { continue; }
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
    let output = match command.output() {
        Ok(output) => output,
        Err(_) => return 0,
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
    fn metadata_download_attempt_count_matches_legacy_hardening() {
        assert_eq!(DOWNLOAD_ATTEMPTS, 3);
    }
}
