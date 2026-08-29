use crate::runtime;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File},
    io::Write,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedHeightmap {
    pub fingerprint: String,
    pub min_x: i32,
    pub min_z: i32,
    pub max_x: i32,
    pub max_z: i32,
    pub scale: i32,
    #[serde(default)]
    pub png_sha256: String,
}

pub fn load(world: &Path) -> Result<Option<(CachedHeightmap, Vec<u8>)>> {
    let fingerprint = fingerprint(world)?;
    let (metadata_path, png_path) = cache_paths(world)?;
    restore_backup_if_needed(&metadata_path)?;
    restore_backup_if_needed(&png_path)?;

    let metadata_bytes = match fs::read(&metadata_path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).with_context(|| format!("read {}", metadata_path.display())),
    };
    let metadata: CachedHeightmap = match serde_json::from_slice(&metadata_bytes) {
        Ok(value) => value,
        Err(_) => return Ok(None),
    };
    if metadata.fingerprint != fingerprint || metadata.png_sha256.is_empty() {
        return Ok(None);
    }
    let png = match fs::read(&png_path) {
        Ok(bytes) if !bytes.is_empty() => bytes,
        Ok(_) => return Ok(None),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).with_context(|| format!("read {}", png_path.display())),
    };
    if sha256_hex(&png) != metadata.png_sha256 {
        return Ok(None);
    }
    Ok(Some((metadata, png)))
}

pub fn save(
    world: &Path,
    png: &[u8],
    min_x: i32,
    min_z: i32,
    max_x: i32,
    max_z: i32,
    scale: i32,
) -> Result<()> {
    let fingerprint = fingerprint(world)?;
    let (metadata_path, png_path) = cache_paths(world)?;
    let parent = metadata_path.parent().context("heightmap cache has no parent")?;
    fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;

    let metadata = CachedHeightmap {
        fingerprint,
        min_x,
        min_z,
        max_x,
        max_z,
        scale,
        png_sha256: sha256_hex(png),
    };
    let metadata_bytes = serde_json::to_vec(&metadata).context("serialize heightmap cache metadata")?;

    // Publish data first, then metadata. The metadata carries the PNG digest,
    // so a crash between these operations can only create a cache miss, never
    // a mismatched image/bounds pair that is accepted as valid.
    atomic_replace(&png_path, png)?;
    atomic_replace(&metadata_path, &metadata_bytes)?;
    Ok(())
}

pub fn invalidate(world: &Path) -> Result<()> {
    let (metadata_path, png_path) = cache_paths(world)?;
    for path in [metadata_path, png_path] {
        for candidate in [path.clone(), backup_path(&path)] {
            match fs::remove_file(&candidate) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error).with_context(|| format!("remove {}", candidate.display())),
            }
        }
    }
    Ok(())
}

