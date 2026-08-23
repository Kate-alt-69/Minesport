use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::{collections::{HashMap, HashSet, VecDeque}, fs::File, path::Path, sync::Arc};

pub const WIDTH: u32 = 1200;
pub const HEIGHT: u32 = 720;
const MAX_PREVIEW_BLOCKS: usize = 60_000;

#[derive(Debug, Clone, Deserialize)]
struct PreviewBlock {
    x: i32,
    y: i32,
    z: i32,
    #[serde(default)]
    id: String,
    #[serde(default = "default_color")]
    r: u8,
    #[serde(default = "default_color")]
    g: u8,
    #[serde(default = "default_color")]
    b: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreviewPick {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub id: String,
}

#[derive(Debug, Clone)]
pub struct PreviewPickMap {
    width: u32,
    height: u32,
    indices: Arc<Vec<i32>>,
    hits: Arc<Vec<PreviewPick>>,
    by_id: Arc<HashMap<String, Vec<[i32; 3]>>>,
    occupied: Arc<HashSet<[i32; 3]>>,
}

impl PreviewPickMap {
    pub fn pick(&self, x: u32, y: u32) -> Option<PreviewPick> {
        if x >= self.width || y >= self.height {
            return None;
        }
        let pixel = (y * self.width + x) as usize;
        let hit = *self.indices.get(pixel)?;
        if hit < 0 {
            return None;
        }
        self.hits.get(hit as usize).cloned()
    }

    pub fn coordinates_for_id(&self, id: &str) -> Vec<[i32; 3]> {
        self.by_id.get(id).cloned().unwrap_or_default()
    }

    /// Fyne parity: select face-connected solid blocks without crossing air.
    /// Block IDs are intentionally ignored, so a touching glass block and
    /// stone block are part of the same joined structure just like the old
    /// native viewer's FloodFill selection. The retired Go viewer treated a
    /// power below 1 as 1, so a zero value still selects the starting block.
    pub fn joined_blocks(&self, start: [i32; 3], max_blocks: usize) -> Vec<[i32; 3]> {
        if !self.occupied.contains(&start) {
            return Vec::new();
        }
        let max_blocks = max_blocks.max(1);
        const OFFSETS: [[i32; 3]; 6] = [
            [1, 0, 0], [-1, 0, 0],
            [0, 1, 0], [0, -1, 0],
            [0, 0, 1], [0, 0, -1],
        ];
        let mut queue = VecDeque::from([start]);
        let mut seen = HashSet::from([start]);
        let mut result = Vec::with_capacity(max_blocks.min(4096));
        while let Some(current) = queue.pop_front() {
            result.push(current);
            if result.len() >= max_blocks { break; }
            for offset in OFFSETS {
                let Some(x) = current[0].checked_add(offset[0]) else { continue; };
                let Some(y) = current[1].checked_add(offset[1]) else { continue; };
                let Some(z) = current[2].checked_add(offset[2]) else { continue; };
                let next = [x, y, z];
                if self.occupied.contains(&next) && seen.insert(next) {
                    queue.push_back(next);
                }
            }
        }
        result
    }

