from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    matches = text.count(old)
    if matches != 1:
        raise SystemExit(f"{label} anchor changed (matches={matches})")
    return text.replace(old, new, 1)


path = Path("desktop/src/runtime_worker.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    "use serde::Deserialize;\nuse std::{",
    "use serde::Deserialize;\nuse sha2::{Digest, Sha256};\nuse std::{",
    "sha2 import",
)

text = replace_once(
    text,
    '''    progress(Progress {
        percent: 1,
        message: "Checking mods…".into(),
    });
    let raw_fingerprint = runtime_worker_mods_fingerprint(family, mods_path)?;''',
    '''    progress(Progress {
        percent: 1,
        message: "Checking runtime inputs…".into(),
    });
    let raw_fingerprint = runtime_worker_inputs_fingerprint(family, mods_path)?;''',
    "runtime input fingerprint call",
)

anchor = '''fn runtime_worker_mods_fingerprint(family: BridgeFamily, mods_path: &Path) -> Result<String> {
    registry::mods_fingerprint_filtered(mods_path, |path, filename| {
        should_stage_runtime_worker_mod(family, path, filename)
    })
}

'''
if text.count(anchor) != 1:
    raise SystemExit("runtime worker fingerprint helper anchor changed")
helpers = r'''fn runtime_worker_inputs_fingerprint(family: BridgeFamily, mods_path: &Path) -> Result<String> {
    let mods = runtime_worker_mods_fingerprint(family, mods_path)?;
    let config = runtime_worker_config_fingerprint(mods_path)?;
    let mut hasher = Sha256::new();
    hasher.update(b"minesport-runtime-worker-inputs-v1\0");
    for component in [mods.as_bytes(), config.as_bytes()] {
        hasher.update((component.len() as u64).to_be_bytes());
        hasher.update(component);
    }
    Ok(hex_digest(&hasher.finalize()))
}

fn runtime_worker_config_fingerprint(mods_path: &Path) -> Result<String> {
    let Some(instance) = mods_path.parent() else {
        return Ok("missing".into());
    };
    let config = instance.join("config");
    if !config.is_dir() {
        return Ok("missing".into());
    }

    let mut hasher = Sha256::new();
    hasher.update(b"minesport-runtime-worker-config-v1\0");
    hash_worker_config_tree(&config, &config, &mut hasher)?;
    Ok(hex_digest(&hasher.finalize()))
}

fn hash_worker_config_tree(root: &Path, current: &Path, hasher: &mut Sha256) -> Result<()> {
    let mut entries = fs::read_dir(current)
        .with_context(|| format!("read runtime worker config {}", current.display()))?
        .collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(|entry| entry.file_name());

    for entry in entries {
        let kind = entry.file_type()?;
        if kind.is_symlink() {
            continue;
        }
        if !kind.is_dir() && !kind.is_file() {
            continue;
        }

        let path = entry.path();
        let relative = path.strip_prefix(root).with_context(|| {
            format!(
                "runtime worker config path {} escaped root {}",
                path.display(),
                root.display()
            )
        })?;
        let relative_bytes = relative.as_os_str().as_encoded_bytes();
        hasher.update(if kind.is_dir() { b"D" } else { b"F" });
        hasher.update((relative_bytes.len() as u64).to_be_bytes());
        hasher.update(relative_bytes);

        if kind.is_dir() {
            hash_worker_config_tree(root, &path, hasher)?;
            continue;
        }

        let metadata = entry.metadata()?;
        hasher.update(metadata.len().to_be_bytes());
        let mut file = File::open(&path)
            .with_context(|| format!("open runtime worker config {}", path.display()))?;
        let mut buffer = [0_u8; 64 * 1024];
        loop {
            let read = file
                .read(&mut buffer)
                .with_context(|| format!("hash runtime worker config {}", path.display()))?;
            if read == 0 {
                break;
            }
            hasher.update(&buffer[..read]);
        }
    }
    Ok(())
}

fn hex_digest(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        encoded.push(HEX[(byte >> 4) as usize] as char);
        encoded.push(HEX[(byte & 0x0f) as usize] as char);
    }
    encoded
}

'''
text = text.replace(anchor, anchor + helpers, 1)

marker = '''    #[test]
    fn crash_assistant_filename_fallback_survives_unreadable_jar() {'''
if text.count(marker) != 1:
    raise SystemExit("runtime worker test insertion anchor changed")
tests = r'''    #[test]
    fn cache_fingerprint_tracks_worker_config_tree_only() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let instance = env::temp_dir().join(format!(
            "minesport-worker-config-fingerprint-{}-{stamp}",
            std::process::id()
        ));
        let mods = instance.join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("normal.jar"), b"normal-mod").unwrap();

        let without_config = runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &mods).unwrap();

        let nested = instance.join("config").join("example");
        fs::create_dir_all(&nested).unwrap();
        let config_file = nested.join("models.json");
        fs::write(&config_file, b"version-one").unwrap();
        let with_config = runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &mods).unwrap();
        assert_ne!(without_config, with_config);

        fs::write(&config_file, b"version-two-with-different-content").unwrap();
        let changed_config = runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &mods).unwrap();
        assert_ne!(with_config, changed_config);

        fs::write(instance.join("options.txt"), b"unrelated-instance-state").unwrap();
        let after_unrelated_change =
            runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &mods).unwrap();
        assert_eq!(changed_config, after_unrelated_change);

        fs::create_dir_all(instance.join("config").join("empty-directory")).unwrap();
        let after_directory_change =
            runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &mods).unwrap();
        assert_ne!(changed_config, after_directory_change);

        let _ = fs::remove_dir_all(instance);
    }

'''
text = text.replace(marker, tests + marker, 1)
path.write_text(text, encoding="utf-8")
