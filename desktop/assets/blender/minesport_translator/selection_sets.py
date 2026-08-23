"""Named logical FLATTER selection sets and materialization safety.

Selection sets live as compact JSON custom data on the FLATTER object, so they
survive inside the .blend and remain independent of the source OBJ/glTF.
"""

import json

import bpy
import gpu
from bpy.props import EnumProperty, StringProperty
from gpu_extras.batch import batch_for_shader

from . import flatter
from . import liquid_merge


SETS_KEY = "minesport_flatter_selection_sets_v1"
PINNED_KEY = "minesport_flatter_pinned"
SET_NAME_KEY = "minesport_flatter_selection_set"
WARNING_KEY = "minesport_flatter_materialize_warning"

_ORIGINAL_MATERIALIZE_MANY = None
_ORIGINAL_RETURN_MATERIALIZED = None
_ORIGINAL_LIQUID_DRAW_LINES = None
_ORIGINAL_LIQUID_SELECTION = None


def _active_flatter(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    return obj


def _sets(obj):
    raw = obj.get(SETS_KEY, "{}") if obj is not None else "{}"
    try:
        value = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        return {}
    if not isinstance(value, dict):
        return {}
    result = {}
    for name, record in value.items():
        if not isinstance(name, str) or not isinstance(record, dict):
            continue
        coords = []
        for xyz in record.get("coords", []):
            if isinstance(xyz, (list, tuple)) and len(xyz) >= 3:
                coords.append([int(xyz[0]), int(xyz[1]), int(xyz[2])])
        result[name] = {
            "coords": coords,
            "pinned": bool(record.get("pinned", False)),
            "protected": bool(record.get("protected", False)),
        }
    return result


def _write_sets(obj, records):
    obj[SETS_KEY] = json.dumps(records, separators=(",", ":"), sort_keys=True)


def _set_coords(record):
    return {
        (int(xyz[0]), int(xyz[1]), int(xyz[2]))
        for xyz in record.get("coords", [])
        if isinstance(xyz, (list, tuple)) and len(xyz) >= 3
    }


def _protected_coords(parent):
    result = set()
    for record in _sets(parent).values():
        if record.get("protected"):
            result.update(_set_coords(record))
    return result


def _materialization_limit(parent):
    props = getattr(parent, "minesport", None)
    if props is None:
        return 1024
    return max(1, int(getattr(props, "flatter_materialization_limit", 1024)))


def _status(message):
    try:
        bpy.context.workspace.status_text_set(message)
    except Exception:
        pass


def _guarded_materialize_many(parent, payload, grid, coordinates):
    requested = []
    seen = set()
    protected = _protected_coords(parent)
    for xyz in coordinates:
        xyz = tuple(map(int, xyz))
        if xyz in seen or xyz in protected:
            continue
        seen.add(xyz)
        requested.append(xyz)

    limit = _materialization_limit(parent)
    if len(requested) > limit:
        message = (
            f"Minesport blocked materializing {len(requested):,} blocks; "
            f"current safety limit is {limit:,}."
        )
        parent[WARNING_KEY] = message
        _status(message)
        print("[Minesport FLATTER] " + message)
        return []

    if WARNING_KEY in parent:
        del parent[WARNING_KEY]
    return _ORIGINAL_MATERIALIZE_MANY(parent, payload, grid, requested)


def _guarded_return_materialized(obj):
    if bool(obj.get(PINNED_KEY, False)):
        name = str(obj.get(SET_NAME_KEY) or "selection set")
        raise RuntimeError(
            f"This block is pinned by '{name}'. Unpin the set before returning it to FLATTER."
        )
    return _ORIGINAL_RETURN_MATERIALIZED(obj)


def _guarded_liquid_selection(obj):
    selected = _ORIGINAL_LIQUID_SELECTION(obj)
    if not selected:
        return selected
    return selected.difference(_protected_coords(obj))


def _guarded_liquid_draw_lines(vertices, color, width):
    obj = liquid_merge._active_flatter()
    props = getattr(obj, "minesport", None) if obj is not None else None
    if props is not None and str(getattr(props, "flatter_overlay_mode", "FULL")) == "OFF":
        return
    opacity = float(getattr(props, "flatter_overlay_opacity", 0.58)) if props is not None else 0.58
    opacity = max(0.05, min(1.0, opacity))
    xray = bool(getattr(props, "flatter_overlay_xray", False)) if props is not None else False
    if not vertices:
        return
    shader = gpu.shader.from_builtin("UNIFORM_COLOR")
    batch = batch_for_shader(shader, "LINES", {"pos": vertices})
    gpu.state.blend_set("ALPHA")
    gpu.state.depth_test_set("NONE" if xray else "LESS_EQUAL")
    gpu.state.line_width_set(width)
    shader.bind()
    rgba = (float(color[0]), float(color[1]), float(color[2]), min(1.0, opacity + 0.25))
    shader.uniform_float("color", rgba)
    batch.draw(shader)
    gpu.state.line_width_set(1.0)
    gpu.state.depth_test_set("NONE")
    gpu.state.blend_set("NONE")


def _find_pinned(parent, set_name):
    identifier = str(parent.get("minesport_flatter_id") or parent.name)
    result = []
    for obj in list(bpy.context.scene.objects):
        if obj.get("minesport_type") != flatter._TYPE_MATERIALIZED:
            continue
        if str(obj.get("minesport_flatter_parent") or "") != identifier:
            continue
        if str(obj.get(SET_NAME_KEY) or "") != set_name:
            continue
        if bool(obj.get(PINNED_KEY, False)):
            result.append(obj)
    return result


class MINESPORT_OT_flatter_interact(bpy.types.Operator):
    bl_idname = "minesport.flatter_interact"
    bl_label = "Start FLATTER Interaction"
    bl_description = "Start the interaction mode selected for this FLATTER object"
    bl_options = {"REGISTER", "UNDO"}

    def invoke(self, context, event):
        parent = _active_flatter(context)
        if parent is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        mode = str(getattr(parent.minesport, "flatter_interaction_mode", "SELECT"))
        if mode == "INSPECT":
            bpy.ops.minesport.flatter_pick("INVOKE_DEFAULT")
            return {"FINISHED"}
        if mode == "SELECT":
            bpy.ops.minesport.flatter_liquid_select("INVOKE_DEFAULT", mode="BLOCK")
            return {"FINISHED"}
        if mode == "BOX":
            bpy.ops.minesport.flatter_liquid_select("INVOKE_DEFAULT", mode="BOX")
            return {"FINISHED"}

        self._parent_name = parent.name
        context.window_manager.modal_handler_add(self)
        context.workspace.status_text_set("FLATTER Materialize: click one logical block · Esc to cancel")
        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        if event.type in {"ESC", "RIGHTMOUSE"}:
            context.workspace.status_text_set(None)
            return {"CANCELLED"}
        if event.type != "LEFTMOUSE" or event.value != "PRESS":
            return {"RUNNING_MODAL"}
        parent = bpy.data.objects.get(getattr(self, "_parent_name", ""))
        if parent is None:
            context.workspace.status_text_set(None)
            return {"CANCELLED"}
        xyz = liquid_merge._hit_xyz(context, parent, event)
        if xyz is None:
            self.report({"WARNING"}, "No logical FLATTER block at that point")
            return {"RUNNING_MODAL"}
        payload = flatter._load_payload(parent)
        grid = flatter._decode_grid(payload) if payload else {}
        created = flatter._materialize_many(parent, payload, grid, [xyz])
        context.workspace.status_text_set(None)
        if not created:
            warning = str(parent.get(WARNING_KEY) or "That logical block is protected or unavailable")
            self.report({"WARNING"}, warning)
            return {"CANCELLED"}
        bpy.ops.object.select_all(action="DESELECT")
        created[0].select_set(True)
        context.view_layer.objects.active = created[0]
        return {"FINISHED"}


class MINESPORT_OT_selection_set_save(bpy.types.Operator):
    bl_idname = "minesport.flatter_selection_set_save"
    bl_label = "Save Selection Set"
    bl_description = "Save the current logical voxel selection as a named FLATTER set"
    bl_options = {"REGISTER", "UNDO"}

    set_name: StringProperty(name="Name", default="Selection Set")

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)

    def execute(self, context):
        obj = _active_flatter(context)
        if obj is None:
            return {"CANCELLED"}
        name = self.set_name.strip()
        if not name:
            self.report({"ERROR"}, "Selection set needs a name")
            return {"CANCELLED"}
        selected = liquid_merge._selection(obj)
        if not selected:
            self.report({"ERROR"}, "Select logical FLATTER blocks first")
            return {"CANCELLED"}
        records = _sets(obj)
        old = records.get(name, {})
        records[name] = {
            "coords": [list(xyz) for xyz in sorted(selected, key=lambda p: (p[1], p[2], p[0]))],
            "pinned": bool(old.get("pinned", False)),
            "protected": bool(old.get("protected", False)),
        }
        _write_sets(obj, records)
        obj.minesport.flatter_active_set = name
        self.report({"INFO"}, f"Saved '{name}' with {len(selected):,} logical block(s)")
        return {"FINISHED"}


