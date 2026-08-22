"""Minecraft-aware light translation and FLATTER light placement.

OBJ cannot carry scene lights, so Minesport restores them from the sidecar.
glTF already imports KHR_lights_punctual natively; this module recognizes those
objects by stable Minesport names and adds the original Minecraft light-level
semantics instead of creating duplicates.

Invisible Minecraft ``light`` blocks use their own 1x1x1 viewport helper mesh.
The helper is separate from the Blender POINT light so hiding it never changes
illumination or Blender's global Extras overlay.
"""

import json

import bpy
from mathutils import Vector

from . import flatter
from . import translate as translate_module


TYPE_LIGHT = "MINECRAFT_LIGHT"
TYPE_LIGHT_BLOCK_HELPER = "MINECRAFT_LIGHT_BLOCK_HELPER"
LIGHT_LEVEL_KEY = "minesport_light_level"
LIGHT_RANGE_KEY = "minesport_light_range_blocks"
LIGHT_FALLOFF_KEY = "minesport_light_falloff"
LIGHT_SOURCE_KEY = "minesport_light_source"
LIGHT_INVISIBLE_KEY = "minesport_light_invisible_source"
LIGHT_IMPORTED_KEY = "minesport_light_imported"
LIGHT_HELPERS_KEY = "minesport_light_helpers_visible"
LIGHT_COLLECTION_KEY = "minesport_light_collection"
LIGHT_HELPER_OWNER_KEY = "minesport_light_helper_owner"
LIGHT_HELPER_PREFIX = "MINESPORT_LIGHT_BLOCK_LVL_"
LIGHT_HELPER_MESH_NAME = "MINESPORT_LIGHT_BLOCK_UNIT_CUBE"

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


def _helper_objects_in_collection(collection):
    return [
        obj for obj in collection.objects
        if obj.get("minesport_type") == TYPE_LIGHT_BLOCK_HELPER
        or obj.name.startswith(LIGHT_HELPER_PREFIX)
    ]


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


def _unit_light_block_mesh():
    mesh = bpy.data.meshes.get(LIGHT_HELPER_MESH_NAME)
    if mesh is not None:
        return mesh

    # Exact one-Blender-unit cube centered on the emitter. Minesport's exported
    # world scale is one Blender unit per Minecraft block, so this is exactly one
    # Minecraft voxel. Edges only: it remains a cheap viewport guide and can
    # never accidentally render as visible world geometry.
    vertices = [
        (-0.5, -0.5, -0.5),
        (0.5, -0.5, -0.5),
        (0.5, 0.5, -0.5),
        (-0.5, 0.5, -0.5),
        (-0.5, -0.5, 0.5),
        (0.5, -0.5, 0.5),
        (0.5, 0.5, 0.5),
        (-0.5, 0.5, 0.5),
    ]
    edges = [
        (0, 1), (1, 2), (2, 3), (3, 0),
        (4, 5), (5, 6), (6, 7), (7, 4),
        (0, 4), (1, 5), (2, 6), (3, 7),
    ]
    mesh = bpy.data.meshes.new(LIGHT_HELPER_MESH_NAME)
    mesh.from_pydata(vertices, edges, [])
    mesh.update()
    return mesh


def _helper_for_light(light_obj):
    owner = light_obj.name
    for collection in light_obj.users_collection:
        for helper in _helper_objects_in_collection(collection):
            if str(helper.get(LIGHT_HELPER_OWNER_KEY, "")) == owner:
                return helper
    for helper in bpy.data.objects:
        if helper.get("minesport_type") != TYPE_LIGHT_BLOCK_HELPER:
            continue
        if str(helper.get(LIGHT_HELPER_OWNER_KEY, "")) == owner:
            return helper
    return None


def _remove_light_block_helper(light_obj):
    helper = _helper_for_light(light_obj)
    if helper is None:
        return
    try:
        bpy.data.objects.remove(helper, do_unlink=True)
    except Exception:
        pass


