"""Minecraft-aware light translation and FLATTER light placement.

OBJ cannot carry scene lights, so Minesport restores them from the sidecar.
glTF already imports KHR_lights_punctual natively; this module recognizes those
objects by stable Minesport names and adds the original Minecraft light-level
semantics instead of creating duplicates.
"""

import json

import bpy
from mathutils import Vector

from . import flatter
from . import translate as translate_module


TYPE_LIGHT = "MINECRAFT_LIGHT"
LIGHT_LEVEL_KEY = "minesport_light_level"
LIGHT_RANGE_KEY = "minesport_light_range_blocks"
LIGHT_FALLOFF_KEY = "minesport_light_falloff"
LIGHT_SOURCE_KEY = "minesport_light_source"
LIGHT_INVISIBLE_KEY = "minesport_light_invisible_source"
LIGHT_IMPORTED_KEY = "minesport_light_imported"
LIGHT_HELPERS_KEY = "minesport_light_helpers_visible"
LIGHT_COLLECTION_KEY = "minesport_light_collection"

_ORIGINAL_TRANSLATE_SCENE = None


def _safe_name(value):
    value = str(value or "Minesport_Light")
    return value.replace(":", "_").replace("/", "_").replace("\\", "_")


def _metadata_name(metadata):
    if isinstance(metadata, dict):
        value = metadata.get("exportName")
        if isinstance(value, str) and value:
            return value
    return "Minesport_Export"


def _light_collection(metadata):
    name = _safe_name(_metadata_name(metadata)) + "_Lights"
    collection = bpy.data.collections.get(name)
    if collection is None:
        collection = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(collection)
    collection[LIGHT_COLLECTION_KEY] = True
    if LIGHT_HELPERS_KEY not in collection:
        collection[LIGHT_HELPERS_KEY] = False
    return collection


def _objects_in_light_collection(collection):
    return [obj for obj in collection.objects if obj.get("minesport_type") == TYPE_LIGHT]


def _existing_light(name):
    exact = bpy.data.objects.get(name)
    if exact is not None and exact.type == "LIGHT":
        return exact
    for obj in bpy.data.objects:
        if obj.type != "LIGHT":
            continue
        if obj.name.startswith(name + ".") and obj.get("minesport_type") == TYPE_LIGHT:
            return obj
    return None


def _incoming_light(imported, name):
    exact = next((obj for obj in imported if obj.type == "LIGHT" and obj.name == name), None)
    if exact is not None:
        return exact
    return next(
        (obj for obj in imported if obj.type == "LIGHT" and obj.name.startswith(name + ".")),
        None,
    )


def _move_to_collection(obj, collection):
    if collection.objects.get(obj.name) is None:
        collection.objects.link(obj)
    for old in list(obj.users_collection):
        if old == collection:
            continue
        try:
            old.objects.unlink(obj)
        except RuntimeError:
            pass


def _vec3(value, fallback):
    if isinstance(value, (list, tuple)) and len(value) >= 3:
        try:
            return float(value[0]), float(value[1]), float(value[2])
        except Exception:
            pass
    return fallback


