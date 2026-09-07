from pathlib import Path

path = Path('desktop/src/registry.rs')
text = path.read_text(encoding='utf-8')

def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    text = text.replace(old, new, 1)

replace_once(
    '.join(safe_component(fingerprint))\n        .join(REGISTRY_FILE)',
    '.join(fingerprint_component(fingerprint))\n        .join(REGISTRY_FILE)',
    'snapshot fingerprint component',
)
replace_once(
    '    let keep = safe_component(keep);\n',
    '    let keep = fingerprint_component(keep);\n',
    'legacy registry prune keep component',
)

anchor = '''fn hex_lower(bytes: &[u8]) -> String { let mut output = String::with_capacity(bytes.len() * 2); for byte in bytes { use std::fmt::Write as _; let _ = write!(output, "{byte:02x}"); } output }\nfn safe_component(value: &str) -> String {'''
insert = '''fn hex_lower(bytes: &[u8]) -> String { let mut output = String::with_capacity(bytes.len() * 2); for byte in bytes { use std::fmt::Write as _; let _ = write!(output, "{byte:02x}"); } output }\n\nfn fingerprint_component(value: &str) -> String {\n    let value = value.trim();\n    if !value.is_empty()\n        && value.len() <= 80\n        && value\n            .bytes()\n            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_'))\n    {\n        // Preserve legacy full-cache locations when their fingerprint was\n        // already a safe component. Only long/unsafe identities are re-keyed.\n        return value.to_string();\n    }\n\n    let mut digest = Sha256::new();\n    digest.update(b"minesport-registry-fingerprint-path-v1\\0");\n    digest.update(value.as_bytes());\n    format!("fp-{}", hex_lower(&digest.finalize()))\n}\n\nfn safe_component(value: &str) -> String {'''
replace_once(anchor, insert, 'fingerprint component helper')

anchor = '''    #[test] fn safe_components_cannot_escape_registry_root() { assert_eq!(safe_component("../1.21.10"), ".._1.21.10"); assert!(!safe_component("../../bad").contains('/')); }\n    #[test] fn writer_uses_big_endian_float_bits() {'''
insert = '''    #[test] fn safe_components_cannot_escape_registry_root() { assert_eq!(safe_component("../1.21.10"), ".._1.21.10"); assert!(!safe_component("../../bad").contains('/')); }\n    #[test]\n    fn fingerprint_components_preserve_legacy_short_identity() {\n        let fingerprint = "neoforge-0123456789abcdef";\n        assert_eq!(fingerprint_component(fingerprint), fingerprint);\n    }\n    #[test]\n    fn long_scoped_fingerprint_paths_preserve_full_identity() {\n        let base = format!("neoforge-{}-scope-", "a".repeat(64));\n        let first = format!("{base}{}", "1".repeat(64));\n        let second = format!("{base}{}", "2".repeat(64));\n        assert_eq!(&first[..80], &second[..80]);\n\n        let first_path = snapshot_path(Path::new("cache"), "1.21.10", &first);\n        let second_path = snapshot_path(Path::new("cache"), "1.21.10", &second);\n        assert_ne!(first_path, second_path);\n\n        for path in [first_path, second_path] {\n            let component = path\n                .parent()\n                .and_then(Path::file_name)\n                .and_then(|value| value.to_str())\n                .unwrap();\n            assert!(component.starts_with("fp-"));\n            assert_eq!(component.len(), 67);\n            assert!(component\n                .bytes()\n                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_')));\n        }\n    }\n    #[test] fn writer_uses_big_endian_float_bits() {'''
replace_once(anchor, insert, 'fingerprint identity tests')

path.write_text(text, encoding='utf-8')
