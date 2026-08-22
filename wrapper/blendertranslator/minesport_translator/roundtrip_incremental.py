"""Incremental FLATTER-cell reuse layered over Minesport round-trip refresh."""

from pathlib import Path

import bpy

from . import flatter
from . import incremental_refresh
from . import liquid_merge
from . import roundtrip


_ORIGINAL_EXECUTE = None


def _flatters_by_id(objects):
    result = {}
    for obj in objects:
        if obj.get("minesport_type") != flatter._TYPE_FLATTER:
            continue
        identifier = roundtrip._flatter_id(obj)
        if identifier:
            result[identifier] = obj
    return result


def _helper_parent_id(obj):
    return str(
        obj.get("minesport_flatter_parent")
        or obj.get(liquid_merge.PROXY_PARENT_KEY)
        or ""
    )


def _belongs_to_ids(obj, identifiers):
    if obj.get("minesport_type") == flatter._TYPE_FLATTER:
        return roundtrip._flatter_id(obj) in identifiers
    if obj.get("minesport_type") in roundtrip._HELPER_TYPES:
        return _helper_parent_id(obj) in identifiers
    return False


def _incremental_execute(self, context):
    scene = context.scene
    source = roundtrip._scene_source(scene)
    if not source:
        self.report({"ERROR"}, "This .blend has no Minesport source export")
        return {"CANCELLED"}
    if not Path(source).is_file():
        self.report({"ERROR"}, f"Minesport source export is missing: {source}")
        return {"CANCELLED"}

    project_id = roundtrip._scene_project_id(scene)
    old_managed = roundtrip._managed_objects(scene, project_id, source)
    if not old_managed:
        self.report({"ERROR"}, "No Minesport-managed objects match this source/project")
        return {"CANCELLED"}

    old_flatters = _flatters_by_id(old_managed)
    runtime = roundtrip._capture_flatter_runtime(old_managed)
    before = {obj.as_pointer() for obj in bpy.data.objects}

    try:
        imported = roundtrip._import_source(source)
        if not imported:
            raise RuntimeError("source import produced no Blender objects")

        new_flatters = _flatters_by_id(imported)
        unchanged = set()
        for identifier, old_obj in old_flatters.items():
            new_obj = new_flatters.get(identifier)
            if new_obj is None:
                continue
            old_hash = incremental_refresh.source_hash(old_obj)
            new_hash = incremental_refresh.source_hash(new_obj)
            if old_hash and new_hash and old_hash == new_hash:
                unchanged.add(identifier)

        redundant_new = [
            obj for obj in imported
            if obj.get("minesport_type") == flatter._TYPE_FLATTER
            and roundtrip._flatter_id(obj) in unchanged
        ]
        if redundant_new:
            redundant_set = set(redundant_new)
            roundtrip._remove_objects(redundant_new)
            imported = [obj for obj in imported if obj not in redundant_set]

        changed_runtime = {
            identifier: state
            for identifier, state in runtime.items()
            if identifier not in unchanged
        }
        restored = roundtrip._restore_flatter_runtime_before_delete(
            imported,
            changed_runtime,
        )
    except Exception as exc:
        roundtrip._cleanup_failed_import(before)
        self.report({"ERROR"}, f"Minesport update failed; old scene was kept: {exc}")
        return {"CANCELLED"}

    old_to_remove = [
        obj for obj in old_managed
        if not _belongs_to_ids(obj, unchanged)
    ]
    roundtrip._remove_objects(old_to_remove)
    roundtrip._normalize_imported_names(imported)
    warnings = roundtrip._finalize_flatter_runtime(restored)

    retained = [old_flatters[identifier] for identifier in sorted(unchanged)]
    selectable = [
        obj for obj in imported + retained
        if obj.name in bpy.data.objects and obj.type in {"MESH", "EMPTY", "ARMATURE"}
    ]
    bpy.ops.object.select_all(action="DESELECT")
    for obj in selectable:
        try:
            obj.select_set(True)
        except Exception:
            pass
    if selectable:
        context.view_layer.objects.active = selectable[0]

    refreshed_cells = max(0, len(new_flatters) - len(unchanged))
    summary = (
        f"Reused {len(unchanged)} unchanged FLATTER cell(s) · "
        f"refreshed {refreshed_cells} changed/new cell(s)"
    )
    if warnings:
        print("[Minesport Round Trip] runtime restore warnings:")
        for warning in warnings:
            print("  - " + warning)
        self.report({"WARNING"}, summary + f" · {len(warnings)} runtime warning(s)")
    else:
        self.report({"INFO"}, summary)
    return {"FINISHED"}


def register():
    global _ORIGINAL_EXECUTE
    if _ORIGINAL_EXECUTE is not None:
        return
    _ORIGINAL_EXECUTE = roundtrip.MINESPORT_OT_roundtrip_update.execute
    roundtrip.MINESPORT_OT_roundtrip_update.execute = _incremental_execute


def unregister():
    global _ORIGINAL_EXECUTE
    if _ORIGINAL_EXECUTE is None:
        return
    roundtrip.MINESPORT_OT_roundtrip_update.execute = _ORIGINAL_EXECUTE
    _ORIGINAL_EXECUTE = None
