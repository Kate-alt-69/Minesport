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

    let mut by_id: HashMap<String, Vec<[i32; 3]>> = HashMap::new();
    let mut by_position: HashMap<[i32; 3], String> = HashMap::new();
    let mut occupied = HashSet::with_capacity(blocks.len());
    for block in &blocks {
        let coordinate = [block.x, block.y, block.z];
        let id = normalized_id(&block.id);
        occupied.insert(coordinate);
        by_position.insert(coordinate, id.clone());
        by_id.entry(id).or_default().push(coordinate);
    }

    let scene = Arc::new(PreviewScene {
        blocks,
        by_id,
        by_position,
        occupied,
        texture_cache: Mutex::new(HashMap::new()),
    });
    render_scene(scene, PreviewCamera::isometric_compat(), None)
}

fn render_scene(scene: Arc<PreviewScene>, camera: PreviewCamera, highlight: HighlightBounds) -> Result<RenderedPreview> {
    let block_count = scene.blocks.len();
    if block_count == 0 {
        bail!("No solid blocks were found in the current selection");
    }

    let mut render_blocks: Vec<&PreviewBlock> = scene.blocks.iter().collect();
    render_blocks.sort_by_key(|block| (block.x + block.z, block.y, block.x));
    if render_blocks.len() > MAX_PREVIEW_BLOCKS {
        let stride = render_blocks.len().div_ceil(MAX_PREVIEW_BLOCKS);
        render_blocks = render_blocks.into_iter().step_by(stride).collect();
    }

    let rendered_occupied = Arc::new(render_blocks
        .iter()
        .map(|block| [block.x, block.y, block.z])
        .collect::<HashSet<[i32; 3]>>());

    let min_x = render_blocks.iter().map(|block| block.x).min().unwrap_or(0);
    let max_x = render_blocks.iter().map(|block| block.x).max().unwrap_or(0);
    let min_y = render_blocks.iter().map(|block| block.y).min().unwrap_or(0);
    let max_y = render_blocks.iter().map(|block| block.y).max().unwrap_or(0);
    let min_z = render_blocks.iter().map(|block| block.z).min().unwrap_or(0);
    let max_z = render_blocks.iter().map(|block| block.z).max().unwrap_or(0);

    let extent_x = (max_x - min_x + 1).max(1) as f32;
    let extent_y = (max_y - min_y + 1).max(1) as f32;
    let extent_z = (max_z - min_z + 1).max(1) as f32;
    let horizontal_units = extent_x + extent_z;
    let vertical_units = (extent_x + extent_z) * 0.5 + extent_y * 1.35;
    let tile = ((WIDTH as f32 * 0.82 / horizontal_units)
        .min(HEIGHT as f32 * 0.82 / vertical_units)
        .clamp(2.0, 28.0)) as i32;

    let mut pixels = vec![0u8; (WIDTH * HEIGHT * 4) as usize];
    let mut depth = vec![f32::INFINITY; (WIDTH * HEIGHT) as usize];
    let mut hit_indices = vec![-1i32; (WIDTH * HEIGHT) as usize];
    let mut hits = Vec::with_capacity(render_blocks.len());
    clear(&mut pixels, [11, 16, 21, 255]);

    let camera_forward = camera.forward();
    let screen_x = WIDTH as f32 / 2.0;
    let screen_y = HEIGHT as f32 * 0.72;
    let anchor = Vec3 {
        x: (min_x + max_x) as f32 / 2.0,
        y: min_y as f32,
        z: (min_z + max_z) as f32 / 2.0,
    };
    let horizontal_scale = tile.max(2) as f32 * HORIZONTAL_CAMERA_SCALE;
    let vertical_scale = tile.max(2) as f32 * VERTICAL_CAMERA_SCALE;
    let (min_depth, max_depth) = scene_depth_span(
        camera,
        anchor,
        [min_x, min_y, min_z],
        [max_x + 1, max_y + 1, max_z + 1],
    );
    let view = PreviewView {
        anchor,
        screen_x,
        screen_y,
        horizontal_scale,
        vertical_scale,
        ray_start_depth: min_depth - 2.0,
        ray_distance: (max_depth - min_depth).abs() + 4.0,
    };
    let mut texture_cache = scene.texture_cache.lock()
        .map_err(|_| anyhow!("preview texture cache lock poisoned"))?;

    for block in &render_blocks {
        let fallback = [block.r, block.g, block.b, 255];
        let top_texture = preview_texture(
            &mut texture_cache,
            texture_key(&block.texture_top, fallback),
            fallback,
        );
        let side_texture = preview_texture(
            &mut texture_cache,
            texture_key(&block.texture_side, fallback),
            fallback,
        );
        let bottom_texture = preview_texture(
            &mut texture_cache,
            texture_key(&block.texture_bottom, fallback),
            fallback,
        );

        let hit_index = hits.len() as i32;
        hits.push(PreviewPick {
            x: block.x,
            y: block.y,
            z: block.z,
            id: normalized_id(&block.id),
        });

        for face in CUBE_FACES {
            let neighbor = [
                block.x + face.neighbor[0],
                block.y + face.neighbor[1],
                block.z + face.neighbor[2],
            ];
            if rendered_occupied.contains(&neighbor) {
                continue;
            }
            if face.normal.dot(camera_forward) >= -1.0e-6 {
                continue;
            }

            let texture = match face.texture {
                FaceTexture::Side => &side_texture,
                FaceTexture::Top => &top_texture,
                FaceTexture::Bottom => &bottom_texture,
            };
            let brightness = face_brightness(face.normal);
            let projected = face.corners.map(|corner| {
                camera.project(
                    Vec3 {
                        x: block.x as f32 + corner[0],
                        y: block.y as f32 + corner[1],
                        z: block.z as f32 + corner[2],
                    },
                    anchor,
                    screen_x,
                    screen_y,
                    horizontal_scale,
                    vertical_scale,
                )
            });
            fill_textured_quad(
                &mut pixels,
                &mut depth,
                &mut hit_indices,
                hit_index,
                projected,
                texture,
                brightness,
            );
        }
    }

    drop(texture_cache);

    if let Some((highlight_min, highlight_max)) = highlight {
        for block in &render_blocks {
            if !inside_box([block.x, block.y, block.z], highlight_min, highlight_max) {
                continue;
            }
            // The retired GL viewer deliberately did not cull highlight faces.
            // Each face was pushed 0.03 blocks outward, then alpha blended after
            // the textured mesh with depth testing still enabled.
            for face in CUBE_FACES {
                let projected = face.corners.map(|corner| {
                    camera.project(
                        Vec3 {
                            x: block.x as f32 + corner[0] + face.normal.x * HIGHLIGHT_INFLATE,
                            y: block.y as f32 + corner[1] + face.normal.y * HIGHLIGHT_INFLATE,
                            z: block.z as f32 + corner[2] + face.normal.z * HIGHLIGHT_INFLATE,
                        },
                        anchor,
                        screen_x,
                        screen_y,
                        horizontal_scale,
                        vertical_scale,
                    )
                });
                fill_solid_quad(&mut pixels, &mut depth, projected, HIGHLIGHT_COLOR);
            }
        }
    }

    Ok(RenderedPreview {
        width: WIDTH,
        height: HEIGHT,
        rgba: pixels,
        block_count,
        rendered_count: render_blocks.len(),
        pick_map: PreviewPickMap {
            width: WIDTH,
            height: HEIGHT,
            indices: Arc::new(hit_indices),
            hits: Arc::new(hits),
            raycast_occupied: rendered_occupied,
            scene,
            camera,
            view,
            highlight,
        },
    })
}

