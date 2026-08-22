use anyhow::{Context, Result, anyhow, bail};
use include_dir::{Dir, include_dir};
use regex::Regex;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    io::Read,
    path::{Component, Path, PathBuf},
    time::Duration,
};

static BRIDGE_SOURCE: Dir<'_> = include_dir!("$OUT_DIR/bridge-source");
static BRIDGE_VERSIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../bridge-versions");
const MANIFEST_JSON: &str = include_str!("../../bridge-versions/manifest.json");
const MAX_NETWORK_TEXT: u64 = 8 * 1024 * 1024;

#[derive(Debug, Clone, Deserialize)]
pub struct Manifest {
    pub schema: i32,
    #[serde(default)]
    pub repository: String,
    #[serde(default, rename = "ref")]
    pub git_ref: String,
    pub base: BaseSpec,
    #[serde(default)]
    pub profiles: Vec<Profile>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct BaseSpec {
    pub version: String,
    #[serde(default)]
    pub compatible: Vec<String>,
    pub source_root: String,
    #[serde(default)]
    pub files: Vec<String>,
    #[serde(default)]
    pub bundled_jar: String,
    pub java: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Profile {
    pub id: String,
    #[serde(rename = "match")]
    pub match_expression: String,
    pub patch: String,
    pub java: u32,
    #[serde(default)]
    pub gradle: String,
    #[serde(default)]
    pub loom: String,
    #[serde(default)]
    pub loader: String,
    #[serde(default, rename = "fabric_api")]
    pub fabric_api: String,
}

#[derive(Debug, Clone, Deserialize)]
struct PatchSet {
    schema: i32,
    #[allow(dead_code)]
    #[serde(default)]
    description: String,
    #[serde(default)]
    operations: Vec<PatchOperation>,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct PatchOperation {
    op: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    file: String,
    #[serde(default)]
    root: String,
    #[serde(default)]
    extensions: Vec<String>,
    #[serde(default)]
    from: String,
    #[serde(default)]
    to: String,
    #[serde(default)]
    pattern: String,
    #[serde(default)]
    replacement: String,
    #[serde(default)]
    source: String,
    #[serde(default)]
    target: String,
    #[serde(default, rename = "url")]
    url: String,
    #[serde(default)]
    sha256: String,
    #[serde(default)]
    key: String,
    #[serde(default)]
    value: String,
    #[serde(default)]
    line: usize,
    #[serde(default)]
    column: usize,
}

#[derive(Debug, Clone)]
pub struct PreparedSource {
    pub version: String,
    pub workspace: PathBuf,
    pub java: u32,
    pub profile_id: String,
    pub variables: HashMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct CompatProgress {
    pub percent: i32,
    pub stage: String,
    pub detail: String,
}

pub fn manifest() -> Result<Manifest> {
    let parsed: Manifest = serde_json::from_str(MANIFEST_JSON).context("parse embedded bridge compatibility manifest")?;
    if parsed.schema != 1 {
        bail!("unsupported bridge compatibility manifest schema {}", parsed.schema);
    }
    if parsed.base.version.trim().is_empty() || parsed.base.files.is_empty() {
        bail!("embedded bridge compatibility manifest has an incomplete base specification");
    }
    Ok(parsed)
}

pub fn normalize_version(raw: &str) -> Option<String> {
    let rx = Regex::new(r"(?:^|[^0-9])((?:1\.[0-9]+(?:\.[0-9]+)?)|(?:2[0-9]\.[0-9]+(?:\.[0-9]+)?(?:-snapshot-[0-9]+)?))").expect("version regex");
    rx.captures_iter(raw.trim()).last().and_then(|capture| capture.get(1)).map(|value| value.as_str().to_string())
}

pub fn is_bundled_compatible(version: &str) -> Result<bool> {
    let version = normalize_version(version).ok_or_else(|| anyhow!("could not determine Minecraft version"))?;
    let manifest = manifest()?;
    Ok(manifest.base.compatible.iter().any(|candidate| candidate == &version))
}

pub fn is_supported(version: &str) -> bool {
    let Some(version) = normalize_version(version) else { return false; };
    let Ok(manifest) = manifest() else { return false; };
    if manifest.base.compatible.iter().any(|candidate| candidate == &version) {
        return true;
    }
    manifest.profiles.iter().any(|profile| {
        Regex::new(&profile.match_expression).map(|rx| rx.is_match(&version)).unwrap_or(false)
    })
}

pub fn required_java(version: &str) -> Result<u32> {
    let version = normalize_version(version).ok_or_else(|| anyhow!("could not determine Minecraft version"))?;
    let manifest = manifest()?;
    if manifest.base.compatible.iter().any(|candidate| candidate == &version) {
        return Ok(manifest.base.java);
    }
    Ok(profile_for(&manifest, &version)?.java)
}

pub fn prepare_source<F>(version: &str, workspace: &Path, mut progress: F) -> Result<PreparedSource>
where
    F: FnMut(CompatProgress),
{
    let version = normalize_version(version).ok_or_else(|| anyhow!("could not determine Minecraft version"))?;
    let manifest = manifest()?;
    let bundled = manifest.base.compatible.iter().any(|candidate| candidate == &version);
    let profile = if bundled { None } else { Some(profile_for(&manifest, &version)?.clone()) };

    if workspace.exists() {
        fs::remove_dir_all(workspace).with_context(|| format!("reset bridge workspace {}", workspace.display()))?;
    }
    fs::create_dir_all(workspace).with_context(|| format!("create bridge workspace {}", workspace.display()))?;

    report(&mut progress, 5, "Preparing Bridge", "Materializing embedded canonical Bridge source");
    for (index, relative) in manifest.base.files.iter().enumerate() {
        let embedded = BRIDGE_SOURCE.get_file(relative).ok_or_else(|| anyhow!("embedded canonical Bridge file is missing: {relative}"))?;
        let target = safe_join(workspace, Path::new(relative))?;
        write_file(&target, embedded.contents())?;
        if relative == "gradlew" {
            make_executable(&target)?;
        }
        let percent = 5 + (((index + 1) * 12) / manifest.base.files.len().max(1)) as i32;
        report(&mut progress, percent, "Preparing Bridge", relative);
    }

    let (profile_id, java, gradle, loom, loader_spec, api_spec, patch_path) = if let Some(profile) = &profile {
        (
            profile.id.clone(),
            profile.java,
            profile.gradle.clone(),
            profile.loom.clone(),
            profile.loader.clone(),
            profile.fabric_api.clone(),
            Some(profile.patch.clone()),
        )
    } else {
        (
            format!("bundled-base-{version}"),
            manifest.base.java,
            String::new(),
            String::new(),
            "dynamic".to_string(),
            "dynamic".to_string(),
            None,
        )
    };

    report(&mut progress, 19, "Preparing Bridge", "Resolving Fabric Loader and Fabric API");
    let loader = if loader_spec.is_empty() || loader_spec == "dynamic" {
        resolve_fabric_loader(&version)?
    } else {
        loader_spec
    };
    let fabric_api = if api_spec.is_empty() || api_spec == "dynamic" {
        resolve_fabric_api(&version)?
    } else {
        api_spec
    };

    let mut variables = HashMap::new();
    variables.insert("minecraft_version".into(), version.clone());
    variables.insert("loader_version".into(), loader.clone());
    variables.insert("fabric_api_version".into(), fabric_api.clone());
    variables.insert("fabric_version".into(), fabric_api.clone());
    variables.insert("java_version".into(), java.to_string());
    variables.insert("gradle_version".into(), gradle.clone());
    variables.insert("loom_version".into(), loom.clone());

    if let Some(patch_path) = patch_path {
        let patch = embedded_version_text(&patch_path)?;
        let patch: PatchSet = serde_json::from_str(&patch).with_context(|| format!("parse embedded compatibility recipe {patch_path}"))?;
        if patch.schema != 1 {
            bail!("unsupported patch schema {} in {patch_path}", patch.schema);
        }
        report(&mut progress, 27, "Preparing Bridge", &format!("Applying compatibility recipe {profile_id}"));
        for operation in &patch.operations {
            apply_operation(workspace, operation, &variables)
                .with_context(|| format!("apply {} operation for {profile_id}", operation.op))?;
        }
    } else {
        set_property(&safe_join(workspace, Path::new("gradle.properties"))?, "minecraft_version", &version)?;
        set_property(&safe_join(workspace, Path::new("gradle.properties"))?, "loader_version", &loader)?;
        set_property(&safe_join(workspace, Path::new("gradle.properties"))?, "fabric_version", &fabric_api)?;
    }

    let metadata = serde_json::json!({
        "minecraft": version.clone(),
        "profile": profile_id.clone(),
        "variables": variables.clone(),
        "purpose": "rust-compatibility-source"
    });
    fs::write(workspace.join("minesport-target.json"), serde_json::to_vec_pretty(&metadata)?)?;
    report(&mut progress, 34, "Preparing Bridge", "Compatibility workspace ready");

    Ok(PreparedSource {
        version,
        workspace: workspace.to_path_buf(),
        java,
        profile_id,
        variables,
    })
}

fn profile_for<'a>(manifest: &'a Manifest, version: &str) -> Result<&'a Profile> {
    for profile in &manifest.profiles {
        let rx = Regex::new(&profile.match_expression)
            .with_context(|| format!("invalid version match expression for profile {}", profile.id))?;
        if rx.is_match(version) {
            return Ok(profile);
        }
    }
    bail!("Minesport has no compatibility recipe for Minecraft {version} yet")
}

fn apply_operation(workspace: &Path, operation: &PatchOperation, variables: &HashMap<String, String>) -> Result<()> {
    let expand = |value: &str| expand_variables(value, variables);
    match operation.op.as_str() {
        "set_property" => {
            let file = safe_join(workspace, Path::new(&expand(&operation.file)))?;
            set_property(&file, &expand(&operation.key), &expand(&operation.value))
        }
        "replace" => {
            let file = safe_join(workspace, Path::new(&expand(&operation.file)))?;
            replace_in_file(&file, &expand(&operation.from), &expand(&operation.to), false)
        }
        "rename_at" => {
            let file = safe_join(workspace, Path::new(&expand(&operation.file)))?;
            replace_at(&file, operation.line, operation.column, &expand(&operation.from), &expand(&operation.to))
        }
        "regex_replace" => {
            let file = safe_join(workspace, Path::new(&expand(&operation.file)))?;
            let text = fs::read_to_string(&file).with_context(|| format!("read {}", file.display()))?;
            let pattern = expand(&operation.pattern);
            let replacement = expand(&operation.replacement);
            let rx = Regex::new(&pattern).with_context(|| format!("compile compatibility regex {pattern:?}"))?;
            if !rx.is_match(&text) {
                bail!("expected regex {pattern:?} was not found in {}", file.display());
            }
            fs::write(&file, rx.replace_all(&text, replacement.as_str()).as_bytes())?;
            Ok(())
        }
        "replace_tree" | "rename_package" => {
            let root = safe_join(workspace, Path::new(&expand(&operation.root)))?;
            let mut extensions = operation.extensions.clone();
            if operation.op == "rename_package" && extensions.is_empty() {
                extensions.push(".java".into());
            }
            replace_tree(&root, &extensions, &expand(&operation.from), &expand(&operation.to))
        }
        "rename_file" => {
            let from = safe_join(workspace, Path::new(&expand(&operation.from)))?;
            let to = safe_join(workspace, Path::new(&expand(&operation.to)))?;
            if let Some(parent) = to.parent() { fs::create_dir_all(parent)?; }
            fs::rename(&from, &to).with_context(|| format!("rename {} to {}", from.display(), to.display()))?;
            Ok(())
        }
        "overlay" => {
            let source = expand(&operation.source);
            let bytes = embedded_version_bytes(&source)?;
            let target = safe_join(workspace, Path::new(&expand(&operation.target)))?;
            write_file(&target, bytes)
        }
        "module" => {
            let data = download_pinned_module(&expand(&operation.url), &expand(&operation.sha256))?;
            let target_text = project_relative(&expand(&operation.target));
            let target = safe_join(workspace, Path::new(&target_text))?;
            write_file(&target, &data)
                .with_context(|| format!("install compatibility module {}", operation.name))
        }
        "delete" => {
            let raw = if operation.target.is_empty() { &operation.file } else { &operation.target };
            let target = safe_join(workspace, Path::new(&expand(raw)))?;
            if target.is_dir() { fs::remove_dir_all(target)?; }
            else if target.exists() { fs::remove_file(target)?; }
            Ok(())
        }
        other => bail!("unknown compatibility operation {other:?}"),
    }
}

fn embedded_version_bytes(repo_path: &str) -> Result<&'static [u8]> {
    let relative = repo_path.trim_start_matches('/').strip_prefix("bridge-versions/").unwrap_or(repo_path);
    BRIDGE_VERSIONS
        .get_file(relative)
        .map(|file| file.contents())
        .ok_or_else(|| anyhow!("embedded compatibility resource is missing: {repo_path}"))
}

fn embedded_version_text(repo_path: &str) -> Result<String> {
    let bytes = embedded_version_bytes(repo_path)?;
    String::from_utf8(bytes.to_vec()).with_context(|| format!("compatibility resource is not UTF-8: {repo_path}"))
}

fn set_property(file: &Path, key: &str, value: &str) -> Result<()> {
    let text = fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
    let prefix = format!("{key}=");
    let mut updated = false;
    let mut lines = Vec::new();
    for line in text.lines() {
        if line.trim_start().starts_with(&prefix) {
            lines.push(format!("{prefix}{value}"));
            updated = true;
        } else {
            lines.push(line.to_string());
        }
    }
    if !updated { lines.push(format!("{prefix}{value}")); }
    let mut output = lines.join("\n");
    if text.ends_with('\n') { output.push('\n'); }
    fs::write(file, output).with_context(|| format!("update Gradle property {key} in {}", file.display()))?;
    Ok(())
}

fn replace_in_file(file: &Path, from: &str, to: &str, optional: bool) -> Result<()> {
    let text = fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
    if !text.contains(from) {
        if optional { return Ok(()); }
        bail!("expected text {from:?} was not found in {}", file.display());
    }
    fs::write(file, text.replace(from, to))?;
    Ok(())
}

fn replace_at(file: &Path, line: usize, column: usize, from: &str, to: &str) -> Result<()> {
    if line == 0 || column == 0 || from.is_empty() {
        bail!("rename_at requires 1-based line/column and non-empty from text");
    }
    let text = fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
    let mut offsets = vec![0usize];
    for (index, byte) in text.bytes().enumerate() {
        if byte == b'\n' { offsets.push(index + 1); }
    }
    let start_of_line = *offsets.get(line - 1).ok_or_else(|| anyhow!("line {line} does not exist in {}", file.display()))?;
    let start = start_of_line + column - 1;
    let line_end = text[start_of_line..].find('\n').map(|value| start_of_line + value).unwrap_or(text.len());
    if start + from.len() > line_end || !text.is_char_boundary(start) || !text.is_char_boundary(start + from.len()) {
        bail!("rename_at location {line}:{column} is invalid in {}", file.display());
    }
    if &text[start..start + from.len()] != from {
        bail!("stale rename_at in {}:{line}:{column}: expected {from:?}", file.display());
    }
    let mut output = String::with_capacity(text.len() + to.len().saturating_sub(from.len()));
    output.push_str(&text[..start]);
    output.push_str(to);
    output.push_str(&text[start + from.len()..]);
    fs::write(file, output)?;
    Ok(())
}

fn replace_tree(root: &Path, extensions: &[String], from: &str, to: &str) -> Result<()> {
    if !root.exists() { bail!("compatibility tree does not exist: {}", root.display()); }
    for entry in fs::read_dir(root).with_context(|| format!("read compatibility tree {}", root.display()))? {
        let entry = entry?;
        let path = entry.path();
        let file_type = entry.file_type()?;
        if file_type.is_symlink() { continue; }
        if file_type.is_dir() {
            replace_tree(&path, extensions, from, to)?;
            continue;
        }
        if !file_type.is_file() || !has_extension(&path, extensions) { continue; }
        replace_in_file(&path, from, to, true)?;
    }
    Ok(())
}

fn has_extension(path: &Path, extensions: &[String]) -> bool {
    if extensions.is_empty() { return true; }
    let ext = path.extension().and_then(|value| value.to_str()).map(|value| format!(".{value}")).unwrap_or_default();
    extensions.iter().any(|candidate| candidate.eq_ignore_ascii_case(&ext))
}

fn expand_variables(value: &str, variables: &HashMap<String, String>) -> String {
    let mut output = value.to_string();
    for (key, replacement) in variables {
        output = output.replace(&format!("${{{key}}}"), replacement);
    }
    output
}

fn project_relative(value: &str) -> String {
    value.trim().trim_start_matches("&PROJECT&/").trim_start_matches("&PROJECT&\\").replace('\\', "/")
}

fn download_pinned_module(url: &str, expected_sha: &str) -> Result<Vec<u8>> {
    if !url.starts_with("https://") { bail!("compatibility module URL must use HTTPS"); }
    if expected_sha.len() != 64 || !expected_sha.chars().all(|ch| ch.is_ascii_hexdigit()) {
        bail!("compatibility module requires a 64-character SHA-256");
    }
    let data = http_get_limited(url, MAX_NETWORK_TEXT)?;
    let actual = format!("{:x}", Sha256::digest(&data));
    if !actual.eq_ignore_ascii_case(expected_sha.trim()) {
        bail!("compatibility module SHA-256 mismatch: expected {expected_sha}, got {actual}");
    }
    std::str::from_utf8(&data).context("compatibility module is not valid UTF-8 text")?;
    Ok(data)
}

fn resolve_fabric_loader(version: &str) -> Result<String> {
    let url = format!("https://meta.fabricmc.net/v2/versions/loader/{version}");
    let data = http_get_limited(&url, MAX_NETWORK_TEXT)?;
    #[derive(Deserialize)]
    struct LoaderInfo { loader: Loader }
    #[derive(Deserialize)]
    struct Loader { version: String, #[serde(default)] stable: bool }
    let values: Vec<LoaderInfo> = serde_json::from_slice(&data).context("parse Fabric Loader metadata")?;
    if let Some(value) = values.iter().find(|value| value.loader.stable && !value.loader.version.is_empty()) {
        return Ok(value.loader.version.clone());
    }
    values.first().filter(|value| !value.loader.version.is_empty()).map(|value| value.loader.version.clone())
        .ok_or_else(|| anyhow!("Fabric Meta does not publish a loader for Minecraft {version}"))
}

fn resolve_fabric_api(version: &str) -> Result<String> {
    let data = http_get_limited(
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml",
        MAX_NETWORK_TEXT,
    )?;
    let text = std::str::from_utf8(&data).context("Fabric API Maven metadata is not UTF-8")?;
    let suffix = format!("+{version}");
    let mut selected = None;
    for chunk in text.split("<version>").skip(1) {
        let Some(candidate) = chunk.split("</version>").next() else { continue; };
        let candidate = candidate.trim();
        if candidate.ends_with(&suffix) { selected = Some(candidate.to_string()); }
    }
    selected.ok_or_else(|| anyhow!("Fabric API does not currently publish a build for Minecraft {version}"))
}

fn http_get_limited(url: &str, max_bytes: u64) -> Result<Vec<u8>> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(120))
        .build();
    let response = agent
        .get(url)
        .set("User-Agent", "Minesport-Rust-Bridge-Builder/0.2.0")
        .call()
        .map_err(|error| anyhow!("HTTP request failed for {url}: {error}"))?;
    let mut reader = response.into_reader().take(max_bytes + 1);
    let mut data = Vec::new();
    reader.read_to_end(&mut data)?;
    if data.len() as u64 > max_bytes { bail!("network resource exceeds {max_bytes} byte limit: {url}"); }
    Ok(data)
}

