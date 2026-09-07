from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/engine_update.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''    let temporary = destination.with_extension("part");
    let _ = fs::remove_file(&temporary);
    let _ = fs::remove_file(destination);

    let response = agent''',
    '''    let temporary = destination.with_extension("part");
    let _ = fs::remove_file(&temporary);

    // Keep any previously verified installer until the replacement download is
    // completely received, synced and hash-verified. A transient network or
    // rename failure must not destroy the last-good staged package.
    let response = agent''',
    "preserve previous installer while downloading",
)

text = replace_once(
    text,
    '''    fs::rename(&temporary, destination).with_context(|| {
        format!(
            "publish verified engine installer {} -> {}",
            temporary.display(),
            destination.display()
        )
    })?;
    Ok(())
}
''',
    '''    replace_file_with_rollback(&temporary, destination).with_context(|| {
        format!(
            "publish verified engine installer {} -> {}",
            temporary.display(),
            destination.display()
        )
    })?;
    Ok(())
}
''',
    "transactional installer publication",
)

text = replace_once(
    text,
    '''    let temporary = path.with_extension("tmp");
    let bytes = serde_json::to_vec_pretty(stage).context("encode staged engine update metadata")?;
    fs::write(&temporary, bytes)
        .with_context(|| format!("write staged engine update {}", temporary.display()))?;
    let _ = fs::remove_file(&path);
    fs::rename(&temporary, &path)
        .with_context(|| format!("publish staged engine update {}", path.display()))?;
    Ok(())
}
''',
    '''    let temporary = path.with_extension("tmp");
    let bytes = serde_json::to_vec_pretty(stage).context("encode staged engine update metadata")?;
    write_durable_replacement(&temporary, &path, &bytes)
        .with_context(|| format!("publish staged engine update {}", path.display()))?;
    Ok(())
}
''',
    "transactional staged metadata publication",
)

text = replace_once(
    text,
    '''    let temporary = path.with_extension("tmp");
    let bytes = serde_json::to_vec(&UpdateState {
        checked_at_unix: unix_now(),
    })?;
    fs::write(&temporary, bytes)
        .with_context(|| format!("write engine update state {}", temporary.display()))?;
    let _ = fs::remove_file(&path);
    fs::rename(&temporary, &path)
        .with_context(|| format!("publish engine update state {}", path.display()))?;
    Ok(())
}

fn update_state_path() -> PathBuf {''',
    '''    let temporary = path.with_extension("tmp");
    let bytes = serde_json::to_vec(&UpdateState {
        checked_at_unix: unix_now(),
    })?;
    write_durable_replacement(&temporary, &path, &bytes)
        .with_context(|| format!("publish engine update state {}", path.display()))?;
    Ok(())
}

fn write_durable_replacement(temporary: &Path, destination: &Path, bytes: &[u8]) -> Result<()> {
    let _ = fs::remove_file(temporary);
    let mut file = fs::File::create(temporary)
        .with_context(|| format!("create durable update state {}", temporary.display()))?;
    file.write_all(bytes)
        .with_context(|| format!("write durable update state {}", temporary.display()))?;
    file.flush()
        .with_context(|| format!("flush durable update state {}", temporary.display()))?;
    file.sync_all()
        .with_context(|| format!("sync durable update state {}", temporary.display()))?;
    drop(file);
    replace_file_with_rollback(temporary, destination)
}

fn replace_file_with_rollback(temporary: &Path, destination: &Path) -> Result<()> {
    let file_name = destination
        .file_name()
        .ok_or_else(|| anyhow!("replacement destination has no file name: {}", destination.display()))?;
    let mut backup_name = file_name.to_os_string();
    backup_name.push(".previous");
    let backup = destination.with_file_name(backup_name);
    let had_destination = destination.exists();

    if had_destination {
        let _ = fs::remove_file(&backup);
        fs::rename(destination, &backup).with_context(|| {
            format!(
                "backup previous update file {} to {}",
                destination.display(),
                backup.display()
            )
        })?;
    }

    match fs::rename(temporary, destination) {
        Ok(()) => {
            let _ = fs::remove_file(&backup);
            Ok(())
        }
        Err(install_error) => {
            let mut failure = anyhow!(install_error).context(format!(
                "move replacement {} to {}",
                temporary.display(),
                destination.display()
            ));
            if !destination.exists() && backup.exists() {
                if let Err(rollback_error) = fs::rename(&backup, destination) {
                    failure = failure.context(format!(
                        "update state rollback also failed: {rollback_error}"
                    ));
                }
            }
            Err(failure)
        }
    }
}

fn update_state_path() -> PathBuf {''',
    "durable update state replacement helpers",
)

regression = r'''
    #[test]
    fn failed_update_file_replacement_restores_last_good_state() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-update-state-transaction-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let destination = root.join("staged-engine-update.json");
        let missing_temporary = root.join("missing.tmp");
        fs::write(&destination, b"last-good-state").unwrap();

        assert!(replace_file_with_rollback(&missing_temporary, &destination).is_err());
        assert_eq!(fs::read(&destination).unwrap(), b"last-good-state");
        assert!(!root.join("staged-engine-update.json.previous").exists());

        let replacement = root.join("replacement.tmp");
        fs::write(&replacement, b"new-state").unwrap();
        replace_file_with_rollback(&replacement, &destination).unwrap();
        assert_eq!(fs::read(&destination).unwrap(), b"new-state");
        assert!(!root.join("staged-engine-update.json.previous").exists());

        let _ = fs::remove_dir_all(root);
    }

'''
text = replace_once(
    text,
    '''    #[test]
    fn semantic_engine_versions_compare_numerically() {''',
    regression + '''    #[test]
    fn semantic_engine_versions_compare_numerically() {''',
    "engine update rollback regression",
)

path.write_text(text, encoding="utf-8")
