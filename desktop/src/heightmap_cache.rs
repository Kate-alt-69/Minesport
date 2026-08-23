use crate::runtime;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
    time::UNIX_EPOCH,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedHeightmap {
    pub fingerprint: String,
    pub min_x: i32,
    pub min_z: i32,
    pub max_x: i32,
    pub max_z: i32,
    pub scale: i32,
}

pub fn load(world: &Path) -> Result<Option<(CachedHeightmap, Vec<u8>)>> {
    let fingerprint = fingerprint(world)?;
    let (metadata_path, png_path) = cache_paths(world)?;
    let metadata_bytes = match fs::read(&metadata_path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).with_context(|| format!("read {}", metadata_path.display())),
    };
    let metadata: CachedHeightmap = match serde_json::from_slice(&metadata_bytes) {
        Ok(value) => value,
        Err(_) => return Ok(None),
    };
    if metadata.fingerprint != fingerprint {
        return Ok(None);
    }
    let png = match fs::read(&png_path) {
        Ok(bytes) if !bytes.is_empty() => bytes,
        Ok(_) => return Ok(None),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).with_context(|| format!("read {}", png_path.display())),
    };
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
    };
    let metadata_bytes = serde_json::to_vec(&metadata).context("serialize heightmap cache metadata")?;

    atomic_replace(&png_path, png)?;
    atomic_replace(&metadata_path, &metadata_bytes)?;
    Ok(())
}

pub fn invalidate(world: &Path) -> Result<()> {
    let (metadata_path, png_path) = cache_paths(world)?;
    for path in [metadata_path, png_path] {
        match fs::remove_file(&path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error).with_context(|| format!("remove {}", path.display())),
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
    let region = world.join("region");
    if region.is_dir() {
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

fn cache_paths(world: &Path) -> Result<(PathBuf, PathBuf)> {
    let absolute = fs::canonicalize(world).unwrap_or_else(|_| world.to_path_buf());
    let mut hash = Sha256::new();
    hash.update(absolute.to_string_lossy().as_bytes());
    let digest = format!("{:x}", hash.finalize());
    let key = &digest[..32];
    let root = runtime::cache_root().join("heightmaps");
    Ok((root.join(format!("{key}.json")), root.join(format!("{key}.png"))))
}

fn atomic_replace(path: &Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension(format!(
        "{}.tmp",
        path.extension().and_then(|value| value.to_str()).unwrap_or("cache")
    ));
    fs::write(&temporary, bytes).with_context(|| format!("write {}", temporary.display()))?;
    let _ = fs::remove_file(path);
    fs::rename(&temporary, path).with_context(|| format!("install {}", path.display()))?;
    Ok(())
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
}
