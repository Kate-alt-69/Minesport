use crate::{diagnostics, engine_lease, ipc, runtime};
use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    cmp::Ordering,
    env, fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    process::Command,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const RELEASE_API: &str = "https://api.github.com/repos/Kate-alt-69/Minesport/releases/latest";
const RELEASE_DOWNLOAD_PREFIX: &str = "https://github.com/Kate-alt-69/Minesport/releases/download/";
const USER_AGENT: &str = "Minesport/0.2.1 engine-updater";
const ENGINE_MANIFEST_SCHEMA: u32 = 1;
const CHECK_INTERVAL: Duration = Duration::from_secs(24 * 60 * 60);
const MAX_RELEASE_JSON: u64 = 2 * 1024 * 1024;
const MAX_ENGINE_MANIFEST: u64 = 64 * 1024;
const MAX_INSTALLER_SIZE: u64 = 512 * 1024 * 1024;
const INSTALL_TIMEOUT: Duration = Duration::from_secs(180);

#[derive(Debug, Clone, Deserialize)]
struct Release {
    tag_name: String,
    assets: Vec<ReleaseAsset>,
}

#[derive(Debug, Clone, Deserialize)]
struct ReleaseAsset {
    name: String,
    browser_download_url: String,
    size: u64,
    digest: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct EngineManifest {
    schema: u32,
    version: String,
    protocol_version: u32,
    sha256: String,
    size: u64,
}

#[derive(Debug, Clone)]
enum LocalEngine {
    Missing,
    Invalid(String),
    Valid(EngineManifest),
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateState {
    checked_at_unix: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct StagedEngineUpdate {
    release_tag: String,
    installer_name: String,
    installer_sha256: String,
    installer_size: u64,
    manifest: EngineManifest,
}

/// Apply a package that was fully downloaded and verified during an earlier
/// GUI session. This runs before `app::run()`, so no minesport-engine process is
/// alive and Windows can safely replace the sidecar executable.
pub fn apply_staged_update() -> Result<()> {
    let executable = env::current_exe()
        .context("resolve current Minesport executable for staged engine update")?;
    if !is_installed_layout(&executable) {
        return Ok(());
    }

    let stage_path = staged_update_path();
    if !stage_path.is_file() {
        return Ok(());
    }
    let Some(_stage_lease) = engine_lease::try_acquire_stage_exclusive()? else {
        diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
            "EngineStagedUpdateBusy",
            "another Minesport process owns the engine update staging area; deferring apply",
            &[],
        );
        return Ok(());
    };
    // The stage can disappear between the cheap pre-check and acquiring the
    // cross-process lock if another process just completed or cleared it.
    if !stage_path.is_file() {
        return Ok(());
    }
    let Some(_engine_use_lease) = engine_lease::try_acquire_engine_use_exclusive()? else {
        diagnostics::Logger::new("ENGINE").child("UPDATE").info(
            "EngineStagedUpdateDeferredInUse",
            "another Minesport process is using the engine; keeping the verified update staged for a later launch",
            &[],
        );
        return Ok(());
    };

    let stage_bytes = fs::read(&stage_path)
        .with_context(|| format!("read staged engine update {}", stage_path.display()))?;
    let stage: StagedEngineUpdate = match serde_json::from_slice(&stage_bytes) {
        Ok(stage) => stage,
        Err(error) => {
            let _ = fs::remove_file(&stage_path);
            return Err(error).context("parse staged engine update metadata");
        }
    };
    validate_staged_update(&stage)?;

    if let LocalEngine::Valid(local) = installed_engine_state(&executable) {
        if compare_versions(&local.version, &stage.manifest.version)? != Ordering::Less {
            diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
                "EngineStagedUpdateNoLongerNeeded",
                "discarding staged engine update because the installed engine is already current or newer",
                &[
                    ("installed_version", local.version),
                    ("staged_version", stage.manifest.version.clone()),
                ],
            );
            clear_staged_update(&stage);
            return Ok(());
        }
    }

    let installer = staged_installer_path(&stage)?;
    let metadata = fs::metadata(&installer)
        .with_context(|| format!("inspect staged engine installer {}", installer.display()))?;
    if metadata.len() != stage.installer_size {
        clear_staged_update(&stage);
        bail!(
            "staged engine installer size mismatch: file={} metadata={}",
            metadata.len(),
            stage.installer_size
        );
    }
    let installer_hash = sha256_file(&installer)?;
    if installer_hash != stage.installer_sha256.to_ascii_lowercase() {
        clear_staged_update(&stage);
        bail!("staged engine installer SHA-256 does not match its saved release digest");
    }

