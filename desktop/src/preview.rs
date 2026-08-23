use crate::preview_picking;
use anyhow::{Context, Result, anyhow, bail};
use image::{DynamicImage, ImageFormat, RgbaImage};
use serde::Deserialize;
use std::{
    collections::{HashMap, HashSet, VecDeque},
    f32::consts::{FRAC_PI_4, PI},
    fs::File,
    path::Path,
    sync::{Arc, Mutex},
};

pub const WIDTH: u32 = 1200;
pub const HEIGHT: u32 = 720;
const MAX_PREVIEW_BLOCKS: usize = 60_000;
const PREVIEW_TILE_SIZE: usize = 16;
const ALPHA_DISCARD_THRESHOLD: u8 = 13;
const ISOMETRIC_PITCH: f32 = -0.615_479_7;
const HORIZONTAL_CAMERA_SCALE: f32 = 1.414_213_5;
const VERTICAL_CAMERA_SCALE: f32 = 1.224_744_9;
const CAMERA_LOOK_SENSITIVITY: f32 = 0.0025;
const MAX_CAMERA_PITCH: f32 = PI / 2.0 - 0.01;
const HIGHLIGHT_INFLATE: f32 = 0.03;
const HIGHLIGHT_COLOR: [u8; 4] = [64, 255, 115, 153];

type HighlightBounds = Option<([i32; 3], [i32; 3])>;

#[derive(Debug, Clone, Copy, Default)]
struct Vec3 {
    x: f32,
    y: f32,
    z: f32,
}

impl Vec3 {
    fn add(self, other: Self) -> Self {
        Self { x: self.x + other.x, y: self.y + other.y, z: self.z + other.z }
    }

    fn sub(self, other: Self) -> Self {
        Self { x: self.x - other.x, y: self.y - other.y, z: self.z - other.z }
    }

    fn scale(self, scalar: f32) -> Self {
        Self { x: self.x * scalar, y: self.y * scalar, z: self.z * scalar }
    }

    fn dot(self, other: Self) -> f32 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    fn cross(self, other: Self) -> Self {
        Self {
            x: self.y * other.z - self.z * other.y,
            y: self.z * other.x - self.x * other.z,
            z: self.x * other.y - self.y * other.x,
        }
    }

    fn normalize(self) -> Self {
        let length = (self.x * self.x + self.y * self.y + self.z * self.z).sqrt();
        if length < 1.0e-6 {
            Self::default()
        } else {
            Self { x: self.x / length, y: self.y / length, z: self.z / length }
        }
    }

    fn as_array(self) -> [f32; 3] {
        [self.x, self.y, self.z]
    }
}

#[derive(Debug, Clone, Copy)]
struct PreviewCamera {
    yaw: f32,
    pitch: f32,
    zoom: f32,
    pan_x: f32,
    pan_y: f32,
}

impl PreviewCamera {
    fn isometric_compat() -> Self {
        Self {
            yaw: -FRAC_PI_4,
            pitch: ISOMETRIC_PITCH,
            zoom: 1.0,
            pan_x: 0.0,
            pan_y: 0.0,
        }
    }

    fn orbit(mut self, dx: f32, dy: f32) -> Self {
        self.yaw += dx * CAMERA_LOOK_SENSITIVITY;
        self.pitch = (self.pitch - dy * CAMERA_LOOK_SENSITIVITY)
            .clamp(-MAX_CAMERA_PITCH, MAX_CAMERA_PITCH);
        self
    }

    fn pan(mut self, dx: f32, dy: f32) -> Self {
        self.pan_x += dx;
        self.pan_y += dy;
        self
    }

    fn dolly(mut self, wheel_steps: f32) -> Self {
        if wheel_steps != 0.0 {
            self.zoom = (self.zoom * 1.1_f32.powf(wheel_steps.clamp(-20.0, 20.0)))
                .clamp(0.05, 64.0);
        }
        self
    }

    fn forward(self) -> Vec3 {
        let (sin_yaw, cos_yaw) = self.yaw.sin_cos();
        let (sin_pitch, cos_pitch) = self.pitch.sin_cos();
        Vec3 {
            x: cos_pitch * sin_yaw,
            y: sin_pitch,
            z: -cos_pitch * cos_yaw,
        }
        .normalize()
    }

