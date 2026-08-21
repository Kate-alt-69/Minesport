"""GPU-only logical block overview for selected FLATTER cells.

The overlay never adds Blender mesh geometry. It reconstructs exposed logical
block boundaries from the embedded palette/RLE grid and draws them as green
viewport lines, so a heavily greedy FLATTER mesh still feels block-addressable.
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

_GREEN = (0.18, 1.0, 0.24, 0.72)
_SELECTED_GREEN = (0.55, 1.0, 0.60, 1.0)

_FACE_CORNERS = {
    "north": ((0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)),
    "south": ((1, 0, 1), (0, 0, 1), (0, 1, 1), (1, 1, 1)),
    "east": ((1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)),
    "west": ((0, 0, 1), (0, 0, 0), (0, 1, 0), (0, 1, 1)),
    "up": ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)),
    "down": ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)),
}


def _active_flatter():
    obj = bpy.context.view_layer.objects.active
    if obj is None or obj.type != "MESH":
        return None
    if obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    if not obj.select_get():
        return None
    return obj


def _payload_stamp(obj):
    raw = obj.get(flatter._DATA_KEY, "")
    return hash(raw) if isinstance(raw, str) else 0


def _edge_key(a, b):
    a = tuple(a)
    b = tuple(b)
    return (a, b) if a <= b else (b, a)


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

    for xyz in grid:
        x, y, z = xyz
        for direction in flatter._DIRECTIONS:
            dx, dy, dz = flatter._DELTA[direction]
            if (x + dx, y + dy, z + dz) in grid:
                continue
            corners = [
                (x + c[0], y + c[1], z + c[2])
                for c in _FACE_CORNERS[direction]
            ]
            for index in range(4):
                edges.add(_edge_key(corners[index], corners[(index + 1) % 4]))

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

    x, y, z = map(int, xyz)
    corners = [
        (x, y, z),
        (x + 1, y, z),
        (x + 1, y + 1, z),
        (x, y + 1, z),
        (x, y, z + 1),
        (x + 1, y, z + 1),
        (x + 1, y + 1, z + 1),
        (x, y + 1, z + 1),
    ]
    pairs = (
        (0, 1), (1, 2), (2, 3), (3, 0),
        (4, 5), (5, 6), (6, 7), (7, 4),
        (0, 4), (1, 5), (2, 6), (3, 7),
    )
    result = []
    for a, b in pairs:
        result.append(flatter._mc_to_blender(corners[a], center))
        result.append(flatter._mc_to_blender(corners[b], center))
    return result


def _world_vertices(obj, local_vertices):
    matrix = obj.matrix_world
    return [tuple(matrix @ Vector(vertex)) for vertex in local_vertices]


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


def _draw_view():
    obj = _active_flatter()
    if obj is None:
        return
    _draw_lines(_world_vertices(obj, _logical_edges(obj)), _GREEN, 1.25)
    selected = _selected_edges(obj)
    if selected:
        _draw_lines(_world_vertices(obj, selected), _SELECTED_GREEN, 2.5)


def _draw_text():
    obj = _active_flatter()
    if obj is None:
        return
    count = int(obj.get("minesport_flatter_block_count", 0))
    mode = str(obj.get("minesport_object_mode") or "LOGICAL")
    label = f"minesport_FLATTER_object  ·  {count:,} logical blocks  ·  {mode}"
    font_id = 0
    blf.position(font_id, 18, 54, 0)
    blf.size(font_id, 14)
    blf.color(font_id, 0.35, 1.0, 0.42, 1.0)
    blf.draw(font_id, label)


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