fn inside_box(position: [i32; 3], min: [i32; 3], max: [i32; 3]) -> bool {
    position[0] >= min[0] && position[0] <= max[0]
        && position[1] >= min[1] && position[1] <= max[1]
        && position[2] >= min[2] && position[2] <= max[2]
}

fn scene_depth_span(
    camera: PreviewCamera,
    anchor: Vec3,
    min: [i32; 3],
    max_exclusive: [i32; 3],
) -> (f32, f32) {
    let forward = camera.forward();
    let mut min_depth = f32::INFINITY;
    let mut max_depth = f32::NEG_INFINITY;
    for x in [min[0] as f32, max_exclusive[0] as f32] {
        for y in [min[1] as f32, max_exclusive[1] as f32] {
            for z in [min[2] as f32, max_exclusive[2] as f32] {
                let depth = forward.dot(Vec3 { x, y, z }.sub(anchor));
                min_depth = min_depth.min(depth);
                max_depth = max_depth.max(depth);
            }
        }
    }
    if !min_depth.is_finite() || !max_depth.is_finite() {
        (0.0, 1.0)
    } else {
        (min_depth, max_depth)
    }
}

fn normalized_id(id: &str) -> String {
    let id = id.trim();
    if id.is_empty() {
        "minecraft:unknown".to_string()
    } else {
        id.to_string()
    }
}

