from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/runtime.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''    env, fs,
    path::{Component, Path, PathBuf},''',
    '''    env, fs,
    io::Write,
    path::{Component, Path, PathBuf},''',
    "runtime asset durable write import",
)

old = '''    if write {
        let temporary = root.join(temporary_name);
        fs::write(&temporary, bytes).with_context(|| format!("write {}", temporary.display()))?;
        let _ = fs::remove_file(&destination);
        fs::rename(&temporary, &destination)
            .with_context(|| format!("install {}", destination.display()))?;
    }
    Ok(destination)
}
'''
new = '''    if write {
        let temporary = root.join(temporary_name);
        let backup = root.join(format!("{temporary_name}.backup"));
        let mut file = fs::File::create(&temporary)
            .with_context(|| format!("create {}", temporary.display()))?;
        file.write_all(bytes)
            .with_context(|| format!("write {}", temporary.display()))?;
        file.sync_all()
            .with_context(|| format!("sync {}", temporary.display()))?;
        drop(file);
        install_runtime_asset(&temporary, &destination, &backup)
            .with_context(|| format!("install {}", destination.display()))?;
    }
    Ok(destination)
}

fn install_runtime_asset(temporary: &Path, destination: &Path, backup: &Path) -> Result<()> {
    let had_destination = destination.exists();
    if had_destination {
        let _ = fs::remove_file(backup);
        fs::rename(destination, backup).with_context(|| {
            format!(
                "backup existing runtime asset {} to {}",
                destination.display(),
                backup.display()
            )
        })?;
    }

    match fs::rename(temporary, destination) {
        Ok(()) => {
            // A stale backup from a prior interrupted install or the backup made
            // above is no longer authoritative once the new asset is installed.
            let _ = fs::remove_file(backup);
            Ok(())
        }
        Err(install_error) => {
            let mut failure = anyhow!(install_error).context(format!(
                "move new runtime asset {} to {}",
                temporary.display(),
                destination.display()
            ));
            if !destination.exists() && backup.exists() {
                if let Err(rollback_error) = fs::rename(backup, destination) {
                    failure = failure.context(format!(
                        "runtime asset rollback also failed: {rollback_error}"
                    ));
                }
            }
            Err(failure)
        }
    }
}
'''
text = replace_once(text, old, new, "transactional runtime asset publication")

regression = r'''
    #[test]
    fn failed_runtime_asset_install_restores_last_good_file() {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-runtime-asset-transaction-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let destination = root.join("worker.jar");
        let missing_temporary = root.join("missing.tmp");
        let backup = root.join("worker.backup");
        fs::write(&destination, b"last-good-worker").unwrap();

        assert!(install_runtime_asset(&missing_temporary, &destination, &backup).is_err());
        assert_eq!(fs::read(&destination).unwrap(), b"last-good-worker");
        assert!(!backup.exists());

        let _ = fs::remove_dir_all(root);
    }

'''
text = replace_once(
    text,
    '''    #[test]
    fn embedded_runtime_assets_are_present() {''',
    regression + '''    #[test]
    fn embedded_runtime_assets_are_present() {''',
    "runtime asset rollback regression",
)

path.write_text(text, encoding="utf-8")
