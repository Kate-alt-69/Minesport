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
    '''fn should_stage_runtime_worker_mod(family: BridgeFamily, jar_path: &Path, filename: &str) -> bool {
    let lower = filename.to_ascii_lowercase();
    if lower.starts_with("minesport-bridge-") || lower.starts_with("minesport-capture-bridge-") {
        return false;
    }
    !should_skip_runtime_worker_mod(family, jar_path, filename)
}''',
    '''fn should_stage_runtime_worker_mod(family: BridgeFamily, jar_path: &Path, filename: &str) -> bool {
    let lower = filename.to_ascii_lowercase();
    if lower.starts_with("minesport-bridge-")
        || lower.starts_with("minesport-capture-bridge-")
        || lower.starts_with("minesport_export_worker-")
        || lower.starts_with("minesport-export-worker-")
    {
        return false;
    }
    !should_skip_runtime_worker_mod(family, jar_path, filename)
}''',
    "reserved runtime worker names",
)

marker = '''    #[test]
    fn crash_assistant_filename_fallback_survives_unreadable_jar() {'''
if text.count(marker) != 1:
    raise SystemExit("runtime worker test insertion anchor changed")
tests = r'''    #[test]
    fn user_export_worker_jar_cannot_replace_embedded_worker() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-worker-collision-{}-{stamp}",
            std::process::id()
        ));
        let source = root.join("mods");
        let target = root.join("worker-mods");
        fs::create_dir_all(&source).unwrap();
        fs::create_dir_all(&target).unwrap();

        let reserved_name = "minesport_export_worker-fabric-1.21.10.jar";
        fs::write(source.join(reserved_name), b"user-supplied-stale-worker").unwrap();
        fs::write(target.join(reserved_name), b"trusted-embedded-worker").unwrap();

        let copied = copy_worker_mods(BridgeFamily::Fabric, &source, &target).unwrap();
        assert_eq!(copied, 0);
        assert_eq!(
            fs::read(target.join(reserved_name)).unwrap(),
            b"trusted-embedded-worker"
        );

        let first = runtime_worker_mods_fingerprint(BridgeFamily::Fabric, &source).unwrap();
        fs::write(source.join(reserved_name), b"different-untrusted-worker-bytes").unwrap();
        let second = runtime_worker_mods_fingerprint(BridgeFamily::Fabric, &source).unwrap();
        assert_eq!(first, second);

        assert!(!should_stage_runtime_worker_mod(
            BridgeFamily::Fabric,
            Path::new("missing.jar"),
            "minesport-export-worker-fabric-1.21.10.jar"
        ));

        let _ = fs::remove_dir_all(root);
    }

'''
text = text.replace(marker, tests + marker, 1)
path.write_text(text, encoding="utf-8")
