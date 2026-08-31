from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/app.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''struct PreflightSummary {
    unique_ids: usize,
    compact: String,
    diagnostics: String,
}

fn analyze_preflight(path: &Path) -> Result<PreflightSummary> {
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let blocks: Vec<PreflightBlock> = serde_json::from_slice(&bytes).context("parse preflight block list")?;
    let mut counts: HashMap<String, usize> = HashMap::new();
    for block in &blocks { *counts.entry(block.id.clone()).or_default() += 1; }
    let mut common: Vec<_> = counts.iter().collect();
    common.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
    let top = common.iter().take(5).map(|(id, count)| format!("{id} × {count}")).collect::<Vec<_>>().join(" · ");
    let transparent = blocks.iter().filter(|block| looks_transparent(&block.id)).count();
    let shape_heavy = blocks.iter().filter(|block| looks_shape_heavy(&block.id)).count();
    let cube_like = blocks.iter().filter(|block| looks_cube_like(&block.id)).count();
    let total = blocks.len().max(1);
    let compact = format!("{} blocks · {} IDs · {} transparent/cutout · {} shape-heavy", blocks.len(), counts.len(), transparent, shape_heavy);
    let diagnostics = format!(
        "Preflight diagnostics:\\nSolid blocks: {}\\nUnique block states/types: {}\\nGeometry upper bound before culling/FLATTER: ~{} faces · ~{} vertices\\nMost common: {}\\nCube-like IDs: {} ({:.1}%)\\nTransparent/cutout-like IDs: {} ({:.1}%)\\nShape-heavy IDs: {} ({:.1}%)\\nExact faces saved are reported only after Java geometry compilation.",
        blocks.len(), counts.len(), blocks.len() * 6, blocks.len() * 24, if top.is_empty() { "—" } else { &top },
        cube_like, cube_like as f64 * 100.0 / total as f64,
        transparent, transparent as f64 * 100.0 / total as f64,
        shape_heavy, shape_heavy as f64 * 100.0 / total as f64,
    );
    Ok(PreflightSummary { unique_ids: counts.len(), compact, diagnostics })
}''',
    '''struct PreflightSummary {
    unique_ids: usize,
    compact: String,
    diagnostics: String,
}

#[derive(Default)]
struct PreflightStats {
    counts: HashMap<String, usize>,
    total: usize,
    transparent: usize,
    shape_heavy: usize,
    cube_like: usize,
}

struct PreflightVisitor;

impl<'de> serde::de::Visitor<'de> for PreflightVisitor {
    type Value = PreflightStats;

    fn expecting(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("a JSON array of preflight block IDs")
    }

    fn visit_seq<A>(self, mut sequence: A) -> std::result::Result<Self::Value, A::Error>
    where
        A: serde::de::SeqAccess<'de>,
    {
        let mut stats = PreflightStats::default();
        while let Some(block) = sequence.next_element::<PreflightBlock>()? {
            let id = block.id;
            stats.total = stats.total.saturating_add(1);
            if looks_transparent(&id) { stats.transparent = stats.transparent.saturating_add(1); }
            if looks_shape_heavy(&id) { stats.shape_heavy = stats.shape_heavy.saturating_add(1); }
            if looks_cube_like(&id) { stats.cube_like = stats.cube_like.saturating_add(1); }
            *stats.counts.entry(id).or_default() += 1;
        }
        Ok(stats)
    }
}

fn read_preflight_stats<R: std::io::Read>(reader: R) -> Result<PreflightStats> {
    let mut deserializer = serde_json::Deserializer::from_reader(reader);
    serde::de::Deserializer::deserialize_seq(&mut deserializer, PreflightVisitor)
        .context("parse preflight block list")
}

fn analyze_preflight(path: &Path) -> Result<PreflightSummary> {
    let file = fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    let stats = read_preflight_stats(std::io::BufReader::new(file))?;
    let PreflightStats { counts, total: block_count, transparent, shape_heavy, cube_like } = stats;
    let mut common: Vec<_> = counts.iter().collect();
    common.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
    let top = common.iter().take(5).map(|(id, count)| format!("{id} × {count}")).collect::<Vec<_>>().join(" · ");
    let total = block_count.max(1);
    let compact = format!("{} blocks · {} IDs · {} transparent/cutout · {} shape-heavy", block_count, counts.len(), transparent, shape_heavy);
    let diagnostics = format!(
        "Preflight diagnostics:\\nSolid blocks: {}\\nUnique block states/types: {}\\nGeometry upper bound before culling/FLATTER: ~{} faces · ~{} vertices\\nMost common: {}\\nCube-like IDs: {} ({:.1}%)\\nTransparent/cutout-like IDs: {} ({:.1}%)\\nShape-heavy IDs: {} ({:.1}%)\\nExact faces saved are reported only after Java geometry compilation.",
        block_count, counts.len(), block_count.saturating_mul(6), block_count.saturating_mul(24), if top.is_empty() { "—" } else { &top },
        cube_like, cube_like as f64 * 100.0 / total as f64,
        transparent, transparent as f64 * 100.0 / total as f64,
        shape_heavy, shape_heavy as f64 * 100.0 / total as f64,
    );
    Ok(PreflightSummary { unique_ids: counts.len(), compact, diagnostics })
}''',
    "stream preflight JSON into counters",
)

text = replace_once(
    text,
    '''    #[test]
    fn preflight_classifiers_are_conservative() {
        assert!(looks_transparent("minecraft:oak_leaves"));
        assert!(looks_shape_heavy("minecraft:oak_stairs"));
        assert!(looks_cube_like("minecraft:stone"));
    }''',
    '''    #[test]
    fn preflight_classifiers_are_conservative() {
        assert!(looks_transparent("minecraft:oak_leaves"));
        assert!(looks_shape_heavy("minecraft:oak_stairs"));
        assert!(looks_cube_like("minecraft:stone"));
    }

    #[test]
    fn preflight_json_streams_directly_into_summary_counters() {
        let payload = br#"[
            {"id":"minecraft:stone"},
            {"id":"minecraft:stone"},
            {"id":"minecraft:oak_leaves"},
            {"id":"minecraft:oak_stairs"}
        ]"#;
        let stats = read_preflight_stats(std::io::Cursor::new(payload)).unwrap();
        assert_eq!(stats.total, 4);
        assert_eq!(stats.counts.len(), 3);
        assert_eq!(stats.counts.get("minecraft:stone"), Some(&2));
        assert_eq!(stats.transparent, 1);
        assert_eq!(stats.shape_heavy, 1);
        assert_eq!(stats.cube_like, 2);
    }''',
    "streaming preflight regression test",
)

path.write_text(text, encoding="utf-8")
print("BUG-074: Rust preflight analysis streams the JSON array instead of materializing all blocks")
