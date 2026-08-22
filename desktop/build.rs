use std::{env, fs, path::{Path, PathBuf}};

fn find_engine_jar() -> Option<PathBuf> {
    if let Ok(path) = env::var("MINESPORT_ENGINE_JAR") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    let root = Path::new("..").join("engine").join("build").join("libs");
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
    println!("cargo:rerun-if-changed=ui/main.slint");
    println!("cargo:rerun-if-env-changed=MINESPORT_ENGINE_JAR");
    println!("cargo:rerun-if-changed=../engine/build/libs");

    slint_build::compile("ui/main.slint").expect("compile Minesport Slint UI");

    let engine = find_engine_jar().expect(
        "Minesport engine JAR not found. Build /engine first or set MINESPORT_ENGINE_JAR.",
    );
    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    fs::copy(&engine, out.join("minesport-engine.jar"))
        .expect("embed Minesport engine JAR into Rust desktop build");
}