    verify_same_authenticode_signer(&executable, &installer)?;
    diagnostics::Logger::new("ENGINE").child("UPDATE").info(
        "EngineStagedInstallerLaunching",
        "installing previously verified engine update before the backend starts",
        &[
            ("release", stage.release_tag.clone()),
            ("engine_version", stage.manifest.version.clone()),
            ("installer", installer.display().to_string()),
        ],
    );

    let exit_code = run_engine_only_installer_elevated(&installer)?;
    if exit_code != 0 {
        bail!("engine-only installer exited with code {exit_code}");
    }

    let installed = installed_engine_state(&executable);
    match installed {
        LocalEngine::Valid(installed) if manifests_match(&installed, &stage.manifest) => {
            // Keep one previous engine generation after a successful update.
            // Hash/protocol validation proves the files are intact, but the new
            // sidecar has not completed a real process/IPC startup yet. The next
            // installer replacement may rotate this rollback generation.
            clear_staged_update(&stage);
            diagnostics::Logger::new("ENGINE").child("UPDATE").info(
                "EngineStagedUpdateInstalled",
                "staged engine update installed and passed local hash/protocol verification",
                &[
                    ("version", installed.version),
                    ("protocol", installed.protocol_version.to_string()),
                ],
            );
            Ok(())
        }
        LocalEngine::Valid(installed) => {
            let error = anyhow!(
                "engine-only installer completed but installed metadata does not match staged engine {} (installed {})",
                stage.manifest.version,
                installed.version
            );
            rollback_previous_engine(&executable);
            clear_staged_update(&stage);
            Err(error)
        }
        LocalEngine::Missing => {
            rollback_previous_engine(&executable);
            clear_staged_update(&stage);
            bail!("engine-only installer completed but minesport-engine.exe is still missing")
        }
        LocalEngine::Invalid(error) => {
            rollback_previous_engine(&executable);
            clear_staged_update(&stage);
            bail!(
                "engine-only installer completed but the installed sidecar still fails verification: {error}"
            )
        }
    }
}

/// Network/update discovery must never hold up the Workbench. A healthy local
/// engine is checked at most once per day. Missing/corrupt engines ignore that
/// interval so a later launch can recover as soon as a Release exists.
pub fn spawn_background_check() {
    if let Err(error) = thread::Builder::new()
        .name("minesport-engine-update".to_string())
        .spawn(|| {
            if let Err(error) = check_and_stage_update_if_due() {
                diagnostics::Logger::new("ENGINE").child("UPDATE").warn(
                    "EngineBackgroundUpdateUnavailable",
                    "engine repair/update check failed; the current verified engine or embedded fallback remains active",
                    &[("error", format!("{error:#}"))],
                );
            }
        })
    {
        diagnostics::Logger::new("ENGINE").child("UPDATE").warn(
            "EngineUpdateThreadUnavailable",
            "could not start background engine update checker",
            &[("error", error.to_string())],
        );
    }
}

fn check_and_stage_update_if_due() -> Result<()> {
    let executable =
        env::current_exe().context("resolve current Minesport executable for engine update")?;
    let installed = installed_engine_state(&executable);
    if !is_installed_layout(&executable) {
        if !matches!(installed, LocalEngine::Valid(_)) {
            diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
                "EngineUpdateSkippedDevelopmentLayout",
                "engine auto-repair is disabled outside an installed Windows layout",
                &[("executable", executable.display().to_string())],
            );
        }
        return Ok(());
    }

