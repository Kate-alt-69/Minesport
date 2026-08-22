"""Minesport -> Blender round-trip refresh.

This is deliberately a full Minesport-managed scene refresh first, not a fake
"changed chunks only" implementation. User-created cameras/lights/objects are
left alone. FLATTER runtime state is copied by stable FLATTER id before old
Minesport objects are removed.
"""

from pathlib import Path

import bpy

from . import active_export
from . import flatter
from . import liquid_merge
from . import selection_sets
from .gltf_import import _MANUAL_IMPORT_GUARD, _prepare_scene
from .metadata import load_sidecar
from .obj_import import _prepare_blender_scene
from .translate import translate_scene

_RUNTIME_CUSTOM_KEYS = (
    flatter._SELECTED_KEY,
    liquid_merge.SELECTION_KEY,
    liquid_merge.MERGE_KEY,
    selection_sets.SETS_KEY,
    "minesport_logical_selection_type",
    "minesport_logical_selection_label",
    "minesport_logical_selection_xyz",
)
_RUNTIME_PROPERTY_NAMES = (
    "flatter_interaction_mode",
    "flatter_overlay_mode",
    "flatter_overlay_opacity",
    "flatter_overlay_xray",
    "flatter_materialization_limit",
    "flatter_active_set",
)
_HELPER_TYPES = {flatter._TYPE_MATERIALIZED, liquid_merge.TYPE_LIQUID_BLOCK}


def _clean_path(value):
    value = str(value or "").strip()
    if not value:
        return ""
    try:
        return str(Path(value).expanduser().resolve())
    except Exception:
        return str(Path(value).expanduser())


def _scene_source(scene):
    return _clean_path(scene.get(active_export.SOURCE_ASSET_KEY, ""))


def _scene_project_id(scene):
    return str(scene.get(active_export.PROJECT_ID_KEY, "") or "").strip()


def _flatter_id(obj):
    return str(obj.get("minesport_flatter_id") or "").strip()


def _is_translated(obj):
    props = getattr(obj, "minesport", None)
    return bool(getattr(props, "translated", False)) if props is not None else False


def _same_source(obj, source):
    if not source:
        return False
    return _clean_path(obj.get(active_export.SOURCE_ASSET_KEY, "")) == source


def _same_project(obj, project_id):
    if not project_id:
        return False
    return str(obj.get(active_export.PROJECT_ID_KEY, "") or "").strip() == project_id


def _managed_objects(scene, project_id, source):
    result = set()
    flatter_parent_ids = set()
    for obj in scene.objects:
        direct = _same_project(obj, project_id) or _same_source(obj, source)
        if direct and (_is_translated(obj) or obj.get("minesport_type")):
            result.add(obj)
            if obj.get("minesport_type") == flatter._TYPE_FLATTER:
                flatter_parent_ids.add(_flatter_id(obj) or obj.name)
    if flatter_parent_ids:
        for obj in scene.objects:
            if obj in result or obj.get("minesport_type") not in _HELPER_TYPES:
                continue
            parent_id = str(
                obj.get("minesport_flatter_parent")
                or obj.get(liquid_merge.PROXY_PARENT_KEY)
                or ""
            )
            if parent_id in flatter_parent_ids:
                result.add(obj)
    return list(result)


def _copy_id_value(value):
    if isinstance(value, (list, tuple)):
        return list(value)
    return value


def _capture_flatter_runtime(objects):
    states = {}
    for obj in objects:
        if obj.get("minesport_type") != flatter._TYPE_FLATTER:
            continue
        identifier = _flatter_id(obj)
        if not identifier:
            continue
        state = {
            "name": obj.name,
            "matrix_world": obj.matrix_world.copy(),
            "custom": {},
            "properties": {},
            "action": None,
        }
        for key in _RUNTIME_CUSTOM_KEYS:
            if key in obj:
                state["custom"][key] = _copy_id_value(obj[key])
        props = getattr(obj, "minesport", None)
        if props is not None:
            for name in _RUNTIME_PROPERTY_NAMES:
                try:
                    state["properties"][name] = getattr(props, name)
                except Exception:
                    pass
        animation_data = getattr(obj, "animation_data", None)
        if animation_data is not None:
            state["action"] = getattr(animation_data, "action", None)
        states[identifier] = state
    return states


