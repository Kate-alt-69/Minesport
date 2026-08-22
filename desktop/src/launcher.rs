use serde::Deserialize;
use serde_json::Value;
use std::{
    env, fs,
    path::{Path, PathBuf},
    time::SystemTime,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LauncherType {
    Official,
    FreeSM,
    Prism,
    MultiMC,
    ATLauncher,
    CurseForge,
}

impl LauncherType {
    pub fn label(self) -> &'static str {
        match self {
            Self::Official => "Official",
            Self::FreeSM => "FreesmLauncher",
            Self::Prism => "PrismLauncher",
            Self::MultiMC => "MultiMC",
            Self::ATLauncher => "ATLauncher",
            Self::CurseForge => "CurseForge",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModLoader {
    Vanilla,
    Fabric,
    Forge,
    NeoForge,
    Quilt,
}

impl ModLoader {
    pub fn label(self) -> &'static str {
        match self {
            Self::Vanilla => "Vanilla",
            Self::Fabric => "Fabric",
            Self::Forge => "Forge",
            Self::NeoForge => "NeoForge",
            Self::Quilt => "Quilt",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Launcher {
    pub launcher_type: LauncherType,
    pub name: String,
    pub root: PathBuf,
}

#[derive(Debug, Clone)]
pub struct Instance {
    pub launcher: LauncherType,
    pub name: String,
    pub version: String,
    pub loader: ModLoader,
    pub mods_path: PathBuf,
    pub minecraft_dir: PathBuf,
    pub worlds: Vec<World>,
    pub has_polymer: bool,
}

#[derive(Debug, Clone)]
pub struct World {
    pub name: String,
    pub path: PathBuf,
    pub last_played: Option<SystemTime>,
}

pub fn discover_all() -> Vec<Launcher> {
    let appdata = env::var_os("APPDATA").map(PathBuf::from).unwrap_or_default();
    let home = env::var_os("HOME")
        .or_else(|| env::var_os("USERPROFILE"))
        .map(PathBuf::from)
        .unwrap_or_default();

    let candidates: &[(LauncherType, &str, Vec<PathBuf>)] = &[
        (
            LauncherType::Official,
            "Minecraft (Official)",
            vec![
                appdata.join(".minecraft"),
                home.join(".minecraft"),
                home.join("Library").join("Application Support").join("minecraft"),
            ],
        ),
        (
            LauncherType::FreeSM,
            "FreesmLauncher",
            vec![appdata.join("FreesmLauncher"), home.join(".local").join("share").join("FreesmLauncher")],
        ),
        (
            LauncherType::Prism,
            "Prism Launcher",
            vec![
                appdata.join("PrismLauncher"),
                home.join(".local").join("share").join("PrismLauncher"),
                home.join("Library").join("Application Support").join("PrismLauncher"),
            ],
        ),
        (
            LauncherType::MultiMC,
            "MultiMC",
            vec![home.join("MultiMC"), appdata.join("MultiMC")],
        ),
        (
            LauncherType::ATLauncher,
            "ATLauncher",
            vec![home.join("ATLauncher"), appdata.join("ATLauncher")],
        ),
        (
            LauncherType::CurseForge,
            "CurseForge",
            vec![
                home.join("curseforge").join("minecraft"),
                home.join("Documents").join("CurseForge").join("Minecraft"),
            ],
        ),
    ];

    let mut launchers = Vec::new();
    for (launcher_type, name, paths) in candidates {
        if let Some(root) = paths.iter().find(|path| path.is_dir()) {
            launchers.push(Launcher {
                launcher_type: *launcher_type,
                name: (*name).to_string(),
                root: root.clone(),
            });
        }
    }
    launchers
}

pub fn discover_instances(launcher: &Launcher) -> Vec<Instance> {
    match launcher.launcher_type {
        LauncherType::Official => discover_official_instances(launcher),
        LauncherType::FreeSM | LauncherType::Prism | LauncherType::MultiMC => discover_multi_instances(launcher),
        LauncherType::ATLauncher => discover_atlauncher_instances(launcher),
        LauncherType::CurseForge => discover_curseforge_instances(launcher),
    }
}

fn discover_official_instances(launcher: &Launcher) -> Vec<Instance> {
    let root = &launcher.root;
    let mods_path = root.join("mods");
    let loader = detect_loader(root);
    let worlds = discover_worlds(&root.join("saves"));
    vec![Instance {
        launcher: launcher.launcher_type,
        name: "Default (.minecraft)".to_string(),
        version: read_official_version(root).unwrap_or_else(|| "?".to_string()),
        loader,
        mods_path: mods_path.clone(),
        minecraft_dir: root.clone(),
        has_polymer: contains_mod(&mods_path, "polymer"),
        worlds,
    }]
}

fn read_official_version(root: &Path) -> Option<String> {
    let data = fs::read(root.join("launcher_profiles.json")).ok()?;
    let value: Value = serde_json::from_slice(&data).ok()?;
    let selected = value.get("selectedProfile")?.as_str()?;
    value.get("profiles")?
        .get(selected)?
        .get("lastVersionId")?
        .as_str()
        .map(str::to_string)
}

#[derive(Debug, Deserialize)]
struct MultiPack {
    #[serde(default)]
    components: Vec<MultiComponent>,
}

#[derive(Debug, Deserialize)]
struct MultiComponent {
    #[serde(default)]
    uid: String,
    #[serde(default)]
    version: String,
}

fn discover_multi_instances(launcher: &Launcher) -> Vec<Instance> {
    let instances_dir = launcher.root.join("instances");
    let Ok(entries) = fs::read_dir(&instances_dir) else { return Vec::new(); };
    let mut instances = Vec::new();

    for entry in entries.flatten() {
        let Ok(kind) = entry.file_type() else { continue; };
        if !kind.is_dir() || entry.file_name().to_string_lossy().starts_with('.') { continue; }
        let instance_dir = entry.path();
        let minecraft_dir = instance_dir.join("minecraft");
        if !minecraft_dir.is_dir() { continue; }
        let mods_path = minecraft_dir.join("mods");
        let loader = read_multi_loader(&instance_dir, &minecraft_dir);
        let version = read_multi_version(&instance_dir).unwrap_or_else(|| "?".to_string());
        let worlds = discover_worlds(&minecraft_dir.join("saves"));
        instances.push(Instance {
            launcher: launcher.launcher_type,
            name: entry.file_name().to_string_lossy().to_string(),
            version,
            loader,
            has_polymer: contains_mod(&mods_path, "polymer"),
            mods_path,
            minecraft_dir,
            worlds,
        });
    }

    instances.sort_by(|a, b| a.name.to_ascii_lowercase().cmp(&b.name.to_ascii_lowercase()));
    instances
}

fn read_multi_pack(instance_dir: &Path) -> Option<MultiPack> {
    let data = fs::read(instance_dir.join("mmc-pack.json")).ok()?;
    serde_json::from_slice(&data).ok()
}

fn read_multi_loader(instance_dir: &Path, minecraft_dir: &Path) -> ModLoader {
    if let Some(pack) = read_multi_pack(instance_dir) {
        for component in pack.components {
            let uid = component.uid.to_ascii_lowercase();
            if uid.contains("neoforge") { return ModLoader::NeoForge; }
            if uid.contains("forge") { return ModLoader::Forge; }
            if uid.contains("fabric-loader") || uid.contains("fabricloader") { return ModLoader::Fabric; }
            if uid.contains("quilt-loader") || uid.contains("quiltloader") { return ModLoader::Quilt; }
        }
    }
    detect_loader(minecraft_dir)
}

fn read_multi_version(instance_dir: &Path) -> Option<String> {
    if let Some(pack) = read_multi_pack(instance_dir) {
        for component in pack.components {
            if component.uid == "net.minecraft" && !component.version.is_empty() {
                return Some(component.version);
            }
        }
    }
    let text = fs::read_to_string(instance_dir.join("instance.cfg")).ok()?;
    text.lines()
        .find_map(|line| line.strip_prefix("IntendedVersion=").map(str::trim).filter(|value| !value.is_empty()).map(str::to_string))
}

fn discover_atlauncher_instances(launcher: &Launcher) -> Vec<Instance> {
    let instances_dir = launcher.root.join("instances");
    let Ok(entries) = fs::read_dir(&instances_dir) else { return Vec::new(); };
    let mut instances = Vec::new();
    for entry in entries.flatten() {
        if !entry.file_type().is_ok_and(|kind| kind.is_dir()) { continue; }
        let instance_root = entry.path();
        let dot_minecraft = instance_root.join(".minecraft");
        let minecraft_dir = if dot_minecraft.is_dir() { dot_minecraft } else { instance_root };
        let saves = minecraft_dir.join("saves");
        if !saves.is_dir() { continue; }
        instances.push(basic_instance(launcher.launcher_type, entry.file_name().to_string_lossy().to_string(), minecraft_dir));
    }
    instances
}

fn discover_curseforge_instances(launcher: &Launcher) -> Vec<Instance> {
    let preferred = launcher.root.join("Instances");
    let instances_dir = if preferred.is_dir() { preferred } else { launcher.root.clone() };
    let Ok(entries) = fs::read_dir(instances_dir) else { return Vec::new(); };
    let mut instances = Vec::new();
    for entry in entries.flatten() {
        if !entry.file_type().is_ok_and(|kind| kind.is_dir()) { continue; }
        let minecraft_dir = entry.path().join(".minecraft");
        if !minecraft_dir.is_dir() { continue; }
        instances.push(basic_instance(launcher.launcher_type, entry.file_name().to_string_lossy().to_string(), minecraft_dir));
    }
    instances
}

fn basic_instance(launcher: LauncherType, name: String, minecraft_dir: PathBuf) -> Instance {
    let mods_path = minecraft_dir.join("mods");
    Instance {
        launcher,
        name,
        version: infer_version_from_path(&minecraft_dir).unwrap_or_else(|| "?".to_string()),
        loader: detect_loader(&minecraft_dir),
        has_polymer: contains_mod(&mods_path, "polymer"),
        worlds: discover_worlds(&minecraft_dir.join("saves")),
        mods_path,
        minecraft_dir,
    }
}

pub fn discover_worlds(saves_dir: &Path) -> Vec<World> {
    let Ok(entries) = fs::read_dir(saves_dir) else { return Vec::new(); };
    let mut worlds = Vec::new();
    for entry in entries.flatten() {
        if !entry.file_type().is_ok_and(|kind| kind.is_dir()) { continue; }
        let path = entry.path();
        let level_dat = path.join("level.dat");
        if !level_dat.is_file() { continue; }
        let last_played = fs::metadata(&level_dat).and_then(|metadata| metadata.modified()).ok();
        worlds.push(World {
            name: entry.file_name().to_string_lossy().to_string(),
            path,
            last_played,
        });
    }
    worlds.sort_by(|a, b| b.last_played.cmp(&a.last_played));
    worlds
}

pub fn detect_loader(minecraft_dir: &Path) -> ModLoader {
    if minecraft_dir.join(".fabric").is_dir() { return ModLoader::Fabric; }
    let mods_dir = minecraft_dir.join("mods");
    let Ok(entries) = fs::read_dir(&mods_dir) else { return ModLoader::Vanilla; };
    let names: Vec<String> = entries.flatten().map(|entry| entry.file_name().to_string_lossy().to_ascii_lowercase()).collect();
    for name in &names {
        if name.contains("fabric-loader") || name.contains("fabric_loader") { return ModLoader::Fabric; }
        if name.contains("neoforge") { return ModLoader::NeoForge; }
        if name.contains("forge") { return ModLoader::Forge; }
        if name.contains("quilt") { return ModLoader::Quilt; }
    }
    if names.is_empty() { ModLoader::Vanilla } else { ModLoader::Fabric }
}

fn contains_mod(mods_dir: &Path, needle: &str) -> bool {
    let Ok(entries) = fs::read_dir(mods_dir) else { return false; };
    entries.flatten().any(|entry| entry.file_name().to_string_lossy().to_ascii_lowercase().contains(needle))
}

fn infer_version_from_path(path: &Path) -> Option<String> {
    for component in path.components().rev() {
        let value = component.as_os_str().to_string_lossy();
        for token in value.split(|ch: char| !(ch.is_ascii_digit() || ch == '.')) {
            if looks_like_version(token) { return Some(token.to_string()); }
        }
    }
    None
}

fn looks_like_version(value: &str) -> bool {
    let parts: Vec<_> = value.split('.').collect();
    if !(2..=3).contains(&parts.len()) { return false; }
    if parts.iter().any(|part| part.is_empty() || !part.chars().all(|ch| ch.is_ascii_digit())) { return false; }
    parts[0] == "1" || parts[0].parse::<u32>().is_ok_and(|major| major >= 20)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loader_labels_are_stable() {
        assert_eq!(ModLoader::Fabric.label(), "Fabric");
        assert_eq!(ModLoader::NeoForge.label(), "NeoForge");
    }

    #[test]
    fn version_parser_accepts_modern_versions() {
        assert!(looks_like_version("1.21.10"));
        assert!(looks_like_version("26.1"));
        assert!(!looks_like_version("hello"));
    }

    #[test]
    fn launcher_labels_match_old_desktop() {
        assert_eq!(LauncherType::FreeSM.label(), "FreesmLauncher");
        assert_eq!(LauncherType::Prism.label(), "PrismLauncher");
    }
}