    let Some(_stage_lease) = engine_lease::try_acquire_stage_exclusive()? else {
        diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
            "EngineUpdateCheckBusy",
            "another Minesport process owns the engine update staging area; skipping this background pass",
            &[],
        );
        return Ok(());
    };

    if staged_update_path().is_file() {
        diagnostics::Logger::new("ENGINE").child("UPDATE").debug(
            "EngineUpdateAlreadyStaged",
            "a verified engine update is already staged for the next launch",
            &[],
        );
        return Ok(());
    }

    let force = env::var("MINESPORT_ENGINE_UPDATE_CHECK")
        .ok()
        .is_some_and(|value| value.eq_ignore_ascii_case("always"));
    let repair_required = !matches!(installed, LocalEngine::Valid(_));
    if !repair_required && !force && !update_check_due() {
        return Ok(());
    }

    let logger = diagnostics::Logger::new("ENGINE").child("UPDATE");
    match &installed {
        LocalEngine::Missing => logger.warn(
            "EngineSidecarMissingRepairRequested",
            "installed Minesport engine sidecar is missing; checking Releases for a repair package",
            &[],
        ),
        LocalEngine::Invalid(error) => logger.warn(
            "EngineSidecarInvalidRepairRequested",
            "installed Minesport engine sidecar failed verification; checking Releases for a repair package",
            &[("error", error.clone())],
        ),
        LocalEngine::Valid(manifest) => logger.debug(
            "EngineUpdateCheckDue",
            "checking Releases for an independently newer engine",
            &[("installed_version", manifest.version.clone())],
        ),
    }

    // Record healthy-engine attempts before network I/O. Offline machines must
    // not pay the same timeout on every launch. Broken engines intentionally
    // retry every launch until a repair can be staged.
    if !repair_required {
        let _ = record_update_check();
    }

    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(5))
        .timeout_read(Duration::from_secs(30))
        .timeout_write(Duration::from_secs(10))
        .build();
    let Some(release) = fetch_latest_release(&agent)? else {
        logger.warn(
            "EngineUpdateReleaseUnavailable",
            "no published GitHub Release is available for engine repair/update",
            &[],
        );
        return Ok(());
    };

    let manifest_asset = release
        .assets
        .iter()
        .find(|asset| asset.name.eq_ignore_ascii_case("minesport-engine.json"))
        .ok_or_else(|| {
            anyhow!(
                "latest release {} has no minesport-engine.json asset",
                release.tag_name
            )
        })?;
    let remote_bytes = download_small_verified_asset(&agent, manifest_asset, MAX_ENGINE_MANIFEST)?;
    let remote: EngineManifest =
        serde_json::from_slice(&remote_bytes).context("parse release minesport-engine.json")?;
    validate_manifest_shape(&remote)?;
    if remote.protocol_version != ipc::ENGINE_PROTOCOL_VERSION {
        bail!(
            "release engine protocol {} is incompatible with this GUI protocol {}",
            remote.protocol_version,
            ipc::ENGINE_PROTOCOL_VERSION
        );
    }

    if let LocalEngine::Valid(local) = &installed {
        match compare_versions(&remote.version, &local.version)? {
            Ordering::Less | Ordering::Equal => {
                logger.debug(
                    "EngineUpdateNotRequired",
                    "installed engine is current for the latest compatible release",
                    &[
                        ("installed_version", local.version.clone()),
                        ("release_version", remote.version.clone()),
                    ],
                );
                return Ok(());
            }
            Ordering::Greater => {}
        }
    }

    // Refuse to download a large executable when this Minesport build has no
    // publisher certificate to anchor the installer trust decision against.
    let running_signer = authenticode_thumbprint(&executable)
        .context("verify publisher certificate before downloading engine update")?;
    let installer_asset = select_installer_asset(&release)?;
    let update_root = update_root();
    fs::create_dir_all(&update_root).with_context(|| {
        format!(
            "create engine update staging directory {}",
            update_root.display()
        )
    })?;
    let installer = update_root.join(&installer_asset.name);
    download_verified_asset(&agent, installer_asset, &installer, MAX_INSTALLER_SIZE)?;

    let result = (|| -> Result<()> {
        let installer_signer = authenticode_thumbprint(&installer)
            .context("verify Authenticode signature on downloaded Minesport installer")?;
        if !running_signer.eq_ignore_ascii_case(&installer_signer) {
            bail!(
                "downloaded installer signer does not match the currently running Minesport publisher certificate"
            );
        }

        let installer_sha256 = release_asset_sha256(installer_asset)?;
        let stage = StagedEngineUpdate {
            release_tag: release.tag_name.clone(),
            installer_name: installer_asset.name.clone(),
            installer_sha256,
            installer_size: installer_asset.size,
            manifest: remote.clone(),
        };
        publish_staged_update(&stage)?;
        logger.info(
            "EngineUpdateStaged",
            "verified engine repair/update package staged for the next clean Minesport launch",
            &[
                ("release", release.tag_name.clone()),
                ("engine_version", remote.version.clone()),
                ("installer", installer.display().to_string()),
            ],
        );
        Ok(())
    })();

    if result.is_err() {
        let _ = fs::remove_file(&installer);
    }
    result
}