def _ensure_light_block_helper(light_obj, collection, level, visible):
    helper = _helper_for_light(light_obj)
    if helper is None:
        helper = bpy.data.objects.new(
            f"{LIGHT_HELPER_PREFIX}{level}_{_safe_name(light_obj.name)}",
            _unit_light_block_mesh(),
        )
        collection.objects.link(helper)
    else:
        _move_to_collection(helper, collection)
        helper.name = f"{LIGHT_HELPER_PREFIX}{level}_{_safe_name(light_obj.name)}"

    helper["minesport_type"] = TYPE_LIGHT_BLOCK_HELPER
    helper[LIGHT_LEVEL_KEY] = int(level)
    helper[LIGHT_HELPER_OWNER_KEY] = light_obj.name
    helper.hide_render = True
    helper.hide_select = True
    helper.display_type = "WIRE"
    helper.show_in_front = True

    # Parent the helper to the emitter with zero local offset. Moving the actual
    # light therefore moves its one-block Minecraft guide as a single unit.
    if helper.parent != light_obj:
        helper.parent = light_obj
    helper.location = (0.0, 0.0, 0.0)
    helper.rotation_euler = (0.0, 0.0, 0.0)
    helper.scale = (1.0, 1.0, 1.0)
    try:
        helper.hide_set(not bool(visible))
    except Exception:
        pass
    return helper


def _configure_light(obj, descriptor, helpers_visible=False, imported=True, collection=None):
    level = max(1, min(15, int(descriptor.get("minecraftLevel", 15))))
    range_blocks = max(0.5, float(descriptor.get("rangeBlocks", level + 0.5)))
    intensity = max(0.0, float(descriptor.get("intensity", 45.0 * level)))
    color = _vec3(descriptor.get("color"), (1.0, 0.92, 0.78))

    data = obj.data
    if data.type != "POINT":
        data.type = "POINT"
    data.color = color
    data.energy = intensity
    if hasattr(data, "use_custom_distance"):
        data.use_custom_distance = True
    if hasattr(data, "cutoff_distance"):
        data.cutoff_distance = range_blocks
    if hasattr(data, "shadow_soft_size"):
        # Keep the physical source comfortably inside its 1x1x1 light voxel.
        data.shadow_soft_size = 0.10

    invisible_source = bool(descriptor.get("invisibleSource", False))
    obj["minesport_type"] = TYPE_LIGHT
    obj[LIGHT_LEVEL_KEY] = level
    obj[LIGHT_RANGE_KEY] = range_blocks
    obj[LIGHT_FALLOFF_KEY] = str(descriptor.get("falloff") or "minecraft_linear_smooth")
    obj[LIGHT_SOURCE_KEY] = str(descriptor.get("source") or "minecraft:light")
    obj[LIGHT_INVISIBLE_KEY] = invisible_source
    obj[LIGHT_IMPORTED_KEY] = bool(imported)
    obj["minesport_light_decay_rule"] = "one_level_per_block"
    obj["minesport_light_smooth_render"] = True
    obj.hide_render = False
    # The actual Blender light is always a normal selectable Blender object.
    # Helper visibility must never disable regular lights or global viewport
    # Extras; only the dedicated MINESPORT_LIGHT_BLOCK_LVL_* cube is toggled.
    obj.hide_select = False
    try:
        obj.hide_set(False)
    except Exception:
        pass

    if collection is None and obj.users_collection:
        collection = obj.users_collection[0]
    if invisible_source and collection is not None:
        _ensure_light_block_helper(obj, collection, level, helpers_visible)
    else:
        _remove_light_block_helper(obj)

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
        _configure_light(
            obj,
            descriptor,
            helpers_visible=helpers_visible,
            imported=True,
            collection=collection,
        )
        keep.add(obj.name)
        result.append(obj)

    for obj in list(_objects_in_light_collection(collection)):
        if not bool(obj.get(LIGHT_IMPORTED_KEY, False)):
            continue
        if obj.name in keep:
            continue
        _remove_light_block_helper(obj)
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
        for helper in _helper_objects_in_collection(collection):
            helper.hide_select = True
            try:
                helper.hide_set(not bool(visible))
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


