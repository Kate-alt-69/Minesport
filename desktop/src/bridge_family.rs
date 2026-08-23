use crate::{bridge_compat, launcher::ModLoader};
use anyhow::{Context, Result, anyhow, bail};
use include_dir::{Dir, include_dir};
use regex::Regex;
use serde::Deserialize;
use std::{
    collections::HashMap,
    fs,
    path::{Component, Path, PathBuf},
};

static FORGE_VERSIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../bridge-forge-versions");
static NEOFORGE_VERSIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../bridge-neoforge-versions");
static QUILT_VERSIONS: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../bridge-quilt-versions");

const FORGE_MANIFEST_JSON: &str = include_str!("../../bridge-forge-versions/manifest.json");
const NEOFORGE_MANIFEST_JSON: &str = include_str!("../../bridge-neoforge-versions/manifest.json");
const QUILT_MANIFEST_JSON: &str = include_str!("../../bridge-quilt-versions/manifest.json");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BridgeFamily {
    Fabric,
    Forge,
    NeoForge,
    Quilt,
}

impl BridgeFamily {
    pub fn from_mod_loader(loader: ModLoader) -> Option<Self> {
        match loader {
            ModLoader::Fabric => Some(Self::Fabric),
            ModLoader::Forge => Some(Self::Forge),
            ModLoader::NeoForge => Some(Self::NeoForge),
            ModLoader::Quilt => Some(Self::Quilt),
            ModLoader::Vanilla => None,
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "fabric" => Some(Self::Fabric),
            "forge" => Some(Self::Forge),
            "neoforge" | "neo-forge" | "neo_forge" => Some(Self::NeoForge),
            "quilt" => Some(Self::Quilt),
            _ => None,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Fabric => "Fabric",
            Self::Forge => "Forge",
            Self::NeoForge => "NeoForge",
            Self::Quilt => "Quilt",
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
struct FamilyManifest {
    schema: i32,
    loader: String,
    base: FamilyBase,
    #[serde(default)]
    profiles: Vec<FamilyProfile>,
}

#[derive(Debug, Clone, Deserialize)]
struct FamilyBase {
    version: String,
    #[serde(default)]
    compatible: Vec<String>,
    java: u32,
    #[serde(default)]
    variables: HashMap<String, String>,
}

#[derive(Debug, Clone, Deserialize)]
struct FamilyProfile {
    id: String,
    #[serde(rename = "match")]
    match_expression: String,
    patch: String,
    java: u32,
    #[serde(default)]
    variables: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
struct PatchSet {
    schema: i32,
    #[serde(default)]
    operations: Vec<PatchOperation>,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct PatchOperation {
    op: String,
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
    #[serde(default)]
    key: String,
    #[serde(default)]
    value: String,
    #[serde(default)]
    line: usize,
    #[serde(default)]
    column: usize,
}

struct EmbeddedFile {
    path: &'static str,
    bytes: &'static [u8],
    executable: bool,
}

const FORGE_FILES: &[EmbeddedFile] = &[
    EmbeddedFile { path: "build.gradle", bytes: include_bytes!("../../bridge-forge/build.gradle"), executable: false },
    EmbeddedFile { path: "settings.gradle", bytes: include_bytes!("../../bridge-forge/settings.gradle"), executable: false },
    EmbeddedFile { path: "gradle.properties", bytes: include_bytes!("../../bridge-forge/gradle.properties"), executable: false },
    EmbeddedFile { path: "gradlew", bytes: include_bytes!("../../bridge-forge/gradlew"), executable: true },
    EmbeddedFile { path: "gradlew.bat", bytes: include_bytes!("../../bridge-forge/gradlew.bat"), executable: false },
    EmbeddedFile { path: "gradle/wrapper/gradle-wrapper.jar", bytes: include_bytes!("../../bridge-forge/gradle/wrapper/gradle-wrapper.jar"), executable: false },
    EmbeddedFile { path: "gradle/wrapper/gradle-wrapper.properties", bytes: include_bytes!("../../bridge-forge/gradle/wrapper/gradle-wrapper.properties"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/MinesportBridge.java", bytes: include_bytes!("../../bridge-forge/src/main/java/dev/kastrick/minesport/bridge/MinesportBridge.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/model/BridgeProtocol.java", bytes: include_bytes!("../../bridge-forge/src/main/java/dev/kastrick/minesport/bridge/model/BridgeProtocol.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/registry/BlockGeometryExtractor.java", bytes: include_bytes!("../../bridge-forge/src/main/java/dev/kastrick/minesport/bridge/registry/BlockGeometryExtractor.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/registry/SpriteUv.java", bytes: include_bytes!("../../bridge-forge/src/main/java/dev/kastrick/minesport/bridge/registry/SpriteUv.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java", bytes: include_bytes!("../../bridge-forge/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java"), executable: false },
    EmbeddedFile { path: "src/main/resources/META-INF/mods.toml", bytes: include_bytes!("../../bridge-forge/src/main/resources/META-INF/mods.toml"), executable: false },
];

const NEOFORGE_FILES: &[EmbeddedFile] = &[
    EmbeddedFile { path: "build.gradle", bytes: include_bytes!("../../bridge-neoforge/build.gradle"), executable: false },
    EmbeddedFile { path: "settings.gradle", bytes: include_bytes!("../../bridge-neoforge/settings.gradle"), executable: false },
    EmbeddedFile { path: "gradle.properties", bytes: include_bytes!("../../bridge-neoforge/gradle.properties"), executable: false },
    EmbeddedFile { path: "gradlew", bytes: include_bytes!("../../bridge-neoforge/gradlew"), executable: true },
    EmbeddedFile { path: "gradlew.bat", bytes: include_bytes!("../../bridge-neoforge/gradlew.bat"), executable: false },
    EmbeddedFile { path: "gradle/wrapper/gradle-wrapper.jar", bytes: include_bytes!("../../bridge-neoforge/gradle/wrapper/gradle-wrapper.jar"), executable: false },
    EmbeddedFile { path: "gradle/wrapper/gradle-wrapper.properties", bytes: include_bytes!("../../bridge-neoforge/gradle/wrapper/gradle-wrapper.properties"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/MinesportBridge.java", bytes: include_bytes!("../../bridge-neoforge/src/main/java/dev/kastrick/minesport/bridge/MinesportBridge.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/model/BridgeProtocol.java", bytes: include_bytes!("../../bridge-neoforge/src/main/java/dev/kastrick/minesport/bridge/model/BridgeProtocol.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/registry/BlockGeometryExtractor.java", bytes: include_bytes!("../../bridge-neoforge/src/main/java/dev/kastrick/minesport/bridge/registry/BlockGeometryExtractor.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/registry/SpriteUv.java", bytes: include_bytes!("../../bridge-neoforge/src/main/java/dev/kastrick/minesport/bridge/registry/SpriteUv.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java", bytes: include_bytes!("../../bridge-neoforge/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java"), executable: false },
    EmbeddedFile { path: "src/main/resources/META-INF/neoforge.mods.toml", bytes: include_bytes!("../../bridge-neoforge/src/main/resources/META-INF/neoforge.mods.toml"), executable: false },
];

const QUILT_FILES: &[EmbeddedFile] = &[
    EmbeddedFile { path: "build.gradle", bytes: include_bytes!("../../bridge-quilt/build.gradle"), executable: false },
    EmbeddedFile { path: "settings.gradle", bytes: include_bytes!("../../bridge-quilt/settings.gradle"), executable: false },
    EmbeddedFile { path: "gradle.properties", bytes: include_bytes!("../../bridge-quilt/gradle.properties"), executable: false },
    EmbeddedFile { path: "gradlew", bytes: include_bytes!("../../bridge-quilt/gradlew"), executable: true },
    EmbeddedFile { path: "gradlew.bat", bytes: include_bytes!("../../bridge-quilt/gradlew.bat"), executable: false },
    EmbeddedFile { path: "gradle/wrapper/gradle-wrapper.jar", bytes: include_bytes!("../../bridge-quilt/gradle/wrapper/gradle-wrapper.jar"), executable: false },
    EmbeddedFile { path: "gradle/wrapper/gradle-wrapper.properties", bytes: include_bytes!("../../bridge-quilt/gradle/wrapper/gradle-wrapper.properties"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/MinesportBridge.java", bytes: include_bytes!("../../bridge-quilt/src/main/java/dev/kastrick/minesport/bridge/MinesportBridge.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/model/BridgeProtocol.java", bytes: include_bytes!("../../bridge-quilt/src/main/java/dev/kastrick/minesport/bridge/model/BridgeProtocol.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/registry/BlockGeometryExtractor.java", bytes: include_bytes!("../../bridge-quilt/src/main/java/dev/kastrick/minesport/bridge/registry/BlockGeometryExtractor.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/registry/SpriteUv.java", bytes: include_bytes!("../../bridge-quilt/src/main/java/dev/kastrick/minesport/bridge/registry/SpriteUv.java"), executable: false },
    EmbeddedFile { path: "src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java", bytes: include_bytes!("../../bridge-quilt/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java"), executable: false },
    EmbeddedFile { path: "src/main/resources/fabric.mod.json", bytes: include_bytes!("../../bridge-quilt/src/main/resources/fabric.mod.json"), executable: false },
];

pub fn is_supported(family: BridgeFamily, version: &str) -> bool {
    if family == BridgeFamily::Fabric {
        return bridge_compat::is_supported(version);
    }
    let Some(version) = bridge_compat::normalize_version(version) else { return false; };
    let Ok(manifest) = manifest(family) else { return false; };
    if manifest.base.compatible.iter().any(|candidate| candidate == &version) {
        return true;
    }
    manifest.profiles.iter().any(|profile| {
        Regex::new(&profile.match_expression)
            .map(|expression| expression.is_match(&version))
            .unwrap_or(false)
    })
}

pub fn prepare_source<F>(
    family: BridgeFamily,
    version: &str,
    workspace: &Path,
    mut progress: F,
) -> Result<bridge_compat::PreparedSource>
where
    F: FnMut(bridge_compat::CompatProgress),
{
    if family == BridgeFamily::Fabric {
        return bridge_compat::prepare_source(version, workspace, progress);
    }

    let version = bridge_compat::normalize_version(version)
        .ok_or_else(|| anyhow!("could not determine Minecraft version"))?;
    let manifest = manifest(family)?;
    let bundled = manifest.base.compatible.iter().any(|candidate| candidate == &version);
    let profile = if bundled {
        None
    } else {
        Some(profile_for(&manifest, &version, family)?.clone())
    };

    if workspace.exists() {
        fs::remove_dir_all(workspace)
            .with_context(|| format!("reset {} Bridge workspace {}", family.label(), workspace.display()))?;
    }
    fs::create_dir_all(workspace)
        .with_context(|| format!("create {} Bridge workspace {}", family.label(), workspace.display()))?;

    let files = canonical_files(family)?;
    report(&mut progress, 5, "Preparing Bridge", &format!("Materializing canonical {} Bridge", family.label()));
    for (index, embedded) in files.iter().enumerate() {
        let target = safe_join(workspace, Path::new(embedded.path))?;
        write_file(&target, embedded.bytes)?;
        if embedded.executable {
            make_executable(&target)?;
        }
        let percent = 5 + (((index + 1) * 12) / files.len().max(1)) as i32;
        report(&mut progress, percent, "Preparing Bridge", embedded.path);
    }

    let (profile_id, java, patch_path, profile_variables) = if let Some(profile) = &profile {
        (
            profile.id.clone(),
            profile.java,
            Some(profile.patch.clone()),
            profile.variables.clone(),
        )
    } else {
        (
            format!("{}-base-{version}", family.label().to_ascii_lowercase()),
            manifest.base.java,
            None,
            HashMap::new(),
        )
    };

    let mut variables = manifest.base.variables.clone();
    variables.extend(profile_variables);
    variables.insert("minecraft_version".into(), version.clone());
    variables.insert("java_version".into(), java.to_string());

    if let Some(patch_path) = patch_path {
        let patch_text = embedded_family_text(family, &patch_path)?;
        let patch: PatchSet = serde_json::from_str(&patch_text)
            .with_context(|| format!("parse {} compatibility recipe {patch_path}", family.label()))?;
        if patch.schema != 1 {
            bail!("unsupported {} patch schema {} in {patch_path}", family.label(), patch.schema);
        }
        report(&mut progress, 27, "Preparing Bridge", &format!("Applying {profile_id}"));
        for operation in &patch.operations {
            apply_operation(family, workspace, operation, &variables)
                .with_context(|| format!("apply {} operation for {}", operation.op, profile_id))?;
        }
    }

    let metadata = serde_json::json!({
        "minecraft": version.clone(),
        "loader": family.label().to_ascii_lowercase(),
        "profile": profile_id.clone(),
        "variables": variables.clone(),
        "purpose": "rust-loader-family-compatibility-source"
    });
    fs::write(workspace.join("minesport-target.json"), serde_json::to_vec_pretty(&metadata)?)?;
    report(&mut progress, 34, "Preparing Bridge", "Compatibility workspace ready");

    Ok(bridge_compat::PreparedSource {
        version,
        workspace: workspace.to_path_buf(),
        java,
        profile_id,
        variables,
    })
}

fn manifest(family: BridgeFamily) -> Result<FamilyManifest> {
    let raw = match family {
        BridgeFamily::Fabric => bail!("Fabric uses the native Bridge compatibility manifest"),
        BridgeFamily::Forge => FORGE_MANIFEST_JSON,
        BridgeFamily::NeoForge => NEOFORGE_MANIFEST_JSON,
        BridgeFamily::Quilt => QUILT_MANIFEST_JSON,
    };
    let parsed: FamilyManifest = serde_json::from_str(raw)
        .with_context(|| format!("parse {} Bridge compatibility manifest", family.label()))?;
    if parsed.schema != 1 {
        bail!("unsupported {} Bridge manifest schema {}", family.label(), parsed.schema);
    }
    if !parsed.loader.eq_ignore_ascii_case(&family.label().replace('-', ""))
        && !parsed.loader.eq_ignore_ascii_case(&family.label())
    {
        bail!("{} Bridge manifest declares loader {:?}", family.label(), parsed.loader);
    }
    if parsed.base.version.trim().is_empty() {
        bail!("{} Bridge manifest has no base version", family.label());
    }
    Ok(parsed)
}

fn profile_for<'a>(manifest: &'a FamilyManifest, version: &str, family: BridgeFamily) -> Result<&'a FamilyProfile> {
    for profile in &manifest.profiles {
        let expression = Regex::new(&profile.match_expression)
            .with_context(|| format!("invalid {} version expression for {}", family.label(), profile.id))?;
        if expression.is_match(version) {
            return Ok(profile);
        }
    }
    bail!("Minesport has no {} Bridge compatibility recipe for Minecraft {version}", family.label())
}

fn canonical_files(family: BridgeFamily) -> Result<&'static [EmbeddedFile]> {
    match family {
        BridgeFamily::Fabric => bail!("Fabric canonical sources are owned by bridge_compat"),
        BridgeFamily::Forge => Ok(FORGE_FILES),
        BridgeFamily::NeoForge => Ok(NEOFORGE_FILES),
        BridgeFamily::Quilt => Ok(QUILT_FILES),
    }
}

fn family_dir(family: BridgeFamily) -> Result<&'static Dir<'static>> {
    match family {
        BridgeFamily::Fabric => bail!("Fabric recipes are owned by bridge_compat"),
        BridgeFamily::Forge => Ok(&FORGE_VERSIONS),
        BridgeFamily::NeoForge => Ok(&NEOFORGE_VERSIONS),
        BridgeFamily::Quilt => Ok(&QUILT_VERSIONS),
    }
}

fn embedded_family_text(family: BridgeFamily, relative: &str) -> Result<String> {
    let bytes = family_dir(family)?
        .get_file(relative.trim_start_matches('/'))
        .map(|file| file.contents())
        .ok_or_else(|| anyhow!("embedded {} compatibility resource is missing: {relative}", family.label()))?;
    String::from_utf8(bytes.to_vec())
        .with_context(|| format!("{} compatibility resource is not UTF-8: {relative}", family.label()))
}

fn embedded_family_bytes(family: BridgeFamily, relative: &str) -> Result<&'static [u8]> {
    family_dir(family)?
        .get_file(relative.trim_start_matches('/'))
        .map(|file| file.contents())
        .ok_or_else(|| anyhow!("embedded {} compatibility resource is missing: {relative}", family.label()))
}

fn apply_operation(
    family: BridgeFamily,
    workspace: &Path,
    operation: &PatchOperation,
    variables: &HashMap<String, String>,
) -> Result<()> {
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
            let expression = Regex::new(&pattern)
                .with_context(|| format!("compile compatibility regex {pattern:?}"))?;
            if !expression.is_match(&text) {
                bail!("expected regex {pattern:?} was not found in {}", file.display());
            }
            fs::write(&file, expression.replace_all(&text, replacement.as_str()).as_bytes())?;
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
            if let Some(parent) = to.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::rename(&from, &to).with_context(|| format!("rename {} to {}", from.display(), to.display()))?;
            Ok(())
        }
        "overlay" => {
            let source = expand(&operation.source);
            let target = safe_join(workspace, Path::new(&expand(&operation.target)))?;
            write_file(&target, embedded_family_bytes(family, &source)?)
        }
        "delete" => {
            let raw = if operation.target.is_empty() { &operation.file } else { &operation.target };
            let target = safe_join(workspace, Path::new(&expand(raw)))?;
            if target.is_dir() {
                fs::remove_dir_all(target)?;
            } else if target.exists() {
                fs::remove_file(target)?;
            }
            Ok(())
        }
        other => bail!("unknown compatibility operation {other:?}"),
    }
}

fn safe_join(root: &Path, relative: &Path) -> Result<PathBuf> {
    if relative.is_absolute() || relative.components().any(|component| {
        matches!(component, Component::ParentDir | Component::RootDir | Component::Prefix(_))
    }) {
        bail!("unsafe compatibility path: {}", relative.display());
    }
    Ok(root.join(relative))
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
    if !updated {
        lines.push(format!("{prefix}{value}"));
    }
    let mut output = lines.join("\n");
    if text.ends_with('\n') {
        output.push('\n');
    }
    fs::write(file, output).with_context(|| format!("update Gradle property {key} in {}", file.display()))?;
    Ok(())
}

fn replace_in_file(file: &Path, from: &str, to: &str, optional: bool) -> Result<()> {
    let text = fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
    if !text.contains(from) {
        if optional {
            return Ok(());
        }
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
        if byte == b'\n' {
            offsets.push(index + 1);
        }
    }
    let start_of_line = *offsets.get(line - 1)
        .ok_or_else(|| anyhow!("line {line} does not exist in {}", file.display()))?;
    let start = start_of_line + column - 1;
    let line_end = text[start_of_line..]
        .find('\n')
        .map(|value| start_of_line + value)
        .unwrap_or(text.len());
    if start + from.len() > line_end
        || !text.is_char_boundary(start)
        || !text.is_char_boundary(start + from.len())
        || &text[start..start + from.len()] != from
    {
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
    if !root.exists() {
        bail!("compatibility tree does not exist: {}", root.display());
    }
    for entry in fs::read_dir(root).with_context(|| format!("read compatibility tree {}", root.display()))? {
        let entry = entry?;
        let path = entry.path();
        let kind = entry.file_type()?;
        if kind.is_symlink() {
            continue;
        }
        if kind.is_dir() {
            replace_tree(&path, extensions, from, to)?;
            continue;
        }
        if !kind.is_file() || !has_extension(&path, extensions) {
            continue;
        }
        replace_in_file(&path, from, to, true)?;
    }
    Ok(())
}

fn has_extension(path: &Path, extensions: &[String]) -> bool {
    if extensions.is_empty() {
        return true;
    }
    let extension = path.extension()
        .and_then(|value| value.to_str())
        .map(|value| format!(".{value}"))
        .unwrap_or_default();
    extensions.iter().any(|candidate| candidate.eq_ignore_ascii_case(&extension))
}

fn expand_variables(value: &str, variables: &HashMap<String, String>) -> String {
    let mut output = value.to_string();
    for (key, replacement) in variables {
        output = output.replace(&format!("${{{key}}}"), replacement);
    }
    output
}

fn write_file(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, bytes).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

fn report<F>(progress: &mut F, percent: i32, stage: &str, detail: &str)
where
    F: FnMut(bridge_compat::CompatProgress),
{
    progress(bridge_compat::CompatProgress {
        percent,
        stage: stage.to_string(),
        detail: detail.to_string(),
    });
}

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(permissions.mode() | 0o111);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loader_family_parser_covers_supported_loaders() {
        assert_eq!(BridgeFamily::parse("Fabric"), Some(BridgeFamily::Fabric));
        assert_eq!(BridgeFamily::parse("forge"), Some(BridgeFamily::Forge));
        assert_eq!(BridgeFamily::parse("NeoForge"), Some(BridgeFamily::NeoForge));
        assert_eq!(BridgeFamily::parse("quilt"), Some(BridgeFamily::Quilt));
        assert_eq!(BridgeFamily::parse("vanilla"), None);
    }

    #[test]
    fn family_manifests_expose_canonical_and_first_patched_version() {
        for family in [BridgeFamily::Forge, BridgeFamily::NeoForge, BridgeFamily::Quilt] {
            assert!(is_supported(family, "1.21.10"), "{} canonical support", family.label());
            assert!(is_supported(family, "1.21.9"), "{} 1.21.9 support", family.label());
            assert!(!is_supported(family, "1.5"), "{} must not fake unsupported versions", family.label());
        }
    }

    #[test]
    fn unsafe_patch_paths_are_rejected() {
        assert!(safe_join(Path::new("root"), Path::new("../escape")).is_err());
        assert!(safe_join(Path::new("root"), Path::new("safe/file.txt")).is_ok());
    }
}
