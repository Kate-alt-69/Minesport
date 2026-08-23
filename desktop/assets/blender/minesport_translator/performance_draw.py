"""Draw-handler handoff for the Minesport performance layer.

Blender stores the callback object passed to draw_handler_add, so replacing a
module function after Liquid Merge registered does not replace that callback.
Keep the lifecycle isolated here so the optimized overlay is genuinely the one
running in the viewport and teardown can still restore the normal handler.
"""

import bpy

from . import liquid_merge
from . import performance


_REGISTERED = False


def register():
    global _REGISTERED
    if _REGISTERED:
        return

    if liquid_merge._DRAW_HANDLE is not None:
        try:
            bpy.types.SpaceView3D.draw_handler_remove(liquid_merge._DRAW_HANDLE, "WINDOW")
        except Exception:
            pass
        liquid_merge._DRAW_HANDLE = None

    liquid_merge._DRAW_HANDLE = bpy.types.SpaceView3D.draw_handler_add(
        performance._fast_liquid_draw_overlay,
        (),
        "WINDOW",
        "POST_VIEW",
    )
    _REGISTERED = True


def unregister():
    global _REGISTERED
    if not _REGISTERED:
        return

    if liquid_merge._DRAW_HANDLE is not None:
        try:
            bpy.types.SpaceView3D.draw_handler_remove(liquid_merge._DRAW_HANDLE, "WINDOW")
        except Exception:
            pass
        liquid_merge._DRAW_HANDLE = None

    original = performance._ORIGINALS.get("liquid_draw")
    if original is not None:
        liquid_merge._DRAW_HANDLE = bpy.types.SpaceView3D.draw_handler_add(
            original,
            (),
            "WINDOW",
            "POST_VIEW",
        )
    _REGISTERED = False
