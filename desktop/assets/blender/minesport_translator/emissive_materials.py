"""Blender-native emissive material repair for Minecraft light-source blocks.

Point lights illuminate the scene; emission keeps the source texture itself from
looking like a dull painted rectangle. This is intentionally conservative and
uses material/image names instead of forcing every bright-looking texture to glow.
"""

import os

import bpy

from . import translate as translate_module


_ORIGINAL_TRANSLATE_SCENE = None

_EMISSIVE_TOKENS = (
    "torch",
    "lantern",
    "glowstone",
    "sea_lantern",
    "shroomlight",
    "froglight",
    "end_rod",
    "jack_o_lantern",
    "fire",
    "lava",
    "glow_lichen",
    "amethyst_cluster",
    "amethyst_bud",
    "crying_obsidian",
    "magma",
    "candle",
    "cave_vines_lit",
    "furnace_front_on",
    "blast_furnace_front_on",
    "smoker_front_on",
    "redstone_torch",
    "redstone_lamp_on",
)


def _image_names(material):
    result = []
    tree = getattr(material, "node_tree", None)
    if tree is None:
        return result
    for node in tree.nodes:
        if node.bl_idname != "ShaderNodeTexImage" or node.image is None:
            continue
        result.append(str(node.image.name).lower())
        path = str(getattr(node.image, "filepath", "") or "")
        if path:
            result.append(os.path.basename(path).lower())
    return result


def _looks_emissive(material):
    names = [str(material.name).lower(), *_image_names(material)]
    joined = " ".join(names)
    if "redstone_lamp" in joined and "redstone_lamp_on" not in joined:
        return False
    if "furnace_front" in joined and "furnace_front_on" not in joined:
        return False
    if "blast_furnace_front" in joined and "blast_furnace_front_on" not in joined:
        return False
    if "smoker_front" in joined and "smoker_front_on" not in joined:
        return False
    return any(token in joined for token in _EMISSIVE_TOKENS)


def _principled(material):
    tree = getattr(material, "node_tree", None)
    if tree is None:
        return None
    return next((node for node in tree.nodes if node.bl_idname == "ShaderNodeBsdfPrincipled"), None)


def _base_color_source(bsdf):
    base = bsdf.inputs.get("Base Color") if bsdf is not None else None
    if base is None or not base.is_linked:
        return None
    return base.links[0].from_socket


def _configure_material(material):
    if material is None or not _looks_emissive(material):
        return False
    material.use_nodes = True
    tree = material.node_tree
    bsdf = _principled(material)
    if tree is None or bsdf is None:
        return False

    emission = bsdf.inputs.get("Emission Color") or bsdf.inputs.get("Emission")
    strength = bsdf.inputs.get("Emission Strength")
    source = _base_color_source(bsdf)
    if emission is not None:
        if source is not None:
            for link in list(emission.links):
                tree.links.remove(link)
            tree.links.new(source, emission)
        elif hasattr(emission, "default_value"):
            emission.default_value = (1.0, 0.82, 0.58, 1.0)
    if strength is not None:
        strength.default_value = max(float(strength.default_value), 1.35)

    material["minesport_emissive"] = True
    material["minesport_emission_strength"] = 1.35
    return True


def configure_scene_emission(objects=None):
    seen = set()
    changed = 0
    candidates = list(objects) if objects is not None else list(bpy.context.scene.objects)
    for obj in candidates:
        data = getattr(obj, "data", None)
        materials = getattr(data, "materials", ()) if data is not None else ()
        for material in materials:
            if material is None or material.as_pointer() in seen:
                continue
            seen.add(material.as_pointer())
            if _configure_material(material):
                changed += 1
    return changed


def _translate_scene_wrapper(metadata=None, objects=None, asset_path=None):
    result = _ORIGINAL_TRANSLATE_SCENE(metadata, objects=objects, asset_path=asset_path)
    try:
        changed = configure_scene_emission(objects=objects)
        if changed:
            print(f"[Minesport Materials] restored emission on {changed} material(s)")
    except Exception as exc:
        print(f"[Minesport Materials] emissive repair failed: {exc}")
    return result


def register():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is None:
        _ORIGINAL_TRANSLATE_SCENE = translate_module.translate_scene
        translate_module.translate_scene = _translate_scene_wrapper


def unregister():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is not None:
        translate_module.translate_scene = _ORIGINAL_TRANSLATE_SCENE
        _ORIGINAL_TRANSLATE_SCENE = None
