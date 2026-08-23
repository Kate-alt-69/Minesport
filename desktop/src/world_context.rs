use crate::launcher;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldContext {
    pub launcher: String,
    pub instance: String,
    pub world_name: String,
    pub world_path: PathBuf,
    pub minecraft_dir: PathBuf,
    pub mods_path: PathBuf,
    pub version: String,
    pub loader: String,
    pub has_polymer: bool,
}

pub fn resolve(world_path: &Path) -> Option<WorldContext> {
    let wanted = normalize(world_path);
    for discovered in launcher::discover_all() {
        for instance in launcher::discover_instances(&discovered) {
            for world in &instance.worlds {
                if normalize(&world.path) != wanted {
                    continue;
                }
                return Some(WorldContext {
                    launcher: discovered.name.clone(),
                    instance: instance.name.clone(),
                    world_name: world.name.clone(),
                    world_path: world.path.clone(),
                    minecraft_dir: instance.minecraft_dir.clone(),
                    mods_path: instance.mods_path.clone(),
                    version: instance.version.clone(),
                    loader: instance.loader.label().to_string(),
                    has_polymer: instance.has_polymer,
                });
            }
        }
    }
    None
}

pub fn all_worlds() -> Vec<WorldContext> {
    let mut result = Vec::new();
    for discovered in launcher::discover_all() {
        for instance in launcher::discover_instances(&discovered) {
            for world in &instance.worlds {
                result.push(WorldContext {
                    launcher: discovered.name.clone(),
                    instance: instance.name.clone(),
                    world_name: world.name.clone(),
                    world_path: world.path.clone(),
                    minecraft_dir: instance.minecraft_dir.clone(),
                    mods_path: instance.mods_path.clone(),
                    version: instance.version.clone(),
                    loader: instance.loader.label().to_string(),
                    has_polymer: instance.has_polymer,
                });
            }
        }
    }
    result.sort_by(|a, b| {
        a.launcher
            .to_ascii_lowercase()
            .cmp(&b.launcher.to_ascii_lowercase())
            .then_with(|| a.instance.to_ascii_lowercase().cmp(&b.instance.to_ascii_lowercase()))
            .then_with(|| a.world_name.to_ascii_lowercase().cmp(&b.world_name.to_ascii_lowercase()))
    });
    result
}

fn normalize(path: &Path) -> PathBuf {
    std::fs::canonicalize(path).unwrap_or_else(|_| path.components().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn context_shape_keeps_instance_owned_mods_path() {
        let context = WorldContext {
            launcher: "Prism Launcher".into(),
            instance: "Test".into(),
            world_name: "World".into(),
            world_path: PathBuf::from("world"),
            minecraft_dir: PathBuf::from("instance/minecraft"),
            mods_path: PathBuf::from("instance/minecraft/mods"),
            version: "1.21.10".into(),
            loader: "Fabric".into(),
            has_polymer: true,
        };
        assert_eq!(context.mods_path, PathBuf::from("instance/minecraft/mods"));
        assert_eq!(context.loader, "Fabric");
        assert!(context.has_polymer);
    }
}
