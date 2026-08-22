"""Region-safe, direct FLATTER picking for Blender's 3D viewport.

A FLATTER object is render-cache geometry backed by an addressable Minecraft
voxel grid. In 0.2.0 the user no longer has to discover/start a separate modal
mode before normal left-clicks can address those logical voxels: the addon adds
a 3D View keymap that consumes a click only when the ray actually hits FLATTER
geometry. All other clicks pass straight back to Blender.
"""

import json

import bpy
from bpy_extras import view3d_utils

from . import flatter
from . import liquid_merge
from . import selection_sets


_ORIGINAL_HIT_XYZ = None
_ORIGINAL_PICK_INVOKE = None
_ORIGINAL_PICK_MODAL = None
_ORIGINAL_INTERACT_INVOKE = None
_ADDON_KEYMAPS = []


def _view3d_area(context):
    area = getattr(context, "area", None)
    if area is not None and area.type == "VIEW_3D":
        return area
    window = getattr(context, "window", None)
    screen = getattr(window, "screen", None) if window is not None else None
    if screen is None:
        return None
    return next((candidate for candidate in screen.areas if candidate.type == "VIEW_3D"), None)


def _window_region(area):
    if area is None:
        return None
    return next((region for region in area.regions if region.type == "WINDOW"), None)


def _region_3d(area):
    if area is None:
        return None
    space = getattr(area.spaces, "active", None)
    return getattr(space, "region_3d", None)


def _ray(context, event):
    area = _view3d_area(context)
    region = _window_region(area)
    rv3d = _region_3d(area)
    if area is None or region is None or rv3d is None:
        return None

    x = float(event.mouse_x - region.x)
    y = float(event.mouse_y - region.y)
    if x < 0 or y < 0 or x >= region.width or y >= region.height:
        return None

    coord = (x, y)
    origin = view3d_utils.region_2d_to_origin_3d(region, rv3d, coord)
    direction = view3d_utils.region_2d_to_vector_3d(region, rv3d, coord)
    hit, location, normal, face, obj, matrix = context.scene.ray_cast(
        context.view_layer.depsgraph,
        origin,
        direction,
    )
    if not hit:
        return None
    return location, normal, face, obj, matrix


def _safe_hit_xyz(context, parent, event):
    result = _ray(context, event)
    if result is None:
        return None
    location, normal, _face, hit_obj, _matrix = result
    if hit_obj is None:
        return None
    if hit_obj == parent:
        return flatter._pick_xyz(parent, location, normal)
    if hit_obj.get("minesport_type") == liquid_merge.TYPE_LIQUID_BLOCK:
        if str(hit_obj.get(liquid_merge.PROXY_PARENT_KEY, "")) == liquid_merge._parent_id(parent):
            return liquid_merge._proxy_xyz(hit_obj)
    return None


def _remember_face(parent, normal):
    if normal is None or parent is None or not hasattr(parent, "minesport"):
        return None
    try:
        from . import lights
        snap = lights._snap_from_world_normal(parent, normal)
    except Exception:
        snap = None
    if snap in {"TOP", "BOTTOM"}:
        parent["minesport_flatter_selected_face"] = snap
        parent.minesport.light_snap_face = snap
        return snap
    parent["minesport_flatter_selected_face"] = "SIDE"
    return "SIDE"


def _redraw_logical_overlay():
    try:
        from . import flatter_overlay
        flatter_overlay.tag_redraw()
    except Exception:
        # Keep the selection functional even if an optional GPU overlay is
        # unavailable on a particular Blender build.
        for window in getattr(bpy.context.window_manager, "windows", []):
            screen = getattr(window, "screen", None)
            if screen is None:
                continue
            for area in screen.areas:
                if area.type == "VIEW_3D":
                    area.tag_redraw()


def _focus(parent, xyz, normal=None):
    xyz = tuple(map(int, xyz))
    parent[flatter._SELECTED_KEY] = json.dumps(list(xyz), separators=(",", ":"))
    payload = flatter._load_payload(parent)
    grid = flatter._decode_grid(payload) if payload else {}
    palette_index = grid.get(xyz)
    entry = flatter._palette(payload, palette_index) if palette_index is not None else None
    block_id = (
        str(entry.get("id") or "minecraft:unknown")
        if isinstance(entry, dict)
        else "minecraft:unknown"
    )
    label = f"{block_id} @ {xyz[0]}, {xyz[1]}, {xyz[2]}"
    parent["minesport_logical_selection_type"] = "Minecraft block"
    parent["minesport_logical_selection_label"] = "Minecraft block: " + label
    parent["minesport_logical_selection_xyz"] = list(xyz)
    snap = _remember_face(parent, normal)
    if hasattr(parent, "minesport"):
        parent.minesport.flatter_selected = label
    try:
        suffix = f" · {snap.lower()} face" if snap in {"TOP", "BOTTOM"} else ""
        bpy.context.workspace.status_text_set("Minecraft block: " + label + suffix)
    except Exception:
        pass
    _redraw_logical_overlay()
    return block_id


