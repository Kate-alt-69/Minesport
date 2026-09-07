from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/runtime_worker.rs")
text = path.read_text(encoding="utf-8")

old = '''    let staged_mods = workspace.join("run").join("mods");
    let staged_raw_fingerprint = runtime_worker_inputs_fingerprint(family, &staged_mods)?;
    let fingerprint = scoped_cache_fingerprint(family, &staged_raw_fingerprint, &scope);
'''
new = '''    let staged_mods = workspace.join("run").join("mods");
    let staged_inputs_fingerprint = runtime_worker_inputs_fingerprint(family, &staged_mods)?;
    let capture_fingerprint = runtime_worker_capture_fingerprint(&workspace)?;
    let staged_raw_fingerprint =
        runtime_worker_cache_fingerprint(&staged_inputs_fingerprint, &capture_fingerprint);
    let fingerprint = scoped_cache_fingerprint(family, &staged_raw_fingerprint, &scope);
'''
text = replace_once(text, old, new, "salt cache identity with capture worker")

helper = r'''
fn runtime_worker_cache_fingerprint(inputs: &str, capture: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"minesport-runtime-worker-cache-v2\0");
    for component in [inputs.as_bytes(), capture.as_bytes()] {
        hasher.update((component.len() as u64).to_be_bytes());
        hasher.update(component);
    }
    hex_digest(&hasher.finalize())
}

fn runtime_worker_capture_fingerprint(workspace: &Path) -> Result<String> {
    let mut hasher = Sha256::new();
    hasher.update(b"minesport-runtime-capture-worker-v1\0");
    hash_runtime_capture_tree(workspace, workspace, &mut hasher)?;

    // The fast Fabric path stages the embedded Minesport worker JAR under run/mods.
    // User mods live there too, so hash only Minesport's own worker artifact here;
    // user mods/config are already covered by runtime_worker_inputs_fingerprint.
    let mods = workspace.join("run").join("mods");
    if mods.is_dir() {
        let mut entries = fs::read_dir(&mods)?.collect::<std::io::Result<Vec<_>>>()?;
        entries.sort_by_key(|entry| entry.file_name());
        for entry in entries {
            if !entry.file_type()?.is_file() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            let lower = name.to_ascii_lowercase();
            if !lower.starts_with("minesport_export_worker-")
                && !lower.starts_with("minesport-export-worker-")
            {
                continue;
            }
            hasher.update(b"embedded-worker\0");
            hash_capture_file(&entry.path(), Path::new(&name), &mut hasher)?;
        }
    }
    Ok(hex_digest(&hasher.finalize()))
}

fn hash_runtime_capture_tree(root: &Path, current: &Path, hasher: &mut Sha256) -> Result<()> {
    let mut entries = fs::read_dir(current)
        .with_context(|| format!("read runtime capture workspace {}", current.display()))?
        .collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(|entry| entry.file_name());

    for entry in entries {
        let kind = entry.file_type()?;
        if kind.is_symlink() || (!kind.is_dir() && !kind.is_file()) {
            continue;
        }
        let path = entry.path();
        let relative = path.strip_prefix(root).with_context(|| {
            format!(
                "runtime capture path {} escaped workspace {}",
                path.display(),
                root.display()
            )
        })?;
        let first = relative.components().next().and_then(|component| match component {
            std::path::Component::Normal(value) => value.to_str(),
            _ => None,
        });
        if matches!(first, Some("run" | ".gradle" | "build" | ".git")) {
            continue;
        }

        if kind.is_dir() {
            hasher.update(b"D");
            hash_capture_relative_path(relative, hasher);
            hash_runtime_capture_tree(root, &path, hasher)?;
        } else {
            hash_capture_file(&path, relative, hasher)?;
        }
    }
    Ok(())
}

fn hash_capture_file(path: &Path, relative: &Path, hasher: &mut Sha256) -> Result<()> {
    hasher.update(b"F");
    hash_capture_relative_path(relative, hasher);
    let metadata = fs::metadata(path)
        .with_context(|| format!("stat runtime capture input {}", path.display()))?;
    hasher.update(metadata.len().to_be_bytes());
    let mut file = File::open(path)
        .with_context(|| format!("open runtime capture input {}", path.display()))?;
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .with_context(|| format!("hash runtime capture input {}", path.display()))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(())
}

fn hash_capture_relative_path(path: &Path, hasher: &mut Sha256) {
    let bytes = path.as_os_str().as_encoded_bytes();
    hasher.update((bytes.len() as u64).to_be_bytes());
    hasher.update(bytes);
}

'''
text = replace_once(
    text,
    "fn runtime_worker_mods_fingerprint(family: BridgeFamily, mods_path: &Path) -> Result<String> {\n",
    helper + "fn runtime_worker_mods_fingerprint(family: BridgeFamily, mods_path: &Path) -> Result<String> {\n",
    "runtime capture identity helpers",
)

regression = r'''
    #[test]
    fn capture_worker_changes_invalidate_runtime_cache_identity() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-runtime-capture-identity-{}-{stamp}",
            std::process::id()
        ));
        let run_mods = root.join("run").join("mods");
        fs::create_dir_all(&run_mods).unwrap();
        fs::create_dir_all(root.join("run").join("config")).unwrap();
        fs::create_dir_all(root.join(".gradle")).unwrap();
        fs::write(root.join("build.gradle"), b"worker-source-v1").unwrap();
        fs::write(run_mods.join("user.jar"), b"user-v1").unwrap();
        fs::write(
            run_mods.join("minesport_export_worker-fabric-1.21.10.jar"),
            b"worker-v1",
        )
        .unwrap();
        fs::write(root.join("run").join("config").join("example.json"), b"cfg-v1").unwrap();
        fs::write(root.join(".gradle").join("noise"), b"noise-v1").unwrap();

        let capture_before = runtime_worker_capture_fingerprint(&root).unwrap();
        let key_before = runtime_worker_cache_fingerprint("same-user-inputs", &capture_before);

        // User inputs and generated Gradle state do not belong to the capture
        // implementation identity; they are keyed independently or ignored.
        fs::write(run_mods.join("user.jar"), b"user-v2").unwrap();
        fs::write(root.join("run").join("config").join("example.json"), b"cfg-v2").unwrap();
        fs::write(root.join(".gradle").join("noise"), b"noise-v2").unwrap();
        assert_eq!(
            capture_before,
            runtime_worker_capture_fingerprint(&root).unwrap()
        );

        fs::write(
            run_mods.join("minesport_export_worker-fabric-1.21.10.jar"),
            b"worker-v2",
        )
        .unwrap();
        let embedded_changed = runtime_worker_capture_fingerprint(&root).unwrap();
        assert_ne!(capture_before, embedded_changed);
        assert_ne!(
            key_before,
            runtime_worker_cache_fingerprint("same-user-inputs", &embedded_changed)
        );

        fs::write(
            run_mods.join("minesport_export_worker-fabric-1.21.10.jar"),
            b"worker-v1",
        )
        .unwrap();
        fs::write(root.join("build.gradle"), b"worker-source-v2").unwrap();
        assert_ne!(
            capture_before,
            runtime_worker_capture_fingerprint(&root).unwrap()
        );

        let _ = fs::remove_dir_all(root);
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn fast_worker_versions_are_pinned() {\n",
    regression + "    #[test]\n    fn fast_worker_versions_are_pinned() {\n",
    "capture worker identity regression",
)

path.write_text(text, encoding="utf-8")
