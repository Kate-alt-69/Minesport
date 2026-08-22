"""Persistent FLATTER logical editing mode for Blender's 3D viewport."""

import bpy

from . import flatter
from . import liquid_merge
from . import viewport_interaction


EDIT_MODE_KEY = "minesport_flatter_edit_mode"
_RUNNING = False


def _set_running(scene, value):
    global _RUNNING
    _RUNNING = bool(value)
    scene[EDIT_MODE_KEY] = bool(value)
    if not value:
        try:
            bpy.context.workspace.status_text_set(None)
        except Exception:
            pass


def _active_flatter(context):
    obj = getattr(context, "object", None)
    if obj is not None and obj.get("minesport_type") == flatter._TYPE_FLATTER:
        return obj
    return None


def _materialize(parent, xyz, context):
    payload = flatter._load_payload(parent)
    grid = flatter._decode_grid(payload) if payload else {}
    created = flatter._materialize_many(parent, payload, grid, [xyz])
    if not created:
        return False
    bpy.ops.object.select_all(action="DESELECT")
    created[0].select_set(True)
    context.view_layer.objects.active = created[0]
    return True


def _interact(context, event, parent, xyz):
    props = getattr(parent, "minesport", None)
    mode = str(getattr(props, "flatter_interaction_mode", "SELECT") or "SELECT")
    viewport_interaction._focus(parent, xyz)

    bpy.ops.object.select_all(action="DESELECT")
    parent.select_set(True)
    context.view_layer.objects.active = parent

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
        return _materialize(parent, xyz, context)
    return False


class MINESPORT_OT_flatter_edit_mode(bpy.types.Operator):
    bl_idname = "minesport.flatter_edit_mode"
    bl_label = "Toggle FLATTER Edit Mode"
    bl_description = (
        "Keep logical FLATTER picking active in the 3D viewport until toggled off or Esc"
    )

    def invoke(self, context, event):
        global _RUNNING
        if _RUNNING:
            _set_running(context.scene, False)
            return {"FINISHED"}
        if viewport_interaction._view3d_area(context) is None:
            self.report({"ERROR"}, "A 3D View is required for FLATTER Edit Mode")
            return {"CANCELLED"}
        if _active_flatter(context) is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        _set_running(context.scene, True)
        context.window_manager.modal_handler_add(self)
        context.workspace.status_text_set(
            "Minesport FLATTER Edit Mode · click logical blocks · Esc exits"
        )
        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        global _RUNNING
        if not _RUNNING or not bool(context.scene.get(EDIT_MODE_KEY, False)):
            _set_running(context.scene, False)
            return {"FINISHED"}
        if event.type == "ESC" and event.value == "PRESS":
            _set_running(context.scene, False)
            return {"FINISHED"}
        if event.type != "LEFTMOUSE" or event.value != "PRESS":
            return {"PASS_THROUGH"}

        result = viewport_interaction._ray(context, event)
        if result is None:
            return {"PASS_THROUGH"}
        location, normal, _face, hit_obj, _matrix = result
        if hit_obj is None or hit_obj.get("minesport_type") != flatter._TYPE_FLATTER:
            return {"PASS_THROUGH"}
        xyz = flatter._pick_xyz(hit_obj, location, normal)
        if xyz is None:
            return {"PASS_THROUGH"}
        if _interact(context, event, hit_obj, xyz):
            return {"RUNNING_MODAL"}
        return {"PASS_THROUGH"}


def _draw(layout, context):
    active = _active_flatter(context)
    running = _RUNNING and bool(context.scene.get(EDIT_MODE_KEY, False))
    row = layout.row()
    row.enabled = running or active is not None
    row.operator(
        MINESPORT_OT_flatter_edit_mode.bl_idname,
        text="FLATTER Edit Mode: ON" if running else "FLATTER Edit Mode: OFF",
        icon="RADIOBUT_ON" if running else "RADIOBUT_OFF",
    )
    if active is not None:
        layout.prop(active.minesport, "flatter_interaction_mode", text="Click action")
    layout.label(
        text="ON captures clicks only when they hit FLATTER; normal Blender clicks pass through.",
        icon="INFO",
    )


class MINESPORT_PT_edit_mode_view(bpy.types.Panel):
    bl_label = "FLATTER Edit Mode"
    bl_idname = "MINESPORT_PT_edit_mode_view"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"

    @classmethod
    def poll(cls, context):
        return _RUNNING or _active_flatter(context) is not None

    def draw(self, context):
        _draw(self.layout, context)


class MINESPORT_PT_edit_mode_properties(bpy.types.Panel):
    bl_label = "FLATTER Edit Mode"
    bl_idname = "MINESPORT_PT_edit_mode_properties"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "object"
    bl_parent_id = "MINESPORT_PT_properties"

    @classmethod
    def poll(cls, context):
        return _RUNNING or _active_flatter(context) is not None

    def draw(self, context):
        _draw(self.layout, context)


_CLASSES = (
    MINESPORT_OT_flatter_edit_mode,
    MINESPORT_PT_edit_mode_view,
    MINESPORT_PT_edit_mode_properties,
)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    if getattr(bpy.context, "scene", None) is not None:
        _set_running(bpy.context.scene, False)
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