def _configure_light(obj, descriptor, helpers_visible=False, imported=True):
    level = max(1, min(15, int(descriptor.get("minecraftLevel", 15))))
    range_blocks = max(0.5, float(descriptor.get("rangeBlocks", level + 0.5)))
    intensity = max(0.0, float(descriptor.get("intensity", 45.0 * level)))
    color = _vec3(descriptor.get("color"), (1.0, 0.92, 0.78))

    data = obj.data
    if data.type != "POINT":
        data.type = "POINT"
    data.color = color
    data.energy = intensity
    # Minecraft's logical reach stays exact in metadata. Blender receives a hard
    # outer range and renders a smooth falloff within it, so level 6 reaches
    # roughly six blocks without looking like Minecraft's old blocky light bands.
    if hasattr(data, "use_custom_distance"):
        data.use_custom_distance = True
    if hasattr(data, "cutoff_distance"):
        data.cutoff_distance = range_blocks
    if hasattr(data, "shadow_soft_size"):
        data.shadow_soft_size = 0.10

    obj["minesport_type"] = TYPE_LIGHT
    obj[LIGHT_LEVEL_KEY] = level
    obj[LIGHT_RANGE_KEY] = range_blocks
    obj[LIGHT_FALLOFF_KEY] = str(descriptor.get("falloff") or "minecraft_linear_smooth")
    obj[LIGHT_SOURCE_KEY] = str(descriptor.get("source") or "minecraft:light")
    obj[LIGHT_INVISIBLE_KEY] = bool(descriptor.get("invisibleSource", False))
    obj[LIGHT_IMPORTED_KEY] = bool(imported)
    obj["minesport_light_decay_rule"] = "one_level_per_block"
    obj["minesport_light_smooth_render"] = True
    obj.hide_render = False
    obj.hide_select = not helpers_visible
    try:
        obj.hide_set(not helpers_visible)
    except Exception:
        pass
    if hasattr(obj, "minesport"):
        obj.minesport.translated = True
        obj.minesport.source_block = str(descriptor.get("source") or "minecraft:light")
        obj.minesport.light_level = level


def translate_lights(metadata, objects=None):
    if not isinstance(metadata, dict):
        return []
    records = metadata.get("lights")
    if not isinstance(records, list):
        return []

    collection = _light_collection(metadata)
    helpers_visible = bool(collection.get(LIGHT_HELPERS_KEY, False))
    imported = list(objects) if objects is not None else list(bpy.context.scene.objects)
    result = []
    keep = set()

    for descriptor in records:
        if not isinstance(descriptor, dict):
            continue
        name = _safe_name(descriptor.get("name"))
        existing = _existing_light(name)
        incoming = _incoming_light(imported, name)

        if existing is not None and incoming is not None and incoming != existing:
            # Full glTF refreshes may import a temporary .001 light because the
            # previous Minesport light already owns the stable name. Reuse the
            # stable object (preserving Blender-side references) and delete only
            # the duplicate native import.
            try:
                bpy.data.objects.remove(incoming, do_unlink=True)
            except Exception:
                pass
            incoming = None

        obj = existing or incoming
        if obj is None:
            data = bpy.data.lights.new(name + "_Data", type="POINT")
            obj = bpy.data.objects.new(name, data)
            collection.objects.link(obj)
        else:
            _move_to_collection(obj, collection)

        position = _vec3(descriptor.get("position"), tuple(obj.location))
        obj.location = position
        _configure_light(obj, descriptor, helpers_visible=helpers_visible, imported=True)
        keep.add(obj.name)
        result.append(obj)

    # Round-trip refresh: remove imported lights that disappeared from Minecraft,
    # but never touch user-created animation/lighting helpers.
    for obj in list(_objects_in_light_collection(collection)):
        if not bool(obj.get(LIGHT_IMPORTED_KEY, False)):
            continue
        if obj.name in keep:
            continue
        try:
            bpy.data.objects.remove(obj, do_unlink=True)
        except Exception:
            pass

    collection["minesport_light_count"] = len(result)
    return result


def _all_light_collections():
    return [
        collection for collection in bpy.data.collections
        if bool(collection.get(LIGHT_COLLECTION_KEY, False))
    ]


def _set_helpers_visible(visible):
    count = 0
    for collection in _all_light_collections():
        collection[LIGHT_HELPERS_KEY] = bool(visible)
        for obj in _objects_in_light_collection(collection):
            obj.hide_select = not visible
            try:
                obj.hide_set(not visible)
            except Exception:
                pass
            count += 1
    return count


def _helpers_visible():
    collections = _all_light_collections()
    return bool(collections) and any(bool(collection.get(LIGHT_HELPERS_KEY, False)) for collection in collections)


