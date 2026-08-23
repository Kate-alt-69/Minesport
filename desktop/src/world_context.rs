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

pub struct WorldDiscovery {
    catalog: Vec<launcher::CatalogEntry>,
    world_count: usize,
}

impl WorldDiscovery {
    pub fn len(&self) -> usize {
        self.world_count
    }

    pub(crate) fn into_catalog(self) -> Vec<launcher::CatalogEntry> {
        self.catalog
    }
}

pub fn resolve(world_path: &Path) -> Option<WorldContext> {
    let wanted = normalize(world_path);
    for entry in launcher::discover_catalog() {
        for instance in &entry.instances {
            for world in &instance.worlds {
                if normalize(&world.path) != wanted {
                    continue;
                }
                return Some(context_from_parts(&entry.launcher, instance, world));
            }
        }
    }
    None
}

pub fn all_worlds() -> WorldDiscovery {
    let catalog = launcher::discover_catalog();
    let world_count = catalog
        .iter()
        .map(|entry| entry.instances.iter().map(|instance| instance.worlds.len()).sum::<usize>())
        .sum();
    WorldDiscovery {
        catalog,
        world_count,
    }
}

pub(crate) fn context_from_parts(
    discovered: &launcher::Launcher,
    instance: &launcher::Instance,
    world: &launcher::World,
) -> WorldContext {
    WorldContext {
        launcher: discovered.name.clone(),
        instance: instance.name.clone(),
        world_name: world.name.clone(),
        world_path: world.path.clone(),
        minecraft_dir: instance.minecraft_dir.clone(),
        mods_path: instance.mods_path.clone(),
        version: instance.version.clone(),
        loader: instance.loader.label().to_string(),
        has_polymer: instance.has_polymer,
    }
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

    #[test]
    fn discovery_len_counts_worlds_without_flattening_them() {
        let discovery = WorldDiscovery {
            catalog: vec![launcher::CatalogEntry {
                launcher: launcher::Launcher {
                    launcher_type: launcher::LauncherType::Prism,
                    name: "Prism Launcher".into(),
                    root: PathBuf::from("PrismLauncher"),
                },
                instances: vec![launcher::Instance {
                    launcher: launcher::LauncherType::Prism,
                    name: "Test".into(),
                    version: "1.21.10".into(),
                    loader: launcher::ModLoader::Fabric,
                    mods_path: PathBuf::from("minecraft/mods"),
                    minecraft_dir: PathBuf::from("minecraft"),
                    worlds: vec![
                        launcher::World {
                            name: "A".into(),
                            path: PathBuf::from("A"),
                            last_played: None,
                        },
                        launcher::World {
                            name: "B".into(),
                            path: PathBuf::from("B"),
                            last_played: None,
                        },
                    ],
                    has_polymer: false,
                }],
            }],
            world_count: 2,
        };
        assert_eq!(discovery.len(), 2);
        assert_eq!(discovery.into_catalog().len(), 1);
    }
}
