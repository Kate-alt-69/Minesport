"""Live-first behavior for the floating FLATTER 2D selector.

The base selector already supports logical-only and live proxy modes. Minesport's
production default is live: dragging cells should immediately show the selected
logical blocks as real proxy geometry in the main 3D viewport, unless the safety
limit would make that dangerous.
"""

from . import flatter_map
from . import liquid_merge


_ORIGINAL_INVOKE = None


def _live_invoke(self, context, event):
    result = _ORIGINAL_INVOKE(self, context, event)
    if "RUNNING_MODAL" not in result:
        return result

    session = flatter_map._SESSION
    if not isinstance(session, dict):
        return result
    parent = flatter_map._session_parent()
    if parent is None:
        return result

    selected = liquid_merge._selection(parent)
    limit = flatter_map._materialization_limit(parent)
    if len(selected) > limit:
        session["live"] = False
        session["message"] = (
            f"LOGICAL ONLY · {len(selected):,} selected exceeds {limit:,} live safety limit"
        )
        liquid_merge._set_merge_state(parent, True, apply=True)
    else:
        session["live"] = True
        session["message"] = "LIVE GEOMETRY · drag cells and watch the 3D viewport update"
        # If there is already a selection, reflect it in the viewport immediately.
        # With no selection this is effectively free; the first drag will create
        # proxies through the selector's normal throttled update path.
        if selected:
            liquid_merge._set_merge_state(parent, False, apply=True)

    flatter_map._tag_redraw()
    return result


def register():
    global _ORIGINAL_INVOKE
    if _ORIGINAL_INVOKE is not None:
        return
    _ORIGINAL_INVOKE = flatter_map.MINESPORT_OT_flatter_map.invoke
    flatter_map.MINESPORT_OT_flatter_map.invoke = _live_invoke


def unregister():
    global _ORIGINAL_INVOKE
    if _ORIGINAL_INVOKE is not None:
        flatter_map.MINESPORT_OT_flatter_map.invoke = _ORIGINAL_INVOKE
        _ORIGINAL_INVOKE = None
