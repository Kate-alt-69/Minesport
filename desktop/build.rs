use regex::Regex;
use serde_json::Value;
use std::{
    collections::{HashMap, HashSet},
    env, fs,
    io::ErrorKind,
    path::{Component, Path, PathBuf},
    thread,
    time::Duration,
};

const BUNDLED_EXPORT_WORKERS: &[(&str, &str, &str, &str)] = &[
    (
        "Fabric",
        "MINESPORT_EXPORT_WORKER_FABRIC_JAR",
        "minesport_export_worker-fabric-1.21.10.jar",
        "minesport_export_worker-fabric.jar",
    ),
    (
        "Forge",
        "MINESPORT_EXPORT_WORKER_FORGE_JAR",
        "minesport_export_worker-forge-1.21.10.jar",
        "minesport_export_worker-forge.jar",
    ),
    (
        "NeoForge",
        "MINESPORT_EXPORT_WORKER_NEOFORGE_JAR",
        "minesport_export_worker-neoforge-1.21.10.jar",
        "minesport_export_worker-neoforge.jar",
    ),
    (
        "Quilt",
        "MINESPORT_EXPORT_WORKER_QUILT_JAR",
        "minesport_export_worker-quilt-1.21.10.jar",
        "minesport_export_worker-quilt.jar",
    ),
];

fn manifest_dir() -> PathBuf {
    PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"))
}

fn repo_root() -> PathBuf {
    manifest_dir()
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .to_path_buf()
}

fn find_engine_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_ENGINE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    let version_path = repo_root().join("engine").join("VERSION");
    let raw = fs::read_to_string(version_path).ok()?;
    let version = raw.trim();
    if version.is_empty()
        || !version
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'+' | b'_'))
    {
        return None;
    }

    let exact = repo_root()
        .join("engine")
        .join("build")
        .join("libs")
        .join(format!("minesport-engine-{version}.jar"));
    exact.is_file().then_some(exact)
}

fn find_export_worker_jar(env_name: &str, staged_name: &str) -> Option<PathBuf> {
    if let Ok(path) = env::var(env_name) {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    // Keep the old single-Bridge override usable as a Fabric-only alias for
    // developer environments while the canonical contract uses loader names.
    if env_name == "MINESPORT_EXPORT_WORKER_FABRIC_JAR" {
        if let Ok(path) = env::var("MINESPORT_EXPORT_WORKER_JAR") {
            let path = PathBuf::from(path);
            if path.is_file() {
                return Some(path);
            }
        }
    }

    let staged = repo_root()
        .join("dist")
        .join("bundled-export-worker")
        .join(staged_name);
    staged.is_file().then_some(staged)
}

fn safe_manifest_relative(value: &str) -> PathBuf {
    let path = PathBuf::from(value);
    if path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        })
    {
        panic!("bridge manifest contains unsafe source path: {value}");
    }
    path
}

fn reset_directory(path: &Path, label: &str) {
    if !path.exists() {
        return;
    }

    const ATTEMPTS: u32 = 24;
    for attempt in 1..=ATTEMPTS {
        match fs::remove_dir_all(path) {
            Ok(()) => return,
            Err(error) if error.kind() == ErrorKind::NotFound => return,
            Err(error) if attempt < ATTEMPTS => {
                // Windows Defender, Explorer, IDE indexers, or a just-exited
                // compiler can briefly retain a directory handle. Do not turn
                // a transient sharing violation into a failed release build.
                let delay_ms = 50_u64.saturating_mul(attempt as u64).min(500);
                thread::sleep(Duration::from_millis(delay_ms));
                if !path.exists() {
                    return;
                }
                let _ = error;
            }
            Err(error) => panic!("{label}: {}: {error}", path.display()),
        }
    }
}