fn fetch_latest_release(agent: &ureq::Agent) -> Result<Option<Release>> {
    let response = match agent
        .get(RELEASE_API)
        .set("User-Agent", USER_AGENT)
        .set("Accept", "application/vnd.github+json")
        .set("X-GitHub-Api-Version", "2022-11-28")
        .call()
    {
        Ok(response) => response,
        Err(ureq::Error::Status(404, _)) => return Ok(None),
        Err(error) => return Err(anyhow!(error)).context("query latest Minesport GitHub Release"),
    };
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(MAX_RELEASE_JSON + 1)
        .read_to_end(&mut bytes)
        .context("read latest Minesport GitHub Release")?;
    if bytes.len() as u64 > MAX_RELEASE_JSON {
        bail!(
            "latest Minesport GitHub Release response exceeds {} bytes",
            MAX_RELEASE_JSON
        );
    }
    let release: Release =
        serde_json::from_slice(&bytes).context("decode latest Minesport GitHub Release")?;
    Ok(Some(release))
}

fn select_installer_asset(release: &Release) -> Result<&ReleaseAsset> {
    let version = release.tag_name.trim_start_matches(['v', 'V']);
    let exact = format!("Minesport-{version}-Setup-x64.exe");
    if let Some(asset) = release
        .assets
        .iter()
        .find(|asset| asset.name.eq_ignore_ascii_case(&exact))
    {
        return Ok(asset);
    }
    let mut candidates = release.assets.iter().filter(|asset| {
        asset.name.starts_with("Minesport-") && asset.name.ends_with("-Setup-x64.exe")
    });
    let first = candidates.next().ok_or_else(|| {
        anyhow!(
            "latest release {} has no x64 NSIS setup asset",
            release.tag_name
        )
    })?;
    if candidates.next().is_some() {
        bail!(
            "latest release {} has multiple x64 setup assets and none matches expected name {exact}",
            release.tag_name
        );
    }
    Ok(first)
}

fn download_small_verified_asset(
    agent: &ureq::Agent,
    asset: &ReleaseAsset,
    max_size: u64,
) -> Result<Vec<u8>> {
    if asset.size == 0 || asset.size > max_size {
        bail!(
            "release asset {} has unsafe size {}",
            asset.name,
            asset.size
        );
    }
    validate_release_asset_url(asset)?;
    let expected = release_asset_sha256(asset)?;
    let response = agent
        .get(&asset.browser_download_url)
        .set("User-Agent", USER_AGENT)
        .call()
        .with_context(|| format!("download release asset {}", asset.name))?;
    let mut reader = response.into_reader().take(max_size + 1);
    let mut bytes = Vec::with_capacity(asset.size as usize);
    reader
        .read_to_end(&mut bytes)
        .with_context(|| format!("read release asset {}", asset.name))?;
    if bytes.len() as u64 != asset.size {
        bail!(
            "release asset {} size mismatch: received={} metadata={}",
            asset.name,
            bytes.len(),
            asset.size
        );
    }
    let actual = sha256_bytes(&bytes);
    if actual != expected {
        bail!("release asset {} SHA-256 mismatch", asset.name);
    }
    Ok(bytes)
}

