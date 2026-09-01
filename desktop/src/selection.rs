use crate::runtime;
use anyhow::{Context, Result};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExactSelection {
    pub coordinates: Vec<[i32; 3]>,
    pub min: [i32; 3],
    pub max: [i32; 3],
    pub label: String,
}

impl ExactSelection {
    // The old Joined/Model preview modes constructed exact coordinate sets.
    // The confirmed Fyne A/B workflow deliberately exports ordinary cuboid
    // bounds instead, so keep this constructor only for schema/file tests until
    // an exact-selection UI is reintroduced intentionally.
    #[cfg(test)]
    pub fn from_coordinates(
        mut coordinates: Vec<[i32; 3]>,
        label: impl Into<String>,
    ) -> Option<Self> {
        if coordinates.is_empty() {
            return None;
        }
        coordinates.sort_unstable();
        coordinates.dedup();
        let mut min = coordinates[0];
        let mut max = coordinates[0];
        for coordinate in &coordinates[1..] {
            for axis in 0..3 {
                min[axis] = min[axis].min(coordinate[axis]);
                max[axis] = max[axis].max(coordinate[axis]);
            }
        }
        Some(Self {
            coordinates,
            min,
            max,
            label: label.into(),
        })
    }

    pub fn matches_bounds(&self, min: [i32; 3], max: [i32; 3]) -> bool {
        self.min == min && self.max == max
    }
}

#[derive(Serialize)]
struct CoordinateRow {
    x: i32,
    y: i32,
    z: i32,
}

pub fn write_selection_file(cache_root: &Path, selection: &ExactSelection) -> Result<PathBuf> {
    let _cache_lease = runtime::acquire_generated_cache_lease()?;
    let directory = cache_root.join("selection");
    fs::create_dir_all(&directory)
        .with_context(|| format!("create exact-selection cache {}", directory.display()))?;
    let rows = selection
        .coordinates
        .iter()
        .map(|coordinate| CoordinateRow {
            x: coordinate[0],
            y: coordinate[1],
            z: coordinate[2],
        })
        .collect::<Vec<_>>();
    let bytes = serde_json::to_vec(&rows).context("encode exact preview selection")?;
    let digest = Sha256::digest(&bytes);
    let fingerprint = format!("{digest:x}");
    let path = directory.join(format!("preview-selection-{}.json", &fingerprint[..16]));
    if !path.is_file() {
        let temporary = directory.join(format!(
            ".preview-selection-{}-{}.tmp",
            std::process::id(),
            &fingerprint[..16]
        ));
        fs::write(&temporary, &bytes)
            .with_context(|| format!("stage exact preview selection {}", temporary.display()))?;
        match fs::rename(&temporary, &path) {
            Ok(()) => {}
            Err(_) if path.is_file() => {
                let _ = fs::remove_file(&temporary);
            }
            Err(error) => {
                return Err(error).with_context(|| {
                    format!("install exact preview selection {}", path.display())
                });
            }
        }
    }
    Ok(path)
}

/// Translate a click in a `contain`-fitted Slint image back into source-image
/// pixels. Returns `None` when the click lands in letterboxing around the image.
pub fn preview_source_point(
    mouse_x: f32,
    mouse_y: f32,
    view_width: f32,
    view_height: f32,
    image_width: u32,
    image_height: u32,
) -> Option<(u32, u32)> {
    if !mouse_x.is_finite()
        || !mouse_y.is_finite()
        || !view_width.is_finite()
        || !view_height.is_finite()
    {
        return None;
    }
    if view_width <= 0.0 || view_height <= 0.0 || image_width == 0 || image_height == 0 {
        return None;
    }
    let scale = (view_width / image_width as f32).min(view_height / image_height as f32);
    if scale <= 0.0 || !scale.is_finite() {
        return None;
    }
    let displayed_width = image_width as f32 * scale;
    let displayed_height = image_height as f32 * scale;
    let offset_x = (view_width - displayed_width) * 0.5;
    let offset_y = (view_height - displayed_height) * 0.5;
    let local_x = mouse_x - offset_x;
    let local_y = mouse_y - offset_y;
    if local_x < 0.0 || local_y < 0.0 || local_x >= displayed_width || local_y >= displayed_height {
        return None;
    }
    let source_x = (local_x / scale)
        .floor()
        .clamp(0.0, image_width.saturating_sub(1) as f32) as u32;
    let source_y = (local_y / scale)
        .floor()
        .clamp(0.0, image_height.saturating_sub(1) as f32) as u32;
    Some((source_x, source_y))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn exact_selection_tracks_bounds_and_deduplicates() {
        let selection =
            ExactSelection::from_coordinates(vec![[4, 70, -3], [2, 68, 5], [4, 70, -3]], "stone")
                .unwrap();
        assert_eq!(selection.coordinates.len(), 2);
        assert_eq!(selection.min, [2, 68, -3]);
        assert_eq!(selection.max, [4, 70, 5]);
        assert!(selection.matches_bounds([2, 68, -3], [4, 70, 5]));
    }

    #[test]
    fn selection_file_uses_engine_coordinate_schema_and_stable_name() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "minesport-selection-{}-{stamp}",
            std::process::id()
        ));
        let selection = ExactSelection::from_coordinates(vec![[1, 2, 3]], "one").unwrap();
        let first = write_selection_file(&root, &selection).unwrap();
        let second = write_selection_file(&root, &selection).unwrap();
        assert_eq!(first, second);
        assert!(
            first
                .file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("preview-selection-")
        );
        let text = fs::read_to_string(&first).unwrap();
        assert!(text.contains("\"x\":1"));
        assert!(text.contains("\"y\":2"));
        assert!(text.contains("\"z\":3"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn contain_click_translation_rejects_letterboxing() {
        // 1200×720 inside a square 1000×1000 viewport becomes 1000×600 with
        // 200 px bars on the top and bottom.
        assert_eq!(
            preview_source_point(500.0, 500.0, 1000.0, 1000.0, 1200, 720),
            Some((600, 360))
        );
        assert_eq!(
            preview_source_point(500.0, 100.0, 1000.0, 1000.0, 1200, 720),
            None
        );
        assert_eq!(
            preview_source_point(500.0, 900.0, 1000.0, 1000.0, 1200, 720),
            None
        );
    }
}
