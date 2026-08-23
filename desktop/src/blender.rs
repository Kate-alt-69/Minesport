use anyhow::{Context, Result, bail};
use include_dir::{Dir, DirEntry, include_dir};
use std::{
    env, fs,
    path::{Path, PathBuf},
};

static ADDON_DIR: Dir<'_> =
    include_dir!("$CARGO_MANIFEST_DIR/assets/blender/minesport_translator");

#[derive(Debug, Clone)]
pub struct InstallReport {
    pub installed_profiles: Vec<PathBuf>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct BlenderTarget {
    version: String,
    profile_dir: PathBuf,
}

pub fn install_detected_profiles() -> Result<InstallReport> {
    let targets = discover_targets()?;
    if targets.is_empty() {
        bail!(
            "No Blender 4.3+ user profile was detected. Start Blender once, then run the installer again."
        );
    }

    let mut installed = Vec::new();
    for target in targets {
        let addons = target.profile_dir.join("scripts").join("addons");
        let destination = addons.join("minesport_translator");
        let temporary = addons.join(".minesport_translator.tmp");
        fs::create_dir_all(&addons)
            .with_context(|| format!("create {}", addons.display()))?;
        if temporary.exists() {
            fs::remove_dir_all(&temporary)
                .with_context(|| format!("reset {}", temporary.display()))?;
        }
        fs::create_dir_all(&temporary)
            .with_context(|| format!("create {}", temporary.display()))?;
        extract_dir(&ADDON_DIR, &temporary)?;

        if destination.exists() {
            fs::remove_dir_all(&destination).with_context(|| {
                format!(
                    "replace old Minesport add-on at {}",
                    destination.display()
                )
            })?;
        }
        fs::rename(&temporary, &destination).with_context(|| {
            format!(
                "install Minesport translator {} -> {}",
                temporary.display(),
                destination.display()
            )
        })?;
        installed.push(target.profile_dir);
    }

    Ok(InstallReport {
        installed_profiles: installed,
    })
}

/// Blender profile directories that Minesport can install into. This keeps the
/// old Rust call-site contract while discovery itself now matches the retired
/// Go implementation, including Blender installations found under Program Files.
pub fn detected_profiles() -> Result<Vec<PathBuf>> {
    Ok(discover_targets()?
        .into_iter()
        .map(|target| target.profile_dir)
        .collect())
}

fn discover_targets() -> Result<Vec<BlenderTarget>> {
    let mut targets = discover_profile_targets()?;
    #[cfg(windows)]
    targets.extend(discover_windows_install_targets()?);

    targets.sort_by(|left, right| {
        left.version
            .cmp(&right.version)
            .then_with(|| left.profile_dir.cmp(&right.profile_dir))
    });
    targets.dedup_by(|left, right| same_path(&left.profile_dir, &right.profile_dir));
    Ok(targets)
}

fn discover_profile_targets() -> Result<Vec<BlenderTarget>> {
    let Some(root) = blender_profile_root() else {
        return Ok(Vec::new());
    };
    if !root.is_dir() {
        return Ok(Vec::new());
    }

    let mut targets = Vec::new();
    for entry in fs::read_dir(&root)
        .with_context(|| format!("read Blender profiles under {}", root.display()))?
    {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let version = entry.file_name().to_string_lossy().to_string();
        if blender_version_at_least_4_3(&version) {
            targets.push(BlenderTarget {
                version,
                profile_dir: entry.path(),
            });
        }
    }
    Ok(targets)
}

#[cfg(windows)]
fn discover_windows_install_targets() -> Result<Vec<BlenderTarget>> {
    let Some(program_files) = env::var_os("ProgramFiles").map(PathBuf::from) else {
        return Ok(Vec::new());
    };
    let Some(appdata) = env::var_os("APPDATA").map(PathBuf::from) else {
        return Ok(Vec::new());
    };
    let root = program_files.join("Blender Foundation");
    if !root.is_dir() {
        return Ok(Vec::new());
    }

    let mut targets = Vec::new();
    for entry in fs::read_dir(&root)
        .with_context(|| format!("read Blender installs under {}", root.display()))?
    {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(version) = name.strip_prefix("Blender ").map(str::trim) else {
            continue;
        };
        if !blender_version_at_least_4_3(version) {
            continue;
        }
        targets.push(BlenderTarget {
            version: version.to_string(),
            profile_dir: appdata
                .join("Blender Foundation")
                .join("Blender")
                .join(version),
        });
    }
    Ok(targets)
}

fn blender_profile_root() -> Option<PathBuf> {
    if cfg!(windows) {
        return env::var_os("APPDATA")
            .map(PathBuf::from)
            .map(|path| path.join("Blender Foundation").join("Blender"));
    }
    if cfg!(target_os = "macos") {
        return home_dir().map(|home| {
            home.join("Library")
                .join("Application Support")
                .join("Blender")
        });
    }
    if let Some(xdg) = env::var_os("XDG_CONFIG_HOME") {
        return Some(PathBuf::from(xdg).join("blender"));
    }
    home_dir().map(|home| home.join(".config").join("blender"))
}

fn home_dir() -> Option<PathBuf> {
    env::var_os("HOME")
        .or_else(|| env::var_os("USERPROFILE"))
        .map(PathBuf::from)
}

fn same_path(left: &Path, right: &Path) -> bool {
    if cfg!(windows) {
        left.to_string_lossy()
            .eq_ignore_ascii_case(&right.to_string_lossy())
    } else {
        left == right
    }
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
    let Some(major) = parts.next() else {
        return false;
    };
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

    #[test]
    fn target_dedupe_compares_profiles() {
        let left = PathBuf::from("Blender/4.3");
        let right = PathBuf::from("Blender/4.3");
        assert!(same_path(&left, &right));
    }
}
