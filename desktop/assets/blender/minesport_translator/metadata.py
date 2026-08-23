import json
from pathlib import Path


def sidecar_path(asset_path: str) -> Path:
    path = Path(asset_path)
    return path.with_suffix(".minesport.json")


def load_sidecar(asset_path: str):
    path = sidecar_path(asset_path)
    if not path.exists():
        return None
    try:
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle)
        if isinstance(value, dict) and value.get("schema") == 1:
            return value
    except Exception as exc:
        print(f"[Minesport Translator] failed to read {path}: {exc}")
    return None


def gltf_asset_path(gltf):
    for name in ("filename", "filepath"):
        value = getattr(gltf, name, None)
        if isinstance(value, str) and value:
            return value
    settings = getattr(gltf, "import_settings", None)
    if isinstance(settings, dict):
        for key in ("filepath", "filename"):
            value = settings.get(key)
            if isinstance(value, str) and value:
                return value
    return None
