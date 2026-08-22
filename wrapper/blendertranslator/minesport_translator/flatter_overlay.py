"""GPU-only 3D logical-block overview for selected FLATTER cells.

The overlay never adds Blender mesh geometry. It reconstructs the complete
logical voxel grid from the embedded palette/RLE payload and draws it as a
viewport aid, so a heavily greedy FLATTER mesh still feels block-addressable
in width, height and depth.
"""

import json

import blf
import bpy
import gpu
from gpu_extras.batch import batch_for_shader
from mathutils import Vector

from . import flatter


_VIEW_HANDLE = None
_TEXT_HANDLE = None
_CACHE = {}

_GRID_RGB = (0.10, 0.33, 0.14)
_SELECTED_GREEN_RGB = (0.48, 1.0, 0.54)

_CUBE_EDGES = (
    (0, 1), (1, 2), (2, 3), (3, 0),
    (4, 5), (5, 6), (6, 7), (7, 4),
    (0, 4), (1, 5), (2, 6), (3, 7),
)


def _active_flatter():
    obj = bpy.context.view_layer.objects.active
    if obj is None or obj.type != "MESH":
        return None
    if obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    if not obj.select_get():
        return None
    return obj


def _overlay_settings(obj):
    props = getattr(obj, "minesport", None)
    if props is None:
        return "SELECTED", 0.32, False
    mode = str(getattr(props, "flatter_overlay_mode", "SELECTED") or "SELECTED")
    opacity = max(0.05, min(1.0, float(getattr(props, "flatter_overlay_opacity", 0.32))))
    xray = bool(getattr(props, "flatter_overlay_xray", False))
    return mode, opacity, xray


def _payload_stamp(obj):
    raw = obj.get(flatter._DATA_KEY, "")
    return hash(raw) if isinstance(raw, str) else 0


def _edge_key(a, b):
    a = tuple(a)
    b = tuple(b)
    return (a, b) if a <= b else (b, a)


def _cube_corners(x, y, z):
    return (
        (x, y, z),
        (x + 1, y, z),
        (x + 1, y + 1, z),
        (x, y + 1, z),
        (x, y, z + 1),
        (x + 1, y, z + 1),
        (x + 1, y + 1, z + 1),
        (x, y + 1, z + 1),
    )


def _logical_edges(obj):
    stamp = _payload_stamp(obj)
    pointer = obj.as_pointer()
    cached = _CACHE.get(pointer)
    if cached is not None and cached[0] == stamp:
        return cached[1]

    payload = flatter._load_payload(obj)
    if payload is None:
        result = []
        _CACHE[pointer] = (stamp, result)
        return result

    grid = flatter._decode_grid(payload)
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    edges = set()

    for x, y, z in grid:
        corners = _cube_corners(x, y, z)
        for a, b in _CUBE_EDGES:
            edges.add(_edge_key(corners[a], corners[b]))

    result = []
    for a, b in edges:
        result.append(flatter._mc_to_blender(a, center))
        result.append(flatter._mc_to_blender(b, center))

    _CACHE[pointer] = (stamp, result)
    return result


def _selected_edges(obj):
    try:
        xyz = tuple(json.loads(obj.get(flatter._SELECTED_KEY, "")))
    except Exception:
        return []
    if len(xyz) != 3:
        return []

    payload = flatter._load_payload(obj)
    if payload is None or xyz not in flatter._decode_grid(payload):
        return []
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))

    corners = _cube_corners(*map(int, xyz))
    result = []
    for a, b in _CUBE_EDGES:
        result.append(flatter._mc_to_blender(corners[a], center))
        result.append(flatter._mc_to_blender(corners[b], center))
    return result


def _world_vertices(obj, local_vertices):
    matrix = obj.matrix_world
    return [tuple(matrix @ Vector(vertex)) for vertex in local_vertices]


