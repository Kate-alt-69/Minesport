mod legacy {
    include!("registry.rs");
}

pub use legacy::{
    mods_fingerprint_filtered, snapshot_path, CaptureNotice, EPHEMERAL_ADDRESS, SNAPSHOT_SCHEMA,
};

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashSet},
    fs::{self, File},
    io::{BufRead, BufReader, BufWriter, Read, Write},
    net::TcpListener,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const REGISTRY_MAGIC: &[u8; 8] = b"MSREGD01";
const READY_FILE: &str = "registry.ready.json";
const MAX_READY_RECEIPT_BYTES: u64 = 64 * 1024;
const MAX_MESSAGE_BYTES: usize = 128 << 20;
const MAX_STRING_BYTES: usize = 4 << 20;
const MAX_BLOCKS: usize = 1_000_000;
const MAX_VARIANTS: usize = 1_000_000;
const MAX_QUADS: usize = 20_000_000;
const MAX_PROPERTIES: usize = 4096;
const MAX_LOADED_MODS: usize = 100_000;
const MAX_FINGERPRINTS_PER_VERSION: usize = 4;

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
    variants: Vec<legacy::BlockVariant>,
    states: Vec<legacy::LightState>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReadyReceipt {
    schema: i32,
    minecraft_version: String,
    fingerprint: String,
    file_size: u64,
}

struct PendingBlock {
    id: String,
    block: legacy::RuntimeBlock,
    light_seen: bool,
}

struct StreamWriter {
    cache_root: PathBuf,
    final_path: PathBuf,
    temporary_path: PathBuf,
    writer: Option<BufWriter<File>>,
    minecraft_version: String,
    fingerprint: String,
    expected_blocks: usize,
    written_blocks: usize,
    committed: bool,
}

impl StreamWriter {
    fn begin(
        cache_root: &Path,
        minecraft_version: &str,
        loader_version: &str,
        loaded_mods: &[String],
        fingerprint: &str,
        total_blocks: usize,
    ) -> Result<Self> {
        if minecraft_version.trim().is_empty() {
            bail!("minecraftVersion is required");
        }
        if fingerprint.trim().is_empty() {
            bail!("modsFingerprint is required");
        }
        if total_blocks > MAX_BLOCKS {
            bail!("runtime registry has too many blocks");
        }
        if loaded_mods.len() > MAX_LOADED_MODS {
            bail!(
                "runtime registry loaded-mod count {} exceeds limit {}",
                loaded_mods.len(),
                MAX_LOADED_MODS
            );
        }

        let final_path = snapshot_path(cache_root, minecraft_version, fingerprint);
        let folder = final_path
            .parent()
            .context("runtime registry path has no parent")?;
        fs::create_dir_all(folder).with_context(|| format!("create {}", folder.display()))?;
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or(0);
        let temporary_path = folder.join(format!(".registry-{}-{nonce}.tmp", std::process::id()));
        let file = File::create(&temporary_path)
            .with_context(|| format!("create {}", temporary_path.display()))?;
        let mut writer = BufWriter::with_capacity(256 * 1024, file);
        let captured_at = unix_timestamp_string();
        if let Err(error) = write_header(
            &mut writer,
            minecraft_version,
            loader_version,
            fingerprint,
            &captured_at,
            loaded_mods,
            total_blocks,
        ) {
            drop(writer);
            let _ = fs::remove_file(&temporary_path);
            return Err(error);
        }

        Ok(Self {
            cache_root: cache_root.to_path_buf(),
            final_path,
            temporary_path,
            writer: Some(writer),
            minecraft_version: minecraft_version.to_string(),
            fingerprint: fingerprint.to_string(),
            expected_blocks: total_blocks,
            written_blocks: 0,
            committed: false,
        })
    }

    fn write_block(&mut self, block_id: &str, block: &legacy::RuntimeBlock) -> Result<()> {
        if self.written_blocks >= self.expected_blocks {
            bail!(
                "runtime registry received more block entries than hello announced ({})",
                self.expected_blocks
            );
        }
        write_block(
            self.writer
                .as_mut()
                .context("runtime registry writer is already finalized")?,
            block_id,
            block,
        )?;
        self.written_blocks += 1;
        Ok(())
    }