fn texture_key(path: &str, fallback: [u8; 4]) -> String {
    let path = path.trim();
    if path.is_empty() {
        format!("fallback:{:02x}{:02x}{:02x}", fallback[0], fallback[1], fallback[2])
    } else {
        path.to_string()
    }
}

fn preview_texture(
    cache: &mut HashMap<String, TextureTile>,
    key: String,
    fallback: [u8; 4],
) -> TextureTile {
    if let Some(tile) = cache.get(&key) {
        return tile.clone();
    }
    let tile = load_preview_tile(&key, fallback);
    cache.insert(key, tile.clone());
    tile
}

fn load_preview_tile(key: &str, fallback: [u8; 4]) -> TextureTile {
    let source = if !key.starts_with("fallback:") {
        std::fs::read(key)
            .ok()
            .and_then(|bytes| image::load_from_memory_with_format(&bytes, ImageFormat::Png).ok())
            .map(DynamicImage::into_rgba8)
    } else {
        None
    };

    let mut pixels = Vec::with_capacity(PREVIEW_TILE_SIZE * PREVIEW_TILE_SIZE);
    for y in 0..PREVIEW_TILE_SIZE {
        for x in 0..PREVIEW_TILE_SIZE {
            let pixel = source
                .as_ref()
                .and_then(|image| sample_first_animation_frame(image, x, y))
                .unwrap_or_else(|| fallback_preview_pixel(fallback, x, y));
            pixels.push(pixel);
        }
    }
    TextureTile { pixels: Arc::new(pixels) }
}

fn sample_first_animation_frame(image: &RgbaImage, x: usize, y: usize) -> Option<[u8; 4]> {
    if image.width() == 0 || image.height() == 0 {
        return None;
    }
    let frame_height = image.height().min(image.width()).max(1);
    let sx = ((x as u64 * image.width() as u64) / PREVIEW_TILE_SIZE as u64)
        .min(image.width().saturating_sub(1) as u64) as u32;
    let sy = ((y as u64 * frame_height as u64) / PREVIEW_TILE_SIZE as u64)
        .min(frame_height.saturating_sub(1) as u64) as u32;
    Some(image.get_pixel(sx, sy).0)
}

