use std::{
    env, fs,
    path::{Component, Path, PathBuf},
};

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

    let root = repo_root().join("engine").join("build").join("libs");
    let entries = fs::read_dir(root).ok()?;
    let mut candidates: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path
                    .extension()
                    .and_then(|ext| ext.to_str())
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| {
                        name.starts_with("minesport-engine-") && !name.contains("sources")
                    })
        })
        .collect();
    candidates.sort();
    candidates.pop()
}

fn find_bridge_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_BRIDGE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }
    let staged = repo_root()
        .join("dist")
        .join("bundled-bridge")
        .join("minesport-bridge-0.2.0.jar");
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

fn stage_bridge_sources(root: &Path, out: &Path) {
    let manifest_path = root.join("bridge-versions").join("manifest.json");
    let manifest_bytes = fs::read(&manifest_path).expect("read bridge compatibility manifest");
    let manifest: serde_json::Value =
        serde_json::from_slice(&manifest_bytes).expect("parse bridge compatibility manifest");

    let base = manifest
        .get("base")
        .and_then(serde_json::Value::as_object)
        .expect("bridge manifest base object");
    let source_root = base
        .get("source_root")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("bridge");
    let source_root = safe_manifest_relative(source_root);
    let files = base
        .get("files")
        .and_then(serde_json::Value::as_array)
        .expect("bridge manifest base.files array");
    if files.is_empty() {
        panic!("bridge manifest base.files must not be empty");
    }

    let staged = out.join("bridge-source");
    if staged.exists() {
        fs::remove_dir_all(&staged).expect("reset staged Bridge source directory");
    }

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

fn main() {
    let manifest = manifest_dir();
    let root = repo_root();
    let ui = manifest.join("ui").join("main.slint");
    let engine_libs = root.join("engine").join("build").join("libs");
    let bridge_staged = root
        .join("dist")
        .join("bundled-bridge")
        .join("minesport-bridge-0.2.0.jar");
    let bridge_versions = root.join("bridge-versions");
    let blender_addon = root
        .join("wrapper")
        .join("blendertranslator")
        .join("minesport_translator");

    println!("cargo:rerun-if-changed={}", ui.display());
    println!("cargo:rerun-if-env-changed=MINESPORT_ENGINE_JAR");
    println!("cargo:rerun-if-env-changed=MINESPORT_BRIDGE_JAR");
    println!("cargo:rerun-if-env-changed=MINESPORT_HEADLESS_BRIDGE_PREPARE");
    println!("cargo:rerun-if-changed={}", engine_libs.display());
    println!("cargo:rerun-if-changed={}", bridge_staged.display());
    println!("cargo:rerun-if-changed={}", bridge_versions.display());
    println!("cargo:rerun-if-changed={}", blender_addon.display());

    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    stage_bridge_sources(&root, &out);

    let headless_recipe = env::var_os("MINESPORT_HEADLESS_BRIDGE_PREPARE").is_some();
    if headless_recipe {
        // `cargo run --bin bridge-prepare` does not compile the Slint main binary
        // and never executes the embedded Java/Bridge materializers. Keep tiny
        // placeholders only so shared runtime.rs include_bytes! paths remain
        // syntactically valid for the headless recipe helper.
        fs::write(out.join("minesport-engine.jar"), b"headless-recipe-helper")
            .expect("write headless engine placeholder");
        fs::write(out.join("minesport-bridge.jar"), b"headless-recipe-helper")
            .expect("write headless Bridge placeholder");
        return;
    }

    slint_build::compile(ui).expect("compile Minesport Slint UI");

    let engine = find_engine_jar().expect(
        "Minesport engine JAR not found. Build /engine first or set MINESPORT_ENGINE_JAR.",
    );
    let bridge = find_bridge_jar().expect(
        "Minesport Bridge JAR not found. Build /bridge and stage dist/bundled-bridge/minesport-bridge-0.2.0.jar first or set MINESPORT_BRIDGE_JAR.",
    );
    fs::copy(&engine, out.join("minesport-engine.jar"))
        .expect("embed Minesport engine JAR into Rust desktop build");
    fs::copy(&bridge, out.join("minesport-bridge.jar"))
        .expect("embed canonical Minesport Bridge JAR into Rust desktop build");
}
