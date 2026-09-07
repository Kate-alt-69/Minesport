from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/runtime_worker.rs")
text = path.read_text(encoding="utf-8")

# Hashing the live source tree before staging cannot safely authorize cache reuse:
# those files may change immediately afterwards. Snapshot first, then hash only
# the exact isolated inputs Minecraft will load. This also removes a redundant
# full mods/config hash on every cache lookup.
source_identity = '''    progress(Progress {
        percent: 1,
        message: "Checking runtime inputs…".into(),
    });
    let raw_fingerprint = runtime_worker_inputs_fingerprint(family, mods_path)?;
    let fingerprint = scoped_cache_fingerprint(family, &raw_fingerprint, &scope);
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    let cache_root = runtime::cache_root();
    let existing = registry::snapshot_path(&cache_root, &version, &fingerprint);
    if !force && registry::snapshot_exists(&cache_root, &version, &fingerprint) {
        progress(Progress {
            percent: 100,
            message: "Runtime ready".into(),
        });
        return Ok(CacheResult {
            fingerprint,
            registry_path: existing,
            reused: true,
        });
    }

'''
staged_identity_prelude = '''    progress(Progress {
        percent: 1,
        message: "Snapshotting runtime inputs…".into(),
    });
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    let cache_root = runtime::cache_root();
'''
text = replace_once(
    text,
    source_identity,
    staged_identity_prelude,
    "remove live-source cache identity",
)

old = '''    let workspace = plan.workspace;
    let cleanup = WorkspaceCleanup(workspace.clone());
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    progress(Progress {
        percent: 40,
        message: "Checking JDK…".into(),
    });'''
new = '''    let workspace = plan.workspace;
    let cleanup = WorkspaceCleanup(workspace.clone());

    // This is the first safe cache identity: later mutations of the selected
    // instance cannot change these copied mod/config inputs.
    let staged_mods = workspace.join("run").join("mods");
    let staged_raw_fingerprint = runtime_worker_inputs_fingerprint(family, &staged_mods)?;
    let fingerprint = scoped_cache_fingerprint(family, &staged_raw_fingerprint, &scope);
    if !force && registry::snapshot_exists(&cache_root, &version, &fingerprint) {
        progress(Progress {
            percent: 100,
            message: "Runtime ready".into(),
        });
        let registry_path = registry::snapshot_path(&cache_root, &version, &fingerprint);
        drop(cleanup);
        return Ok(CacheResult {
            fingerprint,
            registry_path,
            reused: true,
        });
    }
    if cancel.load(Ordering::Relaxed) {
        bail!("runtime cache cancelled");
    }

    progress(Progress {
        percent: 40,
        message: "Checking JDK…".into(),
    });'''
text = replace_once(text, old, new, "use staged runtime cache identity")

text = replace_once(
    text,
    '''        let destination = target.join(entry.file_name());
        link_or_copy(&path, &destination)
            .with_context(|| format!("stage worker mod {}", path.display()))?;
        count += 1;''',
    '''        let destination = target.join(entry.file_name());
        copy_file_snapshot(&path, &destination)
            .with_context(|| format!("stage worker mod {}", path.display()))?;
        count += 1;''',
    "snapshot user worker mods",
)

snapshot_helper = '''
fn copy_file_snapshot(source: &Path, target: &Path) -> Result<()> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    let _ = fs::remove_file(target);
    fs::copy(source, target)
        .with_context(|| format!("copy snapshot {} to {}", source.display(), target.display()))?;
    Ok(())
}

'''
text = replace_once(
    text,
    "fn link_or_copy(source: &Path, target: &Path) -> Result<()> {\n",
    snapshot_helper + "fn link_or_copy(source: &Path, target: &Path) -> Result<()> {\n",
    "snapshot copy helper",
)

regression = r'''
    #[test]
    fn staged_runtime_inputs_are_isolated_from_source_mutation() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-staged-runtime-inputs-{}-{stamp}",
            std::process::id()
        ));
        let instance = root.join("instance");
        let source_mods = instance.join("mods");
        let source_config = instance.join("config");
        let run = root.join("run");
        let staged_mods = run.join("mods");
        fs::create_dir_all(&source_mods).unwrap();
        fs::create_dir_all(&source_config).unwrap();
        fs::create_dir_all(&staged_mods).unwrap();
        fs::write(source_mods.join("example.jar"), b"mod-v1").unwrap();
        fs::write(source_config.join("example.json"), b"config-v1").unwrap();

        let source_before =
            runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &source_mods).unwrap();
        copy_worker_mods(BridgeFamily::Fabric, &source_mods, &staged_mods).unwrap();
        copy_directory(&source_config, &run.join("config")).unwrap();
        let staged_before =
            runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &staged_mods).unwrap();
        assert_eq!(source_before, staged_before);

        // In-place writes mutate a hard-link target too. This regression proves
        // user JARs are copied into the worker snapshot instead of hard-linked.
        fs::write(source_mods.join("example.jar"), b"mod-v2-longer").unwrap();
        fs::write(source_config.join("example.json"), b"config-v2").unwrap();

        let source_after =
            runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &source_mods).unwrap();
        let staged_after =
            runtime_worker_inputs_fingerprint(BridgeFamily::Fabric, &staged_mods).unwrap();
        assert_ne!(source_before, source_after);
        assert_eq!(staged_before, staged_after);

        let _ = fs::remove_dir_all(root);
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn fast_worker_versions_are_pinned() {\n",
    regression + "    #[test]\n    fn fast_worker_versions_are_pinned() {\n",
    "staged runtime snapshot regression",
)

path.write_text(text, encoding="utf-8")