    fn finish(mut self) -> Result<PathBuf> {
        if self.written_blocks != self.expected_blocks {
            bail!(
                "runtime registry completed with {} block entries but hello announced {}",
                self.written_blocks,
                self.expected_blocks
            );
        }

        let mut writer = self
            .writer
            .take()
            .context("runtime registry writer is already finalized")?;
        writer.flush().context("flush runtime registry")?;
        writer
            .get_ref()
            .sync_all()
            .context("sync runtime registry to disk")?;
        drop(writer);

        let receipt_path = ready_receipt_path(&self.final_path);
        let _ = fs::remove_file(&receipt_path);
        let _ = fs::remove_file(&self.final_path);
        fs::rename(&self.temporary_path, &self.final_path)
            .with_context(|| format!("install {}", self.final_path.display()))?;
        write_ready_receipt(&self.final_path, &self.minecraft_version, &self.fingerprint)?;
        self.committed = true;

        if let Some(folder) = self.final_path.parent() {
            let _ = fs::remove_file(folder.join("registry.json"));
        }
        if let Err(error) =
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
        Ok(self.final_path.clone())
    }
}

impl Drop for StreamWriter {
    fn drop(&mut self) {
        if self.committed {
            return;
        }
        let _ = self.writer.take();
        let _ = fs::remove_file(&self.temporary_path);
    }
}

