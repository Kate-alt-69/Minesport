"""Live-FPS timing for the Minesport texture-animation core.

The exported schedule contains one entry per Minecraft tick. Blender scene FPS
is presentation timing and may change after import, so the driver sampler must
convert 20 Minecraft ticks/second using the scene's CURRENT FPS rather than a
value baked when the material was translated. Timeline markers are refreshed by
Blender's RNA message bus only when FPS/FPS-base actually changes; there is no
per-frame or depsgraph polling cost.
"""

import json
import math

import bpy

from . import animation_core


_ORIGINAL_REGISTER = None
_MSGBUS_OWNER = object()


def _register_live_fps_drivers(material):
    if material is None or animation_core.SCHEDULE_KEY not in material:
        return None, None

    sequence = animation_core._material_schedule(material)
    frame_count = max(1, int(material.get(animation_core.FRAME_COUNT_KEY, 1)))
    columns = max(1, int(material.get(animation_core.COLUMNS_KEY, 1)))
    rows = max(1, int(material.get(animation_core.ROWS_KEY, frame_count)))
    base = animation_core._driver_base(material.name)
    x_name = base + "_x"
    y_name = base + "_y"
    scene = getattr(bpy.context, "scene", None)
    if scene is None:
        return None, None

    def sprite(frame, speed):
        try:
            fps_base = max(1.0e-6, float(scene.render.fps_base))
            fps = max(1.0e-6, float(scene.render.fps) / fps_base)
            frames_per_tick = fps / 20.0
            scaled = max(0.0, float(frame) - 1.0) * max(0.0, float(speed))
            index = int(math.floor(scaled / frames_per_tick)) % len(sequence)
            return max(0, min(frame_count - 1, int(sequence[index])))
        except Exception:
            return 0

    def sample_x(frame, speed, enabled):
        if not enabled:
            return 0.0
        try:
            value = sprite(frame, speed)
            return (value % columns) / float(columns)
        except Exception:
            return 0.0

    def sample_y(frame, speed, enabled):
        if not enabled:
            return 0.0
        try:
            value = sprite(frame, speed)
            row = value // columns
            return -(row / float(rows))
        except Exception:
            return 0.0

    bpy.app.driver_namespace[x_name] = sample_x
    bpy.app.driver_namespace[y_name] = sample_y
    return x_name, y_name


def _refresh_texture_markers_for_fps_change():
    for scene in bpy.data.scenes:
        raw = scene.get("minesport_texture_timeline_json", "")
        if not raw:
            continue
        try:
            descriptors = json.loads(str(raw))
        except Exception:
            continue
        if not isinstance(descriptors, list):
            continue
        try:
            animation_core.install_texture_markers({"animations": descriptors}, scene)
        except Exception as exc:
            print(f"[Minesport Animation] FPS marker refresh failed for {scene.name}: {exc}")


def _subscribe_fps_changes():
    try:
        bpy.msgbus.clear_by_owner(_MSGBUS_OWNER)
        for prop in ("fps", "fps_base"):
            bpy.msgbus.subscribe_rna(
                key=(bpy.types.RenderSettings, prop),
                owner=_MSGBUS_OWNER,
                args=(),
                notify=_refresh_texture_markers_for_fps_change,
                options={"PERSISTENT"},
            )
    except Exception as exc:
        # Animation remains live-FPS even if a particular Blender build rejects
        # an RNA message-bus key; only automatic marker relayout is lost.
        print(f"[Minesport Animation] FPS marker subscription unavailable: {exc}")


def register():
    global _ORIGINAL_REGISTER
    if _ORIGINAL_REGISTER is None:
        _ORIGINAL_REGISTER = animation_core._register_material_drivers
        animation_core._register_material_drivers = _register_live_fps_drivers

    # Existing .blend materials may already have driver expressions. Replacing
    # their namespace functions is enough; no node rebuild or per-frame handler
    # is required.
    for material in bpy.data.materials:
        if material is not None and animation_core.SCHEDULE_KEY in material:
            _register_live_fps_drivers(material)
    _subscribe_fps_changes()


def unregister():
    global _ORIGINAL_REGISTER
    try:
        bpy.msgbus.clear_by_owner(_MSGBUS_OWNER)
    except Exception:
        pass
    if _ORIGINAL_REGISTER is not None:
        animation_core._register_material_drivers = _ORIGINAL_REGISTER
        _ORIGINAL_REGISTER = None