fn fallback_preview_pixel(mut color: [u8; 4], x: usize, y: usize) -> [u8; 4] {
    if (x + y) % 4 == 0 {
        color[0] = (color[0] as f32 * 0.88) as u8;
        color[1] = (color[1] as f32 * 0.88) as u8;
        color[2] = (color[2] as f32 * 0.88) as u8;
    }
    color
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

fn face_brightness(normal: Vec3) -> f32 {
    if normal.y < -0.5 {
        0.5
    } else {
        0.65 + 0.35 * normal.y.max(0.0)
    }
}

fn fill_textured_quad(
    pixels: &mut [u8],
    depth: &mut [f32],
    hit_indices: &mut [i32],
    hit_index: i32,
    vertices: [ProjectedVertex; 4],
    texture: &TextureTile,
    brightness: f32,
) {
    let uv = [[0.0, 1.0], [1.0, 1.0], [1.0, 0.0], [0.0, 0.0]];
    fill_textured_triangle(
        pixels, depth, hit_indices, hit_index,
        [vertices[0], vertices[1], vertices[2]],
        [uv[0], uv[1], uv[2]], texture, brightness,
    );
    fill_textured_triangle(
        pixels, depth, hit_indices, hit_index,
        [vertices[0], vertices[2], vertices[3]],
        [uv[0], uv[2], uv[3]], texture, brightness,
    );
}

#[allow(clippy::too_many_arguments)]
fn fill_textured_triangle(
    pixels: &mut [u8],
    depth: &mut [f32],
    hit_indices: &mut [i32],
    hit_index: i32,
    vertices: [ProjectedVertex; 3],
    uv: [[f32; 2]; 3],
    texture: &TextureTile,
    brightness: f32,
) {
    let min_x = vertices.iter().map(|vertex| vertex.x.floor() as i32).min().unwrap_or(0).clamp(0, WIDTH as i32 - 1);
    let max_x = vertices.iter().map(|vertex| vertex.x.ceil() as i32).max().unwrap_or(0).clamp(0, WIDTH as i32 - 1);
    let min_y = vertices.iter().map(|vertex| vertex.y.floor() as i32).min().unwrap_or(0).clamp(0, HEIGHT as i32 - 1);
    let max_y = vertices.iter().map(|vertex| vertex.y.ceil() as i32).max().unwrap_or(0).clamp(0, HEIGHT as i32 - 1);
    let area = edge(vertices[0], vertices[1], vertices[2]);
    if area.abs() < 1.0e-6 { return; }

    for y in min_y..=max_y {
        for x in min_x..=max_x {
            let point = ProjectedVertex { x: x as f32 + 0.5, y: y as f32 + 0.5, depth: 0.0 };
            let w0 = edge(vertices[1], vertices[2], point);
            let w1 = edge(vertices[2], vertices[0], point);
            let w2 = edge(vertices[0], vertices[1], point);
            let inside = if area > 0.0 {
                w0 >= 0.0 && w1 >= 0.0 && w2 >= 0.0
            } else {
                w0 <= 0.0 && w1 <= 0.0 && w2 <= 0.0
            };
            if !inside {
                continue;
            }

            let b0 = w0 / area;
            let b1 = w1 / area;
            let b2 = w2 / area;
            let fragment_depth = vertices[0].depth * b0 + vertices[1].depth * b1 + vertices[2].depth * b2;
            let pixel = (y as u32 * WIDTH + x as u32) as usize;
            if fragment_depth >= depth[pixel] {
                continue;
            }

            let u = uv[0][0] * b0 + uv[1][0] * b1 + uv[2][0] * b2;
            let v = uv[0][1] * b0 + uv[1][1] * b1 + uv[2][1] * b2;
            let sampled = texture.pixel(u, v);
            if sampled[3] < ALPHA_DISCARD_THRESHOLD {
                continue;
            }

            let color = shade(sampled, brightness);
            let rgba = pixel * 4;
            pixels[rgba..rgba + 4].copy_from_slice(&color);
            depth[pixel] = fragment_depth;
            hit_indices[pixel] = hit_index;
        }
    }
}

fn fill_solid_quad(
    pixels: &mut [u8],
    depth: &mut [f32],
    vertices: [ProjectedVertex; 4],
    color: [u8; 4],
) {
    fill_solid_triangle(pixels, depth, [vertices[0], vertices[1], vertices[2]], color);
    fill_solid_triangle(pixels, depth, [vertices[0], vertices[2], vertices[3]], color);
}

fn fill_solid_triangle(
    pixels: &mut [u8],
    depth: &mut [f32],
    vertices: [ProjectedVertex; 3],
    color: [u8; 4],
) {
    let min_x = vertices.iter().map(|vertex| vertex.x.floor() as i32).min().unwrap_or(0).clamp(0, WIDTH as i32 - 1);
    let max_x = vertices.iter().map(|vertex| vertex.x.ceil() as i32).max().unwrap_or(0).clamp(0, WIDTH as i32 - 1);
    let min_y = vertices.iter().map(|vertex| vertex.y.floor() as i32).min().unwrap_or(0).clamp(0, HEIGHT as i32 - 1);
    let max_y = vertices.iter().map(|vertex| vertex.y.ceil() as i32).max().unwrap_or(0).clamp(0, HEIGHT as i32 - 1);
    let area = edge(vertices[0], vertices[1], vertices[2]);
    if area.abs() < 1.0e-6 { return; }

    for y in min_y..=max_y {
        for x in min_x..=max_x {
            let point = ProjectedVertex { x: x as f32 + 0.5, y: y as f32 + 0.5, depth: 0.0 };
            let w0 = edge(vertices[1], vertices[2], point);
            let w1 = edge(vertices[2], vertices[0], point);
            let w2 = edge(vertices[0], vertices[1], point);
            let inside = if area > 0.0 {
                w0 >= 0.0 && w1 >= 0.0 && w2 >= 0.0
            } else {
                w0 <= 0.0 && w1 <= 0.0 && w2 <= 0.0
            };
            if !inside { continue; }

            let b0 = w0 / area;
            let b1 = w1 / area;
            let b2 = w2 / area;
            let fragment_depth = vertices[0].depth * b0 + vertices[1].depth * b1 + vertices[2].depth * b2;
            let pixel = (y as u32 * WIDTH + x as u32) as usize;
            if fragment_depth >= depth[pixel] { continue; }

            let rgba = pixel * 4;
            blend_over(&mut pixels[rgba..rgba + 4], color);
            depth[pixel] = fragment_depth;
        }
    }
}

fn blend_over(destination: &mut [u8], source: [u8; 4]) {
    let alpha = source[3] as f32 / 255.0;
    let inverse = 1.0 - alpha;
    for channel in 0..3 {
        destination[channel] = (source[channel] as f32 * alpha + destination[channel] as f32 * inverse)
            .round()
            .clamp(0.0, 255.0) as u8;
    }
    destination[3] = 255;
}

fn edge(a: ProjectedVertex, b: ProjectedVertex, p: ProjectedVertex) -> f32 {
    (p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x)
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
    fn fyne_highlight_constants_match_retired_gl_viewer() {
        assert_eq!(HIGHLIGHT_COLOR, [64, 255, 115, 153]);
        assert!((HIGHLIGHT_INFLATE - 0.03).abs() < f32::EPSILON);
        let mut pixel = [100, 100, 100, 255];
        blend_over(&mut pixel, HIGHLIGHT_COLOR);
        assert!(pixel[1] > pixel[0]);
        assert!(pixel[1] > pixel[2]);
    }

    #[test]
    fn triangle_edge_has_expected_orientation() {
        let a = ProjectedVertex { x: 0.0, y: 0.0, depth: 0.0 };
        let b = ProjectedVertex { x: 2.0, y: 0.0, depth: 0.0 };
        let c = ProjectedVertex { x: 0.0, y: 2.0, depth: 0.0 };
        assert!(edge(a, b, c).abs() > 0.0);
    }

    #[test]
    fn legacy_camera_projection_preserves_isometric_axes() {
        let camera = PreviewCamera::isometric_compat();
        let anchor = Vec3::default();
        let scale_x = 10.0 * HORIZONTAL_CAMERA_SCALE;
        let scale_y = 10.0 * VERTICAL_CAMERA_SCALE;
        let project = |point| camera.project(point, anchor, 0.0, 0.0, scale_x, scale_y);
        let x = project(Vec3 { x: 1.0, y: 0.0, z: 0.0 });
        let y = project(Vec3 { x: 0.0, y: 1.0, z: 0.0 });
        let z = project(Vec3 { x: 0.0, y: 0.0, z: 1.0 });
        assert!((x.x - 10.0).abs() < 0.01 && (x.y - 5.0).abs() < 0.01);
        assert!(y.x.abs() < 0.01 && (y.y + 10.0).abs() < 0.01);
        assert!((z.x + 10.0).abs() < 0.01 && (z.y - 5.0).abs() < 0.01);
    }

    #[test]
    fn face_brightness_matches_retired_glsl() {
        assert!((face_brightness(Vec3 { x: 1.0, y: 0.0, z: 0.0 }) - 0.65).abs() < f32::EPSILON);
        assert!((face_brightness(Vec3 { x: 0.0, y: 1.0, z: 0.0 }) - 1.0).abs() < f32::EPSILON);
        assert!((face_brightness(Vec3 { x: 0.0, y: -1.0, z: 0.0 }) - 0.5).abs() < f32::EPSILON);
    }

    #[test]
    fn fallback_tile_matches_fyne_checker_detail() {
        let base = [100, 150, 200, 255];
        assert_eq!(fallback_preview_pixel(base, 1, 0), base);
        assert_eq!(fallback_preview_pixel(base, 0, 0), [88, 132, 176, 255]);
    }

    #[test]
    fn first_animation_frame_sampling_stays_in_top_square_frame() {
        let mut image = RgbaImage::new(2, 4);
        for y in 0..2 {
            for x in 0..2 {
                image.put_pixel(x, y, image::Rgba([10, 20, 30, 255]));
            }
        }
        for y in 2..4 {
            for x in 0..2 {
                image.put_pixel(x, y, image::Rgba([200, 210, 220, 255]));
            }
        }
        for y in 0..PREVIEW_TILE_SIZE {
            for x in 0..PREVIEW_TILE_SIZE {
                assert_eq!(sample_first_animation_frame(&image, x, y), Some([10, 20, 30, 255]));
            }
        }
    }

    #[test]
    fn scene_depth_span_contains_all_box_corners() {
        let camera = PreviewCamera::isometric_compat();
        let anchor = Vec3::default();
        let (min_depth, max_depth) = scene_depth_span(camera, anchor, [-2, -1, -3], [4, 6, 5]);
        assert!(min_depth.is_finite());
        assert!(max_depth.is_finite());
        assert!(max_depth > min_depth);
    }

    #[test]
    fn rendered_preview_can_pick_group_flood_box_highlight_and_rerender_without_source_file() {
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
        assert_eq!(rendered.pick_map.blocks_in_box([0, 64, 0], [1, 65, 0]).len(), 3);
        let mut joined = rendered.pick_map.joined_blocks([0, 64, 0], 64);
        joined.sort_unstable();
        assert_eq!(joined, vec![[0, 64, 0], [0, 65, 0], [1, 64, 0]]);
        assert_eq!(rendered.pick_map.joined_blocks([0, 64, 0], 2).len(), 2);
        assert_eq!(rendered.pick_map.joined_blocks([0, 64, 0], 0), vec![[0, 64, 0]]);
        assert_eq!(rendered.pick_map.dimensions(), (WIDTH, HEIGHT));
        assert!(rendered.pick_map.indices.iter().any(|value| *value >= 0));
        let picked = rendered.pick_map.indices.iter().find(|value| **value >= 0).copied().unwrap();
        assert!(rendered.pick_map.hits.get(picked as usize).is_some());

        let highlighted = rendered.pick_map.highlight_box([0, 64, 0], [1, 65, 0]).unwrap();
        assert_eq!(highlighted.pick_map.highlight, Some(([0, 64, 0], [1, 65, 0])));
        let cleared = highlighted.pick_map.clear_highlight().unwrap();
        assert_eq!(cleared.pick_map.highlight, None);

        fs::remove_file(&path).unwrap();
        let orbited = highlighted.pick_map.orbit(24.0, -10.0).unwrap();
        assert!(orbited.pick_map.highlight.is_some());
        let panned = orbited.pick_map.pan(12.0, 8.0).unwrap();
        let zoomed = panned.pick_map.dolly(1.0).unwrap();
        let fitted = zoomed.pick_map.fit().unwrap();
        assert_eq!(fitted.block_count, 4);
        assert_eq!(fitted.pick_map.coordinates_for_id("minecraft:stone").len(), 3);
    }
}