fn ready_receipt_path(registry_path: &Path) -> PathBuf {
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

fn read_ready_receipt(registry_path: &Path) -> Result<ReadyReceipt> {
    let ready_path = ready_receipt_path(registry_path);
    let metadata =
        fs::metadata(&ready_path).with_context(|| format!("inspect {}", ready_path.display()))?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_READY_RECEIPT_BYTES {
        bail!("runtime registry ready receipt is invalid");
    }
    let encoded =
        fs::read(&ready_path).with_context(|| format!("read {}", ready_path.display()))?;
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
        File::open(registry_path).with_context(|| format!("open {}", registry_path.display()))?,
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

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
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

    let newline = available.iter().position(|byte| *byte == b'\n');
    let append_len = newline.unwrap_or(available.len());
    if packet.len().saturating_add(append_len) > max_bytes {
        bail!("runtime registry packet exceeded {} bytes", max_bytes);
    }
    packet.extend_from_slice(&available[..append_len]);
    let consumed = newline.map_or(available.len(), |index| index + 1);
    reader.consume(consumed);

    if newline.is_some() {
        if packet.last() == Some(&b'\r') {
            packet.pop();
        }
        Ok(PacketRead::Complete)
    } else {
        Ok(PacketRead::Pending)
    }
}

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

/// Capture one runtime registry while keeping at most one baked block resident.
///
/// All canonical Export Workers emit `block`, then an optional matching
/// `block_light`, before the next `block`. The receiver enforces that ordering
/// and writes each completed block directly to the schema-4 temporary file.
/// The final cache path is published only after `done` and exact count checks.
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
    if version.is_empty() {
        bail!("Minecraft version is required for runtime capture");
    }
    if fingerprint.is_empty() {
        bail!("mod fingerprint is required for runtime capture");
    }

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

    let mut reader = BufReader::new(stream);
    let mut line = Vec::with_capacity(64 * 1024);
    let mut hello_seen = false;
    let mut complete = false;
    let mut total_blocks = 0usize;
    let mut seen_ids = HashSet::<String>::new();
    let mut pending: Option<PendingBlock> = None;
    let mut output: Option<StreamWriter> = None;

    while !complete {
        if cancel.load(Ordering::Relaxed) {
            bail!("runtime cache cancelled during registry capture");
        }
        match read_registry_packet(&mut reader, &mut line, MAX_MESSAGE_BYTES)? {
            PacketRead::Pending => continue,
            PacketRead::Eof => break,
            PacketRead::Complete => {}
        }
        if line.is_empty() {
            continue;
        }

        let message: WireMessage =
            serde_json::from_slice(&line).context("parse runtime registry packet")?;
        line.clear();
        match message.kind.as_str() {
            "hello" => {
                if hello_seen {
                    bail!("runtime registry protocol sent more than one hello packet");
                }
                let worker_version = message.mc_version.trim();
                if worker_version.is_empty() {
                    bail!("runtime registry hello had no Minecraft version");
                }
                if worker_version != version {
                    bail!(
                        "runtime registry version {} does not match requested {}",
                        worker_version,
                        version
                    );
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

                total_blocks = message.total_blocks;
                output = Some(StreamWriter::begin(
                    cache_root,
                    worker_version,
                    message.loader_version.trim(),
                    &message.loaded_mods,
                    fingerprint,
                    total_blocks,
                )?);
                hello_seen = true;
            }
            "block" => {
                require_hello(hello_seen, "block")?;
                flush_pending(&mut pending, output.as_mut(), total_blocks, &mut notice)?;

                let block_id = message.block_id.trim();
                if block_id.is_empty() {
                    bail!("runtime registry block packet had an empty block ID");
                }
                if !seen_ids.insert(block_id.to_string()) {
                    bail!("runtime registry sent duplicate block entry {block_id}");
                }
                if seen_ids.len() > total_blocks {
                    bail!(
                        "runtime registry received more block entries ({}) than hello announced ({total_blocks})",
                        seen_ids.len()
                    );
                }

                pending = Some(PendingBlock {
                    id: block_id.to_string(),
                    block: legacy::RuntimeBlock {
                        vanilla_mapping: message.vanilla_mapping.trim().to_string(),
                        loader_type: message.loader_type.trim().to_string(),
                        variants: sanitize_variants(message.variants),
                        lights: Vec::new(),
                    },
                    light_seen: false,
                });
            }
            "block_light" => {
                require_hello(hello_seen, "light")?;
                let block_id = message.block_id.trim();
                if block_id.is_empty() {
                    bail!("runtime registry light packet had an empty block ID");
                }
                let current = pending.as_mut().ok_or_else(|| {
                    anyhow!(
                        "runtime registry light packet referenced block {block_id} after it was flushed"
                    )
                })?;
                if current.id != block_id {
                    bail!(
                        "runtime registry light packet for {block_id} did not immediately follow its block entry {}",
                        current.id
                    );
                }
                if current.light_seen {
                    bail!("runtime registry sent duplicate light packet for {block_id}");
                }
                current.block.lights = sanitize_lights(message.states);
                current.light_seen = true;
            }
            "texture" => {
                require_hello(hello_seen, "texture")?;
            }
            "error" => {
                let detail = message.message.trim();
                if detail.is_empty() {
                    bail!("runtime Export Worker reported an unspecified protocol error");
                }
                bail!("runtime Export Worker reported an error: {detail}");
            }
            "done" => {
                require_hello(hello_seen, "done")?;
                flush_pending(&mut pending, output.as_mut(), total_blocks, &mut notice)?;
                complete = true;
            }
            other => bail!("runtime registry sent unknown packet type {other:?}"),
        }
    }

    if !complete {
        bail!("runtime worker disconnected before completing its registry dump");
    }
    let writer = output.context("runtime registry dump completed without a hello packet")?;
    let blocks = writer.written_blocks;
    if blocks != total_blocks {
        bail!(
            "runtime registry completed with {blocks} block entries but hello announced {total_blocks}"
        );
    }
    let path = writer.finish()?;
    notice(CaptureNotice::Complete {
        path: path.clone(),
        blocks,
    });
    Ok(path)
}

fn require_hello(hello_seen: bool, packet: &str) -> Result<()> {
    if !hello_seen {
        bail!("runtime registry {packet} packet arrived before hello");
    }
    Ok(())
}

fn flush_pending<F>(
    pending: &mut Option<PendingBlock>,
    writer: Option<&mut StreamWriter>,
    total_blocks: usize,
    notice: &mut F,
) -> Result<()>
where
    F: FnMut(CaptureNotice),
{
    let Some(pending) = pending.take() else {
        return Ok(());
    };
    let writer = writer.context("runtime registry writer was not initialized")?;
    writer.write_block(&pending.id, &pending.block)?;
    if writer.written_blocks % 128 == 0 || writer.written_blocks == total_blocks {
        notice(CaptureNotice::Progress {
            blocks: writer.written_blocks,
            total_blocks,
        });
    }
    Ok(())
}

fn sanitize_variants(source: Vec<legacy::BlockVariant>) -> Vec<legacy::BlockVariant> {
    source
        .into_iter()
        .map(|variant| legacy::BlockVariant {
            properties: variant.properties,
            quads: variant
                .quads
                .into_iter()
                .filter_map(|mut quad| {
                    if quad.vertices.len() < 32 {
                        return None;
                    }
                    quad.vertices.truncate(32);
                    if quad.vertices.iter().any(|value| !value.is_finite()) {
                        return None;
                    }
                    quad.texture_id = quad.texture_id.trim().to_string();
                    Some(quad)
                })
                .collect(),
        })
        .collect()
}

fn sanitize_lights(source: Vec<legacy::LightState>) -> Vec<legacy::LightState> {
    source
        .into_iter()
        .filter_map(|mut state| {
            if state.light_level < 1 {
                return None;
            }
            state.light_level = state.light_level.min(15);
            Some(state)
        })
        .collect()
}

fn write_header(
    writer: &mut impl Write,
    minecraft_version: &str,
    loader_version: &str,
    fingerprint: &str,
    captured_at: &str,
    loaded_mods: &[String],
    block_count: usize,
) -> Result<()> {
    writer.write_all(REGISTRY_MAGIC)?;
    write_i32(writer, SNAPSHOT_SCHEMA)?;
    write_string(writer, minecraft_version)?;
    write_string(writer, loader_version)?;
    write_string(writer, fingerprint)?;
    write_string(writer, captured_at)?;
    write_count(writer, loaded_mods.len(), MAX_LOADED_MODS, "loaded mods")?;
    for loaded_mod in loaded_mods {
        write_string(writer, loaded_mod)?;
    }
    write_count(writer, block_count, MAX_BLOCKS, "blocks")?;
    Ok(())
}

fn write_block(
    writer: &mut impl Write,
    block_id: &str,
    block: &legacy::RuntimeBlock,
) -> Result<()> {
    write_string(writer, block_id)?;
    write_string(writer, &block.vanilla_mapping)?;
    write_string(writer, &block.loader_type)?;
    write_count(writer, block.variants.len(), MAX_VARIANTS, "variants")?;
    for variant in &block.variants {
        write_string_map(writer, &variant.properties)?;
        write_count(writer, variant.quads.len(), MAX_QUADS, "quads")?;
        for quad in &variant.quads {
            if quad.vertices.len() != 32 {
                bail!(
                    "runtime quad for {block_id} has {} floats, expected 32",
                    quad.vertices.len()
                );
            }
            for value in &quad.vertices {
                if !value.is_finite() {
                    bail!("runtime quad for {block_id} contains non-finite vertex data");
                }
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
        if !(0..=15).contains(&light.light_level) {
            bail!("invalid light level {} for {block_id}", light.light_level);
        }
        write_string_map(writer, &light.properties)?;
        write_i32(writer, light.light_level)?;
    }
    Ok(())
}

fn write_i32(writer: &mut impl Write, value: i32) -> Result<()> {
    writer
        .write_all(&value.to_be_bytes())
        .context("write registry int32")?;
    Ok(())
}

fn write_count(writer: &mut impl Write, count: usize, maximum: usize, label: &str) -> Result<()> {
    if count > maximum || count > i32::MAX as usize {
        bail!("runtime registry {label} count {count} exceeds limit {maximum}");
    }
    write_i32(writer, count as i32)
}

fn write_string(writer: &mut impl Write, value: &str) -> Result<()> {
    let bytes = value.as_bytes();
    if bytes.len() > MAX_STRING_BYTES {
        bail!(
            "runtime registry string is too large: {} bytes",
            bytes.len()
        );
    }
    write_i32(writer, bytes.len() as i32)?;
    writer.write_all(bytes).context("write registry string")?;
    Ok(())
}

fn write_string_map(writer: &mut impl Write, values: &BTreeMap<String, String>) -> Result<()> {
    write_count(writer, values.len(), MAX_PROPERTIES, "properties")?;
    for (key, value) in values {
        write_string(writer, key)?;
        write_string(writer, value)?;
    }
    Ok(())
}

fn prune_sibling_fingerprints(cache_root: &Path, version: &str, keep: &str) -> Result<()> {
    let keep_path = snapshot_path(cache_root, version, keep);
    let keep_dir = keep_path
        .parent()
        .context("runtime registry keep path has no parent")?;
    let version_root = keep_dir
        .parent()
        .context("runtime registry version path has no parent")?;
    if !version_root.is_dir() {
        return Ok(());
    }

    let mut siblings = Vec::new();
    for entry in
        fs::read_dir(version_root).with_context(|| format!("read {}", version_root.display()))?
    {
        let entry = entry?;
        if !entry.file_type()?.is_dir() || entry.path() == keep_dir {
            continue;
        }
        let modified = entry
            .metadata()
            .ok()
            .and_then(|metadata| metadata.modified().ok())
            .unwrap_or(UNIX_EPOCH);
        siblings.push((modified, entry.path()));
    }
    siblings.sort_by(|left, right| right.0.cmp(&left.0));
    let retain_siblings = MAX_FINGERPRINTS_PER_VERSION.saturating_sub(1);
    for (_, path) in siblings.into_iter().skip(retain_siblings) {
        fs::remove_dir_all(&path)
            .with_context(|| format!("remove stale registry {}", path.display()))?;
    }
    Ok(())
}

fn unix_timestamp_string() -> String {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs().to_string())
        .unwrap_or_else(|_| "0".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fragmented_registry_packet_is_preserved_until_newline() {
        let source = std::io::Cursor::new(b"{\"type\":\"done\"}\n".to_vec());
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
        assert_eq!(packet, b"{\"type\":\"done\"}");
    }

    #[test]
    fn oversized_registry_packet_is_rejected_before_buffer_growth() {
        let source = std::io::Cursor::new(b"123456789\n".to_vec());
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

    #[cfg(unix)]
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
            StreamWriter::begin(&cache, "1.21.10", "test-loader", &[], "prune-test", 0).unwrap();
        let keep_path = snapshot_path(&cache, "1.21.10", "prune-test");
        let version_root = keep_path.parent().unwrap().parent().unwrap().to_path_buf();
        for index in 0..4 {
            fs::create_dir_all(version_root.join(format!("stale-{index}"))).unwrap();
        }

        fs::set_permissions(&version_root, fs::Permissions::from_mode(0o500)).unwrap();
        let result = writer.finish();
        fs::set_permissions(&version_root, fs::Permissions::from_mode(0o700)).unwrap();

        assert!(
            result.is_ok(),
            "committed registry was rejected: {result:?}"
        );
        assert!(snapshot_exists(&cache, "1.21.10", "prune-test"));
        let _ = fs::remove_dir_all(cache);
    }

    #[test]
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

    #[test]
    fn streamed_header_matches_schema_four_contract() {
        let mut bytes = Vec::new();
        write_header(
            &mut bytes,
            "1.21.10",
            "0.18.5",
            "abc123",
            "test",
            &["example@1.0.0".into()],
            2,
        )
        .unwrap();
        assert_eq!(&bytes[..8], b"MSREGD01");
        assert_eq!(i32::from_be_bytes(bytes[8..12].try_into().unwrap()), 4);
    }

    #[test]
    fn incomplete_stream_is_never_published() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let cache = std::env::temp_dir().join(format!(
            "minesport-registry-stream-drop-{}-{stamp}",
            std::process::id()
        ));
        let final_path = snapshot_path(&cache, "1.21.10", "drop-test");
        {
            let mut writer =
                StreamWriter::begin(&cache, "1.21.10", "0.18.5", &[], "drop-test", 2).unwrap();
            writer
                .write_block("minecraft:stone", &legacy::RuntimeBlock::default())
                .unwrap();
        }
        assert!(!final_path.exists());
        if let Some(folder) = final_path.parent() {
            if folder.is_dir() {
                assert!(fs::read_dir(folder).unwrap().all(|entry| {
                    !entry
                        .unwrap()
                        .file_name()
                        .to_string_lossy()
                        .ends_with(".tmp")
                }));
            }
        }
        let _ = fs::remove_dir_all(cache);
    }
}
