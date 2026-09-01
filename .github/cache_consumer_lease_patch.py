from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


heightmap = Path("desktop/src/heightmap_cache.rs")
replace_once(
    heightmap,
    '''    let fingerprint = fingerprint(world)?;\n    let (metadata_path, png_path) = cache_paths(world)?;\n    restore_backup_if_needed(&metadata_path)?;\n''',
    '''    let fingerprint = fingerprint(world)?;\n    let (metadata_path, png_path) = cache_paths(world)?;\n    let _cache_lease = runtime::acquire_generated_cache_lease()?;\n    restore_backup_if_needed(&metadata_path)?;\n''',
    "heightmap load cache lease",
)
replace_once(
    heightmap,
    '''    let fingerprint = fingerprint(world)?;\n    let (metadata_path, png_path) = cache_paths(world)?;\n    let parent = metadata_path\n''',
    '''    let fingerprint = fingerprint(world)?;\n    let (metadata_path, png_path) = cache_paths(world)?;\n    let _cache_lease = runtime::acquire_generated_cache_lease()?;\n    let parent = metadata_path\n''',
    "heightmap save cache lease",
)
replace_once(
    heightmap,
    '''pub fn invalidate(world: &Path) -> Result<()> {\n    let (metadata_path, png_path) = cache_paths(world)?;\n''',
    '''pub fn invalidate(world: &Path) -> Result<()> {\n    let (metadata_path, png_path) = cache_paths(world)?;\n    let _cache_lease = runtime::acquire_generated_cache_lease()?;\n''',
    "heightmap invalidate cache lease",
)

selection = Path("desktop/src/selection.rs")
replace_once(
    selection,
    'use anyhow::{Context, Result};\n',
    'use crate::runtime;\nuse anyhow::{Context, Result};\n',
    "selection runtime import",
)
replace_once(
    selection,
    '''pub fn write_selection_file(cache_root: &Path, selection: &ExactSelection) -> Result<PathBuf> {\n    let directory = cache_root.join("selection");\n''',
    '''pub fn write_selection_file(cache_root: &Path, selection: &ExactSelection) -> Result<PathBuf> {\n    let _cache_lease = runtime::acquire_generated_cache_lease()?;\n    let directory = cache_root.join("selection");\n''',
    "exact selection cache lease",
)

prepare = Path("desktop/src/bin/bridge-prepare.rs")
replace_once(
    prepare,
    '''    if !bridge_family::is_supported(family, &version) {\n        bail!(\n            "no embedded {} compatibility recipe for Minecraft {version}",\n            family.label()\n        );\n    }\n\n    let stamp = SystemTime::now()\n''',
    '''    if !bridge_family::is_supported(family, &version) {\n        bail!(\n            "no embedded {} compatibility recipe for Minecraft {version}",\n            family.label()\n        );\n    }\n\n    // The helper writes source/build/output artifacts under generated cache by\n    // default. Keep cache cleanup from another process out of that transaction.\n    let _cache_lease = runtime::acquire_generated_cache_lease()?;\n\n    let stamp = SystemTime::now()\n''',
    "bridge-prepare cache lifecycle lease",
)

blender = Path("desktop/src/blender.rs")
replace_once(
    blender,
    'use anyhow::{Context, Result, bail};\n',
    'use crate::runtime;\nuse anyhow::{Context, Result, bail};\n',
    "blender runtime import",
)
replace_once(
    blender,
    '    path::{Path, PathBuf},\n};\n',
    '    path::{Path, PathBuf},\n    time::Duration,\n};\n',
    "blender duration import",
)
replace_once(
    blender,
    '''pub fn install_detected_profiles() -> Result<InstallReport> {\n    let targets = discover_targets()?;\n''',
    '''pub fn install_detected_profiles() -> Result<InstallReport> {\n    // One translator install owns the fixed staging directory names across all\n    // detected profiles. Concurrent installers would otherwise delete each\n    // other's .minesport_translator.tmp tree.\n    let _install_lease = runtime::acquire_process_lease(\n        "blender-translator",\n        "install",\n        Duration::from_secs(2 * 60),\n    )?;\n    let targets = discover_targets()?;\n''',
    "Blender translator install process lease",
)
