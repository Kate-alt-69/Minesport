"""Minesport 0.1.5+ active-export capability handshake.

The marker is deliberately redundant: exporters place it in the raw OBJ/glTF
and in Minesport metadata when available. Tagged assets enable the richer
0.1.5 import path; untagged assets continue through legacy-safe behavior.
"""

import json
from pathlib import Path


ACTIVE_TAG = "minesport_v1.5_active_export"
ACTIVE_EXPORT_VERSION = "0.1.5"


def detect(asset_path, metadata=None):
    if isinstance(metadata, dict) and metadata.get(ACTIVE_TAG) is True:
        return True
    if not asset_path:
        return False

    path = Path(asset_path)
    suffix = path.suffix.lower()
    try:
        if suffix == ".obj":
            with path.open("r", encoding="utf-8", errors="ignore") as handle:
                for _ in range(64):
                    line = handle.readline()
                    if not line:
                        break
                    if ACTIVE_TAG in line:
                        return True
            return False

        if suffix == ".gltf":
            with path.open("r", encoding="utf-8") as handle:
                root = json.load(handle)
            if not isinstance(root, dict):
                return False
            asset = root.get("asset")
            extras = asset.get("extras") if isinstance(asset, dict) else None
            if isinstance(extras, dict) and extras.get(ACTIVE_TAG) is True:
                return True
            for node in root.get("nodes", []):
                if not isinstance(node, dict):
                    continue
                node_extras = node.get("extras")
                if isinstance(node_extras, dict) and node_extras.get(ACTIVE_TAG) is True:
                    return True
                minesport = node_extras.get("minesport") if isinstance(node_extras, dict) else None
                if isinstance(minesport, dict) and minesport.get(ACTIVE_TAG) is True:
                    return True
    except Exception as exc:
        print(f"[Minesport Translator] active-export detection failed for {path}: {exc}")
    return False


def activate(asset_path, metadata=None, objects=None):
    """Mark an imported asset as using the 0.1.5+ richer Minesport contract."""
    active = detect(asset_path, metadata)
    if not active:
        return False

    try:
        import bpy
    except Exception:
        return True

    targets = list(objects) if objects is not None else [
        obj for obj in bpy.context.scene.objects
        if getattr(getattr(obj, "minesport", None), "translated", False)
        or obj.get("minesport_type") == "FLATTER"
    ]
    for obj in targets:
        obj[ACTIVE_TAG] = True
        obj["minesport_active_export_version"] = ACTIVE_EXPORT_VERSION
    bpy.context.scene[ACTIVE_TAG] = True
    bpy.context.scene["minesport_active_export_version"] = ACTIVE_EXPORT_VERSION
    print(f"[Minesport Translator] active export {ACTIVE_EXPORT_VERSION} detected")
    return True
