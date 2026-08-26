from pathlib import Path


def read(path: str) -> str:
    file = Path(path)
    if not file.is_file():
        raise SystemExit(f"missing required file: {path}")
    return file.read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"expected exactly one match in {path}, found {count}: {old[:120]!r}"
        )
    write(path, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Rust runtime registry capture: make it cancellable and bounded-memory.
# ---------------------------------------------------------------------------
registry_path = "desktop/src/registry.rs"
registry = read(registry_path)

old_imports = """    io::{BufRead, BufReader, BufWriter, Read, Write},
    net::TcpListener,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};"""
new_imports = """    io::{BufRead, BufReader, BufWriter, ErrorKind, Read, Seek, SeekFrom, Write},
    net::TcpListener,
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};"""
if registry.count(old_imports) != 1:
    raise SystemExit("registry imports no longer match expected capture implementation")
registry = registry.replace(old_imports, new_imports, 1)

capture_marker = "/// Receive one complete `MINESPORT_EXPORT_WORKER_MODE=all` registry dump"
capture_start = registry.index(capture_marker)
capture_end = registry.index("\nfn sanitize_variants", capture_start)
new_capture = r'''/// Receive one complete `MINESPORT_EXPORT_WORKER_MODE=all` registry dump and persist
/// it as schema-4 `registry.data`.
///
/// This compatibility wrapper keeps existing callers simple. Runtime jobs use
/// `capture_once_cancellable` so cancellation/error paths can always release the
/// listener and socket.
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

/// Cancellable, bounded-memory registry capture.
///
/// The wire protocol remains newline JSON, but persistent binary records are
/// written one block at a time. Export Worker senders emit `block` followed by
/// its optional `block_light`, so only one RuntimeBlock is retained instead of
/// the entire registry.
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
        .context("make runtime registry listener cancellable")?;
    notice(CaptureNotice::Listening(listener.local_addr()?.to_string()));

    let (stream, peer) = loop {
        if cancel.load(Ordering::Relaxed) {
            bail!("runtime registry capture cancelled");
        }
        match listener.accept() {
            Ok(accepted) => break accepted,
            Err(error) if error.kind() == ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50));
            }
            Err(error) => {
                return Err(error).context("accept Minecraft runtime registry worker");
            }
        }
    };
    stream
        .set_read_timeout(Some(Duration::from_millis(250)))
        .context("set runtime registry socket read timeout")?;
    notice(CaptureNotice::Connected(peer.to_string()));

    let mut reader = BufReader::new(stream);
    let mut line = Vec::with_capacity(64 * 1024);
    let mut writer = None::<StreamingSnapshotWriter>;
    let mut pending = None::<(String, RuntimeBlock)>;
    let mut total_blocks = 0usize;
    let mut complete = false;

    while !complete {
        if cancel.load(Ordering::Relaxed) {
            bail!("runtime registry capture cancelled");
        }

        let bytes = loop {
            match reader.read_until(b'\n', &mut line) {
                Ok(bytes) => break bytes,
                Err(error)
                    if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) =>
                {
                    if line.len() > MAX_MESSAGE_BYTES {
                        bail!(
                            "runtime registry packet exceeded {} bytes",
                            MAX_MESSAGE_BYTES
                        );
                    }
                    if cancel.load(Ordering::Relaxed) {
                        bail!("runtime registry capture cancelled");
                    }
                    continue;
                }
                Err(error) => {
                    return Err(error).context("read runtime registry packet");
                }
            }
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
        while matches!(line.last(), Some(b'\n' | b'\r')) {
            line.pop();
        }
        if line.is_empty() {
            continue;
        }

        let message: WireMessage = match serde_json::from_slice(&line) {
            Ok(value) => value,
            Err(error) => {
                notice(CaptureNotice::WorkerMessage(format!(
                    "Ignoring malformed registry packet: {error}"
                )));
                line.clear();
                continue;
            }
        };
        line.clear();

        match message.kind.as_str() {
            "hello" => {
                if writer.is_some() {
                    bail!("runtime registry worker sent more than one hello packet");
                }
                let mc_version = message.mc_version.trim();
                if mc_version.is_empty() {
                    bail!("runtime registry dump had no Minecraft version");
                }
                if mc_version != version {
                    bail!(
                        "runtime registry version {} does not match requested {}",
                        mc_version,
                        version
                    );
                }
                total_blocks = message.total_blocks.min(MAX_BLOCKS);
                writer = Some(StreamingSnapshotWriter::new(
                    cache_root,
                    mc_version,
                    message.loader_version.trim(),
                    fingerprint,
                    &message.loaded_mods,
                    &unix_timestamp_string(),
                )?);
            }
            "block" => {
                let block_id = message.block_id.trim();
                if block_id.is_empty() {
                    continue;
                }
                let stream_writer = writer
                    .as_mut()
                    .context("runtime registry block arrived before hello")?;
                flush_pending_block(
                    stream_writer,
                    &mut pending,
                    total_blocks,
                    &mut notice,
                )?;
                pending = Some((
                    block_id.to_string(),
                    RuntimeBlock {
                        vanilla_mapping: message.vanilla_mapping.trim().to_string(),
                        loader_type: message.loader_type.trim().to_string(),
                        variants: sanitize_variants(message.variants),
                        lights: Vec::new(),
                    },
                ));
            }
            "block_light" => {
                let block_id = message.block_id.trim();
                if block_id.is_empty() {
                    continue;
                }
                match pending.as_mut() {
                    Some((pending_id, block)) if pending_id == block_id => {
                        block.lights = sanitize_lights(message.states);
                    }
                    Some((pending_id, _)) => {
                        bail!(
                            "runtime light packet for {block_id} arrived while {pending_id} was pending"
                        );
                    }
                    None => {
                        bail!(
                            "runtime light packet for {block_id} arrived before its block packet"
                        );
                    }
                }
            }
            "texture" => {}
            "error" => {
                if !message.message.trim().is_empty() {
                    notice(CaptureNotice::WorkerMessage(message.message));
                }
            }
            "done" => {
                let stream_writer = writer
                    .as_mut()
                    .context("runtime registry completed before hello")?;
                flush_pending_block(
                    stream_writer,
                    &mut pending,
                    total_blocks,
                    &mut notice,
                )?;
                complete = true;
            }
            _ => {}
        }
    }

    if !complete {
        bail!("runtime worker disconnected before completing its registry dump");
    }
    let stream_writer = writer.context("runtime registry dump had no hello packet")?;
    let blocks = stream_writer.blocks_written();
    if blocks > 0 && blocks % 128 != 0 {
        notice(CaptureNotice::Progress {
            blocks,
            total_blocks,
        });
    }
    let path = stream_writer.finish()?;
    notice(CaptureNotice::Complete {
        path: path.clone(),
        blocks,
    });
    Ok(path)
}

struct StreamingSnapshotWriter {
    path: PathBuf,
    temporary: PathBuf,
    writer: Option<BufWriter<File>>,
    block_count_offset: u64,
    blocks_written: usize,
    cache_root: PathBuf,
    version: String,
    fingerprint: String,
    committed: bool,
}

impl StreamingSnapshotWriter {
    fn new(
        cache_root: &Path,
        version: &str,
        loader_version: &str,
        fingerprint: &str,
        loaded_mods: &[String],
        captured_at: &str,
    ) -> Result<Self> {
        if version.trim().is_empty() {
            bail!("minecraftVersion is required");
        }
        if fingerprint.trim().is_empty() {
            bail!("modsFingerprint is required");
        }

        let path = snapshot_path(cache_root, version, fingerprint);
        let folder = path
            .parent()
            .context("runtime registry path has no parent")?;
        fs::create_dir_all(folder)
            .with_context(|| format!("create {}", folder.display()))?;
        let temporary = folder.join(format!(
            ".registry-{}-{}.tmp",
            std::process::id(),
            unix_timestamp_string()
        ));
        let file = File::create(&temporary)
            .with_context(|| format!("create {}", temporary.display()))?;
        let mut writer = BufWriter::with_capacity(256 * 1024, file);

        writer.write_all(REGISTRY_MAGIC)?;
        write_i32(&mut writer, SNAPSHOT_SCHEMA)?;
        write_string(&mut writer, version)?;
        write_string(&mut writer, loader_version)?;
        write_string(&mut writer, fingerprint)?;
        write_string(&mut writer, captured_at)?;
        write_count(
            &mut writer,
            loaded_mods.len(),
            MAX_LOADED_MODS,
            "loaded mods",
        )?;
        for loaded_mod in loaded_mods {
            write_string(&mut writer, loaded_mod)?;
        }
        let block_count_offset = writer
            .stream_position()
            .context("locate runtime registry block count")?;
        write_i32(&mut writer, 0)?;

        Ok(Self {
            path,
            temporary,
            writer: Some(writer),
            block_count_offset,
            blocks_written: 0,
            cache_root: cache_root.to_path_buf(),
            version: version.to_string(),
            fingerprint: fingerprint.to_string(),
            committed: false,
        })
    }

    fn write_block(&mut self, block_id: &str, block: &RuntimeBlock) -> Result<()> {
        if self.blocks_written >= MAX_BLOCKS {
            bail!("runtime registry has too many blocks");
        }
        let writer = self
            .writer
            .as_mut()
            .context("runtime registry stream is closed")?;
        write_runtime_block(writer, block_id, block)?;
        self.blocks_written += 1;
        Ok(())
    }

    fn blocks_written(&self) -> usize {
        self.blocks_written
    }

    fn finish(mut self) -> Result<PathBuf> {
        if self.blocks_written > MAX_BLOCKS || self.blocks_written > i32::MAX as usize {
            bail!("runtime registry has too many blocks");
        }
        let mut writer = self
            .writer
            .take()
            .context("runtime registry stream is closed")?;
        writer.flush().context("flush runtime registry")?;
        writer
            .seek(SeekFrom::Start(self.block_count_offset))
            .context("seek runtime registry block count")?;
        write_i32(&mut writer, self.blocks_written as i32)?;
        writer.flush().context("flush runtime registry block count")?;
        writer
            .get_ref()
            .sync_all()
            .context("sync runtime registry to disk")?;
        drop(writer);

        let _ = fs::remove_file(&self.path);
        fs::rename(&self.temporary, &self.path)
            .with_context(|| format!("install {}", self.path.display()))?;
        self.committed = true;

        if let Some(folder) = self.path.parent() {
            let _ = fs::remove_file(folder.join("registry.json"));
        }
        prune_sibling_fingerprints(
            &self.cache_root,
            &self.version,
            &self.fingerprint,
        )?;
        Ok(self.path.clone())
    }
}

impl Drop for StreamingSnapshotWriter {
    fn drop(&mut self) {
        if !self.committed {
            self.writer.take();
            let _ = fs::remove_file(&self.temporary);
        }
    }
}

fn flush_pending_block<F>(
    writer: &mut StreamingSnapshotWriter,
    pending: &mut Option<(String, RuntimeBlock)>,
    total_blocks: usize,
    notice: &mut F,
) -> Result<()>
where
    F: FnMut(CaptureNotice),
{
    let Some((block_id, block)) = pending.take() else {
        return Ok(());
    };
    writer.write_block(&block_id, &block)?;
    let blocks = writer.blocks_written();
    if blocks % 128 == 0 {
        notice(CaptureNotice::Progress {
            blocks,
            total_blocks,
        });
    }
    Ok(())
}
'''
registry = registry[:capture_start] + new_capture + registry[capture_end:]

function_start = registry.index("fn write_snapshot_data")
loop_start = registry.index("    for (block_id, block) in &snapshot.blocks {", function_start)
loop_end_marker = "    }\n    Ok(())\n}\n\nfn write_i32"
loop_end = registry.index(loop_end_marker, loop_start)
registry = (
    registry[:loop_start]
    + """    for (block_id, block) in &snapshot.blocks {\n        write_runtime_block(writer, block_id, block)?;\n    }\n"""
    + registry[loop_end + len("    }\n") :]
)

helper = r'''fn write_runtime_block(
    writer: &mut impl Write,
    block_id: &str,
    block: &RuntimeBlock,
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

'''
write_i32_marker = "fn write_i32(writer: &mut impl Write, value: i32) -> Result<()> {"
if registry.count(write_i32_marker) != 1:
    raise SystemExit("registry write_i32 marker changed")
registry = registry.replace(write_i32_marker, helper + write_i32_marker, 1)

test_import = "    use std::io::Cursor;"
new_test_import = """    use std::{
        env,
        io::{Cursor, Read as _, Write as _},
        net::TcpStream,
        sync::mpsc,
    };"""
if registry.count(test_import) != 1:
    raise SystemExit("registry test import marker changed")
registry = registry.replace(test_import, new_test_import, 1)

test_close = "    #[test] fn writer_uses_big_endian_float_bits() { let value = 1.0f32; let mut bytes = Cursor::new(Vec::<u8>::new()); bytes.write_all(&value.to_bits().to_be_bytes()).unwrap(); assert_eq!(bytes.into_inner(), [0x3f, 0x80, 0x00, 0x00]); }\n}"
new_tests = r'''    #[test] fn writer_uses_big_endian_float_bits() { let value = 1.0f32; let mut bytes = Cursor::new(Vec::<u8>::new()); bytes.write_all(&value.to_bits().to_be_bytes()).unwrap(); assert_eq!(bytes.into_inner(), [0x3f, 0x80, 0x00, 0x00]); }

    #[test]
    fn cancellable_capture_releases_idle_listener() {
        let root = env::temp_dir().join(format!(
            "minesport-registry-cancel-{}-{}",
            std::process::id(),
            unix_timestamp_string()
        ));
        let thread_root = root.clone();
        let cancel = Arc::new(AtomicBool::new(false));
        let thread_cancel = cancel.clone();
        let (listen_tx, listen_rx) = mpsc::channel();

        let handle = thread::spawn(move || {
            capture_once_cancellable(
                "127.0.0.1:0",
                &thread_root,
                "1.21.10",
                "cancel-test",
                thread_cancel,
                |notice| {
                    if let CaptureNotice::Listening(address) = notice {
                        let _ = listen_tx.send(address);
                    }
                },
            )
        });

        let _address = listen_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        cancel.store(true, Ordering::Relaxed);
        let result = handle.join().unwrap();
        assert!(result.unwrap_err().to_string().contains("cancelled"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn streaming_capture_patches_block_count_and_keeps_light_state() {
        let root = env::temp_dir().join(format!(
            "minesport-registry-stream-{}-{}",
            std::process::id(),
            unix_timestamp_string()
        ));
        let thread_root = root.clone();
        let cancel = Arc::new(AtomicBool::new(false));
        let thread_cancel = cancel.clone();
        let (listen_tx, listen_rx) = mpsc::channel();

        let handle = thread::spawn(move || {
            capture_once_cancellable(
                "127.0.0.1:0",
                &thread_root,
                "1.21.10",
                "stream-test",
                thread_cancel,
                |notice| {
                    if let CaptureNotice::Listening(address) = notice {
                        let _ = listen_tx.send(address);
                    }
                },
            )
        });

        let address = listen_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        let mut stream = TcpStream::connect(address).unwrap();
        for packet in [
            r#"{"type":"hello","mcVersion":"1.21.10","loaderVersion":"test","totalBlocks":1,"loadedMods":[]}"#,
            r#"{"type":"block","blockId":"minecraft:test","vanillaMapping":"minecraft:test","loaderType":"fabric","variants":[]}"#,
            r#"{"type":"block_light","blockId":"minecraft:test","states":[{"properties":{},"lightLevel":15}]}"#,
            r#"{"type":"done"}"#,
        ] {
            stream.write_all(packet.as_bytes()).unwrap();
            stream.write_all(b"\n").unwrap();
        }
        drop(stream);

        let path = handle.join().unwrap().unwrap();
        let bytes = fs::read(path).unwrap();
        let mut cursor = Cursor::new(bytes.as_slice());
        let mut magic = [0u8; 8];
        cursor.read_exact(&mut magic).unwrap();
        assert_eq!(&magic, REGISTRY_MAGIC);
        assert_eq!(read_test_i32(&mut cursor), SNAPSHOT_SCHEMA);
        assert_eq!(read_test_string(&mut cursor), "1.21.10");
        assert_eq!(read_test_string(&mut cursor), "test");
        assert_eq!(read_test_string(&mut cursor), "stream-test");
        assert!(!read_test_string(&mut cursor).is_empty());
        assert_eq!(read_test_i32(&mut cursor), 0);
        assert_eq!(read_test_i32(&mut cursor), 1);
        assert_eq!(read_test_string(&mut cursor), "minecraft:test");
        assert_eq!(read_test_string(&mut cursor), "minecraft:test");
        assert_eq!(read_test_string(&mut cursor), "fabric");
        assert_eq!(read_test_i32(&mut cursor), 0);
        assert_eq!(read_test_i32(&mut cursor), 1);
        assert_eq!(read_test_i32(&mut cursor), 0);
        assert_eq!(read_test_i32(&mut cursor), 15);
        let _ = fs::remove_dir_all(root);
    }

    fn read_test_i32(cursor: &mut Cursor<&[u8]>) -> i32 {
        let mut bytes = [0u8; 4];
        cursor.read_exact(&mut bytes).unwrap();
        i32::from_be_bytes(bytes)
    }

    fn read_test_string(cursor: &mut Cursor<&[u8]>) -> String {
        let len = read_test_i32(cursor);
        assert!(len >= 0);
        let mut bytes = vec![0u8; len as usize];
        cursor.read_exact(&mut bytes).unwrap();
        String::from_utf8(bytes).unwrap()
    }
}'''
if registry.count(test_close) != 1:
    raise SystemExit("registry test tail changed")
registry = registry.replace(test_close, new_tests, 1)
write(registry_path, registry)


# ---------------------------------------------------------------------------
# Rust runtime worker: own + join the registry receiver on every exit path.
# ---------------------------------------------------------------------------
worker_path = "desktop/src/runtime_worker.rs"
worker = read(worker_path)
workspace_marker = """struct WorkspacePlan {
    workspace: PathBuf,
    java: u32,
}
"""
guard = """struct WorkspacePlan {
    workspace: PathBuf,
    java: u32,
}

struct CaptureThreadGuard {
    cancel: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl CaptureThreadGuard {
    fn new(cancel: Arc<AtomicBool>, handle: thread::JoinHandle<()>) -> Self {
        Self {
            cancel,
            handle: Some(handle),
        }
    }
}

impl Drop for CaptureThreadGuard {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}
"""
if worker.count(workspace_marker) != 1:
    raise SystemExit("runtime worker WorkspacePlan marker changed")
worker = worker.replace(workspace_marker, guard, 1)

spawn_start = worker.index(
    "    let (listen_tx, listen_rx) = mpsc::sync_channel::<Result<()>>(1);"
)
spawn_end = worker.index("\n\n    listen_rx", spawn_start)
new_spawn = r'''    let (listen_tx, listen_rx) = mpsc::sync_channel::<Result<()>>(1);
    let (capture_tx, capture_rx) = mpsc::sync_channel::<Result<PathBuf>>(1);
    let (capture_progress_tx, capture_progress_rx) = mpsc::channel::<(usize, usize)>();
    let capture_root = cache_root.clone();
    let capture_version = version.clone();
    let capture_fingerprint = fingerprint.clone();
    let capture_cancel = Arc::new(AtomicBool::new(false));
    let capture_thread_cancel = capture_cancel.clone();
    let capture_handle = thread::spawn(move || {
        let mut announced = false;
        let result = registry::capture_once_cancellable(
            registry::DEFAULT_ADDRESS,
            &capture_root,
            &capture_version,
            &capture_fingerprint,
            capture_thread_cancel,
            |notice| match notice {
                registry::CaptureNotice::Listening(_) if !announced => {
                    announced = true;
                    let _ = listen_tx.send(Ok(()));
                }
                registry::CaptureNotice::Progress {
                    blocks,
                    total_blocks,
                } => {
                    let _ = capture_progress_tx.send((blocks, total_blocks));
                }
                _ => {}
            },
        );
        if !announced {
            let _ = listen_tx.send(Err(anyhow!(
                "registry receiver stopped before listening"
            )));
        }
        let _ = capture_tx.send(result);
    });
    let _capture_guard = CaptureThreadGuard::new(capture_cancel, capture_handle);'''
worker = worker[:spawn_start] + new_spawn + worker[spawn_end:]
write(worker_path, worker)


# ---------------------------------------------------------------------------
# Java export cleanup: expected entity-rendered chest models should be quiet,
# while explicit custom/resource-pack chest block models still win.
# ---------------------------------------------------------------------------
resolver_path = "engine/src/main/java/dev/kastrick/minesport/resolver/ResolverChain.java"
resolver = read(resolver_path)
old_sig = """    public BlockModel resolveModel(String modelPath) { return resolveModel(modelPath, new HashSet<>()); }

    private BlockModel resolveModel(String modelPath, Set<String> visited) {"""
new_sig = """    public BlockModel resolveModel(String modelPath) {
        return resolveModel(modelPath, new HashSet<>(), true);
    }

    public BlockModel resolveModelQuietly(String modelPath) {
        return resolveModel(modelPath, new HashSet<>(), false);
    }

    private BlockModel resolveModel(String modelPath, Set<String> visited, boolean warnMissing) {"""
if resolver.count(old_sig) != 1:
    raise SystemExit("ResolverChain resolveModel signature changed")
resolver = resolver.replace(old_sig, new_sig, 1)
if resolver.count("BlockModel parent = resolveModel(model.parentId, visited);") != 1:
    raise SystemExit("ResolverChain parent lookup changed")
resolver = resolver.replace(
    "BlockModel parent = resolveModel(model.parentId, visited);",
    "BlockModel parent = resolveModel(model.parentId, visited, warnMissing);",
    1,
)
old_missing = """        modelSources.put(normalized, "missing");
        if (missingModels.add(normalized)) System.err.println("[ResolverChain] No model found for: " + normalized);
        return null;"""
new_missing = """        modelSources.put(normalized, "missing");
        if (warnMissing && missingModels.add(normalized)) {
            System.err.println("[ResolverChain] No model found for: " + normalized);
        }
        return null;"""
if resolver.count(old_missing) != 1:
    raise SystemExit("ResolverChain missing-model tail changed")
resolver = resolver.replace(old_missing, new_missing, 1)
write(resolver_path, resolver)

geometry_path = "engine/src/main/java/dev/kastrick/minesport/export/GeometryBuilder.java"
geometry = read(geometry_path)
old_lookup = "            BlockModel model = resolvers.resolveModel(application.modelPath);"
new_lookup = """            BlockModel model = isVanillaChest(block)
                ? resolvers.resolveModelQuietly(application.modelPath)
                : resolvers.resolveModel(application.modelPath);"""
if geometry.count(old_lookup) != 1:
    raise SystemExit("GeometryBuilder model lookup changed")
geometry = geometry.replace(old_lookup, new_lookup, 1)
write(geometry_path, geometry)


# Keep the export front door consistent with WorldCopier's accepted region files.
ipc_path = "engine/src/main/java/dev/kastrick/minesport/IpcMode.java"
ipc = read(ipc_path)
old_region_list = 'File[] mcaFiles = regionDir.listFiles((directory, name) -> name.endsWith(".mca"));'
new_region_list = 'File[] mcaFiles = regionDir.listFiles((directory, name) -> name.endsWith(".mca") || name.endsWith(".mcr"));'
if ipc.count(old_region_list) != 1:
    raise SystemExit("IpcMode region listing changed")
ipc = ipc.replace(old_region_list, new_region_list, 1)
if ipc.count('error("No .mca region files found");') != 1:
    raise SystemExit("IpcMode no-region error changed")
ipc = ipc.replace('error("No .mca region files found");', 'error("No region files found");', 1)

# Keep user-visible export status terse. Detailed diagnostics still go to logs.
status_replacements = {
    'progressIndeterminate("Preparing selected world data");': 'progressIndeterminate("Preparing export…");',
    'progressIndeterminate("Scanning selected regions");': 'progressIndeterminate("Scanning regions…");',
    'progressIndeterminate("Preparing export data");': 'progressIndeterminate("Preparing export…");',
    'progressIndeterminate("Writing Litematica file");': 'progressIndeterminate("Writing Litematica…");',
    'progressIndeterminate("Cleaning temporary world data");': 'progressIndeterminate("Cleaning up…");',
    'progressIndeterminate("Publishing Litematica file");': 'progressIndeterminate("Finalizing…");',
}
for old, new in status_replacements.items():
    if old not in ipc:
        raise SystemExit(f"IpcMode status text changed: {old}")
    ipc = ipc.replace(old, new)
write(ipc_path, ipc)


# Fail closed on the invariants this pass owns.
checks = {
    registry_path: [
        "pub fn capture_once_cancellable",
        "struct StreamingSnapshotWriter",
        "set_nonblocking(true)",
        "set_read_timeout(Some(Duration::from_millis(250)))",
        "streaming_capture_patches_block_count_and_keeps_light_state",
    ],
    worker_path: [
        "struct CaptureThreadGuard",
        "registry::capture_once_cancellable",
        "let _capture_guard = CaptureThreadGuard::new",
    ],
    resolver_path: ["public BlockModel resolveModelQuietly"],
    geometry_path: ["resolvers.resolveModelQuietly(application.modelPath)"],
    ipc_path: [
        'name.endsWith(".mca") || name.endsWith(".mcr")',
        'progressIndeterminate("Writing Litematica…")',
    ],
}
for path, needles in checks.items():
    body = read(path)
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"missing stability invariant in {path}: {needle}")

print("Applied runtime registry stability and export cleanup")