fn fingerprint(world: &Path) -> Result<String> {
    let absolute = fs::canonicalize(world).unwrap_or_else(|_| world.to_path_buf());
    let mut hash = Sha256::new();
    hash.update(absolute.to_string_lossy().as_bytes());
    hash.update(b"\n");

    let mut paths = vec![world.join("level.dat")];
    if let Some(region) = overworld_region_dir(world) {
        let mut regions = fs::read_dir(&region)
            .with_context(|| format!("read {}", region.display()))?
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| {
                path.extension()
                    .and_then(|value| value.to_str())
                    .is_some_and(|value| value.eq_ignore_ascii_case("mca") || value.eq_ignore_ascii_case("mcr"))
            })
            .collect::<Vec<_>>();
        regions.sort();
        paths.extend(regions);
    }

    for path in paths {
        let metadata = fs::metadata(&path).with_context(|| format!("stat {}", path.display()))?;
        let relative = path.strip_prefix(world).unwrap_or(&path);
        let modified = metadata
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .map(|value| value.as_nanos())
            .unwrap_or_default();
        hash.update(relative.to_string_lossy().as_bytes());
        hash.update(b"|");
        hash.update(metadata.len().to_string().as_bytes());
        hash.update(b"|");
        hash.update(modified.to_string().as_bytes());
        hash.update(b"\n");
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn overworld_region_dir(world: &Path) -> Option<PathBuf> {
    let modern = world
        .join("dimensions")
        .join("minecraft")
        .join("overworld")
        .join("region");
    if modern.is_dir() {
        return Some(modern);
    }
    let legacy = world.join("region");
    legacy.is_dir().then_some(legacy)
}

fn cache_paths(world: &Path) -> Result<(PathBuf, PathBuf)> {
    let absolute = fs::canonicalize(world).unwrap_or_else(|_| world.to_path_buf());
    let mut hash = Sha256::new();
    hash.update(absolute.to_string_lossy().as_bytes());
    let digest = format!("{:x}", hash.finalize());
    let key = &digest[..32];
    let root = runtime::cache_root().join("heightmaps");
    Ok((root.join(format!("{key}.json")), root.join(format!("{key}.png"))))
}

fn sha256_hex(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn backup_path(path: &Path) -> PathBuf {
    let extension = path.extension().and_then(|value| value.to_str()).unwrap_or("cache");
    path.with_extension(format!("{extension}.bak"))
}

fn restore_backup_if_needed(path: &Path) -> Result<()> {
    if path.exists() {
        return Ok(());
    }
    let backup = backup_path(path);
    if backup.is_file() {
        fs::rename(&backup, path)
            .with_context(|| format!("restore {} from {}", path.display(), backup.display()))?;
    }
    Ok(())
}

fn atomic_replace(path: &Path, bytes: &[u8]) -> Result<()> {
    restore_backup_if_needed(path)?;
    let extension = path.extension().and_then(|value| value.to_str()).unwrap_or("cache");
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let temporary = path.with_extension(format!(
        "{extension}.{}.{}.tmp",
        std::process::id(),
        stamp
    ));
    let backup = backup_path(path);

    {
        let mut file = File::create(&temporary)
            .with_context(|| format!("create {}", temporary.display()))?;
        file.write_all(bytes)
            .with_context(|| format!("write {}", temporary.display()))?;
        file.sync_all()
            .with_context(|| format!("sync {}", temporary.display()))?;
    }

    let had_original = path.is_file();
    if had_original {
        let _ = fs::remove_file(&backup);
        fs::rename(path, &backup)
            .with_context(|| format!("stage previous cache {}", path.display()))?;
    }

    match fs::rename(&temporary, path) {
        Ok(()) => {
            let _ = fs::remove_file(&backup);
            Ok(())
        }
        Err(error) => {
            let _ = fs::remove_file(&temporary);
            if had_original && backup.is_file() {
                let _ = fs::rename(&backup, path);
            }
            Err(error).with_context(|| format!("install {}", path.display()))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_world(name: &str) -> PathBuf {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("minesport-heightmap-{name}-{}-{stamp}", std::process::id()));
        fs::create_dir_all(root.join("region")).unwrap();
        fs::write(root.join("level.dat"), b"level").unwrap();
        fs::write(root.join("region").join("r.0.0.mca"), b"region-a").unwrap();
        root
    }

    fn temp_modern_world(name: &str) -> PathBuf {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("minesport-heightmap-modern-{name}-{}-{stamp}", std::process::id()));
        let region = root.join("dimensions").join("minecraft").join("overworld").join("region");
        fs::create_dir_all(&region).unwrap();
        fs::write(root.join("level.dat"), b"level").unwrap();
        fs::write(region.join("r.0.0.mca"), b"modern-a").unwrap();
        root
    }

    #[test]
    fn fingerprint_changes_with_region_metadata() {
        let world = temp_world("fingerprint");
        let first = fingerprint(&world).unwrap();
        fs::write(world.join("region").join("r.0.0.mca"), b"region-b-longer").unwrap();
        let second = fingerprint(&world).unwrap();
        assert_ne!(first, second);
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn fingerprint_tracks_modern_overworld_region_metadata() {
        let world = temp_modern_world("fingerprint");
        let region = world.join("dimensions").join("minecraft").join("overworld").join("region");
        let first = fingerprint(&world).unwrap();
        fs::write(region.join("r.0.0.mca"), b"modern-b-longer").unwrap();
        let second = fingerprint(&world).unwrap();
        assert_ne!(first, second);
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn cache_round_trip_preserves_bounds_and_png() {
        let world = temp_world("roundtrip");
        let png = b"not-a-real-png-but-cache-does-not-interpret-it";
        save(&world, png, -32, -16, 64, 80, 1).unwrap();
        let (metadata, restored) = load(&world).unwrap().expect("cache hit");
        assert_eq!((metadata.min_x, metadata.min_z, metadata.max_x, metadata.max_z), (-32, -16, 64, 80));
        assert_eq!(restored, png);
        invalidate(&world).unwrap();
        assert!(load(&world).unwrap().is_none());
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn mismatched_png_is_rejected_instead_of_pairing_with_old_metadata() {
        let world = temp_world("torn-pair");
        save(&world, b"png-a", 0, 0, 16, 16, 1).unwrap();
        let (_, png_path) = cache_paths(&world).unwrap();
        fs::write(&png_path, b"png-b").unwrap();
        assert!(load(&world).unwrap().is_none());
        let _ = fs::remove_dir_all(world);
    }

    #[test]
    fn missing_primary_is_restored_from_backup() {
        let world = temp_world("backup");
        save(&world, b"png", 0, 0, 16, 16, 1).unwrap();
        let (metadata_path, _) = cache_paths(&world).unwrap();
        let backup = backup_path(&metadata_path);
        fs::rename(&metadata_path, &backup).unwrap();
        assert!(load(&world).unwrap().is_some());
        assert!(metadata_path.is_file());
        let _ = fs::remove_dir_all(world);
    }
}
