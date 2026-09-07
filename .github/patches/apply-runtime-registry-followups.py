from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    matches = text.count(old)
    if matches != 1:
        raise SystemExit(f"{label} anchor changed (matches={matches})")
    return text.replace(old, new, 1)


stream = Path("desktop/src/registry_stream.rs")
text = stream.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''pub use legacy::{
    mods_fingerprint_filtered, snapshot_exists, snapshot_path, CaptureNotice, EPHEMERAL_ADDRESS,
    SNAPSHOT_SCHEMA,
};''',
    '''pub use legacy::{
    mods_fingerprint_filtered, snapshot_path, CaptureNotice, EPHEMERAL_ADDRESS, SNAPSHOT_SCHEMA,
};''',
    "registry re-export",
)
text = replace_once(
    text,
    "use serde::Deserialize;",
    "use serde::{Deserialize, Serialize};",
    "serde import",
)
text = replace_once(
    text,
    "    io::{BufRead, BufReader, BufWriter, Write},",
    "    io::{BufRead, BufReader, BufWriter, Read, Write},",
    "io import",
)
text = replace_once(
    text,
    'const REGISTRY_MAGIC: &[u8; 8] = b"MSREGD01";',
    'const REGISTRY_MAGIC: &[u8; 8] = b"MSREGD01";\nconst READY_FILE: &str = "registry.ready.json";\nconst MAX_READY_RECEIPT_BYTES: u64 = 64 * 1024;',
    "registry constants",
)

marker = "struct PendingBlock {"
if text.count(marker) != 1:
    raise SystemExit("pending block anchor changed")
receipt_struct = '''#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReadyReceipt {
    schema: i32,
    minecraft_version: String,
    fingerprint: String,
    file_size: u64,
}

