"""Floating 2D FLATTER selector drawn directly inside Blender's 3D viewport.

The selector is intentionally an overlay instead of a second editor/window:
the user can drag logical Minecraft cells in the map while watching the real
3D viewport behind it update through Liquid Merge.
"""

import json
import time

import blf
import bpy
import gpu
from gpu_extras.batch import batch_for_shader

from . import flatter
from . import liquid_merge


_DRAW_HANDLE = None
_SESSION = None

_PANEL_W = 390
_PANEL_H = 390
_HEADER_H = 38
_TOOLBAR_H = 32
_FOOTER_H = 42
_MARGIN = 12

_BG = (0.035, 0.045, 0.048, 0.96)
_HEADER = (0.075, 0.09, 0.085, 0.98)
_TOOLBAR = (0.055, 0.068, 0.064, 0.98)
_BORDER = (0.20, 0.26, 0.22, 1.0)
_ACCENT = (0.30, 0.95, 0.36, 1.0)
_GRID = (0.15, 0.19, 0.17, 0.95)
_CELL = (0.19, 0.24, 0.21, 0.96)
_SELECTED = (0.27, 0.92, 0.35, 0.86)
_LIVE = (0.95, 0.57, 0.18, 0.92)
_MATERIALIZED = (0.55, 0.35, 0.95, 0.92)
_PREVIEW = (0.45, 1.0, 0.50, 0.30)
_TEXT = (0.86, 0.90, 0.87, 1.0)
_MUTED = (0.55, 0.61, 0.57, 1.0)


def _active_flatter(context=None):
    context = context or bpy.context
    obj = getattr(context, "object", None)
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return None
    return obj


def _rect(x, y, w, h, color, outline=None, outline_width=1.0):
    shader = gpu.shader.from_builtin("UNIFORM_COLOR")
    verts = ((x, y), (x + w, y), (x + w, y + h), (x, y + h))
    gpu.state.blend_set("ALPHA")
    shader.bind()
    shader.uniform_float("color", color)
    batch_for_shader(shader, "TRI_FAN", {"pos": verts}).draw(shader)
    if outline is not None:
        gpu.state.line_width_set(outline_width)
        shader.uniform_float("color", outline)
        batch_for_shader(shader, "LINE_LOOP", {"pos": verts}).draw(shader)
        gpu.state.line_width_set(1.0)
    gpu.state.blend_set("NONE")


def _line(x1, y1, x2, y2, color, width=1.0):
    shader = gpu.shader.from_builtin("UNIFORM_COLOR")
    gpu.state.blend_set("ALPHA")
    gpu.state.line_width_set(width)
    shader.bind()
    shader.uniform_float("color", color)
    batch_for_shader(
        shader,
        "LINES",
        {"pos": ((x1, y1), (x2, y2))},
    ).draw(shader)
    gpu.state.line_width_set(1.0)
    gpu.state.blend_set("NONE")


def _text(x, y, value, size=12, color=_TEXT):
    font_id = 0
    blf.position(font_id, x, y, 0)
    blf.size(font_id, size)
    blf.color(font_id, *color)
    blf.draw(font_id, str(value))


def _session_parent():
    global _SESSION
    if not _SESSION:
        return None
    return bpy.data.objects.get(_SESSION.get("parent", ""))


def _payload_bounds(parent):
    payload = flatter._load_payload(parent)
    if payload is None:
        return None
    origin = flatter._vec3i(payload.get("origin"), (0, 0, 0))
    size = flatter._vec3i(payload.get("size"), (0, 0, 0))
    sx, sy, sz = size
    if sx <= 0 or sy <= 0 or sz <= 0:
        grid = flatter._decode_grid(payload)
        if not grid:
            return None
        xs = [xyz[0] for xyz in grid]
        ys = [xyz[1] for xyz in grid]
        zs = [xyz[2] for xyz in grid]
        return (
            min(xs), max(xs),
            min(ys), max(ys),
            min(zs), max(zs),
            payload,
        )
    return (
        origin[0], origin[0] + sx - 1,
        origin[1], origin[1] + sy - 1,
        origin[2], origin[2] + sz - 1,
        payload,
    )


def _focused_xyz(parent):
    try:
        value = json.loads(parent.get(flatter._SELECTED_KEY, ""))
        if isinstance(value, list) and len(value) >= 3:
            return int(value[0]), int(value[1]), int(value[2])
    except Exception:
        pass
    return None