    fn right(self) -> Vec3 {
        self.forward().cross(Vec3 { x: 0.0, y: 1.0, z: 0.0 }).normalize()
    }

    fn up(self) -> Vec3 {
        self.right().cross(self.forward()).normalize()
    }

    fn project(
        self,
        point: Vec3,
        anchor: Vec3,
        screen_x: f32,
        screen_y: f32,
        horizontal_scale: f32,
        vertical_scale: f32,
    ) -> ProjectedVertex {
        let delta = point.sub(anchor);
        let right = self.right();
        let up = self.up();
        let forward = self.forward();
        ProjectedVertex {
            x: screen_x + self.pan_x + right.dot(delta) * horizontal_scale * self.zoom,
            y: screen_y + self.pan_y - up.dot(delta) * vertical_scale * self.zoom,
            depth: forward.dot(delta),
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct PreviewView {
    anchor: Vec3,
    screen_x: f32,
    screen_y: f32,
    horizontal_scale: f32,
    vertical_scale: f32,
    ray_start_depth: f32,
    ray_distance: f32,
}

impl PreviewView {
    fn ray_for_pixel(self, camera: PreviewCamera, x: u32, y: u32) -> ([f32; 3], [f32; 3], f32) {
        let zoom = camera.zoom.max(0.000_001);
        let horizontal = (self.horizontal_scale * zoom).max(0.000_001);
        let vertical = (self.vertical_scale * zoom).max(0.000_001);
        let pixel_x = x as f32 + 0.5;
        let pixel_y = y as f32 + 0.5;
        let right_offset = (pixel_x - self.screen_x - camera.pan_x) / horizontal;
        let up_offset = -(pixel_y - self.screen_y - camera.pan_y) / vertical;
        let forward = camera.forward();
        let plane = self.anchor
            .add(camera.right().scale(right_offset))
            .add(camera.up().scale(up_offset));
        let origin = plane.add(forward.scale(self.ray_start_depth));
        (origin.as_array(), forward.as_array(), self.ray_distance)
    }
}

#[derive(Debug, Clone, Copy)]
struct ProjectedVertex {
    x: f32,
    y: f32,
    depth: f32,
}

#[derive(Debug, Clone, Copy)]
enum FaceTexture {
    Side,
    Top,
    Bottom,
}

#[derive(Debug, Clone, Copy)]
struct CubeFace {
    corners: [[f32; 3]; 4],
    normal: Vec3,
    neighbor: [i32; 3],
    texture: FaceTexture,
}

const CUBE_FACES: [CubeFace; 6] = [
    CubeFace {
        corners: [[1.0, 0.0, 0.0], [1.0, 0.0, 1.0], [1.0, 1.0, 1.0], [1.0, 1.0, 0.0]],
        normal: Vec3 { x: 1.0, y: 0.0, z: 0.0 },
        neighbor: [1, 0, 0],
        texture: FaceTexture::Side,
    },
    CubeFace {
        corners: [[0.0, 0.0, 1.0], [0.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 1.0, 1.0]],
        normal: Vec3 { x: -1.0, y: 0.0, z: 0.0 },
        neighbor: [-1, 0, 0],
        texture: FaceTexture::Side,
    },
    CubeFace {
        corners: [[0.0, 1.0, 0.0], [1.0, 1.0, 0.0], [1.0, 1.0, 1.0], [0.0, 1.0, 1.0]],
        normal: Vec3 { x: 0.0, y: 1.0, z: 0.0 },
        neighbor: [0, 1, 0],
        texture: FaceTexture::Top,
    },
    CubeFace {
        corners: [[0.0, 0.0, 1.0], [1.0, 0.0, 1.0], [1.0, 0.0, 0.0], [0.0, 0.0, 0.0]],
        normal: Vec3 { x: 0.0, y: -1.0, z: 0.0 },
        neighbor: [0, -1, 0],
        texture: FaceTexture::Bottom,
    },
    CubeFace {
        corners: [[1.0, 0.0, 1.0], [0.0, 0.0, 1.0], [0.0, 1.0, 1.0], [1.0, 1.0, 1.0]],
        normal: Vec3 { x: 0.0, y: 0.0, z: 1.0 },
        neighbor: [0, 0, 1],
        texture: FaceTexture::Side,
    },
    CubeFace {
        corners: [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [1.0, 1.0, 0.0], [0.0, 1.0, 0.0]],
        normal: Vec3 { x: 0.0, y: 0.0, z: -1.0 },
        neighbor: [0, 0, -1],
        texture: FaceTexture::Side,
    },
];

#[derive(Debug, Clone, Deserialize)]
struct PreviewBlock {
    x: i32,
    y: i32,
    z: i32,
    #[serde(default)]
    id: String,
    #[serde(default, rename = "textureTop")]
    texture_top: String,
    #[serde(default, rename = "textureSide")]
    texture_side: String,
    #[serde(default, rename = "textureBottom")]
    texture_bottom: String,
    #[serde(default = "default_color")]
    r: u8,
    #[serde(default = "default_color")]
    g: u8,
    #[serde(default = "default_color")]
    b: u8,
}

#[derive(Debug)]
struct PreviewScene {
    blocks: Vec<PreviewBlock>,
    by_id: HashMap<String, Vec<[i32; 3]>>,
    by_position: HashMap<[i32; 3], String>,
    occupied: HashSet<[i32; 3]>,
    texture_cache: Mutex<HashMap<String, TextureTile>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(dead_code)]
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
    raycast_occupied: Arc<HashSet<[i32; 3]>>,
    scene: Arc<PreviewScene>,
    camera: PreviewCamera,
    view: PreviewView,
    highlight: HighlightBounds,
}

impl PreviewPickMap {
    pub fn pick(&self, x: u32, y: u32) -> Option<PreviewPick> {
        if x >= self.width || y >= self.height {
            return None;
        }

        let (origin, direction, max_distance) = self.view.ray_for_pixel(self.camera, x, y);
        if let Some([block_x, block_y, block_z]) = preview_picking::raycast_occupied(
            &self.raycast_occupied,
            origin,
            direction,
            max_distance,
        ) {
            let id = self.scene.by_position
                .get(&[block_x, block_y, block_z])
                .cloned()
                .unwrap_or_else(|| "minecraft:unknown".to_string());
            return Some(PreviewPick { x: block_x, y: block_y, z: block_z, id });
        }

        let pixel = (y * self.width + x) as usize;
        let hit = *self.indices.get(pixel)?;
        if hit < 0 {
            return None;
        }
        self.hits.get(hit as usize).cloned()
    }

    #[allow(dead_code)]
    pub fn coordinates_for_id(&self, id: &str) -> Vec<[i32; 3]> {
        self.scene.by_id.get(id).cloned().unwrap_or_default()
    }

    pub fn blocks_in_box(&self, min: [i32; 3], max: [i32; 3]) -> Vec<[i32; 3]> {
        let low = [min[0].min(max[0]), min[1].min(max[1]), min[2].min(max[2])];
        let high = [min[0].max(max[0]), min[1].max(max[1]), min[2].max(max[2])];
        let mut result: Vec<[i32; 3]> = self.scene.occupied.iter().copied().filter(|position| {
            inside_box(*position, low, high)
        }).collect();
        result.sort_unstable();
        result
    }

    #[allow(dead_code)]
    pub fn joined_blocks(&self, start: [i32; 3], max_blocks: usize) -> Vec<[i32; 3]> {
        if !self.scene.occupied.contains(&start) {
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
                if self.scene.occupied.contains(&next) && seen.insert(next) {
                    queue.push_back(next);
                }
            }
        }
        result
    }

    pub fn dimensions(&self) -> (u32, u32) {
        (self.width, self.height)
    }

    pub fn look_direction(&self) -> [f32; 3] {
        self.camera.forward().as_array()
    }

    pub fn highlight_box(&self, min: [i32; 3], max: [i32; 3]) -> Result<RenderedPreview> {
        let low = [min[0].min(max[0]), min[1].min(max[1]), min[2].min(max[2])];
        let high = [min[0].max(max[0]), min[1].max(max[1]), min[2].max(max[2])];
        render_scene(self.scene.clone(), self.camera, Some((low, high)))
    }

    pub fn clear_highlight(&self) -> Result<RenderedPreview> {
        render_scene(self.scene.clone(), self.camera, None)
    }

    pub fn orbit(&self, dx: f32, dy: f32) -> Result<RenderedPreview> {
        render_scene(self.scene.clone(), self.camera.orbit(dx, dy), self.highlight)
    }

    pub fn pan(&self, dx: f32, dy: f32) -> Result<RenderedPreview> {
        render_scene(self.scene.clone(), self.camera.pan(dx, dy), self.highlight)
    }

    pub fn dolly(&self, wheel_steps: f32) -> Result<RenderedPreview> {
        render_scene(self.scene.clone(), self.camera.dolly(wheel_steps), self.highlight)
    }

    pub fn fit(&self) -> Result<RenderedPreview> {
        render_scene(self.scene.clone(), PreviewCamera::isometric_compat(), self.highlight)
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

#[derive(Debug, Clone)]
struct TextureTile {
    pixels: Arc<Vec<[u8; 4]>>,
}

impl TextureTile {
    fn pixel(&self, u: f32, v: f32) -> [u8; 4] {
        let x = (u.clamp(0.0, 0.999_999) * PREVIEW_TILE_SIZE as f32) as usize;
        let y = (v.clamp(0.0, 0.999_999) * PREVIEW_TILE_SIZE as f32) as usize;
        self.pixels[y * PREVIEW_TILE_SIZE + x]
    }
}

pub fn render_file(path: &Path) -> Result<RenderedPreview> {
    let file = File::open(path).with_context(|| format!("open preview block list {}", path.display()))?;
    let blocks: Vec<PreviewBlock> = serde_json::from_reader(file)
        .with_context(|| format!("parse preview block list {}", path.display()))?;
    if blocks.is_empty() {
        bail!("No solid blocks were found in the current selection");
    }
    if blocks.len() > MAX_PREVIEW_BLOCKS {
        bail!(
            "3D preview contains {} solid blocks; limit is {}. Reduce the selection before opening the live preview.",
            blocks.len(), MAX_PREVIEW_BLOCKS
        );
    }
    let scene = Arc::new(PreviewScene::new(blocks)?);
    render_scene(scene, PreviewCamera::isometric_compat(), None)
}

pub fn render_file_to_png(path: &Path, output: &Path) -> Result<usize> {
    let rendered = render_file(path)?;
    let image = RgbaImage::from_raw(rendered.width, rendered.height, rendered.rgba)
        .ok_or_else(|| anyhow!("invalid preview pixel buffer"))?;
    DynamicImage::ImageRgba8(image)
        .save_with_format(output, ImageFormat::Png)
        .with_context(|| format!("write preview {}", output.display()))?;
    Ok(rendered.block_count)
}

impl PreviewScene {
    fn new(blocks: Vec<PreviewBlock>) -> Result<Self> {
        let mut by_id = HashMap::<String, Vec<[i32; 3]>>::new();
        let mut by_position = HashMap::<[i32; 3], String>::new();
        let mut occupied = HashSet::with_capacity(blocks.len());
        for block in &blocks {
            let position = [block.x, block.y, block.z];
            occupied.insert(position);
            by_position.insert(position, block.id.clone());
            by_id.entry(block.id.clone()).or_default().push(position);
        }
        Ok(Self {
            blocks,
            by_id,
            by_position,
            occupied,
            texture_cache: Mutex::new(HashMap::new()),
        })
    }
}

fn render_scene(scene: Arc<PreviewScene>, camera: PreviewCamera, highlight: HighlightBounds) -> Result<RenderedPreview> {
    let (min, max) = bounds(&scene.blocks).context("compute preview bounds")?;
    let center = Vec3 {
        x: (min.x + max.x + 1.0) * 0.5,
        y: (min.y + max.y + 1.0) * 0.5,
        z: (min.z + max.z + 1.0) * 0.5,
    };
    let extent_x = max.x - min.x + 1.0;
    let extent_y = max.y - min.y + 1.0;
    let extent_z = max.z - min.z + 1.0;
    let projected_width = ((extent_x + extent_z) * HORIZONTAL_CAMERA_SCALE).max(1.0);
    let projected_height = ((extent_x + extent_z) * 0.5 + extent_y) * VERTICAL_CAMERA_SCALE;
    let base_zoom = ((WIDTH as f32 - 80.0) / projected_width)
        .min((HEIGHT as f32 - 80.0) / projected_height.max(1.0))
        .clamp(0.1, 48.0);
    let camera = PreviewCamera { zoom: camera.zoom * base_zoom, ..camera };
    let forward = camera.forward();
    let depth_extent = (extent_x + extent_y + extent_z).max(1.0);
    let view = PreviewView {
        anchor: center,
        screen_x: WIDTH as f32 * 0.5,
        screen_y: HEIGHT as f32 * 0.5,
        horizontal_scale: HORIZONTAL_CAMERA_SCALE,
        vertical_scale: VERTICAL_CAMERA_SCALE,
        ray_start_depth: -depth_extent * 2.0,
        ray_distance: depth_extent * 4.5 + 32.0,
    };

    let mut rgba = vec![0u8; (WIDTH * HEIGHT * 4) as usize];
    let mut depth = vec![f32::INFINITY; (WIDTH * HEIGHT) as usize];
    let mut indices = vec![-1i32; (WIDTH * HEIGHT) as usize];
    let mut hits = Vec::<PreviewPick>::new();
    let mut rendered_count = 0usize;

    let mut block_order = (0..scene.blocks.len()).collect::<Vec<_>>();
    block_order.sort_by(|left, right| {
        let l = &scene.blocks[*left];
        let r = &scene.blocks[*right];
        let ld = forward.dot(Vec3 { x: l.x as f32, y: l.y as f32, z: l.z as f32 });
        let rd = forward.dot(Vec3 { x: r.x as f32, y: r.y as f32, z: r.z as f32 });
        rd.total_cmp(&ld)
    });

    for block_index in block_order {
        let block = &scene.blocks[block_index];
        let position = [block.x, block.y, block.z];
        let highlighted = highlight.is_some_and(|(low, high)| inside_box(position, low, high));
        for face in CUBE_FACES {
            let neighbor = [block.x + face.neighbor[0], block.y + face.neighbor[1], block.z + face.neighbor[2]];
            if scene.occupied.contains(&neighbor) {
                continue;
            }
            let normal_dot = face.normal.dot(forward);
            if normal_dot >= -0.01 {
                continue;
            }
            let texture = block_texture(block, face.texture);
            let tile = scene.texture_tile(texture);
            draw_face(
                &mut rgba,
                &mut depth,
                &mut indices,
                &mut hits,
                block,
                face,
                tile.as_ref(),
                camera,
                view,
                highlighted,
            );
            rendered_count += 1;
        }
    }

    Ok(RenderedPreview {
        width: WIDTH,
        height: HEIGHT,
        rgba,
        block_count: scene.blocks.len(),
        rendered_count,
        pick_map: PreviewPickMap {
            width: WIDTH,
            height: HEIGHT,
            indices: Arc::new(indices),
            hits: Arc::new(hits),
            raycast_occupied: Arc::new(scene.occupied.clone()),
            scene,
            camera,
            view,
            highlight,
        },
    })
}

fn block_texture(block: &PreviewBlock, face: FaceTexture) -> &str {
    match face {
        FaceTexture::Top => &block.texture_top,
        FaceTexture::Bottom => &block.texture_bottom,
        FaceTexture::Side => &block.texture_side,
    }
}

impl PreviewScene {
    fn texture_tile(&self, path: &str) -> Option<TextureTile> {
        if path.trim().is_empty() {
            return None;
        }
        if let Ok(cache) = self.texture_cache.lock() {
            if let Some(tile) = cache.get(path) {
                return Some(tile.clone());
            }
        }
        let tile = load_texture_tile(path).ok();
        if let Some(tile) = &tile {
            if let Ok(mut cache) = self.texture_cache.lock() {
                cache.insert(path.to_string(), tile.clone());
            }
        }
        tile
    }
}

fn load_texture_tile(path: &str) -> Result<TextureTile> {
    let bytes = std::fs::read(path).with_context(|| format!("read preview texture {path}"))?;
    let image = image::load_from_memory(&bytes).with_context(|| format!("decode preview texture {path}"))?;
    let rgba = image.to_rgba8();
    let width = rgba.width().max(1);
    let height = rgba.height().max(1);
    let mut pixels = Vec::with_capacity(PREVIEW_TILE_SIZE * PREVIEW_TILE_SIZE);
    for y in 0..PREVIEW_TILE_SIZE {
        let sy = ((y as u32 * height) / PREVIEW_TILE_SIZE as u32).min(height - 1);
        for x in 0..PREVIEW_TILE_SIZE {
            let sx = ((x as u32 * width) / PREVIEW_TILE_SIZE as u32).min(width - 1);
            pixels.push(rgba.get_pixel(sx, sy).0);
        }
    }
    Ok(TextureTile { pixels: Arc::new(pixels) })
}

#[allow(clippy::too_many_arguments)]
fn draw_face(
    rgba: &mut [u8],
    depth: &mut [f32],
    indices: &mut [i32],
    hits: &mut Vec<PreviewPick>,
    block: &PreviewBlock,
    face: CubeFace,
    tile: Option<&TextureTile>,
    camera: PreviewCamera,
    view: PreviewView,
    highlighted: bool,
) {
    let inflate = if highlighted { HIGHLIGHT_INFLATE } else { 0.0 };
    let mut projected = [ProjectedVertex { x: 0.0, y: 0.0, depth: 0.0 }; 4];
    let mut world = [[0.0f32; 3]; 4];
    for (index, corner) in face.corners.iter().enumerate() {
        let point = Vec3 {
            x: block.x as f32 + corner[0] + face.normal.x * inflate,
            y: block.y as f32 + corner[1] + face.normal.y * inflate,
            z: block.z as f32 + corner[2] + face.normal.z * inflate,
        };
        world[index] = [point.x, point.y, point.z];
        projected[index] = camera.project(
            point,
            view.anchor,
            view.screen_x,
            view.screen_y,
            view.horizontal_scale,
            view.vertical_scale,
        );
    }

    let base = hits.len() as i32;
    hits.push(PreviewPick { x: block.x, y: block.y, z: block.z, id: block.id.clone() });
    draw_triangle(rgba, depth, indices, base, block, tile, highlighted, [projected[0], projected[1], projected[2]], [world[0], world[1], world[2]]);
    draw_triangle(rgba, depth, indices, base, block, tile, highlighted, [projected[0], projected[2], projected[3]], [world[0], world[2], world[3]]);
}

#[allow(clippy::too_many_arguments)]
fn draw_triangle(
    rgba: &mut [u8],
    depth: &mut [f32],
    indices: &mut [i32],
    hit_index: i32,
    block: &PreviewBlock,
    tile: Option<&TextureTile>,
    highlighted: bool,
    vertices: [ProjectedVertex; 3],
    world: [[f32; 3]; 3],
) {
    let min_x = vertices.iter().map(|vertex| vertex.x).fold(f32::INFINITY, f32::min).floor().max(0.0) as i32;
    let max_x = vertices.iter().map(|vertex| vertex.x).fold(f32::NEG_INFINITY, f32::max).ceil().min((WIDTH - 1) as f32) as i32;
    let min_y = vertices.iter().map(|vertex| vertex.y).fold(f32::INFINITY, f32::min).floor().max(0.0) as i32;
    let max_y = vertices.iter().map(|vertex| vertex.y).fold(f32::NEG_INFINITY, f32::max).ceil().min((HEIGHT - 1) as f32) as i32;
    if min_x > max_x || min_y > max_y { return; }

    let area = edge(vertices[0], vertices[1], vertices[2].x, vertices[2].y);
    if area.abs() < 1.0e-6 { return; }
    for y in min_y..=max_y {
        for x in min_x..=max_x {
            let px = x as f32 + 0.5;
            let py = y as f32 + 0.5;
            let w0 = edge(vertices[1], vertices[2], px, py) / area;
            let w1 = edge(vertices[2], vertices[0], px, py) / area;
            let w2 = edge(vertices[0], vertices[1], px, py) / area;
            if w0 < -0.001 || w1 < -0.001 || w2 < -0.001 { continue; }
            let z = vertices[0].depth * w0 + vertices[1].depth * w1 + vertices[2].depth * w2;
            let pixel = (y as u32 * WIDTH + x as u32) as usize;
            if z >= depth[pixel] { continue; }

            let mut color = block_color(block);
            if let Some(tile) = tile {
                let world_x = world[0][0] * w0 + world[1][0] * w1 + world[2][0] * w2;
                let world_y = world[0][1] * w0 + world[1][1] * w1 + world[2][1] * w2;
                let world_z = world[0][2] * w0 + world[1][2] * w1 + world[2][2] * w2;
                let (u, v) = texture_uv(world_x, world_y, world_z);
                color = tile.pixel(u, v);
                if color[3] <= ALPHA_DISCARD_THRESHOLD { continue; }
            }
            if highlighted {
                color = blend_rgba(color, HIGHLIGHT_COLOR);
            }
            depth[pixel] = z;
            indices[pixel] = hit_index;
            let offset = pixel * 4;
            rgba[offset..offset + 4].copy_from_slice(&color);
        }
    }
}

fn edge(a: ProjectedVertex, b: ProjectedVertex, px: f32, py: f32) -> f32 {
    (px - a.x) * (b.y - a.y) - (py - a.y) * (b.x - a.x)
}

fn texture_uv(x: f32, y: f32, z: f32) -> (f32, f32) {
    let fx = x.fract().abs();
    let fy = y.fract().abs();
    let fz = z.fract().abs();
    if fy < 0.001 || (1.0 - fy) < 0.001 {
        (fx, fz)
    } else if fx < 0.001 || (1.0 - fx) < 0.001 {
        (fz, 1.0 - fy)
    } else {
        (fx, 1.0 - fy)
    }
}

fn block_color(block: &PreviewBlock) -> [u8; 4] {
    [block.r, block.g, block.b, 255]
}

fn blend_rgba(base: [u8; 4], overlay: [u8; 4]) -> [u8; 4] {
    let alpha = overlay[3] as u16;
    let inverse = 255u16 - alpha;
    [
        ((base[0] as u16 * inverse + overlay[0] as u16 * alpha) / 255) as u8,
        ((base[1] as u16 * inverse + overlay[1] as u16 * alpha) / 255) as u8,
        ((base[2] as u16 * inverse + overlay[2] as u16 * alpha) / 255) as u8,
        base[3],
    ]
}

fn inside_box(position: [i32; 3], low: [i32; 3], high: [i32; 3]) -> bool {
    position[0] >= low[0] && position[0] <= high[0]
        && position[1] >= low[1] && position[1] <= high[1]
        && position[2] >= low[2] && position[2] <= high[2]
}

fn bounds(blocks: &[PreviewBlock]) -> Option<(Vec3, Vec3)> {
    let first = blocks.first()?;
    let mut min = Vec3 { x: first.x as f32, y: first.y as f32, z: first.z as f32 };
    let mut max = min;
    for block in &blocks[1..] {
        min.x = min.x.min(block.x as f32);
        min.y = min.y.min(block.y as f32);
        min.z = min.z.min(block.z as f32);
        max.x = max.x.max(block.x as f32);
        max.y = max.y.max(block.y as f32);
        max.z = max.z.max(block.z as f32);
    }
    Some((min, max))
}

fn default_color() -> u8 { 180 }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn camera_look_direction_is_normalized() {
        let direction = PreviewCamera::isometric_compat().forward().as_array();
        let length = (direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]).sqrt();
        assert!((length - 1.0).abs() < 1.0e-5);
    }
}