    pub fn dimensions(&self) -> (u32, u32) {
        (self.width, self.height)
    }
}

#[derive(Debug, Clone)]
pub struct RenderedPreview {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
    pub block_count: usize,
    pub rendered_count: usize,
    pub pick_map: PreviewPickMap,
}

pub fn render_file(path: &Path) -> Result<RenderedPreview> {
    let file = File::open(path).with_context(|| format!("open preview block list {}", path.display()))?;
    let mut blocks: Vec<PreviewBlock> = serde_json::from_reader(file)
        .with_context(|| format!("parse preview block list {}", path.display()))?;
    if blocks.is_empty() {
        bail!("No solid blocks were found in the current selection");
    }

    let block_count = blocks.len();
    let mut by_id: HashMap<String, Vec<[i32; 3]>> = HashMap::new();
    let mut occupied = HashSet::with_capacity(block_count);
    for block in &blocks {
        let coordinate = [block.x, block.y, block.z];
        occupied.insert(coordinate);
        by_id
            .entry(normalized_id(&block.id))
            .or_default()
            .push(coordinate);
    }

    blocks.sort_by_key(|block| (block.x + block.z, block.y, block.x));
    if blocks.len() > MAX_PREVIEW_BLOCKS {
        let stride = blocks.len().div_ceil(MAX_PREVIEW_BLOCKS);
        blocks = blocks.into_iter().step_by(stride).collect();
    }

    let min_x = blocks.iter().map(|block| block.x).min().unwrap_or(0);
    let max_x = blocks.iter().map(|block| block.x).max().unwrap_or(0);
    let min_y = blocks.iter().map(|block| block.y).min().unwrap_or(0);
    let max_y = blocks.iter().map(|block| block.y).max().unwrap_or(0);
    let min_z = blocks.iter().map(|block| block.z).min().unwrap_or(0);
    let max_z = blocks.iter().map(|block| block.z).max().unwrap_or(0);

    let extent_x = (max_x - min_x + 1).max(1) as f32;
    let extent_y = (max_y - min_y + 1).max(1) as f32;
    let extent_z = (max_z - min_z + 1).max(1) as f32;
    let horizontal_units = extent_x + extent_z;
    let vertical_units = (extent_x + extent_z) * 0.5 + extent_y * 1.35;
    let tile = ((WIDTH as f32 * 0.82 / horizontal_units)
        .min(HEIGHT as f32 * 0.82 / vertical_units)
        .clamp(2.0, 28.0)) as i32;
    let half_w = tile.max(2);
    let half_h = (tile / 2).max(1);
    let cube_h = tile.max(2);

    let mut pixels = vec![0u8; (WIDTH * HEIGHT * 4) as usize];
    let mut hit_indices = vec![-1i32; (WIDTH * HEIGHT) as usize];
    let mut hits = Vec::with_capacity(blocks.len());
    clear(&mut pixels, [11, 16, 21, 255]);

    let center_x = WIDTH as i32 / 2;
    let base_y = (HEIGHT as f32 * 0.72) as i32;
    let center_world_x = (min_x + max_x) as f32 / 2.0;
    let center_world_z = (min_z + max_z) as f32 / 2.0;

    for block in &blocks {
        let dx = block.x as f32 - center_world_x;
        let dz = block.z as f32 - center_world_z;
        let dy = block.y - min_y;
        let sx = center_x + ((dx - dz) * half_w as f32) as i32;
        let sy = base_y + ((dx + dz) * half_h as f32) as i32 - dy * cube_h;

        let top = [sx, sy - cube_h];
        let right = [sx + half_w, sy - cube_h + half_h];
        let bottom = [sx, sy - cube_h + half_h * 2];
        let left = [sx - half_w, sy - cube_h + half_h];
        let down_right = [right[0], right[1] + cube_h];
        let down_bottom = [bottom[0], bottom[1] + cube_h];
        let down_left = [left[0], left[1] + cube_h];

        let base = [block.r, block.g, block.b, 255];
        let top_color = shade(base, 1.12);
        let left_color = shade(base, 0.78);
        let right_color = shade(base, 0.92);

        let hit_index = hits.len() as i32;
        hits.push(PreviewPick {
            x: block.x,
            y: block.y,
            z: block.z,
            id: normalized_id(&block.id),
        });

        fill_quad(&mut pixels, &mut hit_indices, hit_index, left, bottom, down_bottom, down_left, left_color);
        fill_quad(&mut pixels, &mut hit_indices, hit_index, bottom, right, down_right, down_bottom, right_color);
        fill_quad(&mut pixels, &mut hit_indices, hit_index, top, right, bottom, left, top_color);
    }

    Ok(RenderedPreview {
        width: WIDTH,
        height: HEIGHT,
        rgba: pixels,
        block_count,
        rendered_count: blocks.len(),
        pick_map: PreviewPickMap {
            width: WIDTH,
            height: HEIGHT,
            indices: Arc::new(hit_indices),
            hits: Arc::new(hits),
            by_id: Arc::new(by_id),
            occupied: Arc::new(occupied),
        },
    })
}

fn normalized_id(id: &str) -> String {
    let id = id.trim();
    if id.is_empty() {
        "minecraft:unknown".to_string()
    } else {
        id.to_string()
    }
}

fn default_color() -> u8 { 170 }

fn clear(pixels: &mut [u8], color: [u8; 4]) {
    for pixel in pixels.chunks_exact_mut(4) {
        pixel.copy_from_slice(&color);
    }
}

fn shade(color: [u8; 4], factor: f32) -> [u8; 4] {
    [
        (color[0] as f32 * factor).clamp(0.0, 255.0) as u8,
        (color[1] as f32 * factor).clamp(0.0, 255.0) as u8,
        (color[2] as f32 * factor).clamp(0.0, 255.0) as u8,
        color[3],
    ]
}

fn fill_quad(
    pixels: &mut [u8],
    hit_indices: &mut [i32],
    hit_index: i32,
    a: [i32; 2],
    b: [i32; 2],
    c: [i32; 2],
    d: [i32; 2],
    color: [u8; 4],
) {
    fill_triangle(pixels, hit_indices, hit_index, a, b, c, color);
    fill_triangle(pixels, hit_indices, hit_index, a, c, d, color);
}

fn fill_triangle(
    pixels: &mut [u8],
    hit_indices: &mut [i32],
    hit_index: i32,
    a: [i32; 2],
    b: [i32; 2],
    c: [i32; 2],
    color: [u8; 4],
) {
    let min_x = a[0].min(b[0]).min(c[0]).clamp(0, WIDTH as i32 - 1);
    let max_x = a[0].max(b[0]).max(c[0]).clamp(0, WIDTH as i32 - 1);
    let min_y = a[1].min(b[1]).min(c[1]).clamp(0, HEIGHT as i32 - 1);
    let max_y = a[1].max(b[1]).max(c[1]).clamp(0, HEIGHT as i32 - 1);
    let area = edge(a, b, c);
    if area == 0 { return; }

    for y in min_y..=max_y {
        for x in min_x..=max_x {
            let point = [x, y];
            let w0 = edge(b, c, point);
            let w1 = edge(c, a, point);
            let w2 = edge(a, b, point);
            let inside = if area > 0 {
                w0 >= 0 && w1 >= 0 && w2 >= 0
            } else {
                w0 <= 0 && w1 <= 0 && w2 <= 0
            };
            if inside {
                let pixel = (y as u32 * WIDTH + x as u32) as usize;
                let rgba = pixel * 4;
                pixels[rgba..rgba + 4].copy_from_slice(&color);
                hit_indices[pixel] = hit_index;
            }
        }
    }
}

fn edge(a: [i32; 2], b: [i32; 2], p: [i32; 2]) -> i64 {
    (p[0] - a[0]) as i64 * (b[1] - a[1]) as i64
        - (p[1] - a[1]) as i64 * (b[0] - a[0]) as i64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, time::{SystemTime, UNIX_EPOCH}};

