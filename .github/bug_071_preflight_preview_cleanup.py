from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


ipc_path = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
ipc = ipc_path.read_text(encoding="utf-8")

ipc = replace_once(
    ipc,
    '''    private static void handleListBlocks(JsonObject request) {''',
    '''    static boolean listBlocksNeedsPreviewAssets(String purpose) {
        return purpose == null || !purpose.trim().equalsIgnoreCase("preflight");
    }

    private static void handleListBlocks(JsonObject request) {''',
    "block-list purpose helper",
)

ipc = replace_once(
    ipc,
    '''        File tempWorldCopy = null;
        try {
            log("Preparing block list for 3D preview...");''',
    '''        String purpose = getString(request, "clientPurpose", "preview");
        boolean includePreviewAssets = listBlocksNeedsPreviewAssets(purpose);

        File tempWorldCopy = null;
        try {
            log(includePreviewAssets
                ? "Preparing block list for 3D preview..."
                : "Preparing block list for preflight...");''',
    "block-list purpose routing",
)

ipc = replace_once(
    ipc,
    '''            ResolverChain previewResolvers = buildPreviewResolverChain(request, worldFolder, tempWorldCopy);
            File previewTextureDir = Files.createTempDirectory("minesport_preview_textures_").toFile();
            previewTextureDir.deleteOnExit();
            Map<String, PreviewTextures> previewTextures = writePreviewTextures(allBlocks, previewResolvers, previewTextureDir);
            log("3D preview textures: " + previewTextures.size() + " block type(s) resolved");''',
    '''            Map<String, PreviewTextures> previewTextures = Collections.emptyMap();
            if (includePreviewAssets) {
                try (ResolverChain previewResolvers = buildPreviewResolverChain(request, worldFolder, tempWorldCopy)) {
                    File previewTextureDir = Files.createTempDirectory("minesport_preview_textures_").toFile();
                    previewTextureDir.deleteOnExit();
                    previewTextures = writePreviewTextures(allBlocks, previewResolvers, previewTextureDir);
                }
                log("3D preview textures: " + previewTextures.size() + " block type(s) resolved");
            } else {
                log("Preflight block list: preview asset resolution skipped");
            }''',
    "preflight preview asset fast path",
)

ipc = replace_once(
    ipc,
    '''                for (BlockData block : allBlocks) {
                    writer.beginObject();
                    writer.name("x").value(block.x);
                    writer.name("y").value(block.y);
                    writer.name("z").value(block.z);
                    writer.name("id").value(block.blockId);
                    PreviewTextures blockTextures = previewTextures.get(blockTextureKey(block));
                    if (blockTextures != null) {
                        if (blockTextures.top() != null) writer.name("textureTop").value(blockTextures.top());
                        if (blockTextures.side() != null) writer.name("textureSide").value(blockTextures.side());
                        if (blockTextures.bottom() != null) writer.name("textureBottom").value(blockTextures.bottom());
                    }
                    int[] color = dev.kastrick.minesport.region.HeightmapGenerator
                        .colorForBlock(block.blockId);
                    writer.name("r").value(color[0]);
                    writer.name("g").value(color[1]);
                    writer.name("b").value(color[2]);
                    writer.endObject();
                }''',
    '''                for (BlockData block : allBlocks) {
                    writer.beginObject();
                    if (includePreviewAssets) {
                        writer.name("x").value(block.x);
                        writer.name("y").value(block.y);
                        writer.name("z").value(block.z);
                    }
                    writer.name("id").value(block.blockId);
                    if (includePreviewAssets) {
                        PreviewTextures blockTextures = previewTextures.get(blockTextureKey(block));
                        if (blockTextures != null) {
                            if (blockTextures.top() != null) writer.name("textureTop").value(blockTextures.top());
                            if (blockTextures.side() != null) writer.name("textureSide").value(blockTextures.side());
                            if (blockTextures.bottom() != null) writer.name("textureBottom").value(blockTextures.bottom());
                        }
                        int[] color = dev.kastrick.minesport.region.HeightmapGenerator
                            .colorForBlock(block.blockId);
                        writer.name("r").value(color[0]);
                        writer.name("g").value(color[1]);
                        writer.name("b").value(color[2]);
                    }
                    writer.endObject();
                }''',
    "compact preflight block JSON",
)

ipc_path.write_text(ipc, encoding="utf-8")


test_path = Path("engine/src/test/java/dev/kastrick/minesport/IpcModeBlockListPurposeTest.java")
if test_path.exists():
    raise SystemExit(f"unexpected existing test: {test_path}")
test_path.write_text(
    '''package dev.kastrick.minesport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcModeBlockListPurposeTest {
    @Test
    void preflightSkipsPreviewAssets() {
        assertFalse(IpcMode.listBlocksNeedsPreviewAssets("preflight"));
        assertFalse(IpcMode.listBlocksNeedsPreviewAssets("  PreFlight  "));
    }

    @Test
    void previewAndLegacyRequestsKeepPreviewAssets() {
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets("preview"));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets(""));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets(null));
        assertTrue(IpcMode.listBlocksNeedsPreviewAssets("legacy-client"));
    }
}
''',
    encoding="utf-8",
)


preview_path = Path("desktop/src/preview.rs")
preview = preview_path.read_text(encoding="utf-8")
preview = replace_once(
    preview,
    '''    if blocks.is_empty() {
        bail!("No solid blocks were found in the current selection");
    }

    let mut by_id: HashMap<String, Vec<[i32; 3]>> = HashMap::new();''',
    '''    if blocks.is_empty() {
        bail!("No solid blocks were found in the current selection");
    }
    let scratch_dirs = preview_scratch_dirs(&blocks);

    let mut by_id: HashMap<String, Vec<[i32; 3]>> = HashMap::new();''',
    "collect preview scratch directories",
)

