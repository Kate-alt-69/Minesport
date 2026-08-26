from pathlib import Path

ROOT = Path('.')


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {count}: {old[:100]!r}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')


WORLD_CACHE_RS = r'''use crate::{diagnostics, world_context};
use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    env,
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    thread,
    time::{Duration, UNIX_EPOCH},
};

const CACHE_FORMAT_VERSION: u16 = 1;
const CHUNK_MAGIC: &[u8; 4] = b"MSCH";
const MAX_CHUNK_PAYLOAD: usize = 64 * 1024 * 1024;
const POLL_INTERVAL: Duration = Duration::from_secs(2);
static REFRESH_GENERATION: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Copy)]
enum ChunkKind {
    Block = 0,
    Entity = 1,
}

impl ChunkKind {
    fn prefix(self) -> &'static str {
        match self {
            Self::Block => "c",
            Self::Entity => "e",
        }
    }

    fn manifest_prefix(self) -> &'static str {
        match self {
            Self::Block => "block",
            Self::Entity => "entity",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct RegionFingerprint {
    modified_ns: u64,
    size: u64,
    chunks: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CacheManifest {
    format_version: u16,
    source_world: String,
    dimension: String,
    regions: BTreeMap<String, RegionFingerprint>,
}

impl CacheManifest {
    fn empty(world: &Path, dimension: &str) -> Self {
        Self {
            format_version: CACHE_FORMAT_VERSION,
            source_world: world.display().to_string(),
            dimension: dimension.to_string(),
            regions: BTreeMap::new(),
        }
    }
}

pub fn start(world_path: PathBuf, context: Option<world_context::WorldContext>) -> PathBuf {
    let root = cache_root(&world_path, context.as_ref());
    let generation = REFRESH_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    let worker_root = root.clone();
    let worker_world = world_path.clone();

    diagnostics::Logger::new("WORLD_CACHE").info(
        "WorldChunkCacheStart",
        "world chunk cache watcher started",
        &[
            ("world", world_path.display().to_string()),
            ("cache", root.display().to_string()),
            ("generation", generation.to_string()),
        ],
    );

    let _ = thread::Builder::new()
        .name("minesport-world-cache".to_string())
        .spawn(move || {
            let logger = diagnostics::Logger::new("WORLD_CACHE");
            while is_current(generation) {
                if let Err(error) = refresh_world(&worker_world, &worker_root, generation) {
                    logger.warn(
                        "WorldChunkCacheRefreshFailed",
                        "world chunk cache refresh failed",
                        &[("world", worker_world.display().to_string()), ("error", format!("{error:#}"))],
                    );
                }
                for _ in 0..20 {
                    if !is_current(generation) {
                        return;
                    }
                    thread::sleep(POLL_INTERVAL / 20);
                }
            }
        });
    root
}

fn is_current(generation: u64) -> bool {
    REFRESH_GENERATION.load(Ordering::SeqCst) == generation
}

fn cache_root(world_path: &Path, context: Option<&world_context::WorldContext>) -> PathBuf {
    let home = env::var_os("USERPROFILE")
        .or_else(|| env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(env::temp_dir);
    let mut root = home.join(".cache").join("kastrick's_software").join("minesport").join("wc");
    if let Some(context) = context {
        root.push(sanitize_component(&context.launcher));
        if !context.instance.trim().is_empty() {
            root.push(sanitize_component(&context.instance));
        }
        root.push(sanitize_component(&context.world_name));
    } else {
        root.push("_manual");
        root.push(sanitize_component(world_path.file_name().and_then(|value| value.to_str()).unwrap_or("world")));
    }
    root
}

fn sanitize_component(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        if ch.is_control() || matches!(ch, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*') {
            out.push('_');
        } else {
            out.push(ch);
        }
    }
    let trimmed = out.trim().trim_matches('.').trim().to_string();
    if trimmed.is_empty() { "_".to_string() } else { trimmed }
}

fn refresh_world(world: &Path, cache_root: &Path, generation: u64) -> Result<()> {
    fs::create_dir_all(cache_root.join("diem"))
        .with_context(|| format!("create world cache {}", cache_root.display()))?;
    for (dimension, dimension_id) in [("overworld", 0u8), ("nether", 1u8), ("end", 2u8)] {
        if !is_current(generation) { return Ok(()); }
        let destination = cache_root.join("diem").join(dimension);
        match vanilla_dimension_root(world, dimension) {
            Some(storage_root) => refresh_dimension(world, dimension, dimension_id, &storage_root, &destination, generation)?,
            None => { if destination.exists() { let _ = fs::remove_dir_all(&destination); } }
        }
    }
    Ok(())
}

fn vanilla_dimension_root(world: &Path, dimension: &str) -> Option<PathBuf> {
    let candidates: Vec<PathBuf> = match dimension {
        "overworld" => vec![world.to_path_buf(), world.join("dimensions").join("minecraft").join("overworld")],
        "nether" => vec![world.join("DIM-1"), world.join("dimensions").join("minecraft").join("the_nether")],
        "end" => vec![world.join("DIM1"), world.join("dimensions").join("minecraft").join("the_end")],
        _ => Vec::new(),
    };
    candidates.into_iter().find(|candidate| contains_region_files(&candidate.join("region")))
}

fn contains_region_files(path: &Path) -> bool {
    let Ok(entries) = fs::read_dir(path) else { return false; };
    entries.flatten().any(|entry| {
        entry.file_type().is_ok_and(|kind| kind.is_file())
            && entry.path().extension().and_then(|value| value.to_str())
                .is_some_and(|extension| extension.eq_ignore_ascii_case("mca") || extension.eq_ignore_ascii_case("mcr"))
    })
}

fn refresh_dimension(world: &Path, dimension: &str, dimension_id: u8, storage_root: &Path, destination: &Path, generation: u64) -> Result<()> {
    fs::create_dir_all(destination).with_context(|| format!("create dimension cache {}", destination.display()))?;
    let manifest_path = destination.join("_manifest.json");
    let previous = load_manifest(&manifest_path)
        .filter(|manifest| manifest.format_version == CACHE_FORMAT_VERSION)
        .unwrap_or_else(|| CacheManifest::empty(world, dimension));
    let sources = source_regions(storage_root)?;
    let mut source_fingerprints = BTreeMap::<String, RegionFingerprint>::new();
    for (kind, path) in &sources {
        source_fingerprints.insert(manifest_key(*kind, path), basic_fingerprint(path)?);
    }
    let previous_shape = previous.regions.iter().map(|(key, fp)| (key.clone(), (fp.modified_ns, fp.size))).collect::<BTreeMap<_, _>>();
    let current_shape = source_fingerprints.iter().map(|(key, fp)| (key.clone(), (fp.modified_ns, fp.size))).collect::<BTreeMap<_, _>>();
    if previous.format_version == CACHE_FORMAT_VERSION && manifest_path.is_file() && previous_shape == current_shape {
        return Ok(());
    }

    let _ = fs::remove_file(&manifest_path);
    let mut next = CacheManifest::empty(world, dimension);
    let mut current_keys = BTreeSet::new();
    for (kind, region_path) in sources {
        if !is_current(generation) { return Ok(()); }
        let key = manifest_key(kind, &region_path);
        current_keys.insert(key.clone());
        let basic = basic_fingerprint(&region_path)?;
        if let Some(old) = previous.regions.get(&key) {
            if old.modified_ns == basic.modified_ns && old.size == basic.size {
                next.regions.insert(key, old.clone());
                continue;
            }
        }
        let (region_x, region_z) = region_coordinates(&region_path)
            .ok_or_else(|| anyhow!("invalid region filename: {}", region_path.display()))?;
        purge_region_chunks(destination, kind, region_x, region_z)?;
        let chunks = index_region(&region_path, destination, kind, dimension_id, generation)?;
        next.regions.insert(key, RegionFingerprint { modified_ns: basic.modified_ns, size: basic.size, chunks });
    }
    for old_key in previous.regions.keys() {
        if current_keys.contains(old_key) { continue; }
        if let Some((kind, region_x, region_z)) = manifest_region(old_key) {
            purge_region_chunks(destination, kind, region_x, region_z)?;
        }
    }
    if !is_current(generation) { return Ok(()); }
    write_manifest(&manifest_path, &next)?;
    diagnostics::Logger::new("WORLD_CACHE").info(
        "WorldChunkCacheDimensionReady",
        "vanilla dimension chunk cache ready",
        &[("dimension", dimension.to_string()), ("cache", destination.display().to_string()), ("regions", next.regions.len().to_string())],
    );
    Ok(())
}

fn source_regions(storage_root: &Path) -> Result<Vec<(ChunkKind, PathBuf)>> {
    let mut result = Vec::new();
    collect_region_files(&storage_root.join("region"), ChunkKind::Block, &mut result)?;
    collect_region_files(&storage_root.join("entities"), ChunkKind::Entity, &mut result)?;
    result.sort_by(|left, right| left.1.cmp(&right.1));
    Ok(result)
}

fn collect_region_files(directory: &Path, kind: ChunkKind, out: &mut Vec<(ChunkKind, PathBuf)>) -> Result<()> {
    if !directory.is_dir() { return Ok(()); }
    for entry in fs::read_dir(directory).with_context(|| format!("read region directory {}", directory.display()))? {
        let entry = entry?;
        if !entry.file_type()?.is_file() { continue; }
        let path = entry.path();
        let supported = path.extension().and_then(|value| value.to_str())
            .is_some_and(|extension| extension.eq_ignore_ascii_case("mca") || extension.eq_ignore_ascii_case("mcr"));
        if supported && region_coordinates(&path).is_some() { out.push((kind, path)); }
    }
    Ok(())
}

fn basic_fingerprint(path: &Path) -> Result<RegionFingerprint> {
    let metadata = fs::metadata(path).with_context(|| format!("stat region {}", path.display()))?;
    let modified_ns = metadata.modified().ok().and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_nanos().min(u64::MAX as u128) as u64).unwrap_or(0);
    Ok(RegionFingerprint { modified_ns, size: metadata.len(), chunks: 0 })
}

fn manifest_key(kind: ChunkKind, path: &Path) -> String {
    format!("{}:{}", kind.manifest_prefix(), path.file_name().and_then(|value| value.to_str()).unwrap_or("region"))
}

fn manifest_region(key: &str) -> Option<(ChunkKind, i32, i32)> {
    let (kind, name) = key.split_once(':')?;
    let kind = match kind { "block" => ChunkKind::Block, "entity" => ChunkKind::Entity, _ => return None };
    let parts = name.split('.').collect::<Vec<_>>();
    if parts.len() < 4 || parts[0] != "r" { return None; }
    Some((kind, parts[1].parse().ok()?, parts[2].parse().ok()?))
}

fn region_coordinates(path: &Path) -> Option<(i32, i32)> {
    let name = path.file_name()?.to_str()?;
    let parts = name.split('.').collect::<Vec<_>>();
    if parts.len() < 4 || parts[0] != "r" { return None; }
    Some((parts[1].parse().ok()?, parts[2].parse().ok()?))
}

fn purge_region_chunks(destination: &Path, kind: ChunkKind, region_x: i32, region_z: i32) -> Result<()> {
    for local_z in 0..32 {
        for local_x in 0..32 {
            let chunk_x = region_x * 32 + local_x;
            let chunk_z = region_z * 32 + local_z;
            let path = destination.join(format!("{}.{}.{}.chunk", kind.prefix(), chunk_x, chunk_z));
            if path.exists() { fs::remove_file(&path).with_context(|| format!("remove stale chunk cache {}", path.display()))?; }
        }
    }
    Ok(())
}

fn index_region(region_path: &Path, destination: &Path, kind: ChunkKind, dimension_id: u8, generation: u64) -> Result<usize> {
    let (region_x, region_z) = region_coordinates(region_path).ok_or_else(|| anyhow!("invalid region filename: {}", region_path.display()))?;
    let mut file = File::open(region_path).with_context(|| format!("open region {}", region_path.display()))?;
    let file_len = file.metadata()?.len();
    if file_len < 8192 { return Ok(0); }
    let mut header = [0u8; 8192];
    file.read_exact(&mut header)?;
    let mut written = 0usize;
    for index in 0..1024usize {
        if !is_current(generation) { return Ok(written); }
        let offset_index = index * 4;
        let sector_offset = ((header[offset_index] as u32) << 16) | ((header[offset_index + 1] as u32) << 8) | header[offset_index + 2] as u32;
        let sector_count = header[offset_index + 3] as u32;
        if sector_offset == 0 || sector_count == 0 { continue; }
        let chunk_x = region_x * 32 + (index as i32 % 32);
        let chunk_z = region_z * 32 + (index as i32 / 32);
        let timestamp_index = 4096 + offset_index;
        let timestamp = u32::from_be_bytes([header[timestamp_index], header[timestamp_index + 1], header[timestamp_index + 2], header[timestamp_index + 3]]);
        let seek = sector_offset as u64 * 4096;
        if seek + 5 > file_len { continue; }
        file.seek(SeekFrom::Start(seek))?;
        let mut length_bytes = [0u8; 4];
        file.read_exact(&mut length_bytes)?;
        let data_length = i32::from_be_bytes(length_bytes);
        if data_length <= 0 { continue; }
        let mut compression = [0u8; 1];
        file.read_exact(&mut compression)?;
        let external = compression[0] & 0x80 != 0;
        let compression_type = compression[0] & 0x7f;
        let payload = if external {
            let external_path = region_path.parent().unwrap_or_else(|| Path::new(".")).join(format!("c.{}.{}.mcc", chunk_x, chunk_z));
            match fs::read(&external_path) { Ok(bytes) => bytes, Err(_) => continue }
        } else {
            if data_length <= 1 { continue; }
            let payload_length = (data_length - 1) as usize;
            let allocated = sector_count as usize * 4096;
            if payload_length == 0 || payload_length > MAX_CHUNK_PAYLOAD || payload_length + 5 > allocated || seek + 5 + payload_length as u64 > file_len { continue; }
            let mut bytes = vec![0u8; payload_length];
            file.read_exact(&mut bytes)?;
            bytes
        };
        if payload.is_empty() || payload.len() > MAX_CHUNK_PAYLOAD { continue; }
        write_chunk(destination, kind, dimension_id, chunk_x, chunk_z, timestamp, compression_type, &payload)?;
        written += 1;
    }
    Ok(written)
}

fn write_chunk(destination: &Path, kind: ChunkKind, dimension_id: u8, chunk_x: i32, chunk_z: i32, timestamp: u32, compression_type: u8, payload: &[u8]) -> Result<()> {
    let target = destination.join(format!("{}.{}.{}.chunk", kind.prefix(), chunk_x, chunk_z));
    let temporary = target.with_extension("chunk.tmp");
    let mut output = File::create(&temporary).with_context(|| format!("create chunk cache {}", temporary.display()))?;
    output.write_all(CHUNK_MAGIC)?;
    output.write_all(&CACHE_FORMAT_VERSION.to_be_bytes())?;
    output.write_all(&[kind as u8, dimension_id])?;
    output.write_all(&chunk_x.to_be_bytes())?;
    output.write_all(&chunk_z.to_be_bytes())?;
    output.write_all(&(timestamp as u64).to_be_bytes())?;
    output.write_all(&[compression_type])?;
    output.write_all(&(payload.len() as u32).to_be_bytes())?;
    output.write_all(payload)?;
    output.flush()?;
    drop(output);
    if target.exists() { let _ = fs::remove_file(&target); }
    fs::rename(&temporary, &target).with_context(|| format!("publish chunk cache {}", target.display()))?;
    Ok(())
}

fn load_manifest(path: &Path) -> Option<CacheManifest> {
    let bytes = fs::read(path).ok()?;
    serde_json::from_slice(&bytes).ok()
}

fn write_manifest(path: &Path, manifest: &CacheManifest) -> Result<()> {
    let temporary = path.with_extension("json.tmp");
    let encoded = serde_json::to_vec_pretty(manifest)?;
    fs::write(&temporary, encoded).with_context(|| format!("write cache manifest {}", temporary.display()))?;
    if path.exists() { let _ = fs::remove_file(path); }
    fs::rename(&temporary, path).with_context(|| format!("publish cache manifest {}", path.display()))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sanitizes_only_path_hostile_characters() {
        assert_eq!(sanitize_component("Freesm Launcher"), "Freesm Launcher");
        assert_eq!(sanitize_component("world:name?"), "world_name_");
        assert_eq!(sanitize_component("..."), "_");
    }
    #[test]
    fn parses_negative_region_coordinates() {
        assert_eq!(region_coordinates(Path::new("r.-3.7.mca")), Some((-3, 7)));
    }
}
'''