class MINESPORT_OT_selection_set_action(bpy.types.Operator):
    bl_idname = "minesport.flatter_selection_set_action"
    bl_label = "FLATTER Selection Set"
    bl_options = {"REGISTER", "UNDO"}

    action: EnumProperty(
        items=(
            ("LOAD", "Load", "Load this set into the logical selection"),
            ("DELETE", "Delete", "Delete this saved selection set"),
            ("PIN", "Pin", "Materialize this set and prevent it from returning to FLATTER"),
            ("UNPIN", "Unpin", "Return untransformed pinned blocks to FLATTER"),
            ("PROTECT", "Protect", "Prevent this set from being materialized"),
            ("UNPROTECT", "Unprotect", "Allow this set to be materialized"),
            ("MATERIALIZE", "Materialize", "Materialize this logical selection set now"),
        )
    )
    set_name: StringProperty()

    def execute(self, context):
        obj = _active_flatter(context)
        if obj is None:
            return {"CANCELLED"}
        records = _sets(obj)
        record = records.get(self.set_name)
        if record is None:
            self.report({"ERROR"}, "Selection set no longer exists")
            return {"CANCELLED"}
        coords = _set_coords(record)
        obj.minesport.flatter_active_set = self.set_name

        if self.action == "LOAD":
            loaded = liquid_merge._store_selection(obj, coords, apply=True)
            self.report({"INFO"}, f"Loaded {len(loaded):,} logical block(s)")
            return {"FINISHED"}

        if self.action == "DELETE":
            if record.get("pinned") or _find_pinned(obj, self.set_name):
                self.report({"ERROR"}, "Unpin this set before deleting it")
                return {"CANCELLED"}
            del records[self.set_name]
            _write_sets(obj, records)
            if obj.minesport.flatter_active_set == self.set_name:
                obj.minesport.flatter_active_set = ""
            liquid_merge._apply_liquid_state(obj, force=True)
            return {"FINISHED"}

        if self.action in {"PROTECT", "UNPROTECT"}:
            record["protected"] = self.action == "PROTECT"
            records[self.set_name] = record
            _write_sets(obj, records)
            liquid_merge._apply_liquid_state(obj, force=True)
            return {"FINISHED"}

        if self.action == "PIN":
            if record.get("protected"):
                self.report({"ERROR"}, "Protected sets cannot be pinned/materialized")
                return {"CANCELLED"}
            payload = flatter._load_payload(obj)
            grid = flatter._decode_grid(payload) if payload else {}
            created = flatter._materialize_many(obj, payload, grid, coords)
            if not created:
                warning = str(obj.get(WARNING_KEY) or "No blocks were materialized")
                self.report({"WARNING"}, warning)
                return {"CANCELLED"}
            for block in created:
                block[PINNED_KEY] = True
                block[SET_NAME_KEY] = self.set_name
            record["pinned"] = True
            records[self.set_name] = record
            _write_sets(obj, records)
            self.report({"INFO"}, f"Pinned {len(created):,} block(s) from '{self.set_name}'")
            return {"FINISHED"}

        if self.action == "UNPIN":
            blocks = _find_pinned(obj, self.set_name)
            returned = 0
            for block in blocks:
                block[PINNED_KEY] = False
                try:
                    _ORIGINAL_RETURN_MATERIALIZED(block)
                    returned += 1
                except Exception as exc:
                    block[PINNED_KEY] = True
                    print(f"[Minesport FLATTER] Could not unpin {block.name}: {exc}")
            record["pinned"] = bool(_find_pinned(obj, self.set_name))
            records[self.set_name] = record
            _write_sets(obj, records)
            self.report({"INFO"}, f"Returned {returned:,} pinned block(s) to FLATTER")
            return {"FINISHED"}

        if self.action == "MATERIALIZE":
            if record.get("protected"):
                self.report({"ERROR"}, "This set is protected from materialization")
                return {"CANCELLED"}
            payload = flatter._load_payload(obj)
            grid = flatter._decode_grid(payload) if payload else {}
            created = flatter._materialize_many(obj, payload, grid, coords)
            if not created:
                self.report({"WARNING"}, str(obj.get(WARNING_KEY) or "No blocks were materialized"))
                return {"CANCELLED"}
            self.report({"INFO"}, f"Materialized {len(created):,} block(s)")
            return {"FINISHED"}

        return {"CANCELLED"}