    #[test]
    fn shading_clamps() {
        assert_eq!(shade([250, 10, 20, 255], 2.0), [255, 20, 40, 255]);
    }

    #[test]
    fn triangle_edge_has_expected_orientation() {
        assert_ne!(edge([0, 0], [2, 0], [0, 2]), 0);
    }

    #[test]
    fn rendered_preview_can_pick_group_and_flood_joined_solids() {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("minesport-preview-{}-{stamp}.json", std::process::id()));
        fs::write(
            &path,
            r#"[
                {"x":0,"y":64,"z":0,"id":"minecraft:stone","r":120,"g":120,"b":120},
                {"x":1,"y":64,"z":0,"id":"minecraft:stone","r":120,"g":120,"b":120},
                {"x":0,"y":65,"z":0,"id":"minecraft:glass","r":190,"g":220,"b":230},
                {"x":8,"y":64,"z":8,"id":"minecraft:stone","r":120,"g":120,"b":120}
            ]"#,
        ).unwrap();
        let rendered = render_file(&path).unwrap();
        assert_eq!(rendered.block_count, 4);
        assert_eq!(rendered.pick_map.coordinates_for_id("minecraft:stone").len(), 3);
        let mut joined = rendered.pick_map.joined_blocks([0, 64, 0], 64);
        joined.sort_unstable();
        assert_eq!(joined, vec![[0, 64, 0], [0, 65, 0], [1, 64, 0]]);
        assert_eq!(rendered.pick_map.joined_blocks([0, 64, 0], 2).len(), 2);
        assert_eq!(rendered.pick_map.joined_blocks([0, 64, 0], 0), vec![[0, 64, 0]]);
        assert_eq!(rendered.pick_map.dimensions(), (WIDTH, HEIGHT));
        assert!(rendered.pick_map.indices.iter().any(|value| *value >= 0));
        let picked = rendered.pick_map.indices.iter().find(|value| **value >= 0).copied().unwrap();
        assert!(rendered.pick_map.hits.get(picked as usize).is_some());
        let _ = fs::remove_file(path);
    }
}