CACHE_MATERIALIZER = r'''package dev.kastrick.minesport.cache;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class ChunkCacheMaterializer {
    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SIZE = SECTOR_SIZE * 2;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;
    private ChunkCacheMaterializer() {}

    public static boolean materialize(File cacheDir, File worldFolder, File tempDir, int minX, int minZ, int maxX, int maxZ, boolean includeEntities, Consumer<String> log) throws IOException {
        if (cacheDir == null || !new File(cacheDir, "_manifest.json").isFile()) throw new IOException("world chunk cache is not ready");
        Files.createDirectories(tempDir.toPath());
        copyIfPresent(new File(worldFolder, "level.dat"), new File(tempDir, "level.dat"));
        copyIfPresent(new File(worldFolder, "level.dat_old"), new File(tempDir, "level.dat_old"));
        int minChunkX = Math.floorDiv(Math.min(minX, maxX), 16);
        int maxChunkX = Math.floorDiv(Math.max(minX, maxX), 16);
        int minChunkZ = Math.floorDiv(Math.min(minZ, maxZ), 16);
        int maxChunkZ = Math.floorDiv(Math.max(minZ, maxZ), 16);
        List<CachedChunk> blocks = collect(cacheDir, 'c', minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        if (blocks.isEmpty()) throw new IOException("world chunk cache has no block chunks for this selection");
        materializeKind(blocks, new File(tempDir, "region"));
        List<CachedChunk> entities = includeEntities ? collect(cacheDir, 'e', minChunkX, maxChunkX, minChunkZ, maxChunkZ) : List.of();
        if (!entities.isEmpty()) materializeKind(entities, new File(tempDir, "entities"));
        if (log != null) log.accept("Chunk cache: " + blocks.size() + " block chunk(s)" + (includeEntities ? " · " + entities.size() + " entity chunk(s)" : ""));
        return !entities.isEmpty();
    }

    private static List<CachedChunk> collect(File cacheDir, char prefix, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) throws IOException {
        List<CachedChunk> result = new ArrayList<>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                File file = new File(cacheDir, prefix + "." + chunkX + "." + chunkZ + ".chunk");
                if (!file.isFile()) continue;
                CachedChunk chunk = read(file);
                int expectedKind = prefix == 'c' ? 0 : 1;
                if (chunk.kind() != expectedKind || chunk.chunkX() != chunkX || chunk.chunkZ() != chunkZ) throw new IOException("chunk cache header does not match " + file.getName());
                result.add(chunk);
            }
        }
        result.sort(Comparator.comparingInt(CachedChunk::chunkZ).thenComparingInt(CachedChunk::chunkX));
        return result;
    }

    private static CachedChunk read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            byte[] magic = in.readNBytes(4);
            if (!Arrays.equals(magic, new byte[] {'M', 'S', 'C', 'H'})) throw new IOException("invalid chunk cache magic: " + file.getName());
            int version = in.readUnsignedShort();
            if (version != FORMAT_VERSION) throw new IOException("unsupported chunk cache version " + version);
            int kind = in.readUnsignedByte();
            int dimension = in.readUnsignedByte();
            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            long timestamp = in.readLong();
            int compression = in.readUnsignedByte();
            int length = in.readInt();
            if (length <= 0 || length > MAX_PAYLOAD) throw new IOException("invalid chunk cache payload length " + length);
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) throw new EOFException("truncated chunk cache payload: " + file.getName());
            return new CachedChunk(kind, dimension, chunkX, chunkZ, timestamp, compression, payload);
        }
    }

    private static void materializeKind(List<CachedChunk> chunks, File targetDir) throws IOException {
        Files.createDirectories(targetDir.toPath());
        Map<Long, List<CachedChunk>> byRegion = new LinkedHashMap<>();
        for (CachedChunk chunk : chunks) {
            int regionX = Math.floorDiv(chunk.chunkX(), 32);
            int regionZ = Math.floorDiv(chunk.chunkZ(), 32);
            long key = (((long) regionX) << 32) ^ (regionZ & 0xffffffffL);
            byRegion.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
        }
        for (List<CachedChunk> regionChunks : byRegion.values()) {
            CachedChunk first = regionChunks.get(0);
            int regionX = Math.floorDiv(first.chunkX(), 32);
            int regionZ = Math.floorDiv(first.chunkZ(), 32);
            writeRegion(new File(targetDir, "r." + regionX + "." + regionZ + ".mca"), regionChunks);
        }
    }

    private static void writeRegion(File output, List<CachedChunk> chunks) throws IOException {
        chunks.sort(Comparator.comparingInt(chunk -> localIndex(chunk.chunkX(), chunk.chunkZ())));
        byte[] header = new byte[HEADER_SIZE];
        int nextSector = 2;
        List<Placement> placements = new ArrayList<>();
        for (CachedChunk chunk : chunks) {
            int totalBytes = 5 + chunk.payload().length;
            int sectors = (totalBytes + SECTOR_SIZE - 1) / SECTOR_SIZE;
            if (sectors <= 0 || sectors > 255) throw new IOException("cached chunk " + chunk.chunkX() + "," + chunk.chunkZ() + " requires external Anvil storage; falling back to the world save");
            int index = localIndex(chunk.chunkX(), chunk.chunkZ());
            writeInt(header, index * 4, (nextSector << 8) | sectors);
            writeInt(header, SECTOR_SIZE + index * 4, (int) Math.min(0xffffffffL, chunk.timestamp()));
            placements.add(new Placement(chunk, nextSector, sectors));
            nextSector += sectors;
        }
        try (RandomAccessFile raf = new RandomAccessFile(output, "rw")) {
            raf.setLength((long) nextSector * SECTOR_SIZE);
            raf.seek(0); raf.write(header);
            byte[] zero = new byte[SECTOR_SIZE];
            for (Placement placement : placements) {
                CachedChunk chunk = placement.chunk();
                raf.seek((long) placement.sector() * SECTOR_SIZE);
                raf.writeInt(chunk.payload().length + 1);
                raf.writeByte(chunk.compression());
                raf.write(chunk.payload());
                int padding = placement.sectors() * SECTOR_SIZE - (5 + chunk.payload().length);
                while (padding > 0) { int count = Math.min(padding, zero.length); raf.write(zero, 0, count); padding -= count; }
            }
        }
    }

    private static int localIndex(int chunkX, int chunkZ) { return Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32); }
    private static void writeInt(byte[] bytes, int offset, int value) { bytes[offset] = (byte)(value >>> 24); bytes[offset + 1] = (byte)(value >>> 16); bytes[offset + 2] = (byte)(value >>> 8); bytes[offset + 3] = (byte)value; }
    private static void copyIfPresent(File source, File destination) throws IOException { if (source.isFile()) Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    private record CachedChunk(int kind, int dimension, int chunkX, int chunkZ, long timestamp, int compression, byte[] payload) {}
    private record Placement(CachedChunk chunk, int sector, int sectors) {}
}
'''