def _draw_sets(layout, obj):
    props = obj.minesport
    controls = layout.box()
    controls.label(text="Interaction", icon="RESTRICT_SELECT_OFF")
    controls.prop(props, "flatter_interaction_mode", text="")
    controls.operator(MINESPORT_OT_flatter_interact.bl_idname, icon="RESTRICT_SELECT_OFF")
    controls.prop(props, "flatter_materialization_limit")

    records = _sets(obj)
    sets_box = layout.box()
    sets_box.label(text=f"Selection Sets · {len(records)}", icon="CUBE")
    sets_box.operator(MINESPORT_OT_selection_set_save.bl_idname, icon="ADD")
    warning = str(obj.get(WARNING_KEY) or "")
    if warning:
        sets_box.label(text=warning, icon="ERROR")

    for name in sorted(records):
        record = records[name]
        row = sets_box.row(align=True)
        label = name
        if record.get("pinned"):
            label += " · PINNED"
        if record.get("protected"):
            label += " · PROTECTED"
        row.label(text=label, icon="CUBE")
        op = row.operator(MINESPORT_OT_selection_set_action.bl_idname, text="Load")
        op.action, op.set_name = "LOAD", name
        op = row.operator(MINESPORT_OT_selection_set_action.bl_idname, text="Materialize")
        op.action, op.set_name = "MATERIALIZE", name

        row = sets_box.row(align=True)
        op = row.operator(MINESPORT_OT_selection_set_action.bl_idname, text="Unpin" if record.get("pinned") else "Pin / Never Merge")
        op.action = "UNPIN" if record.get("pinned") else "PIN"
        op.set_name = name
        op = row.operator(MINESPORT_OT_selection_set_action.bl_idname, text="Unprotect" if record.get("protected") else "Protect")
        op.action = "UNPROTECT" if record.get("protected") else "PROTECT"
        op.set_name = name
        op = row.operator(MINESPORT_OT_selection_set_action.bl_idname, text="", icon="X")
        op.action, op.set_name = "DELETE", name


