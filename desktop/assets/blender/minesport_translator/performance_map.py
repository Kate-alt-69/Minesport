"""Batched drawing for the floating FLATTER 2D selector."""

import bpy
import gpu
from gpu_extras.batch import batch_for_shader

from . import flatter
from . import flatter_map
from . import liquid_merge


_ORIGINAL_DRAW = None
_ORIGINAL_REDRAW = None
_REGISTERED = False
_SHADER = None


def _shader():
    global _SHADER
    if _SHADER is None:
        _SHADER = gpu.shader.from_builtin("UNIFORM_COLOR")
    return _SHADER


def _draw_quads(rects, color):
    if not rects:
        return
    vertices = []
    for x, y, w, h in rects:
        vertices.extend((
            (x, y), (x + w, y), (x + w, y + h),
            (x, y), (x + w, y + h), (x, y + h),
        ))
    shader = _shader()
    gpu.state.blend_set("ALPHA")
    shader.bind()
    shader.uniform_float("color", color)
    batch_for_shader(shader, "TRIS", {"pos": vertices}).draw(shader)
    gpu.state.blend_set("NONE")


def _draw_grid(layout):
    cell = float(layout["cell"])
    if cell < 2.25:
        # Sub-pixel grid lines become shimmer/noise and cost more than they help.
        return
    min_x, max_x, _min_y, _max_y, min_z, max_z, _payload = layout["bounds"]
    cols = max_x - min_x + 1
    rows = max_z - min_z + 1
    gx = float(layout["grid_x"])
    gy = float(layout["grid_y"])
    w = float(layout["draw_w"])
    h = float(layout["draw_h"])
    vertices = []
    for col in range(cols + 1):
        x = gx + col * cell
        vertices.extend(((x, gy), (x, gy + h)))
    for row in range(rows + 1):
        y = gy + row * cell
        vertices.extend(((gx, y), (gx + w, y)))
    if not vertices:
        return
    shader = _shader()
    gpu.state.blend_set("ALPHA")
    gpu.state.line_width_set(1.0)
    shader.bind()
    shader.uniform_float("color", flatter_map._GRID)
    batch_for_shader(shader, "LINES", {"pos": vertices}).draw(shader)
    gpu.state.blend_set("NONE")


def _slice_grid(session, grid, y):
    grid_id = id(grid)
    if session.get("_perf_grid_id") != grid_id:
        by_y = {}
        for xyz in grid:
            by_y.setdefault(int(xyz[1]), []).append(xyz)
        session["_perf_grid_id"] = grid_id
        session["_perf_grid_by_y"] = by_y
    return session.get("_perf_grid_by_y", {}).get(int(y), ())


def _selected(session, parent):
    raw = str(parent.get(liquid_merge.SELECTION_KEY, "[]"))
    if session.get("_perf_selected_raw") != raw:
        values = liquid_merge._selection(parent)
        by_y = {}
        for xyz in values:
            by_y.setdefault(int(xyz[1]), set()).add(xyz)
        session["_perf_selected_raw"] = raw
        session["_perf_selected"] = values
        session["_perf_selected_by_y"] = by_y
    return (
        session.get("_perf_selected", set()),
        session.get("_perf_selected_by_y", {}),
    )


def _materialized(session, parent):
    stamp = (len(bpy.data.objects), str(parent.get("minesport_flatter_id") or parent.name))
    if session.get("_perf_materialized_stamp") != stamp:
        values = flatter_map._materialized_coords(parent)
        by_y = {}
        for xyz in values:
            by_y.setdefault(int(xyz[1]), set()).add(xyz)
        session["_perf_materialized_stamp"] = stamp
        session["_perf_materialized"] = values
        session["_perf_materialized_by_y"] = by_y
    return session.get("_perf_materialized_by_y", {})