write('desktop/src/world_cache.rs', WORLD_CACHE_RS)
write('engine/src/main/java/dev/kastrick/minesport/cache/ChunkCacheMaterializer.java', CACHE_MATERIALIZER)
replace_once('desktop/src/main.rs', 'mod world_context;\nmod world_picker;', 'mod world_cache;\nmod world_context;\nmod world_picker;')
replace_once('desktop/src/app.rs', '    viewer_selection, world_context, world_picker,', '    viewer_selection, world_cache, world_context, world_picker,')
replace_once('desktop/src/app.rs', '    selected_loader: Option<String>,\n    preview_pick_map: Option<preview::PreviewPickMap>,', '    selected_loader: Option<String>,\n    world_cache_root: Option<PathBuf>,\n    preview_pick_map: Option<preview::PreviewPickMap>,')
replace_once('desktop/src/app.rs', '        let display = path.display().to_string();\n        let context_line = discovered.as_ref().map(|context| {', '        let world_cache_root = world_cache::start(path.clone(), discovered.clone());\n        let display = path.display().to_string();\n        let context_line = discovered.as_ref().map(|context| {')
replace_once('desktop/src/app.rs', '            guard.selected_loader = Some(loader.clone());\n            guard.preview_pick_map = None;', '            guard.selected_loader = Some(loader.clone());\n            guard.world_cache_root = Some(world_cache_root.clone());\n            guard.preview_pick_map = None;')
replace_once('desktop/src/app.rs', '        add_bubble_fields(&ui, &mut request);', '        if let Some(cache_root) = state.lock().ok().and_then(|guard| guard.world_cache_root.clone()) {\n            let dimension_cache = cache_root.join("diem").join("overworld");\n            request["worldCachePath"] = Value::String(dimension_cache.display().to_string());\n        }\n        add_bubble_fields(&ui, &mut request);')
replace_once('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', 'import dev.kastrick.minesport.export.*;\nimport dev.kastrick.minesport.region.*;', 'import dev.kastrick.minesport.export.*;\nimport dev.kastrick.minesport.cache.ChunkCacheMaterializer;\nimport dev.kastrick.minesport.region.*;')
old_prepare = '''            progressIndeterminate("Preparing selected world data");
            tempDir = WorldCopier.copyToTemp(
                worldFolder,
                copyMinX, copyMinZ,
                copyMaxX, copyMaxZ,
                IpcMode::log
            );
            boolean separateEntityRegions = format.equals("litematic")
                && WorldCopier.copyOverworldEntitiesToTemp(
                    worldFolder,
                    tempDir,
                    copyMinX, copyMinZ,
                    copyMaxX, copyMaxZ,
                    IpcMode::log
                );
            progressIndeterminate("Scanning selected regions");'''
