use std::{env, fs, path::PathBuf};

fn main() {
    let desktop = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap())
        .parent().unwrap().to_path_buf();
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let picker_source = desktop.join("src/world_picker.rs");
    println!("cargo:rerun-if-changed={}", picker_source.display());
    let source = fs::read_to_string(picker_source).unwrap();
    let markup = source.split_once("slint::slint! {").unwrap().1
        .split_once("\n}\n").unwrap().0;
    let picker = output.join("picker.slint");
    fs::write(&picker, markup).unwrap();
    let preview = output.join("preview.slint");
    // Forward slashes keep generated Slint import strings valid on Windows too.
    let workbench_path = desktop.join("ui/workbench-v3.slint").to_string_lossy().replace('\\', "/");
    let picker_path = picker.to_string_lossy().replace('\\', "/");
    fs::write(&preview, format!(
        "import {{ MainWindow }} from \"{workbench_path}\";\nimport {{ LauncherWorldPicker, PickerRow }} from \"{picker_path}\";\nexport {{ MainWindow, LauncherWorldPicker, PickerRow }}\n"
    )).unwrap();
    slint_build::compile_with_config(preview, slint_build::CompilerConfiguration::new().with_style("fluent".into()))
        .expect("Minesport UX must compile independently of Java/Bridge artifacts");
}
