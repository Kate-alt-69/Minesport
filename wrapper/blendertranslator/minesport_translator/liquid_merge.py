"""Keyframeable FLATTER devirtualization for animation.

Liquid Merge keeps selected logical voxels inside the greedy FLATTER mesh
while ON. When OFF, only the selected voxels are removed from the parent mesh
and exposed as persistent Blender proxy objects. Proxy animation tracks persist
while their mesh is emptied during merged frames, so render vertices only exist
when animation needs them.
"""

import json

import bpy
import gpu
from bpy.app.handlers import persistent
from bpy.props import EnumProperty
from bpy_extras import view3d_utils
from gpu_extras.batch import batch_for_shader
from mathutils import Vector

from . import flatter

MODIFIER_NAME = "Minesport FLATTER Control"
NODE_GROUP_NAME = "Minesport FLATTER Control"
LIQUID_SOCKET_NAME = "Liquid Merge"

MERGE_KEY = "minesport_liquid_merge"
SOCKET_KEY = "minesport_liquid_socket_id"
SELECTION_KEY = "minesport_flatter_liquid_selection"
BOX_ANCHOR_KEY = "minesport_flatter_liquid_box_anchor"
LAST_PROP_KEY = "minesport_liquid_last_property"
LAST_NODE_KEY = "minesport_liquid_last_node"
LAST_APPLIED_KEY = "minesport_liquid_last_applied"
LAST_SELECTION_KEY = "minesport_liquid_last_selection"

TYPE_LIQUID_BLOCK = "FLATTER_LIQUID_BLOCK"
PROXY_PARENT_KEY = "minesport_liquid_parent"
PROXY_XYZ_KEY = "minesport_liquid_xyz"
PROXY_PALETTE_KEY = "minesport_liquid_palette"

_ORIGINAL_ATTACH = None
_DRAW_HANDLE = None
_APPLYING = set()

_SELECTION_GREEN = (0.55, 1.0, 0.18, 0.95)
_ANCHOR_GREEN = (0.80, 1.0, 0.35, 1.0)

_CUBE_EDGES = (
    (0, 1), (1, 2), (2, 3), (3, 0),
    (4, 5), (5, 6), (6, 7), (7, 4),
    (0, 4), (1, 5), (2, 6), (3, 7),
)


