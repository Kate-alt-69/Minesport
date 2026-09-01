from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


update = Path("desktop/src/engine_update.rs")
replace_once(
    update,
    '''    let reader = response.into_reader().take(MAX_RELEASE_JSON + 1);\n    let release: Release =\n        serde_json::from_reader(reader).context("decode latest Minesport GitHub Release")?;\n    Ok(Some(release))\n''',
    '''    let mut bytes = Vec::new();\n    response\n        .into_reader()\n        .take(MAX_RELEASE_JSON + 1)\n        .read_to_end(&mut bytes)\n        .context("read latest Minesport GitHub Release")?;\n    if bytes.len() as u64 > MAX_RELEASE_JSON {\n        bail!(\n            "latest Minesport GitHub Release response exceeds {} bytes",\n            MAX_RELEASE_JSON\n        );\n    }\n    let release: Release =\n        serde_json::from_slice(&bytes).context("decode latest Minesport GitHub Release")?;\n    Ok(Some(release))\n''',
    "explicit latest-release JSON bound",
)

nsi = Path("installer/windows/minesport.nsi")
replace_once(
    nsi,
    '''  ; Keep one rollback generation. The GUI only considers a replacement healthy\n  ; after hash/protocol verification and an IPC handshake, so .prev must survive\n  ; the installer run until Minesport has had a chance to validate the new engine.\n''',
    '''  ; Keep exactly one rollback generation. Each replacement rotates the\n  ; currently installed engine into .prev; Minesport intentionally preserves that\n  ; generation after verification so a later recovery still has a known fallback.\n''',
    "NSIS rollback-generation comment",
)