def _fast_draw_map():
    session = flatter_map._SESSION
    if not session:
        return
    context = bpy.context
    area = getattr(context, "area", None)
    region = getattr(context, "region", None)
    if area is None or region is None:
        return
    if area.as_pointer() != session.get("area"):
        return
    if region.type != "WINDOW" or region.as_pointer() != session.get("region"):
        return

    parent = flatter_map._session_parent()
    if parent is None:
        return
    layout = flatter_map._layout(region, parent)
    if layout is None:
        return

    px, py, _, _ = layout["panel_rect"]
    flatter_map._rect(px, py, flatter_map._PANEL_W, flatter_map._PANEL_H, flatter_map._BG, flatter_map._BORDER, 1.0)
    flatter_map._rect(px, py + flatter_map._PANEL_H - flatter_map._HEADER_H, flatter_map._PANEL_W, flatter_map._HEADER_H, flatter_map._HEADER)
    flatter_map._rect(px, py + flatter_map._PANEL_H - flatter_map._HEADER_H - flatter_map._TOOLBAR_H, flatter_map._PANEL_W, flatter_map._TOOLBAR_H, flatter_map._TOOLBAR)
    flatter_map._line(px, py + flatter_map._PANEL_H - 3, px + flatter_map._PANEL_W, py + flatter_map._PANEL_H - 3, flatter_map._ACCENT, 3.0)

    flatter_map._text(px + 14, py + flatter_map._PANEL_H - 25, "MINESPORT · FLATTER 2D", 13)
    flatter_map._text(px + flatter_map._PANEL_W - 22, py + flatter_map._PANEL_H - 25, "×", 15, flatter_map._MUTED)

    live = bool(session.get("live"))
    flatter_map._rect(
        *layout["live_rect"],
        (0.22, 0.16, 0.07, 0.96) if live else (0.08, 0.10, 0.09, 0.96),
        flatter_map._LIVE if live else flatter_map._BORDER,
        1.0,
    )
    flatter_map._text(
        layout["live_rect"][0] + 8,
        layout["live_rect"][1] + 6,
        "LIVE GEOMETRY" if live else "LOGICAL ONLY",
        9,
        flatter_map._LIVE if live else flatter_map._MUTED,
    )
    flatter_map._rect(*layout["yminus_rect"], (0.08, 0.10, 0.09, 0.96), flatter_map._BORDER, 1.0)
    flatter_map._rect(*layout["yplus_rect"], (0.08, 0.10, 0.09, 0.96), flatter_map._BORDER, 1.0)
    flatter_map._text(layout["yminus_rect"][0] + 8, layout["yminus_rect"][1] + 5, "−", 12, flatter_map._TEXT)
    flatter_map._text(layout["yplus_rect"][0] + 8, layout["yplus_rect"][1] + 5, "+", 12, flatter_map._TEXT)
    flatter_map._text(px + 178, py + flatter_map._PANEL_H - flatter_map._HEADER_H - 21, f"Y {session['slice_y']}", 11, flatter_map._TEXT)
    flatter_map._rect(*layout["clear_rect"], (0.08, 0.10, 0.09, 0.96), flatter_map._BORDER, 1.0)
    flatter_map._text(layout["clear_rect"][0] + 16, layout["clear_rect"][1] + 6, "CLEAR", 9, flatter_map._MUTED)

    payload = layout["payload"]
    grid = flatter._decode_grid(payload)
    selected, selected_by_y = _selected(session, parent)
    merged = bool(parent.get(liquid_merge.MERGE_KEY, True))
    actual_by_y = _materialized(session, parent)
    y = int(session["slice_y"])

    # One dark map background replaces thousands of individual empty-cell quads.
    flatter_map._rect(
        layout["grid_x"], layout["grid_y"], layout["draw_w"], layout["draw_h"],
        (0.055, 0.065, 0.06, 0.72), flatter_map._GRID, 1.0,
    )

    occupied_rects = [flatter_map._cell_rect(layout, xyz) for xyz in _slice_grid(session, grid, y)]
    selected_rects = [flatter_map._cell_rect(layout, xyz) for xyz in selected_by_y.get(y, ())]
    actual_rects = [flatter_map._cell_rect(layout, xyz) for xyz in actual_by_y.get(y, ())]
    _draw_quads(occupied_rects, flatter_map._CELL)
    _draw_quads(selected_rects, flatter_map._LIVE if not merged else flatter_map._SELECTED)
    _draw_quads(actual_rects, flatter_map._MATERIALIZED)
    _draw_grid(layout)

    first = session.get("drag_start")
    current = session.get("drag_current")
    if first is not None and current is not None:
        min_dx, max_dx = sorted((first[0], current[0]))
        min_dz, max_dz = sorted((first[2], current[2]))
        a = flatter_map._cell_rect(layout, (min_dx, y, min_dz))
        b = flatter_map._cell_rect(layout, (max_dx, y, max_dz))
        flatter_map._rect(
            a[0], a[1], (b[0] + b[2]) - a[0], (b[1] + b[3]) - a[1],
            flatter_map._PREVIEW, flatter_map._ACCENT, 1.5,
        )

    focus = flatter_map._focused_xyz(parent)
    if focus is not None and focus[1] == y:
        flatter_map._rect(*flatter_map._cell_rect(layout, focus), (0, 0, 0, 0), flatter_map._ACCENT, 2.4)

    count = len(selected)
    state = f"{count:,} selected · {'real proxy geometry' if not merged and count else 'greedy FLATTER'}"
    flatter_map._text(px + 14, py + 24, state, 11, flatter_map._TEXT)
    flatter_map._text(
        px + 14,
        py + 8,
        str(session.get("message") or "Drag cells · Shift add · Ctrl remove · wheel changes Y"),
        9,
        flatter_map._MUTED,
    )


def _fast_tag_redraw():
    area = getattr(bpy.context, "area", None)
    if area is not None and area.type == "VIEW_3D":
        area.tag_redraw()
        return
    if _ORIGINAL_REDRAW is not None:
        _ORIGINAL_REDRAW()


def register():
    global _ORIGINAL_DRAW, _ORIGINAL_REDRAW, _REGISTERED
    if _REGISTERED:
        return
    _ORIGINAL_DRAW = flatter_map._draw_map
    _ORIGINAL_REDRAW = flatter_map._tag_redraw
    flatter_map._draw_map = _fast_draw_map
    flatter_map._tag_redraw = _fast_tag_redraw
    _REGISTERED = True


def unregister():
    global _ORIGINAL_DRAW, _ORIGINAL_REDRAW, _REGISTERED, _SHADER
    if not _REGISTERED:
        return
    if _ORIGINAL_DRAW is not None:
        flatter_map._draw_map = _ORIGINAL_DRAW
    if _ORIGINAL_REDRAW is not None:
        flatter_map._tag_redraw = _ORIGINAL_REDRAW
    _ORIGINAL_DRAW = None
    _ORIGINAL_REDRAW = None
    _SHADER = None
    _REGISTERED = False
