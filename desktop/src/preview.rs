use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::{fs::File, path::Path};

const WIDTH: u32 = 1200;
const HEIGHT: u32 = 720;
const MAX_PREVIEW_BLOCKS: usize = 60_000;

#[derive(Debug, Clone, Deserialize)]
struct PreviewBlock {
    x: i32,
    y: i32,
    z: i32,
    #[serde(default = "default_color")]
    r: u8,
    #[serde(default = "default_color")]
    g: u8,
    #[serde(default = "default_color")]
    b: u8,
}

#[derive(Debug, Clone)]
pub struct RenderedPreview {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
    pub block_count: usize,
    pub rendered_count: usize,
}

pub fn render_file(path: &Path) -> Result<RenderedPreview> {
    let file = File::open(path).with_context(|| format!("open preview block list {}", path.display()))?;
    let mut blocks: Vec<PreviewBlock> = serde_json::from_reader(file)
        .with_context(|| format!("parse preview block list {}", path.display()))?;
    if blocks.is_empty() {
        bail!("No solid blocks were found in the current selection");
    }

    let block_count = blocks.len();
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
    clear(&mut pixels, [11, 16, 21, 255]);

    let center_x = WIDTH as i32 / 2;
    let base_y = (HEIGHT as f32 * 0.72) as i32;
    let center_world_x = (min_x + max_x) as f32 / 2.0;
    let center_world_z = (min_z + max_z) as f32 / 2.0;

    for block in &blocks {
        let dx = block.x as f32 - center_world_x;
        let dz = block.z as f32 - center_world_z;
        let dy = (block.y - min_y) as i32;
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

        fill_quad(&mut pixels, left, bottom, down_bottom, down_left, left_color);
        fill_quad(&mut pixels, bottom, right, down_right, down_bottom, right_color);
        fill_quad(&mut pixels, top, right, bottom, left, top_color);
    }

    Ok(RenderedPreview {
        width: WIDTH,
        height: HEIGHT,
        rgba: pixels,
        block_count,
        rendered_count: blocks.len(),
    })
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

fn fill_quad(pixels: &mut [u8], a: [i32; 2], b: [i32; 2], c: [i32; 2], d: [i32; 2], color: [u8; 4]) {
    fill_triangle(pixels, a, b, c, color);
    fill_triangle(pixels, a, c, d, color);
}

fn fill_triangle(pixels: &mut [u8], a: [i32; 2], b: [i32; 2], c: [i32; 2], color: [u8; 4]) {
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
            let inside = if area > 0 { w0 >= 0 && w1 >= 0 && w2 >= 0 } else { w0 <= 0 && w1 <= 0 && w2 <= 0 };
            if inside {
                let index = ((y as u32 * WIDTH + x as u32) * 4) as usize;
                pixels[index..index + 4].copy_from_slice(&color);
            }
        }
    }
}

fn edge(a: [i32; 2], b: [i32; 2], p: [i32; 2]) -> i64 {
    (p[0] - a[0]) as i64 * (b[1] - a[1]) as i64 - (p[1] - a[1]) as i64 * (b[0] - a[0]) as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shading_clamps() {
        assert_eq!(shade([250, 10, 20, 255], 2.0), [255, 20, 40, 255]);
    }

    #[test]
    fn triangle_edge_has_expected_orientation() {
        assert_ne!(edge([0, 0], [2, 0], [0, 2]), 0);
    }
}
