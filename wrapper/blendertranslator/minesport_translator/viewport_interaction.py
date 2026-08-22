"""Region-safe FLATTER picking for Blender's 3D viewport.

Blender operators launched from the N-panel or Object Properties do not
necessarily receive the VIEW_3D WINDOW region as context.region. Minesport's
old ray picker used that region directly, so sidebar-launched picking could
cast rays using sidebar coordinates. This module patches the shared hit helper
and the legacy picker to always target the actual 3D window region.
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


def _focus(parent, xyz):
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
    if hasattr(parent, "minesport"):
        parent.minesport.flatter_selected = label
    try:
        bpy.context.workspace.status_text_set("Minecraft block: " + label)
    except Exception:
        pass
    return block_id


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

    block_id = _focus(obj, xyz)
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    context.view_layer.objects.active = obj
    context.workspace.status_text_set(None)
    self.report({"INFO"}, f"Focused {block_id} @ {xyz}")
    return {"FINISHED"}


def _safe_interact_invoke(self, context, event):
    parent = selection_sets._active_flatter(context)
    if parent is not None:
        mode = str(getattr(parent.minesport, "flatter_interaction_mode", "SELECT"))
        if mode == "INSPECT":
            bpy.ops.minesport.flatter_pick("INVOKE_DEFAULT")
            return {"FINISHED"}
    return _ORIGINAL_INTERACT_INVOKE(self, context, event)


def register():
    global _ORIGINAL_HIT_XYZ, _ORIGINAL_PICK_INVOKE, _ORIGINAL_PICK_MODAL
    global _ORIGINAL_INTERACT_INVOKE
    if _ORIGINAL_HIT_XYZ is not None:
        return

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
