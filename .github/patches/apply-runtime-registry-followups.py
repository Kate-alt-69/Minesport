from pathlib import Path
import sys


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise SystemExit(f"{label} anchor changed (matches={text.count(old)})")
    return text.replace(old, new, 1)


def apply_bug125() -> None:
    registry = Path("desktop/src/registry.rs")
    text = registry.read_text(encoding="utf-8")
    start = text.index("pub fn mods_fingerprint(mods_path: &Path) -> Result<String> {")
    end = text.index("\nfn sha256_file(path: &Path)", start)
    replacement = '''pub fn mods_fingerprint(mods_path: &Path) -> Result<String> {
    mods_fingerprint_filtered(mods_path, |_, name| !name.starts_with(CAPTURE_PREFIX))
}

pub fn mods_fingerprint_filtered<F>(mods_path: &Path, mut include: F) -> Result<String>
where
    F: FnMut(&Path, &str) -> bool,
{
    if !mods_path.is_dir() { bail!("mods folder is unavailable: {}", mods_path.display()); }
    let mut jars = Vec::new();
    for entry in fs::read_dir(mods_path).with_context(|| format!("read mods folder {}", mods_path.display()))? {
        let entry = entry?;
        let path = entry.path();
        if !entry.file_type()?.is_file() { continue; }
        if path.extension().and_then(|ext| ext.to_str()).is_none_or(|ext| !ext.eq_ignore_ascii_case("jar")) { continue; }
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if !include(&path, &name) { continue; }
        let size = entry.metadata()?.len();
        jars.push((name, path, size));
    }
    jars.sort_by(|left, right| left.0.cmp(&right.0));
    let mut total = Sha256::new();
    for (name, path, size) in jars {
        let digest = sha256_file(&path)?;
        total.update(name.as_bytes()); total.update([0]); total.update(size.to_string().as_bytes()); total.update([0]); total.update(hex_lower(&digest).as_bytes()); total.update(b"\\n");
    }
    Ok(hex_lower(&total.finalize()))
}
'''
    registry.write_text(text[:start] + replacement + text[end:], encoding="utf-8")

    stream = Path("desktop/src/registry_stream.rs")
    text = stream.read_text(encoding="utf-8")
    old = '''pub use legacy::{
    CaptureNotice, EPHEMERAL_ADDRESS, SNAPSHOT_SCHEMA, mods_fingerprint, snapshot_exists,
    snapshot_path,
};'''
    new = '''pub use legacy::{
    CaptureNotice, EPHEMERAL_ADDRESS, SNAPSHOT_SCHEMA, mods_fingerprint_filtered,
    snapshot_exists, snapshot_path,
};'''
    stream.write_text(replace_once(text, old, new, "registry stream re-export"), encoding="utf-8")

    worker = Path("desktop/src/runtime_worker.rs")
    text = worker.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "    let raw_fingerprint = registry::mods_fingerprint(mods_path)?;",
        "    let raw_fingerprint = runtime_worker_mods_fingerprint(family, mods_path)?;",
        "worker fingerprint call",
    )

    marker = "fn copy_worker_mods(family: BridgeFamily, source: &Path, target: &Path) -> Result<usize> {"
    if text.count(marker) != 1:
        raise SystemExit("copy_worker_mods anchor changed")
    helpers = '''fn should_stage_runtime_worker_mod(
    family: BridgeFamily,
    jar_path: &Path,
    filename: &str,
) -> bool {
    let lower = filename.to_ascii_lowercase();
    if lower.starts_with("minesport-bridge-")
        || lower.starts_with("minesport-capture-bridge-")
    {
        return false;
    }
    !should_skip_runtime_worker_mod(family, jar_path, filename)
}

fn runtime_worker_mods_fingerprint(family: BridgeFamily, mods_path: &Path) -> Result<String> {
    registry::mods_fingerprint_filtered(mods_path, |path, filename| {
        should_stage_runtime_worker_mod(family, path, filename)
    })
}

'''
    text = text.replace(marker, helpers + marker, 1)

    old = '''        let filename = entry.file_name().to_string_lossy().to_string();
        let lower = filename.to_ascii_lowercase();
        if lower.starts_with("minesport-bridge-") || lower.starts_with("minesport-capture-bridge-")
        {
            continue;
        }
        if should_skip_runtime_worker_mod(family, &path, &filename) {
            continue;
        }'''
    new = '''        let filename = entry.file_name().to_string_lossy().to_string();
        if !should_stage_runtime_worker_mod(family, &path, &filename) {
            continue;
        }'''
    text = replace_once(text, old, new, "worker staging filter")

    marker = '''    #[test]
    fn crash_assistant_filename_fallback_survives_unreadable_jar() {'''
    if text.count(marker) != 1:
        raise SystemExit("worker test insertion anchor changed")
    test = '''    #[test]
    fn cache_fingerprint_ignores_mods_the_worker_will_not_stage() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = env::temp_dir().join(format!(
            "minesport-worker-fingerprint-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let normal = root.join("normal.jar");
        let ignored = root.join("CrashAssistant-fabric-26.2.jar");
        fs::write(&normal, b"normal-v1").unwrap();
        fs::write(&ignored, b"ignored-v1").unwrap();

        let first = runtime_worker_mods_fingerprint(BridgeFamily::Fabric, &root).unwrap();
        fs::write(&ignored, b"ignored-v2-with-different-size").unwrap();
        let after_ignored_change =
            runtime_worker_mods_fingerprint(BridgeFamily::Fabric, &root).unwrap();
        assert_eq!(first, after_ignored_change);

        fs::write(&normal, b"normal-v2-with-different-size").unwrap();
        let after_staged_change =
            runtime_worker_mods_fingerprint(BridgeFamily::Fabric, &root).unwrap();
        assert_ne!(first, after_staged_change);
        let _ = fs::remove_dir_all(root);
    }

'''
    worker.write_text(text.replace(marker, test + marker, 1), encoding="utf-8")