fn safe_join(root: &Path, relative: &Path) -> Result<PathBuf> {
    if relative.is_absolute() { bail!("absolute compatibility path is not allowed: {}", relative.display()); }
    for component in relative.components() {
        if matches!(component, Component::ParentDir | Component::RootDir | Component::Prefix(_)) {
            bail!("compatibility path escapes workspace: {}", relative.display());
        }
    }
    Ok(root.join(relative))
}

fn write_file(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    fs::write(path, bytes).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))?;
    Ok(())
}
#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> { Ok(()) }

fn report<F>(progress: &mut F, percent: i32, stage: &str, detail: &str)
where
    F: FnMut(CompatProgress),
{
    progress(CompatProgress { percent, stage: stage.to_string(), detail: detail.to_string() });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_manifest_and_profiles_parse() {
        let manifest = manifest().expect("manifest");
        assert_eq!(manifest.base.version, "1.21.10");
        assert!(manifest.base.compatible.iter().any(|value| value == "1.21.9"));
        assert!(is_supported("1.19.4"));
        assert!(is_supported("1.20.4"));
        assert!(is_supported("1.21.11"));
        assert!(is_supported("26.2"));
        assert!(!is_supported("1.5"));
    }

    #[test]
    fn profile_java_requirements_match_manifest_families() {
        assert_eq!(required_java("1.19.4").unwrap(), 17);
        assert_eq!(required_java("1.21.10").unwrap(), 21);
        assert_eq!(required_java("26.2").unwrap(), 25);
    }

    #[test]
    fn safe_join_rejects_parent_escape() {
        assert!(safe_join(Path::new("root"), Path::new("../nope")).is_err());
        assert!(safe_join(Path::new("root"), Path::new("ok/file.txt")).is_ok());
    }

    #[test]
    fn every_profile_recipe_is_embedded_and_schema_one() {
        let manifest = manifest().unwrap();
        for profile in manifest.profiles {
            let text = embedded_version_text(&profile.patch).unwrap();
            let patch: PatchSet = serde_json::from_str(&text).unwrap();
            assert_eq!(patch.schema, 1, "{}", profile.patch);
            assert!(!patch.operations.is_empty(), "{}", profile.patch);
        }
    }
}