def _parent_and_xyz_from_hit(location, normal, hit_obj):
    if hit_obj is None:
        return None, None
    if hit_obj.get("minesport_type") == flatter._TYPE_FLATTER:
        return hit_obj, flatter._pick_xyz(hit_obj, location, normal)
    if hit_obj.get("minesport_type") == liquid_merge.TYPE_LIQUID_BLOCK:
        identifier = str(hit_obj.get(liquid_merge.PROXY_PARENT_KEY, ""))
        parent = flatter._find_flatter_parent(identifier)
        return parent, liquid_merge._proxy_xyz(hit_obj)
    return None, None


def _apply_direct_interaction(context, event, parent, xyz, normal=None):
    if parent is None or xyz is None:
        return False

    bpy.ops.object.select_all(action="DESELECT")
    parent.select_set(True)
    context.view_layer.objects.active = parent
    _focus(parent, xyz, normal=normal)

    props = getattr(parent, "minesport", None)
    mode = str(getattr(props, "flatter_interaction_mode", "SELECT") or "SELECT")
    if mode == "INSPECT":
        return True
    if mode == "SELECT":
        liquid_merge._apply_click_selection(parent, xyz, event)
        return True
    if mode == "BOX":
        first = liquid_merge._box_anchor(parent)
        if first is None:
            liquid_merge._set_box_anchor(parent, xyz)
            try:
                context.workspace.status_text_set(
                    f"FLATTER Box Select · first corner {xyz} · click second corner"
                )
            except Exception:
                pass
            return True
        liquid_merge._apply_box_selection(parent, first, xyz, event)
        liquid_merge._set_box_anchor(parent, None)
        return True
    if mode == "MATERIALIZE":
        payload = flatter._load_payload(parent)
        grid = flatter._decode_grid(payload) if payload else {}
        created = flatter._materialize_many(parent, payload, grid, [xyz])
        if created:
            bpy.ops.object.select_all(action="DESELECT")
            created[0].select_set(True)
            context.view_layer.objects.active = created[0]
        return bool(created)
    return True


class MINESPORT_OT_flatter_direct_click(bpy.types.Operator):
    bl_idname = "minesport.flatter_direct_click"
    bl_label = "FLATTER Direct Logical Click"
    bl_description = "Address a logical Minecraft voxel when clicking FLATTER; otherwise preserve Blender's normal click"
    bl_options = {"INTERNAL", "UNDO"}

    def invoke(self, context, event):
        if getattr(context, "mode", "OBJECT") != "OBJECT":
            return {"PASS_THROUGH"}
        if event.alt:
            return {"PASS_THROUGH"}
        result = _ray(context, event)
        if result is None:
            return {"PASS_THROUGH"}
        location, normal, _face, hit_obj, _matrix = result
        parent, xyz = _parent_and_xyz_from_hit(location, normal, hit_obj)
        if parent is None or xyz is None:
            return {"PASS_THROUGH"}
        if _apply_direct_interaction(context, event, parent, xyz, normal=normal):
            return {"FINISHED"}
        return {"PASS_THROUGH"}


def _safe_pick_invoke(self, context, event):
    if _view3d_area(context) is None:
        self.report({"ERROR"}, "A 3D View is required for FLATTER picking")
        return {"CANCELLED"}
    context.window_manager.modal_handler_add(self)
    context.workspace.status_text_set("Minesport FLATTER: click a block · Esc to cancel")
    return {"RUNNING_MODAL"}