def _active_flatter(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    return obj


def _active_light(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.type != "LIGHT" or obj.get("minesport_type") != TYPE_LIGHT:
        return None
    return obj


def _focused_xyz(parent):
    try:
        value = json.loads(parent.get(flatter._SELECTED_KEY, ""))
        if isinstance(value, list) and len(value) >= 3:
            return int(value[0]), int(value[1]), int(value[2])
    except Exception:
        pass
    return None


def _descriptor_for_user_light(name, level, position):
    return {
        "name": name,
        "source": "minecraft:light",
        "type": "point",
        "minecraftLevel": int(level),
        "rangeBlocks": float(level) + 0.5,
        "intensity": 45.0 * float(level),
        "falloff": "minecraft_linear_smooth",
        "invisibleSource": True,
        "position": list(position),
        "color": [1.0, 0.92, 0.78],
    }


class MINESPORT_OT_light_helpers_toggle(bpy.types.Operator):
    bl_idname = "minesport.light_helpers_toggle"
    bl_label = "Toggle Minesport Light Helpers"
    bl_description = "Show or hide Minesport light objects in the viewport without disabling final-render lights"

    def execute(self, context):
        visible = not _helpers_visible()
        count = _set_helpers_visible(visible)
        self.report({"INFO"}, f"{'Showing' if visible else 'Hiding'} {count:,} Minesport light helper(s)")
        return {"FINISHED"}


class MINESPORT_OT_light_apply_level(bpy.types.Operator):
    bl_idname = "minesport.light_apply_level"
    bl_label = "Apply Minecraft Light Level"
    bl_description = "Update this Blender light's smooth range and energy from its Minecraft light level"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        obj = _active_light(context)
        if obj is None:
            return {"CANCELLED"}
        level = max(1, min(15, int(obj.minesport.light_level)))
        descriptor = {
            "minecraftLevel": level,
            "rangeBlocks": level + 0.5,
            "intensity": 45.0 * level,
            "falloff": obj.get(LIGHT_FALLOFF_KEY, "minecraft_linear_smooth"),
            "source": obj.get(LIGHT_SOURCE_KEY, "minecraft:light"),
            "invisibleSource": bool(obj.get(LIGHT_INVISIBLE_KEY, False)),
            "color": list(obj.data.color),
        }
        visible = not obj.hide_get()
        _configure_light(
            obj,
            descriptor,
            helpers_visible=visible,
            imported=bool(obj.get(LIGHT_IMPORTED_KEY, False)),
        )
        self.report({"INFO"}, f"Minecraft light level {level} → smooth {level + 0.5:.1f}-block range")
        return {"FINISHED"}


class MINESPORT_OT_flatter_add_light(bpy.types.Operator):
    bl_idname = "minesport.flatter_add_light"
    bl_label = "Place Minecraft Light"
    bl_description = "Place a smooth Blender point light snapped to the focused FLATTER block face"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        parent = _active_flatter(context)
        if parent is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        xyz = _focused_xyz(parent)
        if xyz is None:
            self.report({"ERROR"}, "Pick a logical FLATTER block first")
            return {"CANCELLED"}

        payload = flatter._load_payload(parent)
        if payload is None:
            self.report({"ERROR"}, "FLATTER logical data is unavailable")
            return {"CANCELLED"}

        props = parent.minesport
        level = max(1, min(15, int(props.light_level)))
        snap = str(props.light_snap_face)
        x, y, z = xyz
        if snap == "BOTTOM":
            mc_point = (x + 0.5, y - 0.001, z + 0.5)
        elif snap == "CENTER":
            mc_point = (x + 0.5, y + 0.5, z + 0.5)
        else:
            mc_point = (x + 0.5, y + 1.001, z + 0.5)

        center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
        local = Vector(flatter._mc_to_blender(mc_point, center))
        world = parent.matrix_world @ local
        name = f"Minesport_UserLight_{x}_{y}_{z}_{snap}_{level}"

        collection = _light_collection({"exportName": "Minesport_User"})
        data = bpy.data.lights.new(name + "_Data", type="POINT")
        obj = bpy.data.objects.new(name, data)
        collection.objects.link(obj)
        obj.location = world
        obj["minesport_flatter_parent"] = str(parent.get("minesport_flatter_id") or parent.name)
        obj["minesport_flatter_anchor_xyz"] = json.dumps(list(xyz), separators=(",", ":"))
        obj["minesport_light_snap_face"] = snap
        _configure_light(
            obj,
            _descriptor_for_user_light(name, level, world),
            helpers_visible=bool(collection.get(LIGHT_HELPERS_KEY, False)),
            imported=False,
        )
        collection["minesport_light_count"] = int(collection.get("minesport_light_count", 0)) + 1
        self.report({"INFO"}, f"Placed light level {level} on {snap.lower()} face @ {xyz}")
        return {"FINISHED"}


class MINESPORT_PT_lights(bpy.types.Panel):
    bl_label = "Minecraft Lights"
    bl_idname = "MINESPORT_PT_lights"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"

    def draw(self, context):
        layout = self.layout
        collections = _all_light_collections()
        count = sum(len(_objects_in_light_collection(collection)) for collection in collections)
        visible = _helpers_visible()

        box = layout.box()
        box.label(text=f"{count:,} Minesport light(s)", icon="LIGHT_POINT")
        box.operator(
            MINESPORT_OT_light_helpers_toggle.bl_idname,
            text="Hide Light Helpers" if visible else "Show Light Helpers",
            icon="HIDE_OFF" if visible else "HIDE_ON",
        )
        box.label(text="Logical level falls by 1/block; Blender falloff stays smooth.", icon="INFO")

        light = _active_light(context)
        if light is not None:
            edit = layout.box()
            edit.label(text=str(light.get(LIGHT_SOURCE_KEY, "Minecraft light")), icon="LIGHT_POINT")
            edit.prop(light.minesport, "light_level")
            edit.label(text=f"Range: {float(light.get(LIGHT_RANGE_KEY, 0.0)):.1f} blocks")
            edit.operator(MINESPORT_OT_light_apply_level.bl_idname, icon="FILE_REFRESH")

        parent = _active_flatter(context)
        if parent is not None:
            place = layout.box()
            place.label(text="FLATTER Light Placement", icon="SNAP_ON")
            place.prop(parent.minesport, "light_level")
            place.prop(parent.minesport, "light_snap_face", text="Snap")
            focused = _focused_xyz(parent)
            if focused is None:
                place.label(text="Pick a logical block first.", icon="EYEDROPPER")
            else:
                place.label(text=f"Anchor: {focused[0]}, {focused[1]}, {focused[2]}")
                place.operator(MINESPORT_OT_flatter_add_light.bl_idname, icon="LIGHT_POINT")


_CLASSES = (
    MINESPORT_OT_light_helpers_toggle,
    MINESPORT_OT_light_apply_level,
    MINESPORT_OT_flatter_add_light,
    MINESPORT_PT_lights,
)


def _translate_scene_wrapper(metadata=None, objects=None, asset_path=None):
    result = _ORIGINAL_TRANSLATE_SCENE(metadata, objects=objects, asset_path=asset_path)
    try:
        translate_lights(metadata, objects=objects)
    except Exception as exc:
        print(f"[Minesport Lights] translation failed: {exc}")
    return result


def register():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is None:
        _ORIGINAL_TRANSLATE_SCENE = translate_module.translate_scene
        translate_module.translate_scene = _translate_scene_wrapper
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    global _ORIGINAL_TRANSLATE_SCENE
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
    if _ORIGINAL_TRANSLATE_SCENE is not None:
        translate_module.translate_scene = _ORIGINAL_TRANSLATE_SCENE
        _ORIGINAL_TRANSLATE_SCENE = None
