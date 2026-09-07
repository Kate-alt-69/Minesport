from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/heightmap_cache.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''    io::Write,
    path::{Path, PathBuf},''',
    '''    io::{Read, Write},
    path::{Path, PathBuf},''',
    "heightmap streaming fingerprint import",
)

text = replace_once(
    text,
    '''fn fingerprint(world: &Path) -> Result<String> {
    let absolute = fs::canonicalize(world).unwrap_or_else(|_| world.to_path_buf());
    let mut hash = Sha256::new();
    hash.update(absolute.to_string_lossy().as_bytes());
    hash.update(b"\\n");

    let mut paths = vec![world.join("level.dat")];
    if let Some(region) = overworld_region_dir(world) {
        let mut regions = fs::read_dir(&region)
            .with_context(|| format!("read {}", region.display()))?
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| {
                path.extension()
                    .and_then(|value| value.to_str())
                    .is_some_and(|value| {
                        value.eq_ignore_ascii_case("mca") || value.eq_ignore_ascii_case("mcr")
                    })
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
        hash.update(b"\\n");
    }
    Ok(format!("{:x}", hash.finalize()))
}
''',
    '''fn fingerprint(world: &Path) -> Result<String> {
    const REGION_HEADER_BYTES: u64 = 8 * 1024;

    let absolute = fs::canonicalize(world).unwrap_or_else(|_| world.to_path_buf());
    let mut hash = Sha256::new();
    hash.update(b"minesport-heightmap-fingerprint-v2\\0");
    hash.update(absolute.to_string_lossy().as_bytes());
    hash.update(b"\\n");

    let level = world.join("level.dat");
    hash_heightmap_input(world, &level, None, &mut hash)?;

    if let Some(region) = overworld_region_dir(world) {
        let mut entries = fs::read_dir(&region)
            .with_context(|| format!("read {}", region.display()))?
            .collect::<std::io::Result<Vec<_>>>()?;
        entries.sort_by_key(|entry| entry.file_name());
        for entry in entries {
            if !entry.file_type()?.is_file() {
                continue;
            }
            let path = entry.path();
            let is_region = path
                .extension()
                .and_then(|value| value.to_str())
                .is_some_and(|value| {
                    value.eq_ignore_ascii_case("mca") || value.eq_ignore_ascii_case("mcr")
                });
            if is_region {
                // Minecraft region location/timestamp tables live in the first
                // 8 KiB. Hashing that header catches chunk-layout/content updates
                // without rereading potentially multi-gigabyte worlds.
                hash_heightmap_input(world, &path, Some(REGION_HEADER_BYTES), &mut hash)?;
            }
        }
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn hash_heightmap_input(
    world: &Path,
    path: &Path,
    max_bytes: Option<u64>,
    hash: &mut Sha256,
) -> Result<()> {
    let metadata = fs::metadata(path).with_context(|| format!("stat {}", path.display()))?;
    let relative = path.strip_prefix(world).unwrap_or(path);
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
    hash.update(b"|");

    let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut reader: Box<dyn Read> = match max_bytes {
        Some(limit) => Box::new(file.take(limit)),
        None => Box::new(file),
    };
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        let read = reader
            .read(&mut buffer)
            .with_context(|| format!("hash {}", path.display()))?;
        if read == 0 {
            break;
        }
        hash.update(&buffer[..read]);
    }
    hash.update(b"\\n");
    Ok(())
}
''',
    "heightmap region-header fingerprint",
)

regression = r'''
    #[test]
    fn region_header_bytes_participate_in_heightmap_identity() {
        let world = temp_world("header-identity");
        let region = world.join("region").join("r.0.0.mca");
        let mut first_region = vec![0_u8; 16 * 1024];
        first_region[4096] = 1;
        fs::write(&region, &first_region).unwrap();
        let original_modified = fs::metadata(&region).unwrap().modified().unwrap();
        let first = fingerprint(&world).unwrap();

        // Keep both file length and mtime identical, then change only the
        // Minecraft region header. Metadata-only identities would miss this.
        let mut second_region = first_region;
        second_region[4096] = 2;
        fs::write(&region, &second_region).unwrap();
        fs::File::options()
            .write(true)
            .open(&region)
            .unwrap()
            .set_times(fs::FileTimes::new().set_modified(original_modified))
            .unwrap();
        let second = fingerprint(&world).unwrap();
        assert_ne!(first, second);

        let _ = fs::remove_dir_all(world);
    }

'''
text = replace_once(
    text,
    '''    #[test]
    fn cache_round_trip_preserves_bounds_and_png() {''',
    regression + '''    #[test]
    fn cache_round_trip_preserves_bounds_and_png() {''',
    "heightmap header identity regression",
)

path.write_text(text, encoding="utf-8")