'''
text = text.replace(marker, receipt_struct + marker, 1)

old_finish = '''        let _ = fs::remove_file(&self.final_path);
        fs::rename(&self.temporary_path, &self.final_path)
            .with_context(|| format!("install {}", self.final_path.display()))?;
        self.committed = true;

        if let Some(folder) = self.final_path.parent() {'''
new_finish = '''        let receipt_path = ready_receipt_path(&self.final_path);
        let _ = fs::remove_file(&receipt_path);
        let _ = fs::remove_file(&self.final_path);
        fs::rename(&self.temporary_path, &self.final_path)
            .with_context(|| format!("install {}", self.final_path.display()))?;
        write_ready_receipt(
            &self.final_path,
            &self.minecraft_version,
            &self.fingerprint,
        )?;
        self.committed = true;

        if let Some(folder) = self.final_path.parent() {'''
text = replace_once(text, old_finish, new_finish, "stream finish")

marker = "#[derive(Debug, Clone, Copy, Eq, PartialEq)]\nenum PacketRead {"
if text.count(marker) != 1:
    raise SystemExit("packet reader anchor changed")
helpers = '''fn ready_receipt_path(registry_path: &Path) -> PathBuf {
    registry_path.with_file_name(READY_FILE)
}

fn write_ready_receipt(
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
        let mut file = File::create(&temporary)
            .with_context(|| format!("create {}", temporary.display()))?;
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

fn read_ready_receipt(registry_path: &Path) -> Result<ReadyReceipt> {
    let ready_path = ready_receipt_path(registry_path);
    let metadata = fs::metadata(&ready_path)
        .with_context(|| format!("inspect {}", ready_path.display()))?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_READY_RECEIPT_BYTES {
        bail!("runtime registry ready receipt is invalid");
    }
    let encoded = fs::read(&ready_path)
        .with_context(|| format!("read {}", ready_path.display()))?;
    serde_json::from_slice(&encoded).context("parse runtime registry ready receipt")
}

fn read_registry_i32(reader: &mut impl Read) -> Result<i32> {
    let mut bytes = [0u8; 4];
    reader
        .read_exact(&mut bytes)
        .context("read runtime registry int32")?;
    Ok(i32::from_be_bytes(bytes))
}

fn read_registry_string(reader: &mut impl Read) -> Result<String> {
    let length = read_registry_i32(reader)?;
    if length < 0 || length as usize > MAX_STRING_BYTES {
        bail!("runtime registry header string length is invalid");
    }
    let mut bytes = vec![0u8; length as usize];
    reader
        .read_exact(&mut bytes)
        .context("read runtime registry header string")?;
    String::from_utf8(bytes).context("runtime registry header string is not UTF-8")
}

fn registry_header_matches(
    registry_path: &Path,
    expected_version: &str,
    expected_fingerprint: &str,
) -> Result<()> {
    let mut reader = BufReader::new(
        File::open(registry_path)
            .with_context(|| format!("open {}", registry_path.display()))?,
    );
    let mut magic = [0u8; 8];
    reader
        .read_exact(&mut magic)
        .context("read runtime registry magic")?;
    if &magic != REGISTRY_MAGIC {
        bail!("runtime registry magic is invalid");
    }
    if read_registry_i32(&mut reader)? != SNAPSHOT_SCHEMA {
        bail!("runtime registry schema is invalid");
    }
    let minecraft_version = read_registry_string(&mut reader)?;
    let _loader_version = read_registry_string(&mut reader)?;
    let fingerprint = read_registry_string(&mut reader)?;
    if minecraft_version != expected_version || fingerprint != expected_fingerprint {
        bail!("runtime registry header identity does not match requested cache");
    }
    Ok(())
}

pub fn snapshot_path_is_ready(
    registry_path: &Path,
    expected_version: &str,
    expected_fingerprint: &str,
) -> bool {
    let expected_version = expected_version.trim();
    let expected_fingerprint = expected_fingerprint.trim();
    if expected_version.is_empty() || expected_fingerprint.is_empty() {
        return false;
    }
    let Ok(metadata) = fs::metadata(registry_path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    let Ok(receipt) = read_ready_receipt(registry_path) else {
        return false;
    };
    if receipt.schema != SNAPSHOT_SCHEMA
        || receipt.minecraft_version != expected_version
        || receipt.fingerprint != expected_fingerprint
        || receipt.file_size != metadata.len()
    {
        return false;
    }
    registry_header_matches(registry_path, expected_version, expected_fingerprint).is_ok()
}

pub fn snapshot_exists(cache_root: &Path, version: &str, fingerprint: &str) -> bool {
    let registry_path = snapshot_path(cache_root, version, fingerprint);
    snapshot_path_is_ready(&registry_path, version, fingerprint)
}

#[cfg(test)]
pub(crate) fn write_empty_snapshot_for_test(
    cache_root: &Path,
    version: &str,
    fingerprint: &str,
) -> Result<PathBuf> {
    StreamWriter::begin(cache_root, version, "test-loader", &[], fingerprint, 0)?.finish()
}

'''
text = text.replace(marker, helpers + marker, 1)

marker = '''    #[test]
    fn streamed_header_matches_schema_four_contract() {'''
if text.count(marker) != 1:
    raise SystemExit("registry readiness test anchor changed")
test = '''    #[test]
    fn ready_receipt_rejects_truncated_registry() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let cache = std::env::temp_dir().join(format!(
            "minesport-registry-ready-{}-{stamp}",
            std::process::id()
        ));
        let path = write_empty_snapshot_for_test(&cache, "1.21.10", "ready-test").unwrap();
        assert!(snapshot_exists(&cache, "1.21.10", "ready-test"));
        assert!(snapshot_path_is_ready(&path, "1.21.10", "ready-test"));

        let original_len = fs::metadata(&path).unwrap().len();
        assert!(original_len > 1);
        std::fs::OpenOptions::new()
            .write(true)
            .open(&path)
            .unwrap()
            .set_len(original_len - 1)
            .unwrap();
        assert!(!snapshot_exists(&cache, "1.21.10", "ready-test"));
        assert!(!snapshot_path_is_ready(&path, "1.21.10", "ready-test"));
        let _ = fs::remove_dir_all(cache);
    }

'''
text = text.replace(marker, test + marker, 1)
stream.write_text(text, encoding="utf-8")

cache = Path("desktop/src/runtime_cache.rs")
text = cache.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''use crate::{
    diagnostics,
    runtime_worker::{self, CacheResult, Progress},
};''',
    '''use crate::{
    diagnostics, registry,
    runtime_worker::{self, CacheResult, Progress},
};''',
    "runtime cache import",
)
text = replace_once(
    text,
    "            || !state.ready_path.is_file()",
    '''            || !registry::snapshot_path_is_ready(
                &state.ready_path,
                version,
                &state.fingerprint,
            )''',
    "runtime cache ready check",
)
text = replace_once(
    text,
    '''        let registry = root.join("registry.data");
        std::fs::write(&registry, b"ready").unwrap();''',
    '''        let registry = crate::registry::write_empty_snapshot_for_test(
            &root,
            "1.21.10",
            "exact-fingerprint",
        )
        .unwrap();''',
    "runtime cache test fixture",
)
text = replace_once(text, "            Some(registry)\n", "            Some(registry.clone())\n", "ready path clone")
marker = '''        assert!(manager.ready_path("1.21.10", Path::new("mods")).is_none());
        let _ = std::fs::remove_dir_all(root);'''
if text.count(marker) != 1:
    raise SystemExit("runtime cache truncation test anchor changed")
replacement = '''        assert!(manager.ready_path("1.21.10", Path::new("mods")).is_none());

        let original_len = std::fs::metadata(&registry).unwrap().len();
        std::fs::OpenOptions::new()
            .write(true)
            .open(&registry)
            .unwrap()
            .set_len(original_len - 1)
            .unwrap();
        assert!(
            manager
                .ready_path_for_loader("1.21.10", "Forge", Path::new("mods"))
                .is_none()
        );
        let _ = std::fs::remove_dir_all(root);'''
text = text.replace(marker, replacement, 1)
cache.write_text(text, encoding="utf-8")
