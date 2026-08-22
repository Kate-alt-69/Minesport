"""Blender UI mirrors for FLATTER Liquid Merge.

Keeps the keyframeable Liquid Merge control visible in the Node Editor,
Object Properties, and logical block selection feedback without duplicating
the runtime/geometry implementation.
"""

import json

import bpy

from . import flatter
from . import liquid_merge


_ORIGINAL_CLICK = None
_ORIGINAL_BOX = None


def _active_flatter(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    return obj


def _block_label(obj, xyz):
    payload = flatter._load_payload(obj)
    grid = flatter._decode_grid(payload) if payload else {}
    palette_index = grid.get(tuple(xyz))
    entry = flatter._palette(payload, palette_index) if palette_index is not None else None
    block_id = (
        str(entry.get("id") or "minecraft:unknown")
        if isinstance(entry, dict)
        else "minecraft:unknown"
    )
    return f"Minecraft block: {block_id} @ {xyz[0]}, {xyz[1]}, {xyz[2]}"


def _set_block_feedback(obj, xyz):
    xyz = tuple(map(int, xyz))
    label = _block_label(obj, xyz)
    obj[flatter._SELECTED_KEY] = json.dumps(list(xyz), separators=(",", ":"))
    obj["minesport_logical_selection_type"] = "Minecraft block"
    obj["minesport_logical_selection_label"] = label
    obj["minesport_logical_selection_xyz"] = list(xyz)
    if hasattr(obj, "minesport"):
        obj.minesport.flatter_selected = label.removeprefix("Minecraft block: ")
    try:
        bpy.context.workspace.status_text_set(label)
    except Exception:
        pass
    return label


def _click_wrapper(parent, xyz, event):
    _ORIGINAL_CLICK(parent, xyz, event)
    _set_block_feedback(parent, xyz)


def _box_wrapper(parent, first, second, event):
    _ORIGINAL_BOX(parent, first, second, event)
    _set_block_feedback(parent, second)
    count = len(liquid_merge._selection(parent))
    label = (
        f"Minecraft blocks: {count:,} selected · "
        f"box {first[0]},{first[1]},{first[2]} → {second[0]},{second[1]},{second[2]}"
    )
    parent["minesport_logical_selection_type"] = "Minecraft blocks"
    parent["minesport_logical_selection_label"] = label
    if hasattr(parent, "minesport"):
        parent.minesport.flatter_selected = label.removeprefix("Minecraft blocks: ")
    try:
        bpy.context.workspace.status_text_set(label)
    except Exception:
        pass


def _draw_control(layout, obj, include_selection=True):
    liquid_merge.ensure_control_node(obj)
    merged = bool(obj.get(liquid_merge.MERGE_KEY, True))
    selected = liquid_merge._selection(obj)

    box = layout.box()
    box.label(text="Minesport FLATTER Control", icon="NODETREE")
    row = box.row(align=True)
    row.operator(
        liquid_merge.MINESPORT_OT_liquid_toggle.bl_idname,
        text="Liquid Merge: ON" if merged else "Liquid Merge: OFF",
        icon="CHECKBOX_HLT" if merged else "CHECKBOX_DEHLT",
        depress=merged,
    )
    row.operator(
        liquid_merge.MINESPORT_OT_liquid_keyframe.bl_idname,
        text="",
        icon="KEY_HLT",
    )

    modifier = obj.modifiers.get(liquid_merge.MODIFIER_NAME)
    if modifier is not None:
        box.label(text=f"Geometry Nodes: {liquid_merge.MODIFIER_NAME}", icon="MOD_NODES")

    if include_selection:
        box.label(text=f"{len(selected):,} selected logical block(s)", icon="CUBE")
        label = str(obj.get("minesport_logical_selection_label") or "")
        if label:
            box.label(text=label, icon="CUBE")
        row = box.row(align=True)
        op = row.operator(
            liquid_merge.MINESPORT_OT_liquid_select.bl_idname,
            text="Select Blocks",
            icon="RESTRICT_SELECT_OFF",
        )
        op.mode = "BLOCK"
        op = row.operator(
            liquid_merge.MINESPORT_OT_liquid_select.bl_idname,
            text="Box Select",
            icon="SELECT_SET",
        )
        op.mode = "BOX"


class MINESPORT_PT_liquid_properties(bpy.types.Panel):
    bl_label = "FLATTER Liquid Merge"
    bl_idname = "MINESPORT_PT_liquid_properties"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "object"
    bl_parent_id = "MINESPORT_PT_properties"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        _draw_control(self.layout, _active_flatter(context), include_selection=True)


class MINESPORT_PT_liquid_nodes(bpy.types.Panel):
    bl_label = "Minesport FLATTER"
    bl_idname = "MINESPORT_PT_liquid_nodes"
    bl_space_type = "NODE_EDITOR"
    bl_region_type = "UI"
    bl_category = "Minesport"

    @classmethod
    def poll(cls, context):
        obj = _active_flatter(context)
        if obj is None:
            return False
        space = getattr(context, "space_data", None)
        return space is not None

    def draw(self, context):
        obj = _active_flatter(context)
        _draw_control(self.layout, obj, include_selection=True)
        self.layout.label(
            text="The node is a control surface; FLATTER data remains on the object.",
            icon="INFO",
        )


_CLASSES = (
    MINESPORT_PT_liquid_properties,
    MINESPORT_PT_liquid_nodes,
)


def register():
    global _ORIGINAL_CLICK, _ORIGINAL_BOX
    if _ORIGINAL_CLICK is not None:
        return
    _ORIGINAL_CLICK = liquid_merge._apply_click_selection
    _ORIGINAL_BOX = liquid_merge._apply_box_selection
    liquid_merge._apply_click_selection = _click_wrapper
    liquid_merge._apply_box_selection = _box_wrapper
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    global _ORIGINAL_CLICK, _ORIGINAL_BOX
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
    if _ORIGINAL_CLICK is not None:
        liquid_merge._apply_click_selection = _ORIGINAL_CLICK
        _ORIGINAL_CLICK = None
    if _ORIGINAL_BOX is not None:
        liquid_merge._apply_box_selection = _ORIGINAL_BOX
        _ORIGINAL_BOX = None
