from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/build.rs")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''    configure_build_revision(&root);
    println!("cargo:rerun-if-changed={}", ui.display());
''',
    '''    configure_build_revision(&root);
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        // The generated Slint Workbench constructor is deep enough that the
        // default MSVC 1 MiB main-thread reserve leaves very little headroom on
        // real Windows systems. Reserve 8 MiB of virtual address space; pages
        // are still committed on demand, so this is not an 8 MiB RAM tax.
        println!("cargo:rustc-link-arg-bin=minesport=/STACK:8388608");
    }
    println!("cargo:rerun-if-changed={}", ui.display());
''',
    "Windows Workbench stack reserve",
)
path.write_text(text, encoding="utf-8")