fn stage_bridge_sources(root: &Path, out: &Path) {
    let manifest_path = root
        .join("minesport-bridge-fabric-versions")
        .join("manifest.json");
    let manifest_bytes = fs::read(&manifest_path).expect("read bridge compatibility manifest");
    let manifest: Value =
        serde_json::from_slice(&manifest_bytes).expect("parse bridge compatibility manifest");

    let base = manifest
        .get("base")
        .and_then(Value::as_object)
        .expect("bridge manifest base object");
    let source_root = base
        .get("source_root")
        .and_then(Value::as_str)
        .unwrap_or("minesport-bridge-fabric");
    let source_root = safe_manifest_relative(source_root);
    let files = base
        .get("files")
        .and_then(Value::as_array)
        .expect("bridge manifest base.files array");
    if files.is_empty() {
        panic!("bridge manifest base.files must not be empty");
    }

    let staged = out.join("bridge-source");
    reset_directory(&staged, "reset staged Bridge source directory");

    for entry in files {
        let relative = entry
            .as_str()
            .unwrap_or_else(|| panic!("bridge manifest base.files entries must be strings"));
        let relative = safe_manifest_relative(relative);
        let source = root.join(&source_root).join(&relative);
        if !source.is_file() {
            panic!("manifest Bridge source is missing: {}", source.display());
        }
        let target = staged.join(&relative);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).expect("create staged Bridge source parent");
        }
        fs::copy(&source, &target).unwrap_or_else(|error| {
            panic!(
                "stage Bridge source {} -> {}: {error}",
                source.display(),
                target.display()
            )
        });
        println!("cargo:rerun-if-changed={}", source.display());
    }

    println!("cargo:rerun-if-changed={}", manifest_path.display());
}

fn copy_tree(source: &Path, target: &Path) {
    fs::create_dir_all(target)
        .unwrap_or_else(|error| panic!("create recipe workspace {}: {error}", target.display()));
    for entry in fs::read_dir(source)
        .unwrap_or_else(|error| panic!("read recipe source tree {}: {error}", source.display()))
    {
        let entry = entry.expect("read recipe source entry");
        let kind = entry.file_type().expect("read recipe source entry type");
        let destination = target.join(entry.file_name());
        if kind.is_symlink() {
            continue;
        }
        if kind.is_dir() {
            copy_tree(&entry.path(), &destination);
        } else if kind.is_file() {
            fs::copy(entry.path(), &destination).unwrap_or_else(|error| {
                panic!(
                    "copy recipe validation file {}: {error}",
                    destination.display()
                )
            });
        }
    }
}

fn expand_variables(value: &str, variables: &HashMap<&str, &str>) -> String {
    let mut output = value.to_string();
    for (name, replacement) in variables {
        output = output.replace(&format!("${{{name}}}"), replacement);
    }
    output
}

