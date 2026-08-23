"""Stable FLATTER source fingerprints for incremental Blender refreshes."""

import hashlib
import json

import bpy

from . import flatter


SOURCE_HASH_KEY = "minesport_flatter_source_hash"
_ORIGINAL_ATTACH = None


def record_hash(record):
    if not isinstance(record, dict):
        return ""
    raw = json.dumps(
        record,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def source_hash(obj):
    return str(obj.get(SOURCE_HASH_KEY) or "") if obj is not None else ""


def _find_record_object(record, candidates):
    mesh_name = str(record.get("meshObject") or record.get("id") or "")
    if not mesh_name:
        return None
    exact = next((obj for obj in candidates if obj.name == mesh_name), None)
    if exact is not None:
        return exact
    return next(
        (
            obj for obj in candidates
            if obj.name.startswith(mesh_name + ".")
            and obj.get("minesport_type") == flatter._TYPE_FLATTER
        ),
        None,
    )


def _attach_with_source_hash(metadata, objects=None, asset_path=None):
    _ORIGINAL_ATTACH(metadata, objects=objects, asset_path=asset_path)
    if not isinstance(metadata, dict):
        return
    records = metadata.get("flatterObjects")
    if not isinstance(records, list):
        return
    candidates = list(objects) if objects is not None else list(bpy.context.scene.objects)
    for record in records:
        if not isinstance(record, dict):
            continue
        obj = _find_record_object(record, candidates)
        if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
            continue
        digest = record_hash(record)
        if digest:
            obj[SOURCE_HASH_KEY] = digest


def register():
    global _ORIGINAL_ATTACH
    if _ORIGINAL_ATTACH is not None:
        return
    _ORIGINAL_ATTACH = flatter.attach_flatter_metadata
    flatter.attach_flatter_metadata = _attach_with_source_hash


def unregister():
    global _ORIGINAL_ATTACH
    if _ORIGINAL_ATTACH is None:
        return
    flatter.attach_flatter_metadata = _ORIGINAL_ATTACH
    _ORIGINAL_ATTACH = None