def _new_flatter_by_id(imported):
    result = {}
    for obj in imported:
        if obj.get("minesport_type") != flatter._TYPE_FLATTER:
            continue
        identifier = _flatter_id(obj)
        if identifier:
            result[identifier] = obj
    return result


def _restore_flatter_runtime_before_delete(imported, states):
    restored = []
    by_id = _new_flatter_by_id(imported)
    for identifier, state in states.items():
        obj = by_id.get(identifier)
        if obj is None:
            continue
        try:
            obj.matrix_world = state["matrix_world"]
        except Exception:
            pass
        for key, value in state["custom"].items():
            try:
                obj[key] = _copy_id_value(value)
            except Exception:
                pass
        props = getattr(obj, "minesport", None)
        if props is not None:
            for name, value in state["properties"].items():
                try:
                    setattr(props, name, value)
                except Exception:
                    pass
        action = state.get("action")
        if action is not None:
            try:
                obj.animation_data_create()
                obj.animation_data.action = action
            except Exception:
                pass
        restored.append((obj, state))
    return restored


def _remove_objects(objects):
    for obj in list(objects):
        data = getattr(obj, "data", None)
        try:
            bpy.data.objects.remove(obj, do_unlink=True)
        except Exception:
            continue
        if data is not None and getattr(data, "users", 1) == 0:
            try:
                if isinstance(data, bpy.types.Mesh):
                    bpy.data.meshes.remove(data)
            except Exception:
                pass


def _normalize_imported_names(imported):
    for obj in imported:
        name = obj.name
        if len(name) < 5 or name[-4] != "." or not name[-3:].isdigit():
            continue
        base = name[:-4]
        if bpy.data.objects.get(base) is None:
            try:
                obj.name = base
            except Exception:
                pass


def _finalize_flatter_runtime(restored):
    warnings = []
    for obj, state in restored:
        try:
            liquid_merge.ensure_control_node(obj)
            desired = bool(
                state["custom"].get(
                    liquid_merge.MERGE_KEY,
                    obj.get(liquid_merge.MERGE_KEY, True),
                )
            )
            liquid_merge._set_merge_state(obj, desired, apply=False)
            liquid_merge._apply_liquid_state(obj, force=True)
        except Exception as exc:
            warnings.append(f"{obj.name}: {exc}")
    return warnings


def _import_gltf(path):
    scene = bpy.context.scene
    before = {obj.as_pointer() for obj in bpy.data.objects}
    previous_guard = scene.get(_MANUAL_IMPORT_GUARD, None)
    scene[_MANUAL_IMPORT_GUARD] = True
    try:
        result = bpy.ops.import_scene.gltf(filepath=path)
    finally:
        if previous_guard is None:
            try:
                del scene[_MANUAL_IMPORT_GUARD]
            except Exception:
                pass
        else:
            scene[_MANUAL_IMPORT_GUARD] = previous_guard
    imported = [obj for obj in bpy.data.objects if obj.as_pointer() not in before]
    if "FINISHED" not in result:
        _remove_objects(imported)
        raise RuntimeError("Blender glTF importer did not finish")
    _prepare_scene(imported)
    metadata = load_sidecar(path)
    translate_scene(metadata, imported, asset_path=path)
    flatter.attach_flatter_metadata(metadata, imported, asset_path=path)
    active_export.activate(path, metadata, imported)
    return imported


def _import_obj(path):
    before = {obj.as_pointer() for obj in bpy.data.objects}
    result = bpy.ops.wm.obj_import(
        filepath=path,
        use_split_objects=True,
        use_split_groups=False,
        validate_meshes=True,
    )
    imported = [obj for obj in bpy.data.objects if obj.as_pointer() not in before]
    if "FINISHED" not in result:
        _remove_objects(imported)
        raise RuntimeError("Blender OBJ importer did not finish")
    _prepare_blender_scene(imported)
    metadata = load_sidecar(path)
    translate_scene(metadata, imported, asset_path=path)
    flatter.attach_flatter_metadata(metadata, imported, asset_path=path)
    active_export.activate(path, metadata, imported)
    return imported


def _import_source(path):
    suffix = Path(path).suffix.lower()
    if suffix in {".gltf", ".glb"}:
        return _import_gltf(path)
    if suffix == ".obj":
        return _import_obj(path)
    raise RuntimeError(f"Unsupported Minesport round-trip source: {suffix or 'no extension'}")