def _materialized_coords(parent):
    identifier = str(parent.get("minesport_flatter_id") or parent.name)
    result = set()
    for obj in bpy.context.scene.objects:
        if obj.get("minesport_type") != flatter._TYPE_MATERIALIZED:
            continue
        if str(obj.get("minesport_flatter_parent") or "") != identifier:
            continue
        try:
            value = json.loads(obj.get("minesport_flatter_xyz", ""))
            if isinstance(value, list) and len(value) >= 3:
                result.add((int(value[0]), int(value[1]), int(value[2])))
        except Exception:
            pass
    return result


def _layout(region, parent):
    bounds = _payload_bounds(parent)
    if bounds is None:
        return None

    min_x, max_x, min_y, max_y, min_z, max_z, payload = bounds
    panel_x = float(_SESSION["x"])
    panel_y = float(_SESSION["y"])

    map_x = panel_x + _MARGIN
    map_y = panel_y + _FOOTER_H + _MARGIN
    map_w = _PANEL_W - _MARGIN * 2
    map_h = _PANEL_H - _HEADER_H - _TOOLBAR_H - _FOOTER_H - _MARGIN * 2

    cols = max(1, max_x - min_x + 1)
    rows = max(1, max_z - min_z + 1)
    cell = min(map_w / cols, map_h / rows)
    draw_w = cell * cols
    draw_h = cell * rows
    grid_x = map_x + (map_w - draw_w) * 0.5
    grid_y = map_y + (map_h - draw_h) * 0.5

    return {
        "bounds": bounds,
        "payload": payload,
        "grid_x": grid_x,
        "grid_y": grid_y,
        "cell": cell,
        "draw_w": draw_w,
        "draw_h": draw_h,
        "map_rect": (map_x, map_y, map_w, map_h),
        "header_rect": (
            panel_x,
            panel_y + _PANEL_H - _HEADER_H,
            _PANEL_W,
            _HEADER_H,
        ),
        "toolbar_rect": (
            panel_x,
            panel_y + _PANEL_H - _HEADER_H - _TOOLBAR_H,
            _PANEL_W,
            _TOOLBAR_H,
        ),
        "live_rect": (
            panel_x + 10,
            panel_y + _PANEL_H - _HEADER_H - _TOOLBAR_H + 5,
            108,
            22,
        ),
        "yminus_rect": (
            panel_x + 142,
            panel_y + _PANEL_H - _HEADER_H - _TOOLBAR_H + 5,
            24,
            22,
        ),
        "yplus_rect": (
            panel_x + 228,
            panel_y + _PANEL_H - _HEADER_H - _TOOLBAR_H + 5,
            24,
            22,
        ),
        "clear_rect": (
            panel_x + _PANEL_W - 82,
            panel_y + _PANEL_H - _HEADER_H - _TOOLBAR_H + 5,
            70,
            22,
        ),
        "panel_rect": (panel_x, panel_y, _PANEL_W, _PANEL_H),
    }


def _inside(px, py, rect):
    x, y, w, h = rect
    return x <= px <= x + w and y <= py <= y + h


