"""Live-FPS timing for the Minesport texture-animation core.

The exported schedule contains one entry per Minecraft tick. Blender scene FPS
is presentation timing and may change after import, so the driver sampler must
convert 20 Minecraft ticks/second using the scene's CURRENT FPS rather than a
value baked when the material was translated.
"""

import math

import bpy

from . import animation_core


_ORIGINAL_REGISTER = None


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
    scene = bpy.context.scene

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


def unregister():
    global _ORIGINAL_REGISTER
    if _ORIGINAL_REGISTER is not None:
        animation_core._register_material_drivers = _ORIGINAL_REGISTER
        _ORIGINAL_REGISTER = None