def apply_bug126() -> None:
    path = Path("desktop/src/registry_stream.rs")
    text = path.read_text(encoding="utf-8")

    marker = '''pub fn capture_once<F>(
    address: &str,'''
    if text.count(marker) != 1:
        raise SystemExit("capture_once insertion anchor changed")
    helper = '''#[derive(Debug, Clone, Copy, Eq, PartialEq)]
enum PacketRead {
    Pending,
    Complete,
    Eof,
}

fn read_registry_packet<R: BufRead>(
    reader: &mut R,
    packet: &mut Vec<u8>,
    max_bytes: usize,
) -> Result<PacketRead> {
    let available = match reader.fill_buf() {
        Ok(available) => available,
        Err(error)
            if matches!(
                error.kind(),
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
            ) =>
        {
            return Ok(PacketRead::Pending);
        }
        Err(error) => return Err(error).context("read runtime registry packet"),
    };
    if available.is_empty() {
        if packet.is_empty() {
            return Ok(PacketRead::Eof);
        }
        bail!("runtime worker disconnected before completing a registry packet");
    }

    let newline = available.iter().position(|byte| *byte == b'\\n');
    let append_len = newline.unwrap_or(available.len());
    if packet.len().saturating_add(append_len) > max_bytes {
        bail!("runtime registry packet exceeded {} bytes", max_bytes);
    }
    packet.extend_from_slice(&available[..append_len]);
    let consumed = newline.map_or(available.len(), |index| index + 1);
    reader.consume(consumed);

    if newline.is_some() {
        if packet.last() == Some(&b'\\r') {
            packet.pop();
        }
        Ok(PacketRead::Complete)
    } else {
        Ok(PacketRead::Pending)
    }
}

'''
    text = text.replace(marker, helper + marker, 1)

    old_loop = '''        line.clear();
        let bytes = match reader.read_until(b'\\n', &mut line) {
            Ok(bytes) => bytes,
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                continue;
            }
            Err(error) => return Err(error).context("read runtime registry packet"),
        };
        if bytes == 0 {
            break;
        }
        if line.len() > MAX_MESSAGE_BYTES {
            bail!(
                "runtime registry packet exceeded {} bytes",
                MAX_MESSAGE_BYTES
            );
        }
        while matches!(line.last(), Some(b'\\n' | b'\\r')) {
            line.pop();
        }
        if line.is_empty() {
            continue;
        }
'''
    new_loop = '''        match read_registry_packet(&mut reader, &mut line, MAX_MESSAGE_BYTES)? {
            PacketRead::Pending => continue,
            PacketRead::Eof => break,
            PacketRead::Complete => {}
        }
        if line.is_empty() {
            continue;
        }
'''
    text = replace_once(text, old_loop, new_loop, "registry packet loop")

    parse = 'serde_json::from_slice(&line).context("parse runtime registry packet")?;'
    if text.count(parse) != 1:
        raise SystemExit(f"packet parse expression changed (matches={text.count(parse)})")
    text = text.replace(parse, parse + "\n        line.clear();", 1)

    marker = '''    #[test]
    fn streamed_header_matches_schema_four_contract() {'''
    if text.count(marker) != 1:
        raise SystemExit("registry stream test insertion anchor changed")
    tests = '''    #[test]
    fn fragmented_registry_packet_is_preserved_until_newline() {
        let source = std::io::Cursor::new(b"{\\\"type\\\":\\\"done\\\"}\\n".to_vec());
        let mut reader = BufReader::with_capacity(4, source);
        let mut packet = Vec::new();
        let mut pending = 0;
        loop {
            match read_registry_packet(&mut reader, &mut packet, 128).unwrap() {
                PacketRead::Pending => pending += 1,
                PacketRead::Complete => break,
                PacketRead::Eof => panic!("packet ended before newline"),
            }
        }
        assert!(pending > 0);
        assert_eq!(packet, b"{\\\"type\\\":\\\"done\\\"}");
    }

    #[test]
    fn oversized_registry_packet_is_rejected_before_buffer_growth() {
        let source = std::io::Cursor::new(b"123456789\\n".to_vec());
        let mut reader = BufReader::with_capacity(4, source);
        let mut packet = Vec::new();
        let error = loop {
            match read_registry_packet(&mut reader, &mut packet, 8) {
                Ok(PacketRead::Pending) => continue,
                Ok(other) => panic!("unexpected packet result: {other:?}"),
                Err(error) => break error,
            }
        };
        assert!(error.to_string().contains("exceeded 8 bytes"));
        assert!(packet.len() <= 8);
    }

'''
    path.write_text(text.replace(marker, tests + marker, 1), encoding="utf-8")


if len(sys.argv) != 2 or sys.argv[1] not in {"bug125", "bug126"}:
    raise SystemExit("usage: apply-runtime-registry-followups.py bug125|bug126")

if sys.argv[1] == "bug125":
    apply_bug125()
else:
    apply_bug126()