def _safe_pick_modal(self, context, event):
    if event.type in {"ESC", "RIGHTMOUSE"}:
        context.workspace.status_text_set(None)
        return {"CANCELLED"}
    if event.type != "LEFTMOUSE" or event.value != "PRESS":
        return {"RUNNING_MODAL"}

    result = _ray(context, event)
    if result is None:
        self.report({"WARNING"}, "Click inside the 3D viewport on FLATTER geometry")
        return {"RUNNING_MODAL"}
    location, normal, _face, obj, _matrix = result
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        self.report({"WARNING"}, "That is not FLATTER geometry")
        return {"RUNNING_MODAL"}

    xyz = flatter._pick_xyz(obj, location, normal)
    if xyz is None:
        self.report({"WARNING"}, "No logical block exists at that point")
        return {"RUNNING_MODAL"}

    block_id = _focus(obj, xyz, normal=normal)
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    context.view_layer.objects.active = obj
    context.workspace.status_text_set(None)
    snap = str(obj.get("minesport_flatter_selected_face") or "")
    suffix = f" · {snap.lower()} face" if snap in {"TOP", "BOTTOM"} else ""
    self.report({"INFO"}, f"Focused {block_id} @ {xyz}{suffix}")
    return {"FINISHED"}


def _safe_interact_invoke(self, context, event):
    parent = selection_sets._active_flatter(context)
    if parent is not None:
        mode = str(getattr(parent.minesport, "flatter_interaction_mode", "SELECT"))
        if mode == "INSPECT":
            bpy.ops.minesport.flatter_pick("INVOKE_DEFAULT")
            return {"FINISHED"}
    return _ORIGINAL_INTERACT_INVOKE(self, context, event)


def _register_direct_click_keymap():
    wm = getattr(bpy.context, "window_manager", None)
    keyconfigs = getattr(wm, "keyconfigs", None) if wm is not None else None
    addon = getattr(keyconfigs, "addon", None) if keyconfigs is not None else None
    if addon is None:
        return
    km = addon.keymaps.new(name="3D View", space_type="VIEW_3D")
    kmi = km.keymap_items.new(
        MINESPORT_OT_flatter_direct_click.bl_idname,
        type="LEFTMOUSE",
        value="PRESS",
    )
    _ADDON_KEYMAPS.append((km, kmi))


def register():
    global _ORIGINAL_HIT_XYZ, _ORIGINAL_PICK_INVOKE, _ORIGINAL_PICK_MODAL
    global _ORIGINAL_INTERACT_INVOKE
    if _ORIGINAL_HIT_XYZ is not None:
        return

    bpy.utils.register_class(MINESPORT_OT_flatter_direct_click)
    _register_direct_click_keymap()

    _ORIGINAL_HIT_XYZ = liquid_merge._hit_xyz
    _ORIGINAL_PICK_INVOKE = flatter.MINESPORT_OT_flatter_pick.invoke
    _ORIGINAL_PICK_MODAL = flatter.MINESPORT_OT_flatter_pick.modal
    _ORIGINAL_INTERACT_INVOKE = selection_sets.MINESPORT_OT_flatter_interact.invoke

    liquid_merge._hit_xyz = _safe_hit_xyz
    flatter.MINESPORT_OT_flatter_pick.invoke = _safe_pick_invoke
    flatter.MINESPORT_OT_flatter_pick.modal = _safe_pick_modal
    selection_sets.MINESPORT_OT_flatter_interact.invoke = _safe_interact_invoke


def unregister():
    global _ORIGINAL_HIT_XYZ, _ORIGINAL_PICK_INVOKE, _ORIGINAL_PICK_MODAL
    global _ORIGINAL_INTERACT_INVOKE

    while _ADDON_KEYMAPS:
        km, kmi = _ADDON_KEYMAPS.pop()
        try:
            km.keymap_items.remove(kmi)
        except Exception:
            pass
    try:
        bpy.utils.unregister_class(MINESPORT_OT_flatter_direct_click)
    except Exception:
        pass

    if _ORIGINAL_HIT_XYZ is not None:
        liquid_merge._hit_xyz = _ORIGINAL_HIT_XYZ
        _ORIGINAL_HIT_XYZ = None
    if _ORIGINAL_PICK_INVOKE is not None:
        flatter.MINESPORT_OT_flatter_pick.invoke = _ORIGINAL_PICK_INVOKE
        _ORIGINAL_PICK_INVOKE = None
    if _ORIGINAL_PICK_MODAL is not None:
        flatter.MINESPORT_OT_flatter_pick.modal = _ORIGINAL_PICK_MODAL
        _ORIGINAL_PICK_MODAL = None
    if _ORIGINAL_INTERACT_INVOKE is not None:
        selection_sets.MINESPORT_OT_flatter_interact.invoke = _ORIGINAL_INTERACT_INVOKE
        _ORIGINAL_INTERACT_INVOKE = None