new_prepare = '''            progressIndeterminate("Preparing selected world data");
            boolean separateEntityRegions = false;
            boolean cacheUsed = false;
            String worldCachePath = getString(request, "worldCachePath", "").trim();
            File worldCacheDir = worldCachePath.isEmpty() ? null : new File(worldCachePath);
            if (worldCacheDir != null && new File(worldCacheDir, "_manifest.json").isFile()) {
                try {
                    tempDir = Files.createTempDirectory("minesport-cache-export-").toFile();
                    separateEntityRegions = ChunkCacheMaterializer.materialize(worldCacheDir, worldFolder, tempDir, copyMinX, copyMinZ, copyMaxX, copyMaxZ, format.equals("litematic"), IpcMode::log);
                    cacheUsed = true;
                    log("Using world chunk cache");
                } catch (Exception cacheError) {
                    if (tempDir != null) WorldCopier.cleanupTemp(tempDir);
                    tempDir = null;
                    log("[WARN] Chunk cache unavailable for this export; reading the world save: " + cacheError.getMessage());
                }
            }
            if (!cacheUsed) {
                tempDir = WorldCopier.copyToTemp(worldFolder, copyMinX, copyMinZ, copyMaxX, copyMaxZ, IpcMode::log);
                separateEntityRegions = format.equals("litematic") && WorldCopier.copyOverworldEntitiesToTemp(worldFolder, tempDir, copyMinX, copyMinZ, copyMaxX, copyMaxZ, IpcMode::log);
            }
            progressIndeterminate("Scanning selected regions");'''
