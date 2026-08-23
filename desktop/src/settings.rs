use crate::runtime;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct DesktopSettings {
    pub output_path: String,
    pub export_name: String,
    pub export_format_index: i32,
    pub export_mode_index: i32,
    pub optimize: bool,
    pub face_culling: bool,
    pub flatter_enabled: bool,
    pub flatter_cell_index: i32,
    pub hidden_culling: bool,
    pub blender_export: bool,
    pub select_by_model: bool,
    pub debug_mode: bool,
    pub blender_animation_index: i32,
    pub blender_translator_prompted: bool,
    pub resource_packs: Vec<PathBuf>,
    pub data_packs: Vec<PathBuf>,
}

impl Default for DesktopSettings {
    fn default() -> Self {
        Self {
            output_path: String::new(),
            export_name: "Minesport_Export".to_string(),
            export_format_index: 0,
            export_mode_index: 0,
            optimize: true,
            face_culling: true,
            flatter_enabled: true,
            flatter_cell_index: 3,
            hidden_culling: false,
            blender_export: true,
            select_by_model: false,
            debug_mode: false,
            blender_animation_index: 0,
            blender_translator_prompted: false,
            resource_packs: Vec::new(),
            data_packs: Vec::new(),
        }
    }
}

pub fn load() -> DesktopSettings {
    let path = settings_path();
    let Ok(bytes) = fs::read(&path) else { return DesktopSettings::default(); };
    serde_json::from_slice(&bytes).unwrap_or_default()
}

pub fn save(settings: &DesktopSettings) -> Result<()> {
    let path = settings_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("create settings directory {}", parent.display()))?;
    }
    let bytes = serde_json::to_vec_pretty(settings).context("encode Rust desktop settings")?;
    let temporary = path.with_extension("json.tmp");
    fs::write(&temporary, bytes).with_context(|| format!("write {}", temporary.display()))?;
    let _ = fs::remove_file(&path);
    fs::rename(&temporary, &path).with_context(|| format!("install {}", path.display()))?;
    Ok(())
}

fn settings_path() -> PathBuf {
    runtime::data_root().join("settings").join("desktop.json")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_workbench_expectations() {
        let value = DesktopSettings::default();
        assert!(value.face_culling);
        assert!(value.flatter_enabled);
        assert_eq!(value.flatter_cell_index, 3);
        assert!(value.blender_export);
        assert!(!value.blender_translator_prompted);
    }
}