preview = replace_once(
    preview,
    '''    let scene = Arc::new(PreviewScene {
        blocks,
        by_id,
        by_position,
        occupied,
        texture_cache: Mutex::new(HashMap::new()),
    });
    render_scene(scene, PreviewCamera::isometric_compat(), None)
}

fn render_scene(scene: Arc<PreviewScene>, camera: PreviewCamera, highlight: HighlightBounds) -> Result<RenderedPreview> {''',
    '''    let scene = Arc::new(PreviewScene {
        blocks,
        by_id,
        by_position,
        occupied,
        texture_cache: Mutex::new(HashMap::new()),
    });
    let rendered = render_scene(scene, PreviewCamera::isometric_compat(), None);
    cleanup_preview_scratch_dirs(&scratch_dirs);
    rendered
}

fn preview_scratch_dirs(blocks: &[PreviewBlock]) -> HashSet<std::path::PathBuf> {
    let Ok(temp_root) = std::fs::canonicalize(std::env::temp_dir()) else {
        return HashSet::new();
    };
    let mut dirs = HashSet::new();
    for texture in blocks.iter().flat_map(|block| {
        [&block.texture_top, &block.texture_side, &block.texture_bottom]
    }) {
        if texture.trim().is_empty() {
            continue;
        }
        let Some(parent) = Path::new(texture).parent() else { continue; };
        let Some(name) = parent.file_name().and_then(|value| value.to_str()) else { continue; };
        if !name.starts_with("minesport_preview_textures_") {
            continue;
        }
        let Ok(canonical) = std::fs::canonicalize(parent) else { continue; };
        if canonical.parent() == Some(temp_root.as_path()) {
            dirs.insert(canonical);
        }
    }
    dirs
}

fn cleanup_preview_scratch_dirs(dirs: &HashSet<std::path::PathBuf>) {
    for dir in dirs {
        if let Err(error) = std::fs::remove_dir_all(dir) {
            eprintln!(
                "[preview] Could not remove preview texture scratch directory {}: {error}",
                dir.display()
            );
        }
    }
}

fn render_scene(scene: Arc<PreviewScene>, camera: PreviewCamera, highlight: HighlightBounds) -> Result<RenderedPreview> {''',
    "cleanup preview scratch directories",
)

preview = replace_once(
    preview,
    '''    #[test]
    fn rendered_preview_can_pick_group_flood_box_highlight_and_rerender_without_source_file() {''',
    '''    #[test]
    fn preview_texture_scratch_is_removed_after_first_render_without_touching_user_assets() {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let temp = std::env::temp_dir();
        let scratch_dir = temp.join(format!(
            "minesport_preview_textures_test-{}-{stamp}",
            std::process::id()
        ));
        let user_dir = temp.join(format!(
            "minesport-user-textures-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&scratch_dir).unwrap();
        fs::create_dir_all(&user_dir).unwrap();

        let scratch_texture = scratch_dir.join("stone.png");
        let user_texture = user_dir.join("glass.png");
        DynamicImage::ImageRgba8(RgbaImage::from_pixel(
            2,
            2,
            image::Rgba([120, 120, 120, 255]),
        )).save_with_format(&scratch_texture, ImageFormat::Png).unwrap();
        DynamicImage::ImageRgba8(RgbaImage::from_pixel(
            2,
            2,
            image::Rgba([190, 220, 230, 255]),
        )).save_with_format(&user_texture, ImageFormat::Png).unwrap();

        let block_list = temp.join(format!(
            "minesport-preview-texture-cleanup-{}-{stamp}.json",
            std::process::id()
        ));
        let scratch_texture = scratch_texture.display().to_string();
        let user_texture = user_texture.display().to_string();
        let payload = serde_json::json!([
            {
                "x": 0, "y": 64, "z": 0, "id": "minecraft:stone",
                "textureTop": scratch_texture.clone(),
                "textureSide": scratch_texture.clone(),
                "textureBottom": scratch_texture.clone(),
                "r": 120, "g": 120, "b": 120
            },
            {
                "x": 1, "y": 64, "z": 0, "id": "minecraft:glass",
                "textureTop": user_texture.clone(),
                "textureSide": user_texture.clone(),
                "textureBottom": user_texture.clone(),
                "r": 190, "g": 220, "b": 230
            }
        ]);
        fs::write(&block_list, serde_json::to_vec(&payload).unwrap()).unwrap();

        let rendered = render_file(&block_list).unwrap();
        assert!(!scratch_dir.exists());
        assert!(user_dir.exists());
        let rerendered = rendered.pick_map.orbit(12.0, -4.0).unwrap();
        assert_eq!(rerendered.block_count, 2);

        fs::remove_file(block_list).unwrap();
        fs::remove_dir_all(user_dir).unwrap();
    }

    #[test]
    fn rendered_preview_can_pick_group_flood_box_highlight_and_rerender_without_source_file() {''',
    "preview scratch cleanup regression test",
)

preview_path.write_text(preview, encoding="utf-8")


app_path = Path("desktop/src/app.rs")
app = app_path.read_text(encoding="utf-8")
app = replace_once(
    app,
    '''        ui.set_task_detail("Reading selected block set and preview assets…".into());''',
    '''        ui.set_task_detail("Reading selected block set…".into());''',
    "preflight status text",
)
app_path.write_text(app, encoding="utf-8")

print("BUG-071: preflight skips preview assets and preview scratch data is lifecycle-cleaned")