def _draw_lines(vertices, color, width, xray=False):
    if not vertices:
        return
    shader = gpu.shader.from_builtin("UNIFORM_COLOR")
    batch = batch_for_shader(shader, "LINES", {"pos": vertices})
    gpu.state.blend_set("ALPHA")
    gpu.state.depth_test_set("NONE" if xray else "LESS_EQUAL")
    gpu.state.line_width_set(width)
    shader.bind()
    shader.uniform_float("color", color)
    batch.draw(shader)
    gpu.state.line_width_set(1.0)
    gpu.state.depth_test_set("NONE")
    gpu.state.blend_set("NONE")


def _draw_view():
    obj = _active_flatter()
    if obj is None:
        return
    mode, opacity, xray = _overlay_settings(obj)
    if mode == "OFF":
        return

    if mode == "FULL":
        _draw_lines(
            _world_vertices(obj, _logical_edges(obj)),
            (*_GRID_RGB, min(0.42, opacity * 0.55)),
            0.8,
            xray=xray,
        )

    selected = _selected_edges(obj)
    if selected:
        _draw_lines(
            _world_vertices(obj, selected),
            (*_SELECTED_GREEN_RGB, min(1.0, opacity + 0.60)),
            3.0,
            xray=xray,
        )


def _dimensions(payload):
    dimensions = payload.get("dimensions") if isinstance(payload, dict) else None
    if isinstance(dimensions, dict):
        return (
            int(dimensions.get("width", 0)),
            int(dimensions.get("height", 0)),
            int(dimensions.get("depth", 0)),
        )
    size = payload.get("size") if isinstance(payload, dict) else None
    if isinstance(size, (list, tuple)) and len(size) >= 3:
        return int(size[0]), int(size[1]), int(size[2])
    return 0, 0, 0


def _draw_text():
    obj = _active_flatter()
    if obj is None:
        return
    mode_overlay, _opacity, _xray = _overlay_settings(obj)
    if mode_overlay == "OFF":
        return
    count = int(obj.get("minesport_flatter_block_count", 0))
    mode = str(obj.get("minesport_object_mode") or "LOGICAL")
    payload = flatter._load_payload(obj) or {}
    width, height, depth = _dimensions(payload)
    version = str(
        obj.get("minesport_flatter_version")
        or payload.get("flatterVersion")
        or "legacy"
    )
    label = (
        f"minesport_FLATTER_object  ·  v{version}  ·  "
        f"{width}×{height}×{depth}  ·  {count:,} logical blocks  ·  {mode}"
    )
    font_id = 0
    blf.position(font_id, 18, 54, 0)
    blf.size(font_id, 14)
    blf.color(font_id, 0.35, 1.0, 0.42, 1.0)
    blf.draw(font_id, label)

    focus = str(getattr(getattr(obj, "minesport", None), "flatter_selected", "") or "")
    if focus:
        blf.position(font_id, 18, 35, 0)
        blf.size(font_id, 11)
        blf.color(font_id, 0.78, 0.95, 0.80, 1.0)
        blf.draw(font_id, "Focused · " + focus)


def tag_redraw():
    for window in bpy.context.window_manager.windows:
        screen = window.screen
        if screen is None:
            continue
        for area in screen.areas:
            if area.type == "VIEW_3D":
                area.tag_redraw()


def register():
    global _VIEW_HANDLE, _TEXT_HANDLE
    if _VIEW_HANDLE is None:
        _VIEW_HANDLE = bpy.types.SpaceView3D.draw_handler_add(
            _draw_view, (), "WINDOW", "POST_VIEW"
        )
    if _TEXT_HANDLE is None:
        _TEXT_HANDLE = bpy.types.SpaceView3D.draw_handler_add(
            _draw_text, (), "WINDOW", "POST_PIXEL"
        )


def unregister():
    global _VIEW_HANDLE, _TEXT_HANDLE
    if _VIEW_HANDLE is not None:
        bpy.types.SpaceView3D.draw_handler_remove(_VIEW_HANDLE, "WINDOW")
        _VIEW_HANDLE = None
    if _TEXT_HANDLE is not None:
        bpy.types.SpaceView3D.draw_handler_remove(_TEXT_HANDLE, "WINDOW")
        _TEXT_HANDLE = None
    _CACHE.clear()
