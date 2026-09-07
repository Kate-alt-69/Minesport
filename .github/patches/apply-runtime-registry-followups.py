from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    matches = text.count(old)
    if matches != 1:
        raise SystemExit(f"{label} anchor changed (matches={matches})")
    return text.replace(old, new, 1)


path = Path("desktop/src/registry_stream.rs")
text = path.read_text(encoding="utf-8")

old = '''        prune_sibling_fingerprints(&self.cache_root, &self.minecraft_version, &self.fingerprint)?;
        Ok(self.final_path.clone())'''
new = '''        if let Err(error) =
            prune_sibling_fingerprints(&self.cache_root, &self.minecraft_version, &self.fingerprint)
        {
            crate::diagnostics::Logger::new("RUNTIME")
                .child("REGISTRY")
                .warn(
                    "RuntimeRegistryStalePruneFailed",
                    "runtime registry was committed but stale cache pruning failed",
                    &[
                        ("registry_path", self.final_path.display().to_string()),
                        ("error", format!("{error:#}")),
                    ],
                );
        }
        Ok(self.final_path.clone())'''
text = replace_once(text, old, new, "committed registry prune")

marker = '''    #[test]
    fn ready_receipt_rejects_truncated_registry() {'''
if text.count(marker) != 1:
    raise SystemExit("registry prune test insertion anchor changed")

test = '''    #[cfg(unix)]
    #[test]
    fn committed_registry_survives_stale_prune_permission_failure() {
        use std::os::unix::fs::PermissionsExt;

        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let cache = std::env::temp_dir().join(format!(
            "minesport-registry-prune-{}-{stamp}",
            std::process::id()
        ));
        let writer =
            StreamWriter::begin(&cache, "1.21.10", "test-loader", &[], "prune-test", 0)
                .unwrap();
        let keep_path = snapshot_path(&cache, "1.21.10", "prune-test");
        let version_root = keep_path
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .to_path_buf();
        for index in 0..4 {
            fs::create_dir_all(version_root.join(format!("stale-{index}"))).unwrap();
        }

        fs::set_permissions(&version_root, fs::Permissions::from_mode(0o500)).unwrap();
        let result = writer.finish();
        fs::set_permissions(&version_root, fs::Permissions::from_mode(0o700)).unwrap();

        assert!(result.is_ok(), "committed registry was rejected: {result:?}");
        assert!(snapshot_exists(&cache, "1.21.10", "prune-test"));
        let _ = fs::remove_dir_all(cache);
    }

'''
text = text.replace(marker, test + marker, 1)
path.write_text(text, encoding="utf-8")