fn download_verified_asset(
    agent: &ureq::Agent,
    asset: &ReleaseAsset,
    destination: &Path,
    max_size: u64,
) -> Result<()> {
    if asset.size == 0 || asset.size > max_size {
        bail!(
            "release asset {} has unsafe size {}",
            asset.name,
            asset.size
        );
    }
    validate_release_asset_url(asset)?;
    let expected = release_asset_sha256(asset)?;
    let temporary = destination.with_extension("part");
    let _ = fs::remove_file(&temporary);
    let _ = fs::remove_file(destination);

    let response = agent
        .get(&asset.browser_download_url)
        .set("User-Agent", USER_AGENT)
        .call()
        .with_context(|| format!("download release asset {}", asset.name))?;
    let mut reader = response.into_reader();
    let mut file = fs::File::create(&temporary)
        .with_context(|| format!("create update download {}", temporary.display()))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 1024 * 1024];
    let mut total = 0_u64;
    loop {
        let read = reader
            .read(&mut buffer)
            .with_context(|| format!("read release asset {}", asset.name))?;
        if read == 0 {
            break;
        }
        total = total.saturating_add(read as u64);
        if total > max_size || total > asset.size {
            let _ = fs::remove_file(&temporary);
            bail!(
                "release asset {} exceeded declared/allowed size",
                asset.name
            );
        }
        file.write_all(&buffer[..read])
            .with_context(|| format!("write update download {}", temporary.display()))?;
        hasher.update(&buffer[..read]);
    }
    file.flush().context("flush engine installer download")?;
    file.sync_all().context("sync engine installer download")?;
    drop(file);

    if total != asset.size {
        let _ = fs::remove_file(&temporary);
        bail!(
            "release asset {} size mismatch: received={total} metadata={}",
            asset.name,
            asset.size
        );
    }
    let digest = hasher.finalize();
    let actual = hex_digest(&digest);
    if actual != expected {
        let _ = fs::remove_file(&temporary);
        bail!("release asset {} SHA-256 mismatch", asset.name);
    }
    fs::rename(&temporary, destination).with_context(|| {
        format!(
            "publish verified engine installer {} -> {}",
            temporary.display(),
            destination.display()
        )
    })?;
    Ok(())
}

fn validate_release_asset_url(asset: &ReleaseAsset) -> Result<()> {
    if !asset
        .browser_download_url
        .starts_with(RELEASE_DOWNLOAD_PREFIX)
    {
        bail!(
            "refusing release asset {} from unexpected URL {}",
            asset.name,
            asset.browser_download_url
        );
    }
    Ok(())
}

fn release_asset_sha256(asset: &ReleaseAsset) -> Result<String> {
    let digest = asset
        .digest
        .as_deref()
        .ok_or_else(|| anyhow!("release asset {} has no GitHub SHA-256 digest", asset.name))?;
    let value = digest
        .strip_prefix("sha256:")
        .ok_or_else(|| {
            anyhow!(
                "release asset {} uses unsupported digest {digest}",
                asset.name
            )
        })?
        .to_ascii_lowercase();
    validate_sha256(&value)?;
    Ok(value)
}

fn installed_engine_state(desktop_executable: &Path) -> LocalEngine {
    let Some(root) = desktop_executable.parent() else {
        return LocalEngine::Invalid("Minesport executable has no install directory".to_string());
    };
    let engine = root.join("minesport-engine.exe");
    if !engine.is_file() {
        return LocalEngine::Missing;
    }
    match validate_installed_engine(&engine) {
        Ok(manifest) => LocalEngine::Valid(manifest),
        Err(error) => LocalEngine::Invalid(format!("{error:#}")),
    }
}

fn validate_installed_engine(engine: &Path) -> Result<EngineManifest> {
    let manifest_path = engine.with_file_name("minesport-engine.json");
    let bytes = fs::read(&manifest_path)
        .with_context(|| format!("read installed engine manifest {}", manifest_path.display()))?;
    let manifest: EngineManifest = serde_json::from_slice(&bytes).with_context(|| {
        format!(
            "parse installed engine manifest {}",
            manifest_path.display()
        )
    })?;
    validate_manifest_shape(&manifest)?;
    if manifest.protocol_version != ipc::ENGINE_PROTOCOL_VERSION {
        bail!(
            "installed engine protocol {} is incompatible with GUI protocol {}",
            manifest.protocol_version,
            ipc::ENGINE_PROTOCOL_VERSION
        );
    }
    let metadata = fs::metadata(engine)
        .with_context(|| format!("inspect installed engine {}", engine.display()))?;
    if metadata.len() != manifest.size {
        bail!(
            "installed engine size mismatch: file={} manifest={}",
            metadata.len(),
            manifest.size
        );
    }
    let actual = sha256_file(engine)?;
    if actual != manifest.sha256.to_ascii_lowercase() {
        bail!("installed engine SHA-256 does not match its manifest");
    }
    Ok(manifest)
}

fn validate_manifest_shape(manifest: &EngineManifest) -> Result<()> {
    if manifest.schema != ENGINE_MANIFEST_SCHEMA {
        bail!(
            "unsupported engine manifest schema {}; expected {}",
            manifest.schema,
            ENGINE_MANIFEST_SCHEMA
        );
    }
    if manifest.version.trim().is_empty() {
        bail!("engine manifest version is empty");
    }
    if manifest.protocol_version == 0 {
        bail!("engine manifest protocolVersion must be positive");
    }
    if manifest.size == 0 {
        bail!("engine manifest size must be positive");
    }
    validate_sha256(&manifest.sha256.to_ascii_lowercase())?;
    Ok(())
}