def _place_flatter_light(parent, xyz, snap, level):
    payload = flatter._load_payload(parent)
    if payload is None:
        return None

    level = max(1, min(15, int(level)))
    snap = str(snap or "TOP")
    x, y, z = map(int, xyz)

    # Minecraft's invisible Light is a real one-block voxel even though it has
    # no rendered model. Place the emitter at the CENTER of that voxel rather
    # than 0.001 outside the clicked surface. This avoids dirty near-surface
    # lighting and makes the 1x1x1 helper line up with Minecraft placement.
    if snap == "BOTTOM":
        mc_point = (x + 0.5, y - 0.5, z + 0.5)
        light_xyz = (x, y - 1, z)
    elif snap == "CENTER":
        mc_point = (x + 0.5, y + 0.5, z + 0.5)
        light_xyz = (x, y, z)
    else:
        snap = "TOP"
        mc_point = (x + 0.5, y + 1.5, z + 0.5)
        light_xyz = (x, y + 1, z)

    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    local = Vector(flatter._mc_to_blender(mc_point, center))
    world = parent.matrix_world @ local
    lx, ly, lz = light_xyz
    name = f"Minesport_UserLight_{lx}_{ly}_{lz}_{level}"

    collection = _light_collection({"exportName": "Minesport_User"})
    existing = _existing_light(name)
    if existing is not None:
        obj = existing
        _move_to_collection(obj, collection)
    else:
        data = bpy.data.lights.new(name + "_Data", type="POINT")
        obj = bpy.data.objects.new(name, data)
        collection.objects.link(obj)
    obj.location = world
    obj["minesport_flatter_parent"] = str(parent.get("minesport_flatter_id") or parent.name)
    obj["minesport_flatter_anchor_xyz"] = json.dumps(list(light_xyz), separators=(",", ":"))
    obj["minesport_light_snap_face"] = snap
    _configure_light(
        obj,
        _descriptor_for_user_light(name, level, world),
        helpers_visible=bool(collection.get(LIGHT_HELPERS_KEY, False)),
        imported=False,
        collection=collection,
    )
    collection["minesport_light_count"] = len(_objects_in_light_collection(collection))
    return obj


def _snap_from_world_normal(parent, normal):
    if normal is None:
        return None
    try:
        local = parent.matrix_world.to_3x3().transposed() @ Vector(normal)
        if local.length_squared > 1e-12:
            local.normalize()
    except Exception:
        local = Vector(normal)
    if local.z >= 0.55:
        return "TOP"
    if local.z <= -0.55:
        return "BOTTOM"
    return None


class MINESPORT_OT_light_helpers_toggle(bpy.types.Operator):
    bl_idname = "minesport.light_helpers_toggle"
    bl_label = "Toggle Minesport Light Blocks"
    bl_description = "Show or hide only Minesport's 1x1x1 invisible Light-block helpers; regular Blender lights/extras are untouched"

    def execute(self, context):
        visible = not _helpers_visible()
        count = _set_helpers_visible(visible)
        self.report({"INFO"}, f"{'Showing' if visible else 'Hiding'} {count:,} Minesport light block helper(s)")
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
        collection = obj.users_collection[0] if obj.users_collection else None
        _configure_light(
            obj,
            descriptor,
            helpers_visible=_helpers_visible(),
            imported=bool(obj.get(LIGHT_IMPORTED_KEY, False)),
            collection=collection,
        )
        self.report({"INFO"}, f"Minecraft light level {level} → smooth {level + 0.5:.1f}-block range")
        return {"FINISHED"}


class MINESPORT_OT_flatter_add_light(bpy.types.Operator):
    bl_idname = "minesport.flatter_add_light"
    bl_label = "Place Minecraft Light"
    bl_description = "Place a Minecraft Light voxel adjacent to the focused FLATTER block face"
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

        level = max(1, min(15, int(parent.minesport.light_level)))
        snap = str(parent.minesport.light_snap_face)
        created = _place_flatter_light(parent, xyz, snap, level)
        if created is None:
            self.report({"ERROR"}, "FLATTER logical data is unavailable")
            return {"CANCELLED"}
        self.report({"INFO"}, f"Placed 1x1x1 light block level {level} on {snap.lower()} face @ {xyz}")
        return {"FINISHED"}


