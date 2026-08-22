"""Logical LIGHT_BLOCK -> Blender light adapter.

Minesport exporter writes LIGHT_BLOCK as Minecraft truth: block coordinate,
0..15 level, local emitter offset and color. The existing lights subsystem then
builds smooth Blender POINT lights from that truth. The legacy `lights` array is
used only when LIGHT_BLOCK is absent.
"""

import json

from . import lights


_ORIGINAL_TRANSLATE_LIGHTS = None


def _safe_int(value, fallback=0):
    try:
        return int(value)
    except Exception:
        return fallback


def _logical_descriptors(metadata):
    if not isinstance(metadata, dict):
        return None
    records = metadata.get("LIGHT_BLOCK")
    if not isinstance(records, list):
        return None

    descriptors = []
    for record in records:
        if not isinstance(record, dict):
            continue
        level = max(0, min(15, _safe_int(record.get("level"), 0)))
        if level <= 0:
            continue
        position = record.get("blenderPosition")
        if not isinstance(position, (list, tuple)) or len(position) < 3:
            continue
        descriptors.append({
            "name": record.get("name"),
            "source": record.get("source", "minecraft:light"),
            "type": "point",
            "minecraftLevel": level,
            "rangeBlocks": float(level) + 0.5,
            "intensity": 45.0 * float(level),
            "falloff": "minecraft_linear_smooth",
            "invisibleSource": bool(record.get("invisibleSource", False)),
            "position": list(position[:3]),
            "color": record.get("color", [1.0, 0.92, 0.78]),
            "_logical": record,
        })
    return descriptors


def _translate_lights_v2(metadata, objects=None):
    descriptors = _logical_descriptors(metadata)
    if descriptors is None:
        return _ORIGINAL_TRANSLATE_LIGHTS(metadata, objects=objects)

    # Feed derived rendering descriptors into the mature light implementation,
    # but never mutate the original exporter metadata object in-place.
    patched = dict(metadata)
    patched["lights"] = [
        {key: value for key, value in descriptor.items() if key != "_logical"}
        for descriptor in descriptors
    ]
    result = _ORIGINAL_TRANSLATE_LIGHTS(patched, objects=objects)

    by_name = {}
    for descriptor in descriptors:
        name = lights._safe_name(descriptor.get("name"))
        by_name[name] = descriptor.get("_logical", {})

    for obj in result or []:
        logical = by_name.get(obj.name)
        if logical is None:
            # Blender may suffix a duplicate; source name is still the prefix.
            logical = next(
                (value for name, value in by_name.items() if obj.name.startswith(name + ".")),
                None,
            )
        if not isinstance(logical, dict):
            continue
        obj["minesport_light_block_x"] = _safe_int(logical.get("x"), 0)
        obj["minesport_light_block_y"] = _safe_int(logical.get("y"), 0)
        obj["minesport_light_block_z"] = _safe_int(logical.get("z"), 0)
        obj["minesport_light_block_level"] = max(0, min(15, _safe_int(logical.get("level"), 0)))
        try:
            obj["minesport_light_local_offset"] = json.dumps(
                logical.get("localOffset", [0.5, 0.5, 0.5]),
                separators=(",", ":"),
            )
        except Exception:
            pass
        obj["minesport_light_source_array"] = "LIGHT_BLOCK"

    return result


def register():
    global _ORIGINAL_TRANSLATE_LIGHTS
    if _ORIGINAL_TRANSLATE_LIGHTS is None:
        _ORIGINAL_TRANSLATE_LIGHTS = lights.translate_lights
        lights.translate_lights = _translate_lights_v2


def unregister():
    global _ORIGINAL_TRANSLATE_LIGHTS
    if _ORIGINAL_TRANSLATE_LIGHTS is not None:
        lights.translate_lights = _ORIGINAL_TRANSLATE_LIGHTS
        _ORIGINAL_TRANSLATE_LIGHTS = None
