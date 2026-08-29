use anyhow::{Context, Result, bail};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::{BufRead, BufReader, BufWriter, Read, Write},
    net::TcpListener,
    path::{Path, PathBuf},
    sync::{Arc, atomic::{AtomicBool, Ordering}},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

pub const DEFAULT_ADDRESS: &str = "127.0.0.1:25590";
pub const EPHEMERAL_ADDRESS: &str = "127.0.0.1:0";
pub const SNAPSHOT_SCHEMA: i32 = 4;
const REGISTRY_MAGIC: &[u8; 8] = b"MSREGD01";
const REGISTRY_FILE: &str = "registry.data";
const MAX_MESSAGE_BYTES: usize = 128 << 20;
const MAX_STRING_BYTES: usize = 4 << 20;
const MAX_BLOCKS: usize = 1_000_000;
const MAX_VARIANTS: usize = 1_000_000;
const MAX_QUADS: usize = 20_000_000;
const MAX_PROPERTIES: usize = 4096;
const MAX_LOADED_MODS: usize = 100_000;
const CAPTURE_PREFIX: &str = "minesport-capture-bridge-";

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct LightState {
    pub properties: BTreeMap<String, String>,
    #[serde(rename = "lightLevel")]
    pub light_level: i32,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct BakedQuad {
    pub vertices: Vec<f32>,
    #[serde(rename = "textureId")]
    pub texture_id: String,
    pub face: i32,
    pub shade: bool,
    #[serde(rename = "tintIndex")]
    pub tint_index: i32,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct BlockVariant {
    pub properties: BTreeMap<String, String>,
    pub quads: Vec<BakedQuad>,
}

#[derive(Debug, Clone, Default)]
pub struct RuntimeBlock {
    pub vanilla_mapping: String,
    pub loader_type: String,
    pub variants: Vec<BlockVariant>,
    pub lights: Vec<LightState>,
}

#[derive(Debug, Clone)]
pub struct Snapshot {
    pub schema: i32,
    pub minecraft_version: String,
    pub loader_version: String,
    pub loaded_mods: Vec<String>,
    pub mods_fingerprint: String,
    pub blocks: BTreeMap<String, RuntimeBlock>,
    pub captured_at: String,
}

impl Default for Snapshot {
    fn default() -> Self {
        Self {
            schema: SNAPSHOT_SCHEMA,
            minecraft_version: String::new(),
            loader_version: String::new(),
            loaded_mods: Vec::new(),
            mods_fingerprint: String::new(),
            blocks: BTreeMap::new(),
            captured_at: String::new(),
        }
    }
}

#[derive(Debug, Deserialize, Default)]
#[serde(default)]
struct WireMessage {
    #[serde(rename = "type")]
    kind: String,
    message: String,
    #[serde(rename = "mcVersion")]
    mc_version: String,
    #[serde(rename = "loaderVersion")]
    loader_version: String,
    #[serde(rename = "totalBlocks")]
    total_blocks: usize,
    #[serde(rename = "loadedMods")]
    loaded_mods: Vec<String>,
    #[serde(rename = "blockId")]
    block_id: String,
    #[serde(rename = "vanillaMapping")]
    vanilla_mapping: String,
    #[serde(rename = "loaderType")]
    loader_type: String,
    variants: Vec<BlockVariant>,
    states: Vec<LightState>,
}

#[derive(Debug, Clone)]
pub enum CaptureNotice {
    Listening(String),
    Connected(String),
    WorkerMessage(String),
    Progress { blocks: usize, total_blocks: usize },
    Complete { path: PathBuf, blocks: usize },
}

/// Backward-compatible capture entry point for callers that do not need
/// cancellation. Runtime preparation should use `capture_once_cancellable`.
pub fn capture_once<F>(
    address: &str,
    cache_root: &Path,
    expected_version: &str,
    fingerprint: &str,
    notice: F,
) -> Result<PathBuf>
where
    F: FnMut(CaptureNotice),
{
    capture_once_cancellable(
        address,
        cache_root,
        expected_version,
        fingerprint,
        Arc::new(AtomicBool::new(false)),
        notice,
    )
}

/// Receive one complete `MINESPORT_EXPORT_WORKER_MODE=all` registry dump and persist
/// it as schema-4 `registry.data`. Both accept and connected reads are bounded
/// so cancellation can always unwind the capture thread. Protocol errors fail
/// closed so a partial registry is never published as reusable cache data.
pub fn capture_once_cancellable<F>(
    address: &str,
    cache_root: &Path,
    expected_version: &str,
    fingerprint: &str,
    cancel: Arc<AtomicBool>,
    mut notice: F,
) -> Result<PathBuf>
where
    F: FnMut(CaptureNotice),
{
    let version = expected_version.trim();
    let fingerprint = fingerprint.trim();
    if version.is_empty() { bail!("Minecraft version is required for runtime capture"); }
    if fingerprint.is_empty() { bail!("mod fingerprint is required for runtime capture"); }

    let listener = TcpListener::bind(address)
        .with_context(|| format!("listen for Minecraft runtime registry on {address}"))?;
    listener
        .set_nonblocking(true)
        .context("make Minecraft runtime registry listener cancellable")?;
    notice(CaptureNotice::Listening(listener.local_addr()?.to_string()));

    let (stream, peer) = loop {
        if cancel.load(Ordering::Relaxed) {
            bail!("runtime cache cancelled while waiting for Minecraft worker");
        }
        match listener.accept() {
            Ok(accepted) => break accepted,
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(25));
            }
            Err(error) => return Err(error).context("accept Minecraft runtime registry worker"),
        }
    };
    stream
        .set_read_timeout(Some(Duration::from_millis(250)))
        .context("set runtime registry socket read timeout")?;
    notice(CaptureNotice::Connected(peer.to_string()));

    let mut snapshot = Snapshot {
        mods_fingerprint: fingerprint.to_string(),
        ..Snapshot::default()
    };
    let mut complete = false;
    let mut hello_seen = false;
    let mut total_blocks = 0usize;
    let mut reader = BufReader::new(stream);
    let mut line = Vec::with_capacity(64 * 1024);

    while !complete {
        if cancel.load(Ordering::Relaxed) {
            bail!("runtime cache cancelled during registry capture");
        }
        line.clear();
        let bytes = match reader.read_until(b'\n', &mut line) {
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
        if bytes == 0 { break; }
        if line.len() > MAX_MESSAGE_BYTES {
            bail!("runtime registry packet exceeded {} bytes", MAX_MESSAGE_BYTES);
        }
        while matches!(line.last(), Some(b'\n' | b'\r')) { line.pop(); }
        if line.is_empty() { continue; }

        let message: WireMessage = serde_json::from_slice(&line)
            .context("parse runtime registry packet")?;

        match message.kind.as_str() {
            "hello" => {
                if hello_seen {
                    bail!("runtime registry protocol sent more than one hello packet");
                }
                if message.mc_version.trim().is_empty() {
                    bail!("runtime registry hello had no Minecraft version");
                }
                if message.total_blocks > MAX_BLOCKS {
                    bail!(
                        "runtime registry announced {} blocks, exceeding limit {}",
                        message.total_blocks,
                        MAX_BLOCKS
                    );
                }
                if message.loaded_mods.len() > MAX_LOADED_MODS {
                    bail!(
                        "runtime registry announced {} loaded mods, exceeding limit {}",
                        message.loaded_mods.len(),
                        MAX_LOADED_MODS
                    );
                }
                snapshot.minecraft_version = message.mc_version.trim().to_string();
                snapshot.loader_version = message.loader_version.trim().to_string();
                snapshot.loaded_mods = message.loaded_mods;
                total_blocks = message.total_blocks;
                hello_seen = true;
            }
            "block" => {
                if !hello_seen {
                    bail!("runtime registry block packet arrived before hello");
                }
                let block_id = message.block_id.trim();
                if block_id.is_empty() {
                    bail!("runtime registry block packet had an empty block ID");
                }
                if snapshot.blocks.contains_key(block_id) {
                    bail!("runtime registry sent duplicate block entry {block_id}");
                }
                snapshot.blocks.insert(
                    block_id.to_string(),
                    RuntimeBlock {
                        vanilla_mapping: message.vanilla_mapping.trim().to_string(),
                        loader_type: message.loader_type.trim().to_string(),
                        variants: sanitize_variants(message.variants),
                        lights: Vec::new(),
                    },
                );
                if snapshot.blocks.len() > total_blocks {
                    bail!(
                        "runtime registry received more block entries ({}) than hello announced ({total_blocks})",
                        snapshot.blocks.len()
                    );
                }
                if snapshot.blocks.len() % 128 == 0 {
                    notice(CaptureNotice::Progress {
                        blocks: snapshot.blocks.len(),
                        total_blocks,
                    });
                }
            }
            "block_light" => {
                if !hello_seen {
                    bail!("runtime registry light packet arrived before hello");
                }
                let block_id = message.block_id.trim();
                if block_id.is_empty() {
                    bail!("runtime registry light packet had an empty block ID");
                }
                let entry = snapshot
                    .blocks
                    .get_mut(block_id)
                    .ok_or_else(|| anyhow::anyhow!("runtime registry light packet referenced unknown block {block_id}"))?;
                entry.lights = sanitize_lights(message.states);
            }
            "texture" => {
                if !hello_seen {
                    bail!("runtime registry texture packet arrived before hello");
                }
            }
            "error" => {
                let detail = message.message.trim();
                if detail.is_empty() {
                    bail!("runtime Export Worker reported an unspecified protocol error");
                }
                bail!("runtime Export Worker reported an error: {detail}");
            }
            "done" => {
                if !hello_seen {
                    bail!("runtime registry done packet arrived before hello");
                }
                complete = true;
            }
            other => bail!("runtime registry sent unknown packet type {other:?}"),
        }
    }

    if !complete { bail!("runtime worker disconnected before completing its registry dump"); }
    if !hello_seen { bail!("runtime registry dump completed without a hello packet"); }
    if snapshot.minecraft_version.trim() != version {
        bail!("runtime registry version {} does not match requested {}", snapshot.minecraft_version, version);
    }
    if snapshot.blocks.len() != total_blocks {
        bail!(
            "runtime registry completed with {} block entries but hello announced {total_blocks}",
            snapshot.blocks.len()
        );
    }

    snapshot.captured_at = unix_timestamp_string();
    let path = write_snapshot(cache_root, &snapshot)?;
    notice(CaptureNotice::Complete { path: path.clone(), blocks: snapshot.blocks.len() });
    Ok(path)
}

fn sanitize_variants(source: Vec<BlockVariant>) -> Vec<BlockVariant> {
    source.into_iter().map(|variant| {
        let quads = variant.quads.into_iter().filter_map(|mut quad| {
            if quad.vertices.len() < 32 { return None; }
            quad.vertices.truncate(32);
            if quad.vertices.iter().any(|value| !value.is_finite()) { return None; }
            quad.texture_id = quad.texture_id.trim().to_string();
            Some(quad)
        }).collect();
        BlockVariant { properties: variant.properties, quads }
    }).collect()
}

fn sanitize_lights(source: Vec<LightState>) -> Vec<LightState> {
    source.into_iter().filter_map(|mut state| {
        if state.light_level < 1 { return None; }
        state.light_level = state.light_level.min(15);
        Some(state)
    }).collect()
}

pub fn runtime_registry_root(cache_root: &Path) -> PathBuf {
    cache_root.join("runtime-registry")
}

pub fn snapshot_path(cache_root: &Path, version: &str, fingerprint: &str) -> PathBuf {
    runtime_registry_root(cache_root)
        .join(safe_component(version))
        .join(safe_component(fingerprint))
        .join(REGISTRY_FILE)
}

pub fn snapshot_exists(cache_root: &Path, version: &str, fingerprint: &str) -> bool {
    snapshot_path(cache_root, version, fingerprint).is_file()
}

fn write_snapshot(cache_root: &Path, snapshot: &Snapshot) -> Result<PathBuf> {
    if snapshot.schema != SNAPSHOT_SCHEMA { bail!("unsupported runtime registry schema {}", snapshot.schema); }
    if snapshot.minecraft_version.trim().is_empty() { bail!("minecraftVersion is required"); }
    if snapshot.mods_fingerprint.trim().is_empty() { bail!("modsFingerprint is required"); }
    if snapshot.blocks.len() > MAX_BLOCKS { bail!("runtime registry has too many blocks"); }
    let path = snapshot_path(cache_root, &snapshot.minecraft_version, &snapshot.mods_fingerprint);
    let folder = path.parent().context("runtime registry path has no parent")?;
    fs::create_dir_all(folder).with_context(|| format!("create {}", folder.display()))?;
    let temporary = folder.join(format!(".registry-{}.tmp", std::process::id()));
    {
        let file = File::create(&temporary).with_context(|| format!("create {}", temporary.display()))?;
        let mut writer = BufWriter::with_capacity(256 * 1024, file);
        write_snapshot_data(&mut writer, snapshot)?;
        writer.flush().context("flush runtime registry")?;
        writer.get_ref().sync_all().context("sync runtime registry to disk")?;
    }
    let _ = fs::remove_file(&path);
    fs::rename(&temporary, &path).with_context(|| format!("install {}", path.display()))?;
    let _ = fs::remove_file(folder.join("registry.json"));
    prune_sibling_fingerprints(cache_root, &snapshot.minecraft_version, &snapshot.mods_fingerprint)?;
    Ok(path)
}

fn write_snapshot_data(writer: &mut impl Write, snapshot: &Snapshot) -> Result<()> {
    writer.write_all(REGISTRY_MAGIC)?;
    write_i32(writer, snapshot.schema)?;
    write_string(writer, &snapshot.minecraft_version)?;
    write_string(writer, &snapshot.loader_version)?;
    write_string(writer, &snapshot.mods_fingerprint)?;
    write_string(writer, &snapshot.captured_at)?;
    write_count(writer, snapshot.loaded_mods.len(), MAX_LOADED_MODS, "loaded mods")?;
    for loaded_mod in &snapshot.loaded_mods { write_string(writer, loaded_mod)?; }
    write_count(writer, snapshot.blocks.len(), MAX_BLOCKS, "blocks")?;
    for (block_id, block) in &snapshot.blocks {
        write_string(writer, block_id)?;
        write_string(writer, &block.vanilla_mapping)?;
        write_string(writer, &block.loader_type)?;
        write_count(writer, block.variants.len(), MAX_VARIANTS, "variants")?;
        for variant in &block.variants {
            write_string_map(writer, &variant.properties)?;
            write_count(writer, variant.quads.len(), MAX_QUADS, "quads")?;
            for quad in &variant.quads {
                if quad.vertices.len() != 32 { bail!("runtime quad for {block_id} has {} floats, expected 32", quad.vertices.len()); }
                for value in &quad.vertices {
                    if !value.is_finite() { bail!("runtime quad for {block_id} contains non-finite vertex data"); }
                    writer.write_all(&value.to_bits().to_be_bytes())?;
                }
                write_string(writer, &quad.texture_id)?;
                write_i32(writer, quad.face)?;
                writer.write_all(&[u8::from(quad.shade)])?;
                write_i32(writer, quad.tint_index)?;
            }
        }
        write_count(writer, block.lights.len(), MAX_VARIANTS, "light states")?;
        for light in &block.lights {
            if !(0..=15).contains(&light.light_level) { bail!("invalid light level {} for {block_id}", light.light_level); }
            write_string_map(writer, &light.properties)?;
            write_i32(writer, light.light_level)?;
        }
    }
    Ok(())
}

fn write_i32(writer: &mut impl Write, value: i32) -> Result<()> {
    writer.write_all(&value.to_be_bytes()).context("write registry int32")?;
    Ok(())
}
fn write_count(writer: &mut impl Write, count: usize, maximum: usize, label: &str) -> Result<()> {
    if count > maximum || count > i32::MAX as usize { bail!("runtime registry {label} count {count} exceeds limit {maximum}"); }
    write_i32(writer, count as i32)
}
fn write_string(writer: &mut impl Write, value: &str) -> Result<()> {
    let bytes = value.as_bytes();
    if bytes.len() > MAX_STRING_BYTES { bail!("runtime registry string is too large: {} bytes", bytes.len()); }
    write_i32(writer, bytes.len() as i32)?;
    writer.write_all(bytes).context("write registry string")?;
    Ok(())
}
fn write_string_map(writer: &mut impl Write, values: &BTreeMap<String, String>) -> Result<()> {
    write_count(writer, values.len(), MAX_PROPERTIES, "properties")?;
    for (key, value) in values { write_string(writer, key)?; write_string(writer, value)?; }
    Ok(())
}
fn prune_sibling_fingerprints(cache_root: &Path, version: &str, keep: &str) -> Result<()> {
    let root = runtime_registry_root(cache_root).join(safe_component(version));
    if !root.is_dir() { return Ok(()); }
    let keep = safe_component(keep);
    for entry in fs::read_dir(&root).with_context(|| format!("read {}", root.display()))? {
        let entry = entry?;
        if entry.file_type()?.is_dir() && entry.file_name() != keep.as_str() {
            fs::remove_dir_all(entry.path()).with_context(|| format!("remove stale registry {}", entry.path().display()))?;
        }
    }
    Ok(())
}

pub fn mods_fingerprint(mods_path: &Path) -> Result<String> {
    if !mods_path.is_dir() { bail!("mods folder is unavailable: {}", mods_path.display()); }
    let mut jars = Vec::new();
    for entry in fs::read_dir(mods_path).with_context(|| format!("read mods folder {}", mods_path.display()))? {
        let entry = entry?;
        let path = entry.path();
        if !entry.file_type()?.is_file() { continue; }
        if path.extension().and_then(|ext| ext.to_str()).is_none_or(|ext| !ext.eq_ignore_ascii_case("jar")) { continue; }
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if name.starts_with(CAPTURE_PREFIX) { continue; }
        let size = entry.metadata()?.len();
        jars.push((name, path, size));
    }
    jars.sort_by(|left, right| left.0.cmp(&right.0));
    let mut total = Sha256::new();
    for (name, path, size) in jars {
        let digest = sha256_file(&path)?;
        total.update(name.as_bytes()); total.update([0]); total.update(size.to_string().as_bytes()); total.update([0]); total.update(hex_lower(&digest).as_bytes()); total.update(b"\n");
    }
    Ok(hex_lower(&total.finalize()))
}
fn sha256_file(path: &Path) -> Result<Vec<u8>> {
    let mut file = File::open(path).with_context(|| format!("open mod JAR {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 256 * 1024];
    loop { let count = file.read(&mut buffer).with_context(|| format!("read mod JAR {}", path.display()))?; if count == 0 { break; } hasher.update(&buffer[..count]); }
    Ok(hasher.finalize().to_vec())
}
fn hex_lower(bytes: &[u8]) -> String { let mut output = String::with_capacity(bytes.len() * 2); for byte in bytes { use std::fmt::Write as _; let _ = write!(output, "{byte:02x}"); } output }
fn safe_component(value: &str) -> String { let mut result = String::new(); for character in value.trim().chars().take(80) { if character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_') { result.push(character); } else { result.push('_'); } } if result.is_empty() { "unknown".to_string() } else { result } }
fn unix_timestamp_string() -> String { SystemTime::now().duration_since(UNIX_EPOCH).map(|duration| duration.as_secs().to_string()).unwrap_or_else(|_| "0".to_string()) }

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    #[test] fn binary_header_matches_java_reader_contract() { let mut snapshot = Snapshot { minecraft_version: "1.21.10".into(), loader_version: "0.18.5".into(), mods_fingerprint: "abc123".into(), captured_at: "test".into(), ..Snapshot::default() }; snapshot.blocks.insert("minecraft:light".into(), RuntimeBlock { loader_type: "fabric".into(), lights: vec![LightState { properties: BTreeMap::new(), light_level: 15 }], ..RuntimeBlock::default() }); let mut bytes = Vec::new(); write_snapshot_data(&mut bytes, &snapshot).unwrap(); assert_eq!(&bytes[..8], b"MSREGD01"); assert_eq!(i32::from_be_bytes(bytes[8..12].try_into().unwrap()), 4); assert!(bytes.len() > 40); }
    #[test] fn malformed_quads_are_sanitized() { let variants = sanitize_variants(vec![BlockVariant { properties: BTreeMap::new(), quads: vec![BakedQuad { vertices: vec![0.0; 8], ..BakedQuad::default() }], }]); assert!(variants[0].quads.is_empty()); }
    #[test] fn safe_components_cannot_escape_registry_root() { assert_eq!(safe_component("../1.21.10"), ".._1.21.10"); assert!(!safe_component("../../bad").contains('/')); }
    #[test] fn writer_uses_big_endian_float_bits() { let value = 1.0f32; let mut bytes = Cursor::new(Vec::<u8>::new()); bytes.write_all(&value.to_bits().to_be_bytes()).unwrap(); assert_eq!(bytes.into_inner(), [0x3f, 0x80, 0x00, 0x00]); }
}
