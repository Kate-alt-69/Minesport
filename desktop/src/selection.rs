use anyhow::{Context, Result};
use serde::Serialize;
use std::{fs, path::{Path, PathBuf}};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExactSelection {
    pub coordinates: Vec<[i32; 3]>,
    pub min: [i32; 3],
    pub max: [i32; 3],
    pub label: String,
}

impl ExactSelection {
    pub fn from_coordinates(mut coordinates: Vec<[i32; 3]>, label: impl Into<String>) -> Option<Self> {
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
        Some(Self { coordinates, min, max, label: label.into() })
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
    let directory = cache_root.join("selection");
    fs::create_dir_all(&directory)
        .with_context(|| format!("create exact-selection cache {}", directory.display()))?;
    let path = directory.join("preview-selection.json");
    let rows = selection.coordinates.iter().map(|coordinate| CoordinateRow {
        x: coordinate[0],
        y: coordinate[1],
        z: coordinate[2],
    }).collect::<Vec<_>>();
    let bytes = serde_json::to_vec(&rows).context("encode exact preview selection")?;
    fs::write(&path, bytes)
        .with_context(|| format!("write exact preview selection {}", path.display()))?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn exact_selection_tracks_bounds_and_deduplicates() {
        let selection = ExactSelection::from_coordinates(
            vec![[4, 70, -3], [2, 68, 5], [4, 70, -3]],
            "stone",
        ).unwrap();
        assert_eq!(selection.coordinates.len(), 2);
        assert_eq!(selection.min, [2, 68, -3]);
        assert_eq!(selection.max, [4, 70, 5]);
        assert!(selection.matches_bounds([2, 68, -3], [4, 70, 5]));
    }

    #[test]
    fn selection_file_uses_engine_coordinate_schema() {
        let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("minesport-selection-{}-{stamp}", std::process::id()));
        let selection = ExactSelection::from_coordinates(vec![[1, 2, 3]], "one").unwrap();
        let path = write_selection_file(&root, &selection).unwrap();
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.contains("\"x\":1"));
        assert!(text.contains("\"y\":2"));
        assert!(text.contains("\"z\":3"));
        let _ = fs::remove_dir_all(root);
    }
}
