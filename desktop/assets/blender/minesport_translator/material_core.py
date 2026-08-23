"""Minecraft translucent material repair for Blender.

The generic translator intentionally handles ordinary cutout textures
conservatively. Water and glass need different semantics: blended/transmitted
light, low roughness, and no alpha-clip treatment. This module upgrades those
materials after every Minesport scene translation without changing opaque/cutout
materials such as leaves, rails or flowers.
"""

import bpy

from . import translate as translate_module


_ORIGINAL_TRANSLATE_SCENE = None


def _kind(material):
    name = str(getattr(material, "name", "")).lower()
    if "water_still" in name or "water_flow" in name or "block_water" in name:
        return "WATER"
    if "glass" in name and "glass_bottle" not in name:
        return "GLASS"
    return None


def _principled(material):
    tree = getattr(material, "node_tree", None)
    if tree is None:
        return None
    return next((node for node in tree.nodes if node.bl_idname == "ShaderNodeBsdfPrincipled"), None)


def _set_input(node, names, value):
    if node is None:
        return False
    for name in names:
        socket = node.inputs.get(name)
        if socket is None:
            continue
        try:
            socket.default_value = value
            return True
        except Exception:
            pass
    return False


def configure_material(material):
    kind = _kind(material)
    if kind is None:
        return False

    material.use_nodes = True
    bsdf = _principled(material)
    if kind == "WATER":
        _set_input(bsdf, ("Roughness",), 0.16)
        _set_input(bsdf, ("IOR",), 1.333)
        _set_input(bsdf, ("Transmission Weight", "Transmission"), 0.35)
        # The water PNG/tint remains the actual color source. Transmission makes
        # it visibly liquid even when a resource pack ships an opaque-alpha PNG.
        material["minesport_material_class"] = "WATER"
        material["minesport_transmission"] = 0.35
    else:
        _set_input(bsdf, ("Roughness",), 0.08)
        _set_input(bsdf, ("IOR",), 1.45)
        _set_input(bsdf, ("Transmission Weight", "Transmission"), 0.92)
        material["minesport_material_class"] = "GLASS"
        material["minesport_transmission"] = 0.92

    # Blender 4.x uses surface_render_method. Older supported installs use
    # blend_method. Keep both paths so an exported scene does not fall back to
    # alpha clip just because the Blender API changed names.
    try:
        if hasattr(material, "surface_render_method"):
            material.surface_render_method = "DITHERED"
        elif hasattr(material, "blend_method"):
            material.blend_method = "BLEND"
    except Exception:
        pass
    try:
        if hasattr(material, "use_transparency_overlap"):
            material.use_transparency_overlap = False
    except Exception:
        pass
    try:
        if hasattr(material, "show_transparent_back"):
            material.show_transparent_back = True
    except Exception:
        pass

    material["minesport_translucent"] = True
    material["minesport_alpha_clip"] = False
    return True


def configure_scene_materials():
    count = 0
    for material in bpy.data.materials:
        try:
            if configure_material(material):
                count += 1
        except Exception as exc:
            print(f"[Minesport Materials] {material.name} failed: {exc}")
    bpy.context.scene["minesport_translucent_material_count"] = count
    return count


def _translate_scene_wrapper(metadata=None, objects=None, asset_path=None):
    result = _ORIGINAL_TRANSLATE_SCENE(metadata, objects=objects, asset_path=asset_path)
    try:
        configure_scene_materials()
    except Exception as exc:
        print(f"[Minesport Materials] translation repair failed: {exc}")
    return result


def register():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is None:
        _ORIGINAL_TRANSLATE_SCENE = translate_module.translate_scene
        translate_module.translate_scene = _translate_scene_wrapper
    # Also repair an already-open Minesport scene when enabling/updating addon.
    try:
        configure_scene_materials()
    except Exception:
        pass


def unregister():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is not None:
        translate_module.translate_scene = _ORIGINAL_TRANSLATE_SCENE
        _ORIGINAL_TRANSLATE_SCENE = None
