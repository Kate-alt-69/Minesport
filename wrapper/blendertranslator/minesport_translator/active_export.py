"""Minesport active-export capability and project-identity handshake.

The active-export marker enables the richer FLATTER path. Project identity is
orthogonal: any Minesport sidecar can carry projectId/projectPath so an imported
.blend can later be matched back to the workbench that created it.
"""

import json
from pathlib import Path


ACTIVE_TAG = "minesport_v1.5_active_export"
ACTIVE_EXPORT_VERSION = "0.1.6"
PROJECT_ID_KEY = "minesport_project_id"
PROJECT_PATH_KEY = "minesport_project_path"


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


def _project_identity(metadata):
    if not isinstance(metadata, dict):
        return "", ""
    project_id = str(metadata.get("projectId") or "").strip()
    project_path = str(metadata.get("projectPath") or "").strip()
    return project_id, project_path


def activate(asset_path, metadata=None, objects=None):
    """Activate richer export features and persist Minesport project identity."""
    active = detect(asset_path, metadata)
    project_id, project_path = _project_identity(metadata)
    if not active and not project_id:
        return False

    try:
        import bpy
    except Exception:
        return active

    targets = list(objects) if objects is not None else [
        obj for obj in bpy.context.scene.objects
        if getattr(getattr(obj, "minesport", None), "translated", False)
        or obj.get("minesport_type") == "FLATTER"
    ]
    for obj in targets:
        if active:
            obj[ACTIVE_TAG] = True
            obj["minesport_active_export_version"] = ACTIVE_EXPORT_VERSION
        if project_id:
            obj[PROJECT_ID_KEY] = project_id
            if project_path:
                obj[PROJECT_PATH_KEY] = project_path

    if active:
        bpy.context.scene[ACTIVE_TAG] = True
        bpy.context.scene["minesport_active_export_version"] = ACTIVE_EXPORT_VERSION
        print(f"[Minesport Translator] active export {ACTIVE_EXPORT_VERSION} detected")
    if project_id:
        bpy.context.scene[PROJECT_ID_KEY] = project_id
        if project_path:
            bpy.context.scene[PROJECT_PATH_KEY] = project_path
        print(f"[Minesport Translator] project identity: {project_id[:8]}")
    return active