class MINESPORT_OT_flatter_click_place_light(bpy.types.Operator):
    bl_idname = "minesport.flatter_click_place_light"
    bl_label = "Click Place Light"
    bl_description = "Click a FLATTER top or bottom face to place a one-block Minecraft Light voxel"
    bl_options = {"REGISTER", "UNDO", "BLOCKING"}

    def invoke(self, context, event):
        parent = _active_flatter(context)
        if parent is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        self._parent_name = parent.name
        context.window_manager.modal_handler_add(self)
        try:
            context.workspace.status_text_set(
                "Minesport Light: click a FLATTER top/bottom face · Esc to cancel"
            )
        except Exception:
            pass
        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        if event.type in {"ESC", "RIGHTMOUSE"}:
            try:
                context.workspace.status_text_set(None)
            except Exception:
                pass
            return {"CANCELLED"}
        if event.type != "LEFTMOUSE" or event.value != "PRESS":
            return {"RUNNING_MODAL"}

        parent = bpy.data.objects.get(getattr(self, "_parent_name", ""))
        if parent is None:
            return {"CANCELLED"}

        from . import liquid_merge
        from . import viewport_interaction

        hit = viewport_interaction._ray(context, event)
        if hit is None:
            self.report({"WARNING"}, "Click inside the 3D viewport on FLATTER geometry")
            return {"RUNNING_MODAL"}
        location, normal, _face, hit_obj, _matrix = hit
        xyz = None
        if hit_obj == parent:
            xyz = flatter._pick_xyz(parent, location, normal)
        elif hit_obj is not None and hit_obj.get("minesport_type") == liquid_merge.TYPE_LIQUID_BLOCK:
            if str(hit_obj.get(liquid_merge.PROXY_PARENT_KEY, "")) == liquid_merge._parent_id(parent):
                xyz = liquid_merge._proxy_xyz(hit_obj)
        if xyz is None:
            self.report({"WARNING"}, "That is not a logical block from the active FLATTER object")
            return {"RUNNING_MODAL"}

        snap = _snap_from_world_normal(parent, normal)
        if snap is None:
            self.report({"WARNING"}, "Click the top or bottom face of a block")
            return {"RUNNING_MODAL"}

        viewport_interaction._focus(parent, xyz)
        parent.minesport.light_snap_face = snap
        level = max(1, min(15, int(parent.minesport.light_level)))
        created = _place_flatter_light(parent, xyz, snap, level)
        if created is None:
            self.report({"ERROR"}, "Could not read FLATTER logical data")
            return {"CANCELLED"}

        try:
            context.workspace.status_text_set(None)
        except Exception:
            pass
        self.report({"INFO"}, f"Placed 1x1x1 level {level} light block on {snap.lower()} face @ {tuple(xyz)}")
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
        helper_count = sum(len(_helper_objects_in_collection(collection)) for collection in collections)
        visible = _helpers_visible()

        box = layout.box()
        box.label(text=f"{count:,} Minesport light(s)", icon="LIGHT_POINT")
        box.operator(
            MINESPORT_OT_light_helpers_toggle.bl_idname,
            text="Hide Light Blocks" if visible else "Show Light Blocks",
            icon="HIDE_OFF" if visible else "HIDE_ON",
        )
        box.label(text=f"{helper_count:,} invisible 1x1x1 Light-block helper(s)", icon="CUBE")
        box.label(text="Only MINESPORT_LIGHT_BLOCK_LVL_* helpers are toggled.", icon="INFO")
        box.label(text="Blender lights/cameras/empties and global Extras stay untouched.", icon="INFO")

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
            place.operator(
                MINESPORT_OT_flatter_click_place_light.bl_idname,
                text="Click Top / Bottom Face",
                icon="EYEDROPPER",
            )
            place.separator()
            place.prop(parent.minesport, "light_snap_face", text="Manual Snap")
            focused = _focused_xyz(parent)
            if focused is None:
                place.label(text="Or pick a logical block for manual placement.", icon="INFO")
            else:
                place.label(text=f"Anchor block: {focused[0]}, {focused[1]}, {focused[2]}")
                place.operator(MINESPORT_OT_flatter_add_light.bl_idname, icon="LIGHT_POINT")


_CLASSES = (
    MINESPORT_OT_light_helpers_toggle,
    MINESPORT_OT_light_apply_level,
    MINESPORT_OT_flatter_add_light,
    MINESPORT_OT_flatter_click_place_light,
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