class MINESPORT_PT_selection_sets_view(bpy.types.Panel):
    bl_label = "Interaction & Selection Sets"
    bl_idname = "MINESPORT_PT_selection_sets_view"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"
    bl_parent_id = "MINESPORT_PT_flatter_view"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        _draw_sets(self.layout, _active_flatter(context))


class MINESPORT_PT_selection_sets_properties(bpy.types.Panel):
    bl_label = "FLATTER Selection Sets"
    bl_idname = "MINESPORT_PT_selection_sets_properties"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "object"
    bl_parent_id = "MINESPORT_PT_properties"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        _draw_sets(self.layout, _active_flatter(context))


class MINESPORT_PT_selection_sets_nodes(bpy.types.Panel):
    bl_label = "FLATTER Selection Sets"
    bl_idname = "MINESPORT_PT_selection_sets_nodes"
    bl_space_type = "NODE_EDITOR"
    bl_region_type = "UI"
    bl_category = "Minesport"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        _draw_sets(self.layout, _active_flatter(context))


_CLASSES = (
    MINESPORT_OT_flatter_interact,
    MINESPORT_OT_selection_set_save,
    MINESPORT_OT_selection_set_action,
    MINESPORT_PT_selection_sets_view,
    MINESPORT_PT_selection_sets_properties,
    MINESPORT_PT_selection_sets_nodes,
)


def register():
    global _ORIGINAL_MATERIALIZE_MANY, _ORIGINAL_RETURN_MATERIALIZED
    global _ORIGINAL_LIQUID_DRAW_LINES, _ORIGINAL_LIQUID_SELECTION
    if _ORIGINAL_MATERIALIZE_MANY is not None:
        return
    _ORIGINAL_MATERIALIZE_MANY = flatter._materialize_many
    _ORIGINAL_RETURN_MATERIALIZED = flatter._return_materialized
    _ORIGINAL_LIQUID_DRAW_LINES = liquid_merge._draw_lines
    _ORIGINAL_LIQUID_SELECTION = liquid_merge._selection
    flatter._materialize_many = _guarded_materialize_many
    flatter._return_materialized = _guarded_return_materialized
    liquid_merge._draw_lines = _guarded_liquid_draw_lines
    liquid_merge._selection = _guarded_liquid_selection
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    global _ORIGINAL_MATERIALIZE_MANY, _ORIGINAL_RETURN_MATERIALIZED
    global _ORIGINAL_LIQUID_DRAW_LINES, _ORIGINAL_LIQUID_SELECTION
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
    if _ORIGINAL_MATERIALIZE_MANY is not None:
        flatter._materialize_many = _ORIGINAL_MATERIALIZE_MANY
        _ORIGINAL_MATERIALIZE_MANY = None
    if _ORIGINAL_RETURN_MATERIALIZED is not None:
        flatter._return_materialized = _ORIGINAL_RETURN_MATERIALIZED
        _ORIGINAL_RETURN_MATERIALIZED = None
    if _ORIGINAL_LIQUID_DRAW_LINES is not None:
        liquid_merge._draw_lines = _ORIGINAL_LIQUID_DRAW_LINES
        _ORIGINAL_LIQUID_DRAW_LINES = None
    if _ORIGINAL_LIQUID_SELECTION is not None:
        liquid_merge._selection = _ORIGINAL_LIQUID_SELECTION
        _ORIGINAL_LIQUID_SELECTION = None