fn manifests_match(left: &EngineManifest, right: &EngineManifest) -> bool {
    left.schema == right.schema
        && left.version == right.version
        && left.protocol_version == right.protocol_version
        && left.sha256.eq_ignore_ascii_case(&right.sha256)
        && left.size == right.size
}

fn publish_staged_update(stage: &StagedEngineUpdate) -> Result<()> {
    validate_staged_update(stage)?;
    let path = staged_update_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create staged update directory {}", parent.display()))?;
    }
    let temporary = path.with_extension("tmp");
    let bytes = serde_json::to_vec_pretty(stage).context("encode staged engine update metadata")?;
    fs::write(&temporary, bytes)
        .with_context(|| format!("write staged engine update {}", temporary.display()))?;
    let _ = fs::remove_file(&path);
    fs::rename(&temporary, &path)
        .with_context(|| format!("publish staged engine update {}", path.display()))?;
    Ok(())
}

fn validate_staged_update(stage: &StagedEngineUpdate) -> Result<()> {
    validate_manifest_shape(&stage.manifest)?;
    if stage.manifest.protocol_version != ipc::ENGINE_PROTOCOL_VERSION {
        bail!(
            "staged engine protocol {} is incompatible with GUI protocol {}",
            stage.manifest.protocol_version,
            ipc::ENGINE_PROTOCOL_VERSION
        );
    }
    validate_sha256(&stage.installer_sha256.to_ascii_lowercase())?;
    if stage.installer_size == 0 || stage.installer_size > MAX_INSTALLER_SIZE {
        bail!(
            "staged engine installer has unsafe size {}",
            stage.installer_size
        );
    }
    let path = Path::new(&stage.installer_name);
    if path.file_name().and_then(|name| name.to_str()) != Some(stage.installer_name.as_str()) {
        bail!("staged engine installer name is not a plain file name");
    }
    if !stage.installer_name.starts_with("Minesport-")
        || !stage.installer_name.ends_with("-Setup-x64.exe")
    {
        bail!("staged engine installer name is not a Minesport x64 setup executable");
    }
    Ok(())
}

fn staged_installer_path(stage: &StagedEngineUpdate) -> Result<PathBuf> {
    validate_staged_update(stage)?;
    Ok(update_root().join(&stage.installer_name))
}

fn clear_staged_update(stage: &StagedEngineUpdate) {
    if let Ok(installer) = staged_installer_path(stage) {
        let _ = fs::remove_file(installer);
    }
    let _ = fs::remove_file(staged_update_path());
}

fn rollback_previous_engine(desktop_executable: &Path) {
    let Some(root) = desktop_executable.parent() else {
        return;
    };
    let engine = root.join("minesport-engine.exe");
    let manifest = root.join("minesport-engine.json");
    let previous_engine = root.join("minesport-engine.exe.prev");
    let previous_manifest = root.join("minesport-engine.json.prev");
    if !previous_engine.is_file() || !previous_manifest.is_file() {
        return;
    }

    let _ = fs::remove_file(&engine);
    let _ = fs::remove_file(&manifest);
    let engine_restored = fs::rename(&previous_engine, &engine).is_ok();
    let manifest_restored = fs::rename(&previous_manifest, &manifest).is_ok();
    diagnostics::Logger::new("ENGINE").child("UPDATE").warn(
        "EngineUpdateRollback",
        "engine update validation failed; attempted to restore the previous sidecar generation",
        &[
            ("engine_restored", engine_restored.to_string()),
            ("manifest_restored", manifest_restored.to_string()),
        ],
    );
}

fn verify_same_authenticode_signer(current_exe: &Path, installer: &Path) -> Result<()> {
    let running_signer = authenticode_thumbprint(current_exe)
        .context("verify Authenticode signature on running Minesport.exe")?;
    let installer_signer = authenticode_thumbprint(installer)
        .context("verify Authenticode signature on downloaded Minesport installer")?;
    if !running_signer.eq_ignore_ascii_case(&installer_signer) {
        bail!(
            "downloaded installer signer does not match the currently running Minesport publisher certificate"
        );
    }
    Ok(())
}