replace_once('engine/src/main/java/dev/kastrick/minesport/IpcMode.java', old_prepare, new_prepare)

paths = [Path('build.ps1'), Path('build.sh'), Path('desktop'), Path('engine'), Path('installer'), Path('minesport-bridge-fabric'), Path('minesport-bridge-forge'), Path('minesport-bridge-neoforge'), Path('minesport-bridge-quilt'), Path('minesport-bridge-fabric-versions/manifest.json'), Path('.github/workflows/build.yml')]
for root in paths:
    files = [root] if root.is_file() else list(root.rglob('*'))
    for file in files:
        if not file.is_file() or any(part in {'build', 'target', 'dist', '.gradle'} for part in file.parts): continue
        try: text = file.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError): continue
        if '0.2.0' in text: file.write_text(text.replace('0.2.0', '0.2.1'), encoding='utf-8')

release = ROOT / 'doc/releases/0.2.1.md'
release.parent.mkdir(parents=True, exist_ok=True)
release.write_text("""# Minesport 0.2.1\n\n## World chunk cache foundation\n\n- Adds a persistent background world cache under `~/.cache/kastrick's_software/minesport/wc/...`.\n- Cache identity follows launcher / instance / world and then `diem/<overworld|nether|end>`.\n- Vanilla Overworld, Nether, and End are indexed; modded dimensions are intentionally deferred.\n- Region files are split into coordinate-addressed `.chunk` records (`c.X.Z.chunk` and `e.X.Z.chunk`).\n- The selected world is watched in the background and changed regions refresh their derived chunk files.\n- Export prefers the ready chunk cache and falls back to the authoritative Minecraft save whenever the cache is incomplete or cannot represent a selected chunk.\n\nMinecraft region files remain the source of truth; the Minesport cache is disposable derived data.\n""", encoding='utf-8')
changelog = ROOT / 'CHANGELOG.md'
if changelog.exists():
    text = changelog.read_text(encoding='utf-8')
    if '## 0.2.1' not in text:
        changelog.write_text('## 0.2.1\n\n- Added background vanilla-dimension `.chunk` world cache and cache-backed export fallback.\n- Bumped Minesport desktop, engine, bridges and packaging to 0.2.1.\n\n' + text, encoding='utf-8')
print('Applied Minesport 0.2.1 world chunk cache patch')