def _cleanup_failed_import(before):
    partial = [obj for obj in bpy.data.objects if obj.as_pointer() not in before]
    _remove_objects(partial)


class MINESPORT_OT_roundtrip_update(bpy.types.Operator):
    bl_idname = "minesport.roundtrip_update"
    bl_label = "Update from Minesport Export"
    bl_description = (
        "Re-import the source Minesport export, replace only Minesport-managed "
        "objects, and preserve the rest of the Blender scene"
    )
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        scene = context.scene
        source = _scene_source(scene)
        if not source:
            self.report({"ERROR"}, "This .blend has no Minesport source export")
            return {"CANCELLED"}
        if not Path(source).is_file():
            self.report({"ERROR"}, f"Minesport source export is missing: {source}")
            return {"CANCELLED"}
        project_id = _scene_project_id(scene)
        old_managed = _managed_objects(scene, project_id, source)
        if not old_managed:
            self.report({"ERROR"}, "No Minesport-managed objects match this source/project")
            return {"CANCELLED"}
        runtime = _capture_flatter_runtime(old_managed)
        before = {obj.as_pointer() for obj in bpy.data.objects}
        try:
            imported = _import_source(source)
            if not imported:
                raise RuntimeError("source import produced no Blender objects")
            restored = _restore_flatter_runtime_before_delete(imported, runtime)
        except Exception as exc:
            _cleanup_failed_import(before)
            self.report({"ERROR"}, f"Minesport update failed; old scene was kept: {exc}")
            return {"CANCELLED"}
        _remove_objects(old_managed)
        _normalize_imported_names(imported)
        warnings = _finalize_flatter_runtime(restored)
        bpy.ops.object.select_all(action="DESELECT")
        selectable = [
            obj for obj in imported
            if obj.name in bpy.data.objects and obj.type in {"MESH", "EMPTY", "ARMATURE"}
        ]
        for obj in selectable:
            try:
                obj.select_set(True)
            except Exception:
                pass
        if selectable:
            context.view_layer.objects.active = selectable[0]
        if warnings:
            print("[Minesport Round Trip] runtime restore warnings:")
            for warning in warnings:
                print("  - " + warning)
            self.report(
                {"WARNING"},
                f"Updated {len(imported)} Minesport object(s); {len(warnings)} FLATTER runtime warning(s) logged",
            )
        else:
            self.report({"INFO"}, f"Updated {len(imported)} Minesport object(s); user scene objects preserved")
        return {"FINISHED"}


def _draw_roundtrip(layout, scene):
    source = _scene_source(scene)
    project_id = _scene_project_id(scene)
    box = layout.box()
    box.label(text="Minesport Round Trip", icon="FILE_REFRESH")
    if source:
        box.label(text=Path(source).name, icon="FILE_3D")
    else:
        box.label(text="No source export attached", icon="ERROR")
    if project_id:
        box.label(text=f"Project {project_id[:8]}", icon="BOOKMARKS")
    row = box.row()
    row.enabled = bool(source) and Path(source).is_file()
    row.operator(MINESPORT_OT_roundtrip_update.bl_idname, text="Update from Export", icon="FILE_REFRESH")
    box.label(text="Full managed refresh for now; cameras, lights and user objects stay.", icon="INFO")


class MINESPORT_PT_roundtrip_view(bpy.types.Panel):
    bl_label = "Round Trip"
    bl_idname = "MINESPORT_PT_roundtrip_view"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"

    @classmethod
    def poll(cls, context):
        return bool(_scene_source(context.scene))

    def draw(self, context):
        _draw_roundtrip(self.layout, context.scene)


class MINESPORT_PT_roundtrip_properties(bpy.types.Panel):
    bl_label = "Minesport Round Trip"
    bl_idname = "MINESPORT_PT_roundtrip_properties"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "scene"

    @classmethod
    def poll(cls, context):
        return bool(_scene_source(context.scene))

    def draw(self, context):
        _draw_roundtrip(self.layout, context.scene)


_CLASSES = (
    MINESPORT_OT_roundtrip_update,
    MINESPORT_PT_roundtrip_view,
    MINESPORT_PT_roundtrip_properties,
)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