fn operation_text(operation: &Value, key: &str) -> String {
    operation
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

fn set_property(file: &Path, key: &str, value: &str) {
    let text = fs::read_to_string(file)
        .unwrap_or_else(|error| panic!("read recipe property file {}: {error}", file.display()));
    let prefix = format!("{key}=");
    let mut updated = false;
    let mut lines = Vec::new();
    for line in text.lines() {
        if line.trim_start().starts_with(&prefix) {
            lines.push(format!("{prefix}{value}"));
            updated = true;
        } else {
            lines.push(line.to_string());
        }
    }
    if !updated {
        lines.push(format!("{prefix}{value}"));
    }
    let mut output = lines.join("\n");
    if text.ends_with('\n') {
        output.push('\n');
    }
    fs::write(file, output)
        .unwrap_or_else(|error| panic!("write recipe property file {}: {error}", file.display()));
}

fn replace_required(file: &Path, from: &str, to: &str, recipe: &str, index: usize) {
    let text = fs::read_to_string(file).unwrap_or_else(|error| {
        panic!(
            "recipe {recipe} operation {index} could not read {}: {error}",
            file.display()
        )
    });
    if !text.contains(from) {
        panic!(
            "recipe {recipe} operation {index} no longer applies: expected text {from:?} was not found in {}",
            file.display()
        );
    }
    fs::write(file, text.replace(from, to)).unwrap_or_else(|error| {
        panic!(
            "recipe {recipe} operation {index} could not update {}: {error}",
            file.display()
        )
    });
}

fn replace_at(
    file: &Path,
    line: usize,
    column: usize,
    from: &str,
    to: &str,
    recipe: &str,
    index: usize,
) {
    if line == 0 || column == 0 || from.is_empty() {
        panic!("recipe {recipe} operation {index} has an invalid rename_at location");
    }
    let text = fs::read_to_string(file).unwrap_or_else(|error| {
        panic!(
            "recipe {recipe} operation {index} could not read {}: {error}",
            file.display()
        )
    });
    let mut offsets = vec![0usize];
    for (offset, byte) in text.bytes().enumerate() {
        if byte == b'\n' {
            offsets.push(offset + 1);
        }
    }
    let start_of_line = *offsets.get(line - 1).unwrap_or_else(|| {
        panic!(
            "recipe {recipe} operation {index}: line {line} does not exist in {}",
            file.display()
        )
    });
    let start = start_of_line + column - 1;
    let line_end = text[start_of_line..]
        .find('\n')
        .map(|value| start_of_line + value)
        .unwrap_or(text.len());
    if start + from.len() > line_end
        || !text.is_char_boundary(start)
        || !text.is_char_boundary(start + from.len())
        || &text[start..start + from.len()] != from
    {
        panic!(
            "recipe {recipe} operation {index} has a stale rename_at at {}:{line}:{column}; expected {from:?}",
            file.display()
        );
    }
    let mut output = String::with_capacity(text.len() + to.len().saturating_sub(from.len()));
    output.push_str(&text[..start]);
    output.push_str(to);
    output.push_str(&text[start + from.len()..]);
    fs::write(file, output).unwrap_or_else(|error| {
        panic!(
            "recipe {recipe} operation {index} could not update {}: {error}",
            file.display()
        )
    });
}

fn path_has_extension(path: &Path, extensions: &[String]) -> bool {
    if extensions.is_empty() {
        return true;
    }
    let extension = path
        .extension()
        .and_then(|value| value.to_str())
        .map(|value| format!(".{value}"))
        .unwrap_or_default();
    extensions
        .iter()
        .any(|candidate| candidate.eq_ignore_ascii_case(&extension))
}

fn replace_tree(root: &Path, extensions: &[String], from: &str, to: &str) {
    if !root.exists() {
        panic!(
            "compatibility recipe tree does not exist: {}",
            root.display()
        );
    }
    for entry in fs::read_dir(root).unwrap_or_else(|error| {
        panic!("read compatibility recipe tree {}: {error}", root.display())
    }) {
        let entry = entry.expect("read compatibility recipe entry");
        let path = entry.path();
        let kind = entry
            .file_type()
            .expect("read compatibility recipe entry type");
        if kind.is_symlink() {
            continue;
        }
        if kind.is_dir() {
            replace_tree(&path, extensions, from, to);
            continue;
        }
        if !kind.is_file() || !path_has_extension(&path, extensions) {
            continue;
        }
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        if text.contains(from) {
            fs::write(&path, text.replace(from, to)).unwrap_or_else(|error| {
                panic!(
                    "update compatibility recipe tree file {}: {error}",
                    path.display()
                )
            });
        }
    }
}

fn apply_recipe_validation_operation(
    repo_root: &Path,
    workspace: &Path,
    operation: &Value,
    variables: &HashMap<&str, &str>,
    recipe: &str,
    index: usize,
) {
    let name = operation
        .get("op")
        .and_then(Value::as_str)
        .unwrap_or_else(|| panic!("recipe {recipe} operation {index} is missing op"));
    let expand = |key: &str| expand_variables(&operation_text(operation, key), variables);
    match name {
        "set_property" => {
            let file = workspace.join(safe_manifest_relative(&expand("file")));
            set_property(&file, &expand("key"), &expand("value"));
        }
        "replace" => {
            let file = workspace.join(safe_manifest_relative(&expand("file")));
            replace_required(&file, &expand("from"), &expand("to"), recipe, index);
        }
        "rename_at" => {
            let file = workspace.join(safe_manifest_relative(&expand("file")));
            let line = operation.get("line").and_then(Value::as_u64).unwrap_or(0) as usize;
            let column = operation.get("column").and_then(Value::as_u64).unwrap_or(0) as usize;
            replace_at(
                &file,
                line,
                column,
                &expand("from"),
                &expand("to"),
                recipe,
                index,
            );
        }
        "regex_replace" => {
            let file = workspace.join(safe_manifest_relative(&expand("file")));
            let text = fs::read_to_string(&file).unwrap_or_else(|error| {
                panic!(
                    "recipe {recipe} operation {index} could not read {}: {error}",
                    file.display()
                )
            });
            let pattern = expand("pattern");
            let replacement = expand("replacement");
            let expression = Regex::new(&pattern).unwrap_or_else(|error| {
                panic!("recipe {recipe} operation {index} has invalid regex {pattern:?}: {error}")
            });
            if !expression.is_match(&text) {
                panic!(
                    "recipe {recipe} operation {index} no longer applies: regex {pattern:?} was not found in {}",
                    file.display()
                );
            }
            fs::write(
                &file,
                expression
                    .replace_all(&text, replacement.as_str())
                    .as_bytes(),
            )
            .unwrap_or_else(|error| {
                panic!(
                    "recipe {recipe} operation {index} could not update {}: {error}",
                    file.display()
                )
            });
        }
        "replace_tree" | "rename_package" => {
            let root = workspace.join(safe_manifest_relative(&expand("root")));
            let mut extensions = operation
                .get("extensions")
                .and_then(Value::as_array)
                .map(|values| {
                    values
                        .iter()
                        .filter_map(Value::as_str)
                        .map(str::to_string)
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            if name == "rename_package" && extensions.is_empty() {
                extensions.push(".java".to_string());
            }
            replace_tree(&root, &extensions, &expand("from"), &expand("to"));
        }
        "rename_file" => {
            let from = workspace.join(safe_manifest_relative(&expand("from")));
            let to = workspace.join(safe_manifest_relative(&expand("to")));
            if let Some(parent) = to.parent() {
                fs::create_dir_all(parent).expect("create recipe rename target parent");
            }
            fs::rename(&from, &to).unwrap_or_else(|error| {
                panic!(
                    "recipe {recipe} operation {index} could not rename {} -> {}: {error}",
                    from.display(),
                    to.display()
                )
            });
        }
        "overlay" => {
            let source_relative = safe_manifest_relative(&expand("source"));
            let source = repo_root.join(source_relative);
            if !source.is_file() {
                panic!(
                    "recipe {recipe} operation {index} overlay source is missing: {}",
                    source.display()
                );
            }
            let target = workspace.join(safe_manifest_relative(&expand("target")));
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).expect("create recipe overlay target parent");
            }
            fs::copy(&source, &target).unwrap_or_else(|error| {
                panic!(
                    "recipe {recipe} operation {index} could not overlay {} -> {}: {error}",
                    source.display(),
                    target.display()
                )
            });
            println!("cargo:rerun-if-changed={}", source.display());
        }
        "module" => {
            let url = expand("url");
            let sha = expand("sha256");
            if !url.starts_with("https://") {
                panic!("recipe {recipe} operation {index} module URL must use HTTPS");
            }
            if sha.len() != 64 || !sha.chars().all(|ch| ch.is_ascii_hexdigit()) {
                panic!("recipe {recipe} operation {index} module SHA-256 is invalid");
            }
        }
        "delete" => {
            let raw = if operation_text(operation, "target").is_empty() {
                expand("file")
            } else {
                expand("target")
            };
            let target = workspace.join(safe_manifest_relative(&raw));
            if target.is_dir() {
                fs::remove_dir_all(&target).unwrap_or_else(|error| {
                    panic!(
                        "recipe {recipe} operation {index} could not delete {}: {error}",
                        target.display()
                    )
                });
            } else if target.exists() {
                fs::remove_file(&target).unwrap_or_else(|error| {
                    panic!(
                        "recipe {recipe} operation {index} could not delete {}: {error}",
                        target.display()
                    )
                });
            }
        }
        other => panic!("recipe {recipe} operation {index} uses unknown operation {other:?}"),
    }
}

fn validate_bridge_recipes(root: &Path, out: &Path) {
    let manifest_path = root
        .join("minesport-bridge-fabric-versions")
        .join("manifest.json");
    let manifest: Value = serde_json::from_slice(
        &fs::read(&manifest_path).expect("read bridge compatibility manifest for validation"),
    )
    .expect("parse bridge compatibility manifest for validation");
    if manifest.get("schema").and_then(Value::as_i64) != Some(1) {
        panic!("unsupported bridge compatibility manifest schema");
    }

    let profiles = manifest
        .get("profiles")
        .and_then(Value::as_array)
        .expect("bridge compatibility manifest profiles array");
    let mut recipes = HashSet::new();
    for profile in profiles {
        let patch = profile
            .get("patch")
            .and_then(Value::as_str)
            .unwrap_or_else(|| panic!("bridge compatibility profile is missing patch"));
        recipes.insert(patch.to_string());
    }

    let variables = HashMap::from([
        ("minecraft_version", "validation-version"),
        ("loader_version", "validation-loader"),
        ("fabric_api_version", "validation-fabric-api"),
        ("fabric_version", "validation-fabric-api"),
        ("java_version", "21"),
        ("gradle_version", "9.5.1"),
        ("loom_version", "1.17.18"),
    ]);
    let validation_root = out.join("bridge-recipe-validation");
    reset_directory(&validation_root, "reset bridge recipe validation directory");

    let staged = out.join("bridge-source");
    let mut recipes = recipes.into_iter().collect::<Vec<_>>();
    recipes.sort();
    for (recipe_index, recipe) in recipes.iter().enumerate() {
        let workspace = validation_root.join(format!("recipe-{recipe_index}"));
        copy_tree(&staged, &workspace);

        let recipe_path = root.join(safe_manifest_relative(recipe));
        let patch: Value = serde_json::from_slice(
            &fs::read(&recipe_path)
                .unwrap_or_else(|error| panic!("read compatibility recipe {recipe}: {error}")),
        )
        .unwrap_or_else(|error| panic!("parse compatibility recipe {recipe}: {error}"));
        if patch.get("schema").and_then(Value::as_i64) != Some(1) {
            panic!("compatibility recipe {recipe} uses an unsupported schema");
        }
        let operations = patch
            .get("operations")
            .and_then(Value::as_array)
            .unwrap_or_else(|| panic!("compatibility recipe {recipe} is missing operations"));
        for (index, operation) in operations.iter().enumerate() {
            apply_recipe_validation_operation(
                root,
                &workspace,
                operation,
                &variables,
                recipe,
                index + 1,
            );
        }
        println!("cargo:rerun-if-changed={}", recipe_path.display());
    }

    let _ = fs::remove_dir_all(validation_root);
}

fn write_placeholder_runtime_assets(out: &Path, label: &[u8]) {
    fs::write(out.join("minesport-engine.jar"), label).expect("write placeholder engine payload");
    for (_, _, _, embedded_name) in BUNDLED_EXPORT_WORKERS {
        fs::write(out.join(embedded_name), label)
            .unwrap_or_else(|error| panic!("write placeholder {embedded_name} payload: {error}"));
    }
}

fn main() {
    let manifest = manifest_dir();
    let root = repo_root();
    let ui = manifest.join("ui").join("workbench-v3.slint");
    let engine_libs = root.join("engine").join("build").join("libs");
    let bridge_staged = root.join("dist").join("bundled-export-worker");
    let bridge_versions = root.join("minesport-bridge-fabric-versions");
    let blender_addon = manifest
        .join("assets")
        .join("blender")
        .join("minesport_translator");

    println!("cargo:rerun-if-changed={}", ui.display());
    println!("cargo:rerun-if-env-changed=MINESPORT_ENGINE_JAR");
    println!("cargo:rerun-if-env-changed=MINESPORT_EXPORT_WORKER_JAR");
    for (_, env_name, staged_name, _) in BUNDLED_EXPORT_WORKERS {
        println!("cargo:rerun-if-env-changed={env_name}");
        println!(
            "cargo:rerun-if-changed={}",
            bridge_staged.join(staged_name).display()
        );
    }
    println!("cargo:rerun-if-env-changed=MINESPORT_HEADLESS_BRIDGE_PREPARE");
    println!("cargo:rerun-if-env-changed=MINESPORT_FAST_CHECK");
    println!("cargo:rerun-if-changed={}", engine_libs.display());
    println!(
        "cargo:rerun-if-changed={}",
        root.join("engine").join("VERSION").display()
    );
    println!("cargo:rerun-if-changed={}", bridge_versions.display());
    println!("cargo:rerun-if-changed={}", blender_addon.display());

    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    stage_bridge_sources(&root, &out);
    validate_bridge_recipes(&root, &out);

    let headless_recipe = env::var_os("MINESPORT_HEADLESS_BRIDGE_PREPARE").is_some();
    if headless_recipe {
        write_placeholder_runtime_assets(&out, b"headless-recipe-helper");
        return;
    }

    // Fast checks must still compile the real Slint contract, but they should
    // never require a 20-minute Forge/NeoForge build just to satisfy include_bytes!.
    // Small non-empty placeholders keep every Rust embedding path type-checked.
    let fast_check = env::var_os("MINESPORT_FAST_CHECK").is_some();
    slint_build::compile(ui).expect("compile Minesport Slint UI");
    if fast_check {
        write_placeholder_runtime_assets(&out, b"minesport-fast-check-placeholder");
        return;
    }

    let engine = find_engine_jar()
        .expect("Minesport engine JAR not found. Build /engine first or set MINESPORT_ENGINE_JAR.");
    fs::copy(&engine, out.join("minesport-engine.jar"))
        .expect("embed Minesport engine JAR into Rust desktop build");

    for (loader, env_name, staged_name, embedded_name) in BUNDLED_EXPORT_WORKERS {
        let bridge = find_export_worker_jar(env_name, staged_name).unwrap_or_else(|| {
            panic!(
                "Minesport {loader} Export Worker JAR not found. Build and stage dist/bundled-export-worker/{staged_name} first or set {env_name}."
            )
        });
        fs::copy(&bridge, out.join(embedded_name)).unwrap_or_else(|error| {
            panic!("embed canonical Minesport {loader} Export Worker JAR into Rust desktop build: {error}")
        });
    }
}