def _active_flatter(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.type != "MESH":
        return None
    return obj if obj.get("minesport_type") == flatter._TYPE_FLATTER else None


def _selection(obj):
    raw = obj.get(SELECTION_KEY, "[]")
    try:
        values = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        return set()
    result = set()
    if isinstance(values, list):
        for item in values:
            if isinstance(item, (list, tuple)) and len(item) >= 3:
                result.add((int(item[0]), int(item[1]), int(item[2])))
    return result


def _selection_json(coords):
    return json.dumps(
        [list(xyz) for xyz in sorted(coords, key=lambda p: (p[1], p[2], p[0]))],
        separators=(",", ":"),
    )


def _store_selection(obj, coords, apply=True):
    payload = flatter._load_payload(obj)
    grid = flatter._decode_grid(payload) if payload else {}
    cleaned = set()
    for xyz in coords:
        value = tuple(map(int, xyz))
        if value in grid:
            cleaned.add(value)
    obj[SELECTION_KEY] = _selection_json(cleaned)
    if apply:
        _apply_liquid_state(obj, force=True)
    _tag_redraw()
    return cleaned


def _box_anchor(obj):
    raw = obj.get(BOX_ANCHOR_KEY, "")
    if not raw:
        return None
    try:
        value = json.loads(raw)
        if isinstance(value, list) and len(value) >= 3:
            return int(value[0]), int(value[1]), int(value[2])
    except Exception:
        pass
    return None


def _set_box_anchor(obj, xyz):
    if xyz is None:
        if BOX_ANCHOR_KEY in obj:
            del obj[BOX_ANCHOR_KEY]
    else:
        obj[BOX_ANCHOR_KEY] = json.dumps(list(map(int, xyz)), separators=(",", ":"))
    _tag_redraw()


def _ensure_merge_property(obj):
    if MERGE_KEY not in obj:
        obj[MERGE_KEY] = True
        try:
            obj.id_properties_ui(MERGE_KEY).update(
                default=True,
                description=(
                    "ON keeps selected voxels inside FLATTER; OFF exposes them "
                    "as real animated block geometry."
                ),
            )
        except Exception:
            pass
    return bool(obj.get(MERGE_KEY, True))


def _node_group():
    group = bpy.data.node_groups.get(NODE_GROUP_NAME)
    if group is not None and group.bl_idname == "GeometryNodeTree":
        socket_id = group.get("minesport_liquid_socket_id")
        if socket_id:
            return group, str(socket_id)

    if group is not None:
        bpy.data.node_groups.remove(group, do_unlink=True)

    group = bpy.data.node_groups.new(NODE_GROUP_NAME, "GeometryNodeTree")
    geo_in = group.interface.new_socket(
        name="Geometry", in_out="INPUT", socket_type="NodeSocketGeometry"
    )
    liquid = group.interface.new_socket(
        name=LIQUID_SOCKET_NAME, in_out="INPUT", socket_type="NodeSocketBool"
    )
    group.interface.new_socket(
        name="Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry"
    )

    input_node = group.nodes.new("NodeGroupInput")
    output_node = group.nodes.new("NodeGroupOutput")
    input_node.name = "Minesport FLATTER Input"
    output_node.name = "Minesport FLATTER Output"
    group.links.new(input_node.outputs[geo_in.name], output_node.inputs["Geometry"])

    liquid_id = getattr(liquid, "identifier", None) or LIQUID_SOCKET_NAME
    group["minesport_liquid_socket_id"] = str(liquid_id)
    group["minesport_flatter_control_version"] = "0.1.0"
    return group, str(liquid_id)


def ensure_control_node(obj):
    if obj is None or obj.type != "MESH":
        return None
    if obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None

    prop = _ensure_merge_property(obj)
    try:
        group, socket_id = _node_group()
        modifier = obj.modifiers.get(MODIFIER_NAME)
        if modifier is None or modifier.type != "NODES":
            modifier = obj.modifiers.new(MODIFIER_NAME, "NODES")
        modifier.node_group = group
        obj[SOCKET_KEY] = socket_id
        if modifier.get(socket_id) is None:
            modifier[socket_id] = prop
        if LAST_PROP_KEY not in obj:
            obj[LAST_PROP_KEY] = prop
        if LAST_NODE_KEY not in obj:
            obj[LAST_NODE_KEY] = bool(modifier.get(socket_id, prop))
        return modifier
    except Exception as exc:
        print(f"[Minesport FLATTER] Liquid Merge node unavailable on {obj.name}: {exc}")
        return None


def _modifier_state(obj):
    prop = _ensure_merge_property(obj)
    modifier = obj.modifiers.get(MODIFIER_NAME)
    socket_id = str(obj.get(SOCKET_KEY, ""))
    if modifier is None or not socket_id:
        return prop, None, modifier
    try:
        return prop, bool(modifier.get(socket_id, prop)), modifier
    except Exception:
        return prop, None, modifier


def _sync_control(obj):
    ensure_control_node(obj)
    prop, node, modifier = _modifier_state(obj)
    last_prop = bool(obj.get(LAST_PROP_KEY, prop))
    last_node = bool(obj.get(LAST_NODE_KEY, node if node is not None else prop))
    prop_changed = prop != last_prop
    node_changed = node is not None and node != last_node

    if node_changed and not prop_changed:
        obj[MERGE_KEY] = bool(node)
        prop = bool(node)
    elif prop_changed and node is not None:
        socket_id = str(obj.get(SOCKET_KEY, ""))
        try:
            modifier[socket_id] = bool(prop)
            node = bool(prop)
        except Exception:
            pass
    elif node is not None and node != prop:
        socket_id = str(obj.get(SOCKET_KEY, ""))
        try:
            modifier[socket_id] = bool(prop)
            node = bool(prop)
        except Exception:
            pass

    obj[LAST_PROP_KEY] = bool(prop)
    obj[LAST_NODE_KEY] = bool(node if node is not None else prop)
    return bool(prop)


def _parent_id(parent):
    return str(parent.get("minesport_flatter_id") or parent.name)


def _proxy_name(parent, xyz):
    x, y, z = xyz
    safe = _parent_id(parent).replace(":", "_").replace("/", "_").replace("\\", "_")
    return f"Minesport_Liquid_{safe}_{x}_{y}_{z}"


def _proxy_xyz(obj):
    raw = obj.get(PROXY_XYZ_KEY, "")
    try:
        value = json.loads(raw)
        if isinstance(value, list) and len(value) >= 3:
            return int(value[0]), int(value[1]), int(value[2])
    except Exception:
        pass
    return None


def _proxies(parent):
    identifier = _parent_id(parent)
    result = {}
    for obj in bpy.context.scene.objects:
        if obj.get("minesport_type") != TYPE_LIQUID_BLOCK:
            continue
        if str(obj.get(PROXY_PARENT_KEY, "")) != identifier:
            continue
        xyz = _proxy_xyz(obj)
        if xyz is not None:
            result[xyz] = obj
    return result


def _clear_mesh(obj):
    mesh = getattr(obj, "data", None)
    if mesh is None:
        return
    try:
        mesh.clear_geometry()
        mesh.update()
        return
    except Exception:
        pass
    replacement = bpy.data.meshes.new(obj.name + "_MergedEmpty")
    old = obj.data
    obj.data = replacement
    if old is not None and old.users == 0:
        bpy.data.meshes.remove(old)


def _create_empty_proxy(parent, payload, xyz, palette_index):
    entry = flatter._palette(payload, palette_index)
    block_id = str(entry.get("id") or "minecraft:unknown") if isinstance(entry, dict) else "minecraft:unknown"
    name = _proxy_name(parent, xyz)
    mesh = bpy.data.meshes.new(name + "_Mesh")
    proxy = bpy.data.objects.new(name, mesh)
    collection = parent.users_collection[0] if parent.users_collection else bpy.context.scene.collection
    collection.objects.link(proxy)
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    proxy.location = Vector(flatter._mc_to_blender(xyz, center))
    proxy["minesport_type"] = TYPE_LIQUID_BLOCK
    proxy[PROXY_PARENT_KEY] = _parent_id(parent)
    proxy[PROXY_XYZ_KEY] = json.dumps(list(xyz), separators=(",", ":"))
    proxy[PROXY_PALETTE_KEY] = int(palette_index)
    proxy["minesport_source_block"] = block_id
    proxy["minesport_liquid_merge_controlled"] = True
    if hasattr(proxy, "minesport"):
        proxy.minesport.translated = True
        proxy.minesport.source_block = block_id
    return proxy


def _fill_proxy(parent, proxy, payload, xyz, palette_index):
    entry = flatter._palette(payload, palette_index)
    if entry is None:
        return False
    temp = flatter._create_materialized_block(parent, payload, xyz, palette_index, entry)
    if temp is None:
        return False
    old = proxy.data
    copied = temp.data.copy()
    temp_mesh = temp.data
    bpy.data.objects.remove(temp, do_unlink=True)
    proxy.data = copied
    if temp_mesh is not None and temp_mesh.users == 0:
        bpy.data.meshes.remove(temp_mesh)
    if old is not None and old.users == 0:
        bpy.data.meshes.remove(old)
    return True


def _sync_proxies(parent, payload, full_grid, selected, merged):
    existing = _proxies(parent)
    for xyz, proxy in list(existing.items()):
        if xyz not in selected or xyz not in full_grid:
            bpy.data.objects.remove(proxy, do_unlink=True)
            existing.pop(xyz, None)

    for xyz in selected:
        palette_index = full_grid.get(xyz)
        if palette_index is None:
            continue
        proxy = existing.get(xyz)
        if proxy is None:
            proxy = _create_empty_proxy(parent, payload, xyz, palette_index)
            existing[xyz] = proxy

        if merged:
            if len(proxy.data.polygons):
                _clear_mesh(proxy)
        elif len(proxy.data.polygons) == 0:
            _fill_proxy(parent, proxy, payload, xyz, palette_index)

        proxy.hide_viewport = False
        proxy.hide_render = False


def _apply_liquid_state(obj, force=False):
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return False
    pointer = obj.as_pointer()
    if pointer in _APPLYING:
        return False

    payload = flatter._load_payload(obj)
    if payload is None:
        return False
    full_grid = flatter._decode_grid(payload)
    selected = {xyz for xyz in _selection(obj) if xyz in full_grid}
    encoded = _selection_json(selected)
    if obj.get(SELECTION_KEY, "[]") != encoded:
        obj[SELECTION_KEY] = encoded

    merged = _sync_control(obj)
    last_state = bool(obj.get(LAST_APPLIED_KEY, not merged))
    last_selection = str(obj.get(LAST_SELECTION_KEY, ""))
    if not force and last_state == merged and last_selection == encoded:
        return False

    _APPLYING.add(pointer)
    try:
        obj[LAST_APPLIED_KEY] = bool(merged)
        obj[LAST_SELECTION_KEY] = encoded
        _sync_proxies(obj, payload, full_grid, selected, merged)
        render_grid = full_grid if merged else {
            xyz: palette for xyz, palette in full_grid.items() if xyz not in selected
        }
        flatter._rebuild_flatter(obj, payload, render_grid)
        obj["minesport_liquid_selected_count"] = len(selected)
        obj["minesport_liquid_merge_active"] = bool(merged)
        _tag_redraw()
        return True
    finally:
        _APPLYING.discard(pointer)


def _set_merge_state(obj, value, apply=True):
    obj[MERGE_KEY] = bool(value)
    modifier = ensure_control_node(obj)
    socket_id = str(obj.get(SOCKET_KEY, ""))
    if modifier is not None and socket_id:
        try:
            modifier[socket_id] = bool(value)
        except Exception:
            pass
    obj[LAST_PROP_KEY] = bool(value)
    obj[LAST_NODE_KEY] = bool(value)
    if apply:
        _apply_liquid_state(obj, force=True)


def _keyframe_merge(obj):
    _ensure_merge_property(obj)
    frame = bpy.context.scene.frame_current
    data_path = f'["{MERGE_KEY}"]'
    obj.keyframe_insert(data_path=data_path, frame=frame)
    action = getattr(getattr(obj, "animation_data", None), "action", None)
    if action is not None:
        for curve in action.fcurves:
            if curve.data_path == data_path:
                for key in curve.keyframe_points:
                    if abs(key.co.x - frame) < 1e-4:
                        key.interpolation = "CONSTANT"
    _apply_liquid_state(obj, force=True)


def _hit_xyz(context, parent, event):
    region = context.region
    rv3d = context.space_data.region_3d
    coord = (event.mouse_region_x, event.mouse_region_y)
    origin = view3d_utils.region_2d_to_origin_3d(region, rv3d, coord)
    direction = view3d_utils.region_2d_to_vector_3d(region, rv3d, coord)
    hit, location, normal, _face, hit_obj, _matrix = context.scene.ray_cast(
        context.view_layer.depsgraph, origin, direction
    )
    if not hit or hit_obj is None:
        return None
    if hit_obj == parent:
        return flatter._pick_xyz(parent, location, normal)
    if hit_obj.get("minesport_type") == TYPE_LIQUID_BLOCK:
        if str(hit_obj.get(PROXY_PARENT_KEY, "")) == _parent_id(parent):
            return _proxy_xyz(hit_obj)
    return None


def _apply_click_selection(parent, xyz, event):
    current = _selection(parent)
    if event.ctrl:
        current.discard(xyz)
    elif event.shift:
        current.add(xyz)
    else:
        current = {xyz} if current != {xyz} else set()
    _store_selection(parent, current, apply=True)


def _apply_box_selection(parent, first, second, event):
    payload = flatter._load_payload(parent)
    grid = flatter._decode_grid(payload) if payload else {}
    mins = tuple(min(first[i], second[i]) for i in range(3))
    maxs = tuple(max(first[i], second[i]) for i in range(3))
    box = {
        xyz for xyz in grid
        if all(mins[i] <= xyz[i] <= maxs[i] for i in range(3))
    }
    current = _selection(parent)
    if event.ctrl:
        current.difference_update(box)
    elif event.shift:
        current.update(box)
    else:
        current = box
    _store_selection(parent, current, apply=True)


class MINESPORT_OT_liquid_select(bpy.types.Operator):
    bl_idname = "minesport.flatter_liquid_select"
    bl_label = "Select FLATTER Voxels"
    bl_description = "Continuously select logical FLATTER blocks without creating render geometry"
    bl_options = {"REGISTER", "UNDO"}

    mode: EnumProperty(
        name="Mode",
        items=(
            ("BLOCK", "Blocks", "Click individual logical voxels"),
            ("BOX", "Box", "Pick two corners to select an occupied 3D voxel box"),
        ),
        default="BLOCK",
    )

    def invoke(self, context, event):
        parent = _active_flatter(context)
        if parent is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        self._parent_name = parent.name
        _set_box_anchor(parent, None)
        context.window_manager.modal_handler_add(self)
        context.workspace.status_text_set(
            "FLATTER Box Select: corner A → corner B · Shift add · Ctrl remove · Enter/Esc finish"
            if self.mode == "BOX"
            else "FLATTER Voxel Select: click blocks · Shift add · Ctrl remove · Enter/Esc finish"
        )
        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        parent = bpy.data.objects.get(getattr(self, "_parent_name", ""))
        if parent is None:
            context.workspace.status_text_set(None)
            return {"CANCELLED"}
        if event.type in {"ESC", "RET", "NUMPAD_ENTER"}:
            _set_box_anchor(parent, None)
            context.workspace.status_text_set(None)
            return {"FINISHED"}
        if event.type != "LEFTMOUSE" or event.value != "PRESS":
            return {"RUNNING_MODAL"}

        xyz = _hit_xyz(context, parent, event)
        if xyz is None:
            self.report({"WARNING"}, "No FLATTER logical voxel at that point")
            return {"RUNNING_MODAL"}

        if self.mode == "BOX":
            anchor = _box_anchor(parent)
            if anchor is None:
                _set_box_anchor(parent, xyz)
                self.report({"INFO"}, f"Box corner A: {xyz}")
            else:
                _apply_box_selection(parent, anchor, xyz, event)
                _set_box_anchor(parent, None)
                self.report({"INFO"}, f"Selected voxel box {anchor} → {xyz}")
        else:
            _apply_click_selection(parent, xyz, event)
            self.report({"INFO"}, f"Liquid selection: {len(_selection(parent))} voxel(s)")
        return {"RUNNING_MODAL"}


class MINESPORT_OT_liquid_clear_selection(bpy.types.Operator):
    bl_idname = "minesport.flatter_liquid_clear_selection"
    bl_label = "Clear Liquid Selection"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        obj = _active_flatter(context)
        if obj is None:
            return {"CANCELLED"}
        _set_box_anchor(obj, None)
        _store_selection(obj, set(), apply=True)
        return {"FINISHED"}


class MINESPORT_OT_liquid_toggle(bpy.types.Operator):
    bl_idname = "minesport.flatter_liquid_toggle"
    bl_label = "Toggle Liquid Merge"
    bl_description = "Toggle whether selected voxels belong to the greedy FLATTER mesh"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        obj = _active_flatter(context)
        if obj is None:
            return {"CANCELLED"}
        value = not bool(obj.get(MERGE_KEY, True))
        _set_merge_state(obj, value, apply=True)
        self.report(
            {"INFO"},
            "Liquid Merge ON — greedy" if value else "Liquid Merge OFF — live block geometry",
        )
        return {"FINISHED"}


class MINESPORT_OT_liquid_keyframe(bpy.types.Operator):
    bl_idname = "minesport.flatter_liquid_keyframe"
    bl_label = "Keyframe Liquid Merge"
    bl_description = "Insert a constant keyframe for the current Liquid Merge state"

    def execute(self, context):
        obj = _active_flatter(context)
        if obj is None:
            return {"CANCELLED"}
        _keyframe_merge(obj)
        self.report({"INFO"}, f"Liquid Merge keyframed at frame {context.scene.frame_current}")
        return {"FINISHED"}


class MINESPORT_PT_liquid_merge(bpy.types.Panel):
    bl_label = "FLATTER Liquid Merge"
    bl_idname = "MINESPORT_PT_liquid_merge"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"
    bl_parent_id = "MINESPORT_PT_flatter_view"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        layout = self.layout
        obj = _active_flatter(context)
        ensure_control_node(obj)
        selected = _selection(obj)
        merged = bool(obj.get(MERGE_KEY, True))

        node_box = layout.box()
        node_box.label(text="Minesport FLATTER Control", icon="NODETREE")
        row = node_box.row(align=True)
        row.operator(
            MINESPORT_OT_liquid_toggle.bl_idname,
            text="Liquid Merge: ON" if merged else "Liquid Merge: OFF",
            icon="CHECKBOX_HLT" if merged else "CHECKBOX_DEHLT",
            depress=merged,
        )
        row.operator(MINESPORT_OT_liquid_keyframe.bl_idname, text="", icon="KEY_HLT")
        node_box.label(
            text=(
                "Selected voxels are inside the greedy mesh."
                if merged else
                "Selected voxels are live Blender block geometry."
            ),
            icon="INFO",
        )

        select_box = layout.box()
        select_box.label(text=f"{len(selected):,} liquid-selected voxel(s)", icon="CUBE")
        row = select_box.row(align=True)
        op = row.operator(MINESPORT_OT_liquid_select.bl_idname, text="Select Blocks", icon="CUBE")
        op.mode = "BLOCK"
        op = row.operator(MINESPORT_OT_liquid_select.bl_idname, text="Box Select", icon="CUBE")
        op.mode = "BOX"
        select_box.operator(MINESPORT_OT_liquid_clear_selection.bl_idname, text="Clear Selection", icon="X")
        select_box.label(text="Bright green boxes are logical-only; no render vertices.", icon="INFO")
        select_box.label(text="OFF creates geometry only for selected animation blocks.", icon="INFO")


def _cube_corners(x, y, z):
    return (
        (x, y, z), (x + 1, y, z), (x + 1, y + 1, z), (x, y + 1, z),
        (x, y, z + 1), (x + 1, y, z + 1), (x + 1, y + 1, z + 1), (x, y + 1, z + 1),
    )


def _edge_key(a, b):
    return (a, b) if a <= b else (b, a)


def _world_edges(obj, coords):
    payload = flatter._load_payload(obj)
    if payload is None:
        return []
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    edges = set()
    for xyz in coords:
        corners = _cube_corners(*xyz)
        for a, b in _CUBE_EDGES:
            edges.add(_edge_key(corners[a], corners[b]))
    matrix = obj.matrix_world
    result = []
    for a, b in edges:
        result.append(tuple(matrix @ Vector(flatter._mc_to_blender(a, center))))
        result.append(tuple(matrix @ Vector(flatter._mc_to_blender(b, center))))
    return result


def _draw_lines(vertices, color, width):
    if not vertices:
        return
    shader = gpu.shader.from_builtin("UNIFORM_COLOR")
    batch = batch_for_shader(shader, "LINES", {"pos": vertices})
    gpu.state.blend_set("ALPHA")
    gpu.state.depth_test_set("LESS_EQUAL")
    gpu.state.line_width_set(width)
    shader.bind()
    shader.uniform_float("color", color)
    batch.draw(shader)
    gpu.state.line_width_set(1.0)
    gpu.state.depth_test_set("NONE")
    gpu.state.blend_set("NONE")


def _draw_overlay():
    obj = _active_flatter()
    if obj is None or not obj.select_get():
        return
    selected = _selection(obj)
    if selected:
        _draw_lines(_world_edges(obj, selected), _SELECTION_GREEN, 2.0)
    anchor = _box_anchor(obj)
    if anchor is not None:
        _draw_lines(_world_edges(obj, {anchor}), _ANCHOR_GREEN, 3.0)


def _tag_redraw():
    try:
        for window in bpy.context.window_manager.windows:
            screen = window.screen
            if screen is None:
                continue
            for area in screen.areas:
                if area.type == "VIEW_3D":
                    area.tag_redraw()
    except Exception:
        pass


def _attach_wrapper(metadata, objects=None, asset_path=None):
    _ORIGINAL_ATTACH(metadata, objects=objects, asset_path=asset_path)
    candidates = list(objects) if objects is not None else list(bpy.context.scene.objects)
    for obj in candidates:
        if obj.get("minesport_type") != flatter._TYPE_FLATTER:
            continue
        ensure_control_node(obj)
        if SELECTION_KEY not in obj:
            obj[SELECTION_KEY] = "[]"
        _apply_liquid_state(obj, force=True)


@persistent
def _frame_change(scene, _depsgraph=None):
    for obj in list(scene.objects):
        if obj.get("minesport_type") == flatter._TYPE_FLATTER:
            try:
                _apply_liquid_state(obj, force=False)
            except Exception as exc:
                print(f"[Minesport FLATTER] Liquid Merge frame update failed on {obj.name}: {exc}")


@persistent
def _depsgraph_update(_scene, _depsgraph):
    obj = _active_flatter()
    if obj is None:
        return
    try:
        _apply_liquid_state(obj, force=False)
    except Exception as exc:
        print(f"[Minesport FLATTER] Liquid Merge live update failed on {obj.name}: {exc}")


_CLASSES = (
    MINESPORT_OT_liquid_select,
    MINESPORT_OT_liquid_clear_selection,
    MINESPORT_OT_liquid_toggle,
    MINESPORT_OT_liquid_keyframe,
    MINESPORT_PT_liquid_merge,
)


def register():
    global _ORIGINAL_ATTACH, _DRAW_HANDLE
    if _ORIGINAL_ATTACH is not None:
        return
    _ORIGINAL_ATTACH = flatter.attach_flatter_metadata
    flatter.attach_flatter_metadata = _attach_wrapper
    for cls in _CLASSES:
        bpy.utils.register_class(cls)
    if _frame_change not in bpy.app.handlers.frame_change_post:
        bpy.app.handlers.frame_change_post.append(_frame_change)
    if _depsgraph_update not in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.append(_depsgraph_update)
    if _DRAW_HANDLE is None:
        _DRAW_HANDLE = bpy.types.SpaceView3D.draw_handler_add(_draw_overlay, (), "WINDOW", "POST_VIEW")
    for obj in list(bpy.context.scene.objects):
        if obj.get("minesport_type") == flatter._TYPE_FLATTER:
            ensure_control_node(obj)
            if SELECTION_KEY not in obj:
                obj[SELECTION_KEY] = "[]"
            _apply_liquid_state(obj, force=True)


def unregister():
    global _ORIGINAL_ATTACH, _DRAW_HANDLE
    if _DRAW_HANDLE is not None:
        try:
            bpy.types.SpaceView3D.draw_handler_remove(_DRAW_HANDLE, "WINDOW")
        except Exception:
            pass
        _DRAW_HANDLE = None
    if _frame_change in bpy.app.handlers.frame_change_post:
        bpy.app.handlers.frame_change_post.remove(_frame_change)
    if _depsgraph_update in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.remove(_depsgraph_update)
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
    if _ORIGINAL_ATTACH is not None:
        flatter.attach_flatter_metadata = _ORIGINAL_ATTACH
        _ORIGINAL_ATTACH = None
