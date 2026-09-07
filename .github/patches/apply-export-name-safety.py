from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/app.rs")
text = path.read_text(encoding="utf-8")

old = r'''fn sanitize_export_name(value: &str) -> String {
    let cleaned: String = value
        .trim()
        .chars()
        .map(|ch| match ch {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            ch if ch.is_control() => '_',
            _ => ch,
        })
        .collect();
    let cleaned = cleaned.trim_matches([' ', '.']);
    if cleaned.is_empty() {
        "Minesport_Export".to_string()
    } else {
        cleaned.to_string()
    }
}
'''
new = r'''fn sanitize_export_name(value: &str) -> String {
    let cleaned: String = value
        .trim()
        .chars()
        .map(|ch| match ch {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            ch if ch.is_control() => '_',
            _ => ch,
        })
        .collect();
    let cleaned = cleaned.trim_matches([' ', '.']);
    if cleaned.is_empty() {
        return "Minesport_Export".to_string();
    }
    if windows_reserved_export_name(cleaned) {
        format!("_{cleaned}")
    } else {
        cleaned.to_string()
    }
}

fn windows_reserved_export_name(value: &str) -> bool {
    // Windows reserves device basenames even when an extension is present
    // (for example CON.gltf or NUL.obj). Prefix those names so an export chosen
    // on another platform also remains portable to Windows.
    let basename = value
        .split('.')
        .next()
        .unwrap_or(value)
        .trim_end_matches([' ', '.'])
        .to_ascii_uppercase();
    matches!(basename.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || basename
            .strip_prefix("COM")
            .is_some_and(|suffix| matches!(suffix, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"))
        || basename
            .strip_prefix("LPT")
            .is_some_and(|suffix| matches!(suffix, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"))
}
'''
text = replace_once(text, old, new, "Windows reserved export names")

old_test = '''    #[test]
    fn sanitizes_windows_export_name() {
        assert_eq!(sanitize_export_name(" chest:test? "), "chest_test_");
    }
'''
new_test = '''    #[test]
    fn sanitizes_windows_export_name() {
        assert_eq!(sanitize_export_name(" chest:test? "), "chest_test_");
    }

    #[test]
    fn sanitizes_windows_reserved_device_export_names() {
        for reserved in [
            "CON", "con", "NUL", "AUX", "PRN", "COM1", "com9", "LPT1", "lpt9",
            "CON.backup", "NUL.world",
        ] {
            let sanitized = sanitize_export_name(reserved);
            assert!(sanitized.starts_with('_'), "{reserved} -> {sanitized}");
            assert!(!windows_reserved_export_name(&sanitized));
        }
        assert_eq!(sanitize_export_name("COM10"), "COM10");
        assert_eq!(sanitize_export_name("console"), "console");
        assert_eq!(sanitize_export_name("LPT10"), "LPT10");
    }
'''
text = replace_once(text, old_test, new_test, "reserved export name regression")

path.write_text(text, encoding="utf-8")