def _cell_from_mouse(layout, mx, my):
    gx = layout["grid_x"]
    gy = layout["grid_y"]
    cell = layout["cell"]
    if cell <= 0:
        return None
    if not _inside(mx, my, (gx, gy, layout["draw_w"], layout["draw_h"])):
        return None
    min_x, max_x, _min_y, _max_y, min_z, max_z, _payload = layout["bounds"]
    col = int((mx - gx) // cell)
    row = int((my - gy) // cell)
    x = min_x + col
    z = min_z + row
    if not (min_x <= x <= max_x and min_z <= z <= max_z):
        return None
    return x, int(_SESSION["slice_y"]), z


def _cell_rect(layout, xyz):
    min_x, _max_x, _min_y, _max_y, min_z, _max_z, _payload = layout["bounds"]
    x, _y, z = xyz
    cell = layout["cell"]
    return (
        layout["grid_x"] + (x - min_x) * cell,
        layout["grid_y"] + (z - min_z) * cell,
        cell,
        cell,
    )


def _selection_for_box(parent, first, second):
    payload = flatter._load_payload(parent)
    grid = flatter._decode_grid(payload) if payload else {}
    min_x, max_x = sorted((first[0], second[0]))
    min_z, max_z = sorted((first[2], second[2]))
    y = int(_SESSION["slice_y"])
    return {
        xyz for xyz in grid
        if xyz[1] == y
        and min_x <= xyz[0] <= max_x
        and min_z <= xyz[2] <= max_z
    }


def _focus(parent, xyz):
    if xyz is None:
        return
    parent[flatter._SELECTED_KEY] = json.dumps(
        list(map(int, xyz)),
        separators=(",", ":"),
    )
    payload = flatter._load_payload(parent)
    grid = flatter._decode_grid(payload) if payload else {}
    palette_index = grid.get(tuple(xyz))
    entry = (
        flatter._palette(payload, palette_index)
        if palette_index is not None
        else None
    )
    block_id = (
        str(entry.get("id") or "minecraft:unknown")
        if isinstance(entry, dict)
        else "minecraft:unknown"
    )
    parent["minesport_logical_selection_label"] = (
        f"Minecraft block: {block_id} @ {xyz[0]}, {xyz[1]}, {xyz[2]}"
    )
    if hasattr(parent, "minesport"):
        parent.minesport.flatter_selected = (
            f"{block_id} @ {xyz[0]}, {xyz[1]}, {xyz[2]}"
        )


def _materialization_limit(parent):
    props = getattr(parent, "minesport", None)
    if props is None:
        return 1024
    return max(1, int(getattr(props, "flatter_materialization_limit", 1024)))


def _compose_selection(parent, box):
    base = set(_SESSION.get("drag_base", set()))
    mode = _SESSION.get("drag_mode", "REPLACE")
    if mode == "ADD":
        return base | box
    if mode == "REMOVE":
        return base - box
    return set(box)


def _apply_drag(parent, force=False):
    first = _SESSION.get("drag_start")
    current = _SESSION.get("drag_current")
    if first is None or current is None:
        return
    box = _selection_for_box(parent, first, current)
    desired = _compose_selection(parent, box)
    now = time.monotonic()
    signature = (frozenset(desired), bool(_SESSION.get("live")))
    if not force:
        if signature == _SESSION.get("last_signature"):
            return
        if now - float(_SESSION.get("last_apply", 0.0)) < 0.075:
            return

    _SESSION["last_apply"] = now
    _SESSION["last_signature"] = signature
    liquid_merge._store_selection(parent, desired, apply=False)
    _focus(parent, current)

    live = bool(_SESSION.get("live"))
    if live and len(desired) > _materialization_limit(parent):
        _SESSION["message"] = (
            f"Live geometry paused: {len(desired):,} selected "
            f"> {_materialization_limit(parent):,} safety limit"
        )
        liquid_merge._set_merge_state(parent, True, apply=True)
    elif live:
        _SESSION["message"] = f"{len(desired):,} block(s) live in Blender"
        liquid_merge._set_merge_state(parent, False, apply=True)
    else:
        _SESSION["message"] = f"{len(desired):,} logical block(s) selected"
        liquid_merge._set_merge_state(parent, True, apply=True)

    try:
        bpy.context.workspace.status_text_set(_SESSION["message"])
    except Exception:
        pass


def _clear(parent):
    liquid_merge._store_selection(parent, set(), apply=False)
    liquid_merge._set_merge_state(parent, True, apply=True)
    _SESSION["message"] = "Selection cleared"
    _SESSION["drag_start"] = None
    _SESSION["drag_current"] = None
    _SESSION["drag_base"] = set()
    _SESSION["last_signature"] = None


def _toggle_live(parent):
    _SESSION["live"] = not bool(_SESSION.get("live"))
    selected = liquid_merge._selection(parent)
    if _SESSION["live"] and len(selected) <= _materialization_limit(parent):
        liquid_merge._set_merge_state(parent, False, apply=True)
        _SESSION["message"] = "Live geometry ON"
    else:
        liquid_merge._set_merge_state(parent, True, apply=True)
        _SESSION["message"] = "Live geometry OFF"


def _draw_map():
    global _SESSION
    if not _SESSION:
        return
    context = bpy.context
    area = getattr(context, "area", None)
    region = getattr(context, "region", None)
    if area is None or region is None:
        return
    if area.as_pointer() != _SESSION.get("area"):
        return

    parent = _session_parent()
    if parent is None:
        return
    layout = _layout(region, parent)
    if layout is None:
        return

    px, py, _, _ = layout["panel_rect"]
    _rect(px, py, _PANEL_W, _PANEL_H, _BG, _BORDER, 1.0)
    _rect(
        px,
        py + _PANEL_H - _HEADER_H,
        _PANEL_W,
        _HEADER_H,
        _HEADER,
    )
    _rect(
        px,
        py + _PANEL_H - _HEADER_H - _TOOLBAR_H,
        _PANEL_W,
        _TOOLBAR_H,
        _TOOLBAR,
    )
    _line(
        px,
        py + _PANEL_H - 3,
        px + _PANEL_W,
        py + _PANEL_H - 3,
        _ACCENT,
        3.0,
    )

    _text(px + 14, py + _PANEL_H - 25, "MINESPORT · FLATTER 2D", 13)
    _text(
        px + _PANEL_W - 22,
        py + _PANEL_H - 25,
        "×",
        15,
        _MUTED,
    )

    live = bool(_SESSION.get("live"))
    _rect(
        *layout["live_rect"],
        (0.22, 0.16, 0.07, 0.96) if live else (0.08, 0.10, 0.09, 0.96),
        _LIVE if live else _BORDER,
        1.0,
    )
    _text(
        layout["live_rect"][0] + 8,
        layout["live_rect"][1] + 6,
        "LIVE GEOMETRY" if live else "LOGICAL ONLY",
        9,
        _LIVE if live else _MUTED,
    )
    _rect(*layout["yminus_rect"], (0.08, 0.10, 0.09, 0.96), _BORDER, 1.0)
    _rect(*layout["yplus_rect"], (0.08, 0.10, 0.09, 0.96), _BORDER, 1.0)
    _text(layout["yminus_rect"][0] + 8, layout["yminus_rect"][1] + 5, "−", 12, _TEXT)
    _text(layout["yplus_rect"][0] + 8, layout["yplus_rect"][1] + 5, "+", 12, _TEXT)
    _text(
        px + 178,
        py + _PANEL_H - _HEADER_H - 21,
        f"Y {_SESSION['slice_y']}",
        11,
        _TEXT,
    )
    _rect(*layout["clear_rect"], (0.08, 0.10, 0.09, 0.96), _BORDER, 1.0)
    _text(layout["clear_rect"][0] + 16, layout["clear_rect"][1] + 6, "CLEAR", 9, _MUTED)

    payload = layout["payload"]
    grid = flatter._decode_grid(payload)
    selected = liquid_merge._selection(parent)
    merged = bool(parent.get(liquid_merge.MERGE_KEY, True))
    actual = _materialized_coords(parent)
    y = int(_SESSION["slice_y"])

    min_x, max_x, _min_y, _max_y, min_z, max_z, _ = layout["bounds"]
    for z in range(min_z, max_z + 1):
        for x in range(min_x, max_x + 1):
            xyz = (x, y, z)
            rect = _cell_rect(layout, xyz)
            fill = (0.055, 0.065, 0.06, 0.72)
            if xyz in grid:
                fill = _CELL
            if xyz in selected:
                fill = _LIVE if not merged else _SELECTED
            if xyz in actual:
                fill = _MATERIALIZED
            _rect(*rect, fill, _GRID, 0.7)

    first = _SESSION.get("drag_start")
    current = _SESSION.get("drag_current")
    if first is not None and current is not None:
        min_dx, max_dx = sorted((first[0], current[0]))
        min_dz, max_dz = sorted((first[2], current[2]))
        a = _cell_rect(layout, (min_dx, y, min_dz))
        b = _cell_rect(layout, (max_dx, y, max_dz))
        x = a[0]
        yy = a[1]
        w = (b[0] + b[2]) - a[0]
        h = (b[1] + b[3]) - a[1]
        _rect(x, yy, w, h, _PREVIEW, _ACCENT, 1.5)

    focus = _focused_xyz(parent)
    if focus is not None and focus[1] == y:
        r = _cell_rect(layout, focus)
        _rect(*r, (0, 0, 0, 0), _ACCENT, 2.4)

    count = len(selected)
    state = (
        f"{count:,} selected · "
        f"{'real proxy geometry' if not merged and count else 'greedy FLATTER'}"
    )
    _text(px + 14, py + 24, state, 11, _TEXT)
    _text(
        px + 14,
        py + 8,
        str(_SESSION.get("message") or "Drag cells · Shift add · Ctrl remove · wheel changes Y"),
        9,
        _MUTED,
    )


def _tag_redraw():
    for window in bpy.context.window_manager.windows:
        screen = window.screen
        if screen is None:
            continue
        for area in screen.areas:
            if area.type == "VIEW_3D":
                area.tag_redraw()


class MINESPORT_OT_flatter_map(bpy.types.Operator):
    bl_idname = "minesport.flatter_map"
    bl_label = "Open 2D FLATTER Selector"
    bl_description = (
        "Open a floating top-down FLATTER selector; drag cells while watching "
        "the main 3D viewport update through Liquid Merge"
    )

    def invoke(self, context, event):
        global _SESSION, _DRAW_HANDLE
        parent = _active_flatter(context)
        if parent is None:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        if context.area is None or context.area.type != "VIEW_3D":
            self.report({"ERROR"}, "Open the FLATTER selector from a 3D View")
            return {"CANCELLED"}

        bounds = _payload_bounds(parent)
        if bounds is None:
            self.report({"ERROR"}, "FLATTER logical data is unavailable")
            return {"CANCELLED"}

        focus = _focused_xyz(parent)
        default_y = focus[1] if focus is not None else bounds[3]
        x = max(18, context.region.width - _PANEL_W - 22)
        y = max(52, context.region.height - _PANEL_H - 72)
        _SESSION = {
            "parent": parent.name,
            "area": context.area.as_pointer(),
            "window": context.window.as_pointer(),
            "x": x,
            "y": y,
            "slice_y": int(default_y),
            "live": not bool(parent.get(liquid_merge.MERGE_KEY, True)),
            "drag_start": None,
            "drag_current": None,
            "drag_base": set(),
            "drag_mode": "REPLACE",
            "drag_panel": False,
            "panel_offset": (0, 0),
            "last_apply": 0.0,
            "last_signature": None,
            "message": "Drag cells · Shift add · Ctrl remove · wheel changes Y",
        }
        if _DRAW_HANDLE is None:
            _DRAW_HANDLE = bpy.types.SpaceView3D.draw_handler_add(
                _draw_map,
                (),
                "WINDOW",
                "POST_PIXEL",
            )
        context.window_manager.modal_handler_add(self)
        _tag_redraw()
        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        global _SESSION
        if not _SESSION:
            return {"CANCELLED"}
        if context.window.as_pointer() != _SESSION.get("window"):
            return {"PASS_THROUGH"}

        parent = _session_parent()
        if parent is None:
            self._finish(context)
            return {"CANCELLED"}

        if event.type == "ESC" and event.value == "PRESS":
            self._finish(context)
            return {"FINISHED"}

        layout = _layout(context.region, parent)
        if layout is None:
            self._finish(context)
            return {"CANCELLED"}

        mx = float(event.mouse_region_x)
        my = float(event.mouse_region_y)
        inside_panel = _inside(mx, my, layout["panel_rect"])

        if event.type in {"WHEELUPMOUSE", "WHEELDOWNMOUSE"} and inside_panel:
            min_y, max_y = layout["bounds"][2], layout["bounds"][3]
            step = 1 if event.type == "WHEELUPMOUSE" else -1
            _SESSION["slice_y"] = max(
                min_y,
                min(max_y, int(_SESSION["slice_y"]) + step),
            )
            _SESSION["message"] = f"Y slice {_SESSION['slice_y']}"
            _tag_redraw()
            return {"RUNNING_MODAL"}

        if event.type == "L" and event.value == "PRESS" and inside_panel:
            _toggle_live(parent)
            _tag_redraw()
            return {"RUNNING_MODAL"}

        if event.type == "C" and event.value == "PRESS" and inside_panel:
            _clear(parent)
            _tag_redraw()
            return {"RUNNING_MODAL"}

        if event.type == "LEFTMOUSE" and event.value == "PRESS" and inside_panel:
            close_rect = (
                layout["panel_rect"][0] + _PANEL_W - 34,
                layout["panel_rect"][1] + _PANEL_H - _HEADER_H,
                34,
                _HEADER_H,
            )
            if _inside(mx, my, close_rect):
                self._finish(context)
                return {"FINISHED"}

            if _inside(mx, my, layout["live_rect"]):
                _toggle_live(parent)
                _tag_redraw()
                return {"RUNNING_MODAL"}

            if _inside(mx, my, layout["clear_rect"]):
                _clear(parent)
                _tag_redraw()
                return {"RUNNING_MODAL"}

            if _inside(mx, my, layout["yminus_rect"]) or _inside(mx, my, layout["yplus_rect"]):
                min_y, max_y = layout["bounds"][2], layout["bounds"][3]
                step = 1 if _inside(mx, my, layout["yplus_rect"]) else -1
                _SESSION["slice_y"] = max(
                    min_y,
                    min(max_y, int(_SESSION["slice_y"]) + step),
                )
                _SESSION["message"] = f"Y slice {_SESSION['slice_y']}"
                _tag_redraw()
                return {"RUNNING_MODAL"}

            if _inside(mx, my, layout["header_rect"]):
                _SESSION["drag_panel"] = True
                _SESSION["panel_offset"] = (
                    mx - float(_SESSION["x"]),
                    my - float(_SESSION["y"]),
                )
                return {"RUNNING_MODAL"}

            cell = _cell_from_mouse(layout, mx, my)
            if cell is not None:
                _SESSION["drag_start"] = cell
                _SESSION["drag_current"] = cell
                _SESSION["drag_base"] = set(liquid_merge._selection(parent))
                _SESSION["drag_mode"] = (
                    "REMOVE" if event.ctrl
                    else "ADD" if event.shift
                    else "REPLACE"
                )
                _focus(parent, cell)
                _apply_drag(parent, force=True)
                _tag_redraw()
                return {"RUNNING_MODAL"}

        if event.type == "MOUSEMOVE":
            if _SESSION.get("drag_panel"):
                ox, oy = _SESSION.get("panel_offset", (0, 0))
                _SESSION["x"] = max(
                    0,
                    min(context.region.width - _PANEL_W, mx - ox),
                )
                _SESSION["y"] = max(
                    0,
                    min(context.region.height - _PANEL_H, my - oy),
                )
                _tag_redraw()
                return {"RUNNING_MODAL"}

            if _SESSION.get("drag_start") is not None:
                cell = _cell_from_mouse(layout, mx, my)
                if cell is not None and cell != _SESSION.get("drag_current"):
                    _SESSION["drag_current"] = cell
                    _focus(parent, cell)
                    _apply_drag(parent, force=False)
                    _tag_redraw()
                return {"RUNNING_MODAL"}

        if event.type == "LEFTMOUSE" and event.value == "RELEASE":
            if _SESSION.get("drag_panel"):
                _SESSION["drag_panel"] = False
                return {"RUNNING_MODAL"}
            if _SESSION.get("drag_start") is not None:
                _apply_drag(parent, force=True)
                _SESSION["drag_start"] = None
                _SESSION["drag_current"] = None
                _SESSION["drag_base"] = set(liquid_merge._selection(parent))
                _tag_redraw()
                return {"RUNNING_MODAL"}

        return {"RUNNING_MODAL" if inside_panel else "PASS_THROUGH"}

    def _finish(self, context):
        global _SESSION, _DRAW_HANDLE
        try:
            context.workspace.status_text_set(None)
        except Exception:
            pass
        _SESSION = None
        if _DRAW_HANDLE is not None:
            try:
                bpy.types.SpaceView3D.draw_handler_remove(_DRAW_HANDLE, "WINDOW")
            except Exception:
                pass
            _DRAW_HANDLE = None
        _tag_redraw()


class MINESPORT_PT_flatter_map(bpy.types.Panel):
    bl_label = "FLATTER 2D Selector"
    bl_idname = "MINESPORT_PT_flatter_map"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"
    bl_parent_id = "MINESPORT_PT_flatter_view"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        layout = self.layout
        layout.operator(
            MINESPORT_OT_flatter_map.bl_idname,
            icon="UV",
        )
        layout.label(
            text="Drag a top-down slice while the 3D scene stays visible.",
            icon="INFO",
        )
        layout.label(
            text="Wheel = Y slice · L = live geometry · C = clear",
            icon="MOUSE_LMB",
        )


class MINESPORT_PT_flatter_map_properties(bpy.types.Panel):
    bl_label = "FLATTER 2D Selector"
    bl_idname = "MINESPORT_PT_flatter_map_properties"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "object"
    bl_parent_id = "MINESPORT_PT_properties"

    @classmethod
    def poll(cls, context):
        return _active_flatter(context) is not None

    def draw(self, context):
        self.layout.operator(
            MINESPORT_OT_flatter_map.bl_idname,
            text="Open Floating 2D Selector",
            icon="UV",
        )


_CLASSES = (
    MINESPORT_OT_flatter_map,
    MINESPORT_PT_flatter_map,
    MINESPORT_PT_flatter_map_properties,
)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    global _SESSION, _DRAW_HANDLE
    _SESSION = None
    if _DRAW_HANDLE is not None:
        try:
            bpy.types.SpaceView3D.draw_handler_remove(_DRAW_HANDLE, "WINDOW")
        except Exception:
            pass
        _DRAW_HANDLE = None
    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass
