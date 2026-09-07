from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/registry_stream.rs")
text = path.read_text(encoding="utf-8")

old_finish = r'''        let receipt_path = ready_receipt_path(&self.final_path);
        let _ = fs::remove_file(&receipt_path);
        let _ = fs::remove_file(&self.final_path);
        fs::rename(&self.temporary_path, &self.final_path)
            .with_context(|| format!("install {}", self.final_path.display()))?;
        write_ready_receipt(&self.final_path, &self.minecraft_version, &self.fingerprint)?;
        self.committed = true;
'''
new_finish = r'''        // Prepare the matching readiness receipt before touching the last-good
        // snapshot. The registry file and its receipt are then replaced as one
        // rollback-capable transaction, which is especially important on Windows
        // where rename-over-existing is not portable.
        let staged_receipt = stage_ready_receipt(
            &self.temporary_path,
            &self.minecraft_version,
            &self.fingerprint,
        )?;
        install_registry_snapshot(
            &self.temporary_path,
            &self.final_path,
            &staged_receipt,
        )?;
        self.committed = true;
'''
text = replace_once(text, old_finish, new_finish, "transactional registry finish")

old_receipt = r'''fn write_ready_receipt(
    registry_path: &Path,
    minecraft_version: &str,
    fingerprint: &str,
) -> Result<()> {
    let file_size = fs::metadata(registry_path)
        .with_context(|| format!("inspect {}", registry_path.display()))?
        .len();
    let receipt = ReadyReceipt {
        schema: SNAPSHOT_SCHEMA,
        minecraft_version: minecraft_version.to_string(),
        fingerprint: fingerprint.to_string(),
        file_size,
    };
    let encoded = serde_json::to_vec(&receipt).context("encode runtime registry ready receipt")?;
    if encoded.len() as u64 > MAX_READY_RECEIPT_BYTES {
        bail!("runtime registry ready receipt is unexpectedly large");
    }

    let ready_path = ready_receipt_path(registry_path);
    let folder = ready_path
        .parent()
        .context("runtime registry ready receipt has no parent")?;
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let temporary = folder.join(format!(
        ".registry-ready-{}-{nonce}.tmp",
        std::process::id()
    ));
    {
        let mut file =
            File::create(&temporary).with_context(|| format!("create {}", temporary.display()))?;
        file.write_all(&encoded)
            .context("write runtime registry ready receipt")?;
        file.sync_all()
            .context("sync runtime registry ready receipt")?;
    }
    let _ = fs::remove_file(&ready_path);
    if let Err(error) = fs::rename(&temporary, &ready_path) {
        let _ = fs::remove_file(&temporary);
        bail!(
            "install runtime registry ready receipt {}: {error}",
            ready_path.display()
        );
    }
    Ok(())
}
'''
new_receipt = r'''fn stage_ready_receipt(
    registry_path: &Path,
    minecraft_version: &str,
    fingerprint: &str,
) -> Result<PathBuf> {
    let file_size = fs::metadata(registry_path)
        .with_context(|| format!("inspect {}", registry_path.display()))?
        .len();
    let receipt = ReadyReceipt {
        schema: SNAPSHOT_SCHEMA,
        minecraft_version: minecraft_version.to_string(),
        fingerprint: fingerprint.to_string(),
        file_size,
    };
    let encoded = serde_json::to_vec(&receipt).context("encode runtime registry ready receipt")?;
    if encoded.len() as u64 > MAX_READY_RECEIPT_BYTES {
        bail!("runtime registry ready receipt is unexpectedly large");
    }

    let folder = registry_path
        .parent()
        .context("runtime registry ready receipt has no parent")?;
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let temporary = folder.join(format!(
        ".registry-ready-{}-{nonce}.tmp",
        std::process::id()
    ));
    {
        let mut file =
            File::create(&temporary).with_context(|| format!("create {}", temporary.display()))?;
        file.write_all(&encoded)
            .context("write runtime registry ready receipt")?;
        file.sync_all()
            .context("sync runtime registry ready receipt")?;
    }
    Ok(temporary)
}

fn install_registry_snapshot(
    staged_registry: &Path,
    final_path: &Path,
    staged_receipt: &Path,
) -> Result<()> {
    let ready_path = ready_receipt_path(final_path);
    let folder = final_path
        .parent()
        .context("runtime registry path has no parent")?;
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let registry_backup = folder.join(format!(
        ".registry-backup-{}-{nonce}.tmp",
        std::process::id()
    ));
    let receipt_backup = folder.join(format!(
        ".registry-ready-backup-{}-{nonce}.tmp",
        std::process::id()
    ));

    for existing in [final_path, ready_path.as_path()] {
        match fs::symlink_metadata(existing) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                let _ = fs::remove_file(staged_receipt);
                bail!(
                    "refusing to replace non-regular runtime registry path {}",
                    existing.display()
                );
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                let _ = fs::remove_file(staged_receipt);
                return Err(error).with_context(|| format!("inspect {}", existing.display()));
            }
        }
    }

    let had_registry = final_path.is_file();
    let had_receipt = ready_path.is_file();
    if had_registry {
        fs::rename(final_path, &registry_backup)
            .with_context(|| format!("backup {}", final_path.display()))?;
    }
    if had_receipt {
        if let Err(error) = fs::rename(&ready_path, &receipt_backup) {
            if had_registry {
                let _ = fs::rename(&registry_backup, final_path);
            }
            let _ = fs::remove_file(staged_receipt);
            return Err(error).with_context(|| format!("backup {}", ready_path.display()));
        }
    }

    let install_result = (|| -> Result<()> {
        fs::rename(staged_registry, final_path)
            .with_context(|| format!("install {}", final_path.display()))?;
        fs::rename(staged_receipt, &ready_path)
            .with_context(|| format!("install {}", ready_path.display()))?;
        Ok(())
    })();

    if let Err(error) = install_result {
        let _ = fs::remove_file(final_path);
        let _ = fs::remove_file(&ready_path);
        let _ = fs::remove_file(staged_receipt);

        let mut rollback_errors = Vec::new();
        if had_registry && registry_backup.exists() {
            if let Err(restore) = fs::rename(&registry_backup, final_path) {
                rollback_errors.push(format!(
                    "restore {}: {restore}",
                    final_path.display()
                ));
            }
        }
        if had_receipt && receipt_backup.exists() {
            if let Err(restore) = fs::rename(&receipt_backup, &ready_path) {
                rollback_errors.push(format!(
                    "restore {}: {restore}",
                    ready_path.display()
                ));
            }
        }
        if rollback_errors.is_empty() {
            return Err(error);
        }
        bail!(
            "{error:#}; runtime registry rollback also failed: {}",
            rollback_errors.join("; ")
        );
    }

    let _ = fs::remove_file(&registry_backup);
    let _ = fs::remove_file(&receipt_backup);
    Ok(())
}
'''
text = replace_once(text, old_receipt, new_receipt, "stage and install ready receipt transaction")

regression = r'''
    #[test]
    fn failed_snapshot_replacement_restores_last_good_registry_and_receipt() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let cache = std::env::temp_dir().join(format!(
            "minesport-registry-transaction-{}-{stamp}",
            std::process::id()
        ));
        let final_path = write_empty_snapshot_for_test(
            &cache,
            "1.21.10",
            "transaction-test",
        )
        .unwrap();
        let ready_path = ready_receipt_path(&final_path);
        let old_registry = fs::read(&final_path).unwrap();
        let old_receipt = fs::read(&ready_path).unwrap();

        let staged_registry = final_path
            .parent()
            .unwrap()
            .join("new-registry.tmp");
        fs::write(&staged_registry, b"replacement-registry").unwrap();
        let missing_receipt = final_path
            .parent()
            .unwrap()
            .join("missing-ready.tmp");

        let error = install_registry_snapshot(
            &staged_registry,
            &final_path,
            &missing_receipt,
        )
        .unwrap_err();
        assert!(error.to_string().contains("install"));
        assert_eq!(fs::read(&final_path).unwrap(), old_registry);
        assert_eq!(fs::read(&ready_path).unwrap(), old_receipt);
        assert!(snapshot_exists(&cache, "1.21.10", "transaction-test"));
        let _ = fs::remove_dir_all(cache);
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn ready_receipt_rejects_truncated_registry() {\n",
    regression + "    #[test]\n    fn ready_receipt_rejects_truncated_registry() {\n",
    "registry replacement rollback regression",
)

path.write_text(text, encoding="utf-8")
