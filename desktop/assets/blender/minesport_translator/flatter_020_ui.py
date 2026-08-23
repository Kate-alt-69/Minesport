"""Minesport 0.2 FLATTER workflow UI.

This panel deliberately explains the interaction model where the user is
already looking: directly under the FLATTER panel. It also exposes Minecraft
Light-block placement without requiring a second panel hunt.
"""

import bpy

from . import flatter
from . import lights


def _active_flatter(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    return obj


class MINESPORT_OT_flatter_place_light_020(bpy.types.Operator):
    bl_idname = "minesport.flatter_place_light_020"
    bl_label = "Place 1×1×1 Light Block"
    bl_description = (
        "Click a FLATTER top/bottom face and place a Minecraft Light voxel; "
        "the wire cube is viewport-only while the child POINT light provides illumination"
    )
    bl_options = {"REGISTER", "UNDO"}

    def invoke(self, context, event):
        parent = _active_flatter(context)
        if parent is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}

        # User-created Light blocks should be visible immediately. Imported
        # Minecraft invisible-light helpers may still default hidden to avoid a
        # giant diagnostic wire-cube cloud.
        collection = lights._light_collection({"exportName": "Minesport_User"})
        collection[lights.LIGHT_HELPERS_KEY] = True
        return bpy.ops.minesport.flatter_click_place_light("INVOKE_DEFAULT")


class MINESPORT_PT_flatter_workflow_020(bpy.types.Panel):
    bl_label = "FLATTER Workflow · 0.2"
    bl_idname = "MINESPORT_PT_flatter_workflow_020"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"
    bl_parent_id = "MINESPORT_PT_flatter_view"
    bl_options = {"DEFAULT_CLOSED"}

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        layout = self.layout
        obj = _active_flatter(context)
        props = obj.minesport

        help_box = layout.box()
        help_box.label(text="Direct logical block selection", icon="RESTRICT_SELECT_OFF")
        help_box.label(text="LMB · select/focus block")
        help_box.label(text="Shift + LMB · add to selection")
        help_box.label(text="Ctrl + LMB · remove from selection")
        help_box.label(text="Clicks outside FLATTER stay normal Blender clicks.", icon="INFO")

        interaction = layout.box()
        interaction.label(text="Click behavior", icon="MOUSE_LMB")
        interaction.prop(props, "flatter_interaction_mode", text="")
        focused = str(getattr(props, "flatter_selected", "") or "")
        if focused:
            interaction.label(text="Focused: " + focused, icon="EYEDROPPER")
        else:
            interaction.label(text="Click a visible FLATTER block to focus it.", icon="INFO")
        interaction.prop(props, "flatter_overlay_mode")
        if props.flatter_overlay_mode != "OFF":
            interaction.prop(props, "flatter_overlay_xray")

        light_box = layout.box()
        light_box.label(text="Minecraft Light Block", icon="LIGHT_POINT")
        light_box.prop(props, "light_level")
        light_box.operator(
            MINESPORT_OT_flatter_place_light_020.bl_idname,
            text="Place 1×1×1 Light Block",
            icon="LIGHT_POINT",
        )
        light_box.label(text="Click the top or bottom face of a logical block.", icon="INFO")
        light_box.label(text="Green/wire cube = MINESPORT_LIGHT_BLOCK_LVL_<level>.", icon="CUBE")
        light_box.label(text="POINT child = actual Blender illumination.", icon="LIGHT")
        light_box.operator(
            lights.MINESPORT_OT_light_helpers_toggle.bl_idname,
            text="Show / Hide Minesport Light Blocks",
            icon="HIDE_OFF",
        )


_CLASSES = (
    MINESPORT_OT_flatter_place_light_020,
    MINESPORT_PT_flatter_workflow_020,
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
