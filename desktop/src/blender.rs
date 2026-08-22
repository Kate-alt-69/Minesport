use anyhow::{Context, Result, bail};
use include_dir::{Dir, DirEntry, include_dir};
use std::{env, fs, path::{Path, PathBuf}};

static ADDON_DIR: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../wrapper/blendertranslator/minesport_translator");

#[derive(Debug, Clone)]
pub struct InstallReport {
    pub installed_profiles: Vec<PathBuf>,
}

pub fn install_detected_profiles() -> Result<InstallReport> {
    let profiles = detected_profiles()?;
    if profiles.is_empty() {
        bail!("No Blender 4.3+ user profile was detected. Start Blender once, then run the installer again.");
    }

    let mut installed = Vec::new();
    for profile in profiles {
        let addons = profile.join("scripts").join("addons");
        let target = addons.join("minesport_translator");
        fs::create_dir_all(&addons).with_context(|| format!("create {}", addons.display()))?;
        if target.exists() {
            fs::remove_dir_all(&target).with_context(|| format!("replace old Minesport add-on at {}", target.display()))?;
        }
        fs::create_dir_all(&target).with_context(|| format!("create {}", target.display()))?;
        extract_dir(&ADDON_DIR, &target)?;
        installed.push(profile);
    }

    Ok(InstallReport { installed_profiles: installed })
}

pub fn detected_profiles() -> Result<Vec<PathBuf>> {
    let root = blender_profile_root();
    if !root.is_dir() {
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    for entry in fs::read_dir(&root).with_context(|| format!("read Blender profiles under {}", root.display()))? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let version = entry.file_name().to_string_lossy().to_string();
        if blender_version_at_least_4_3(&version) {
            profiles.push(entry.path());
        }
    }
    profiles.sort();
    Ok(profiles)
}

fn blender_profile_root() -> PathBuf {
    if cfg!(windows) {
        if let Some(appdata) = env::var_os("APPDATA") {
            return PathBuf::from(appdata).join("Blender Foundation").join("Blender");
        }
    }
    if cfg!(target_os = "macos") {
        if let Some(home) = env::var_os("HOME") {
            return PathBuf::from(home)
                .join("Library")
                .join("Application Support")
                .join("Blender");
        }
    }
    if let Some(xdg) = env::var_os("XDG_CONFIG_HOME") {
        return PathBuf::from(xdg).join("blender");
    }
    if let Some(home) = env::var_os("HOME").or_else(|| env::var_os("USERPROFILE")) {
        return PathBuf::from(home).join(".config").join("blender");
    }
    PathBuf::new()
}

fn extract_dir(source: &Dir<'_>, target: &Path) -> Result<()> {
    for entry in source.entries() {
        match entry {
            DirEntry::Dir(directory) => {
                let destination = target.join(directory.path());
                fs::create_dir_all(&destination)
                    .with_context(|| format!("create {}", destination.display()))?;
                extract_dir(directory, target)?;
            }
            DirEntry::File(file) => {
                let destination = target.join(file.path());
                if let Some(parent) = destination.parent() {
                    fs::create_dir_all(parent)?;
                }
                fs::write(&destination, file.contents())
                    .with_context(|| format!("write {}", destination.display()))?;
            }
        }
    }
    Ok(())
}

fn blender_version_at_least_4_3(value: &str) -> bool {
    let mut parts = value.split('.').filter_map(|part| part.parse::<u32>().ok());
    let Some(major) = parts.next() else { return false; };
    let minor = parts.next().unwrap_or(0);
    major > 4 || (major == 4 && minor >= 3)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_filter_matches_addon_minimum() {
        assert!(!blender_version_at_least_4_3("3.6"));
        assert!(!blender_version_at_least_4_3("4.2"));
        assert!(blender_version_at_least_4_3("4.3"));
        assert!(blender_version_at_least_4_3("5.2"));
    }

    #[test]
    fn embedded_addon_is_not_empty() {
        assert!(ADDON_DIR.get_file("__init__.py").is_some());
    }
}