fn authenticode_thumbprint(path: &Path) -> Result<String> {
    let powershell = powershell_executable();
    let script = "$s=Get-AuthenticodeSignature -LiteralPath $env:MINESPORT_VERIFY_FILE; if($s.Status -ne [System.Management.Automation.SignatureStatus]::Valid){exit 31}; if($null -eq $s.SignerCertificate){exit 32}; [Console]::Out.Write($s.SignerCertificate.Thumbprint)";
    let mut command = Command::new(powershell);
    command
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script,
        ])
        .env("MINESPORT_VERIFY_FILE", path)
        .env_remove("MINESPORT_ENGINE_UPDATE_CHECK");
    hide_console_window(&mut command);
    let output = runtime::output_with_timeout(&mut command, Duration::from_secs(15))?
        .ok_or_else(|| anyhow!("Authenticode verification timed out for {}", path.display()))?;
    if !output.status.success() {
        bail!(
            "Authenticode signature is not valid for {} (status {})",
            path.display(),
            output.status
        );
    }
    let thumbprint = String::from_utf8(output.stdout)
        .context("decode Authenticode certificate thumbprint")?
        .trim()
        .to_ascii_uppercase();
    if thumbprint.len() < 32 || !thumbprint.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        bail!("Authenticode signer returned an invalid certificate thumbprint");
    }
    Ok(thumbprint)
}

fn run_engine_only_installer_elevated(installer: &Path) -> Result<i32> {
    let powershell = powershell_executable();
    let script = "$ErrorActionPreference='Stop'; $p=Start-Process -FilePath $env:MINESPORT_ENGINE_INSTALLER -ArgumentList @('--installonly-engine','--nogui') -Verb RunAs -Wait -PassThru; [Console]::Out.Write($p.ExitCode)";
    let mut command = Command::new(powershell);
    command
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script,
        ])
        .env("MINESPORT_ENGINE_INSTALLER", installer)
        .env_remove("MINESPORT_ENGINE_UPDATE_CHECK");
    hide_console_window(&mut command);
    let output = runtime::output_with_timeout(&mut command, INSTALL_TIMEOUT)?
        .ok_or_else(|| anyhow!("engine-only installer elevation/launch timed out"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        bail!(
            "could not launch elevated engine-only installer (PowerShell status {}{}); UAC may have been cancelled",
            output.status,
            if stderr.is_empty() {
                String::new()
            } else {
                format!(": {stderr}")
            }
        );
    }
    let text =
        String::from_utf8(output.stdout).context("decode engine-only installer exit code")?;
    text.trim()
        .parse::<i32>()
        .with_context(|| format!("parse engine-only installer exit code from {text:?}"))
}

fn powershell_executable() -> PathBuf {
    env::var_os("SystemRoot")
        .map(PathBuf::from)
        .map(|root| root.join("System32/WindowsPowerShell/v1.0/powershell.exe"))
        .filter(|candidate| candidate.is_file())
        .unwrap_or_else(|| PathBuf::from("powershell.exe"))
}

fn compare_versions(left: &str, right: &str) -> Result<Ordering> {
    let left = parse_semver_core(left)
        .ok_or_else(|| anyhow!("engine version is not semantic version x.y.z: {left}"))?;
    let right = parse_semver_core(right)
        .ok_or_else(|| anyhow!("engine version is not semantic version x.y.z: {right}"))?;
    Ok(left.cmp(&right))
}

fn parse_semver_core(value: &str) -> Option<(u64, u64, u64)> {
    let core = value
        .trim()
        .trim_start_matches(['v', 'V'])
        .split(['-', '+'])
        .next()?;
    let mut parts = core.split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next()?.parse().ok()?;
    if parts.next().is_some() {
        return None;
    }
    Some((major, minor, patch))
}

fn update_check_due() -> bool {
    let Ok(bytes) = fs::read(update_state_path()) else {
        return true;
    };
    let Ok(state) = serde_json::from_slice::<UpdateState>(&bytes) else {
        return true;
    };
    let now = unix_now();
    now.saturating_sub(state.checked_at_unix) >= CHECK_INTERVAL.as_secs()
}

