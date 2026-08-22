use std::{env, fs, path::{Path, PathBuf}};

fn manifest_dir() -> PathBuf {
    PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"))
}

fn find_engine_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_ENGINE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    let root = manifest_dir()
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join("engine")
        .join("build")
        .join("libs");
    let entries = fs::read_dir(root).ok()?;
    let mut candidates: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path.extension().and_then(|ext| ext.to_str()).is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
                && path.file_name().and_then(|name| name.to_str()).is_some_and(|name| {
                    name.starts_with("minesport-engine-") && !name.contains("sources")
                })
        })
        .collect();
    candidates.sort();
    candidates.pop()
}

fn main() {
    let manifest = manifest_dir();
    let ui = manifest.join("ui").join("main.slint");
    let engine_libs = manifest.parent().unwrap_or_else(|| Path::new(".")).join("engine").join("build").join("libs");

    println!("cargo:rerun-if-changed={}", ui.display());
    println!("cargo:rerun-if-env-changed=MINESPORT_ENGINE_JAR");
    println!("cargo:rerun-if-changed={}", engine_libs.display());

    slint_build::compile(ui).expect("compile Minesport Slint UI");

    let engine = find_engine_jar().expect(
        "Minesport engine JAR not found. Build /engine first or set MINESPORT_ENGINE_JAR.",
    );
    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    fs::copy(&engine, out.join("minesport-engine.jar"))
        .expect("embed Minesport engine JAR into Rust desktop build");
}
