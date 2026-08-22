use std::{env, fs, path::{Path, PathBuf}};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"))
}

fn repo_root() -> PathBuf {
    manifest_dir().parent().unwrap_or_else(|| Path::new(".")).to_path_buf()
}

fn find_engine_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_ENGINE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() { return Some(path); }
    }

    let root = repo_root().join("engine").join("build").join("libs");
    let entries = fs::read_dir(root).ok()?;
    let mut candidates: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path.extension().and_then(|ext| ext.to_str()).is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
                && path.file_name().and_then(|name| name.to_str()).is_some_and(|name| name.starts_with("minesport-engine-") && !name.contains("sources"))
        })
        .collect();
    candidates.sort();
    candidates.pop()
}

fn find_bridge_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_BRIDGE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() { return Some(path); }
    }
    let staged = repo_root().join("dist").join("bundled-bridge").join("minesport-bridge-0.2.0.jar");
    staged.is_file().then_some(staged)
}

fn main() {
    let manifest = manifest_dir();
    let root = repo_root();
    let ui = manifest.join("ui").join("main.slint");
    let engine_libs = root.join("engine").join("build").join("libs");
    let bridge_staged = root.join("dist").join("bundled-bridge").join("minesport-bridge-0.2.0.jar");
    let bridge_source = root.join("bridge");
    let bridge_versions = root.join("bridge-versions");
    let blender_addon = root.join("wrapper").join("blendertranslator").join("minesport_translator");

    println!("cargo:rerun-if-changed={}", ui.display());
    println!("cargo:rerun-if-env-changed=MINESPORT_ENGINE_JAR");
    println!("cargo:rerun-if-env-changed=MINESPORT_BRIDGE_JAR");
    println!("cargo:rerun-if-changed={}", engine_libs.display());
    println!("cargo:rerun-if-changed={}", bridge_staged.display());
    println!("cargo:rerun-if-changed={}", bridge_source.display());
    println!("cargo:rerun-if-changed={}", bridge_versions.display());
    println!("cargo:rerun-if-changed={}", blender_addon.display());

    slint_build::compile(ui).expect("compile Minesport Slint UI");

    let engine = find_engine_jar().expect("Minesport engine JAR not found. Build /engine first or set MINESPORT_ENGINE_JAR.");
    let bridge = find_bridge_jar().expect("Minesport Bridge JAR not found. Build /bridge and stage dist/bundled-bridge/minesport-bridge-0.2.0.jar first or set MINESPORT_BRIDGE_JAR.");
    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    fs::copy(&engine, out.join("minesport-engine.jar")).expect("embed Minesport engine JAR into Rust desktop build");
    fs::copy(&bridge, out.join("minesport-bridge.jar")).expect("embed canonical Minesport Bridge JAR into Rust desktop build");
}