fn record_update_check() -> Result<()> {
    let path = update_state_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create update state directory {}", parent.display()))?;
    }
    let temporary = path.with_extension("tmp");
    let bytes = serde_json::to_vec(&UpdateState {
        checked_at_unix: unix_now(),
    })?;
    fs::write(&temporary, bytes)
        .with_context(|| format!("write engine update state {}", temporary.display()))?;
    let _ = fs::remove_file(&path);
    fs::rename(&temporary, &path)
        .with_context(|| format!("publish engine update state {}", path.display()))?;
    Ok(())
}

fn update_state_path() -> PathBuf {
    runtime::data_root().join("engine-update-state.json")
}

fn update_root() -> PathBuf {
    // A verified package is pending durable state, not disposable cache. Cache
    // cleanup must never delete an installer while another launch is applying it.
    runtime::data_root().join("engine-update").join("staged")
}

fn staged_update_path() -> PathBuf {
    update_root().join("staged-engine-update.json")
}

fn is_installed_layout(executable: &Path) -> bool {
    if env::var("MINESPORT_ENGINE_AUTO_REPAIR")
        .ok()
        .is_some_and(|value| matches!(value.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
    {
        return true;
    }
    let Some(root) = executable.parent() else {
        return false;
    };
    if root.join("Uninstall.exe").is_file() {
        return true;
    }
    [env::var_os("ProgramFiles"), env::var_os("ProgramW6432")]
        .into_iter()
        .flatten()
        .map(PathBuf::from)
        .any(|program_files| path_starts_with_case_insensitive(executable, &program_files))
}

fn path_starts_with_case_insensitive(path: &Path, parent: &Path) -> bool {
    let path = path
        .to_string_lossy()
        .replace('/', "\\")
        .to_ascii_lowercase();
    let mut parent = parent
        .to_string_lossy()
        .replace('/', "\\")
        .to_ascii_lowercase();
    while parent.ends_with('\\') {
        parent.pop();
    }
    path == parent || path.starts_with(&(parent + "\\"))
}

fn sha256_file(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)
        .with_context(|| format!("open file for SHA-256 {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 1024 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .with_context(|| format!("read file for SHA-256 {}", path.display()))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    let digest = hasher.finalize();
    Ok(hex_digest(&digest))
}

fn sha256_bytes(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    hex_digest(&digest)
}

fn hex_digest(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        encoded.push(HEX[(byte >> 4) as usize] as char);
        encoded.push(HEX[(byte & 0x0f) as usize] as char);
    }
    encoded
}

fn validate_sha256(value: &str) -> Result<()> {
    if value.len() != 64 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        bail!("invalid SHA-256 digest");
    }
    Ok(())
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn semantic_engine_versions_compare_numerically() {
        assert_eq!(
            compare_versions("0.2.10", "0.2.9").unwrap(),
            Ordering::Greater
        );
        assert_eq!(
            compare_versions("v1.0.0", "1.0.0").unwrap(),
            Ordering::Equal
        );
        assert_eq!(
            compare_versions("0.3.0-beta.1", "0.2.99").unwrap(),
            Ordering::Greater
        );
    }

    #[test]
    fn release_digest_must_be_sha256() {
        let asset = ReleaseAsset {
            name: "test".to_string(),
            browser_download_url: format!("{RELEASE_DOWNLOAD_PREFIX}v0.2.1/test"),
            size: 1,
            digest: Some(format!("sha256:{}", "a".repeat(64))),
        };
        assert_eq!(release_asset_sha256(&asset).unwrap(), "a".repeat(64));
    }

    #[test]
    fn release_asset_url_is_repository_scoped() {
        let asset = ReleaseAsset {
            name: "test".to_string(),
            browser_download_url: "https://example.com/test".to_string(),
            size: 1,
            digest: Some(format!("sha256:{}", "a".repeat(64))),
        };
        assert!(validate_release_asset_url(&asset).is_err());
    }

    #[test]
    fn staged_installer_name_rejects_path_traversal() {
        let stage = StagedEngineUpdate {
            release_tag: "v0.2.2".to_string(),
            installer_name: "../Minesport-0.2.2-Setup-x64.exe".to_string(),
            installer_sha256: "a".repeat(64),
            installer_size: 123,
            manifest: EngineManifest {
                schema: 1,
                version: "0.2.2".to_string(),
                protocol_version: ipc::ENGINE_PROTOCOL_VERSION,
                sha256: "b".repeat(64),
                size: 456,
            },
        };
        assert!(validate_staged_update(&stage).is_err());
    }
}
