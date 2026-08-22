"""Minecraft cutout-material repair for Blender.

Minecraft foliage/plants/rails/etc. use binary cutout transparency rather than
semi-transparent blending. Feeding their raw alpha into Blender's dithered
surface path can make stacked leaves look dark/noisy. This module inserts one
cheap 0.5 alpha threshold node for known cutout families while deliberately
leaving water/glass and other translucent materials alone.
"""

import bpy

from . import translate as translate_module


_ORIGINAL_TRANSLATE_SCENE = None
_CUTOUT_NODE_LABEL = "Minesport Alpha Cutout"

# Conservative block/material families whose vanilla render layer is cutout.
# Names are exporter material names, so substring matching also covers tinted
# variants and most modded equivalents without touching generic translucent
# materials such as water, glass, ice or portals.
_CUTOUT_TOKENS = (
    "leaves", "foliage", "sapling", "grass", "fern", "vine",
    "flower", "tulip", "orchid", "dandelion", "poppy", "allium",
    "azure_bluet", "cornflower", "lily", "sunflower", "peony", "lilac",
    "rose_bush", "mushroom", "fungus", "roots", "sprouts", "propagule",
    "sugar_cane", "reeds", "cobweb", "_web", "rail", "redstone_wire",
    "ladder", "chain", "iron_bars", "torch", "end_rod", "tripwire",
    "crop", "wheat", "carrot", "potato", "beetroot", "nether_wart",
    "kelp", "seagrass", "bamboo", "cocoa", "cactus_flower",
    "hanging_roots", "spore_blossom", "pink_petals", "fire",
)

_TRANSLUCENT_TOKENS = (
    "water", "glass", "ice", "portal", "slime", "honey",
)


def _is_cutout_material(material):
    if material is None:
        return False
    if str(material.get("minesport_material_class", "")).upper() in {"WATER", "GLASS"}:
        return False
    name = str(getattr(material, "name", "")).lower()
    if any(token in name for token in _TRANSLUCENT_TOKENS):
        return False
    return any(token in name for token in _CUTOUT_TOKENS)


def _principled(material):
    tree = getattr(material, "node_tree", None)
    if tree is None:
        return None
    return next(
        (node for node in tree.nodes if node.bl_idname == "ShaderNodeBsdfPrincipled"),
        None,
    )


def _alpha_image_node(bsdf):
    alpha = bsdf.inputs.get("Alpha") if bsdf is not None else None
    if alpha is None or not alpha.is_linked:
        return None
    source = alpha.links[0].from_node
    if source.bl_idname == "ShaderNodeTexImage":
        return source
    if source.bl_idname == "ShaderNodeMath" and source.label == _CUTOUT_NODE_LABEL:
        input_socket = source.inputs[0]
        if input_socket.is_linked and input_socket.links[0].from_node.bl_idname == "ShaderNodeTexImage":
            return input_socket.links[0].from_node
    return None


def configure_material(material):
    if not _is_cutout_material(material):
        return False

    material.use_nodes = True
    tree = material.node_tree
    bsdf = _principled(material)
    alpha = bsdf.inputs.get("Alpha") if bsdf is not None else None
    image = _alpha_image_node(bsdf)
    if tree is None or alpha is None or image is None:
        return False

    node = next(
        (item for item in tree.nodes if item.bl_idname == "ShaderNodeMath" and item.label == _CUTOUT_NODE_LABEL),
        None,
    )
    if node is None:
        node = tree.nodes.new("ShaderNodeMath")
        node.label = _CUTOUT_NODE_LABEL
        node.name = _CUTOUT_NODE_LABEL
    node.operation = "GREATER_THAN"
    node.inputs[1].default_value = 0.5

    for link in list(alpha.links):
        tree.links.remove(link)
    for link in list(node.inputs[0].links):
        tree.links.remove(link)
    tree.links.new(image.outputs["Alpha"], node.inputs[0])
    tree.links.new(node.outputs[0], alpha)

    try:
        material.alpha_threshold = 0.5
    except Exception:
        pass
    try:
        if hasattr(material, "surface_render_method"):
            # Blender 4.x has no old CLIP enum. Binary alpha entering DITHERED
            # behaves as a hard cutout without semi-transparent dark stacking.
            material.surface_render_method = "DITHERED"
        elif hasattr(material, "blend_method"):
            material.blend_method = "CLIP"
    except Exception:
        pass

    material["minesport_alpha_clip"] = True
    material["minesport_alpha_threshold"] = 0.5
    material["minesport_material_class"] = "CUTOUT"
    return True


def configure_scene_materials():
    count = 0
    for material in bpy.data.materials:
        try:
            if configure_material(material):
                count += 1
        except Exception as exc:
            print(f"[Minesport Cutout] {material.name} failed: {exc}")
    scene = getattr(bpy.context, "scene", None)
    if scene is not None:
        scene["minesport_cutout_material_count"] = count
    return count


def _translate_scene_wrapper(metadata=None, objects=None, asset_path=None):
    result = _ORIGINAL_TRANSLATE_SCENE(metadata, objects=objects, asset_path=asset_path)
    try:
        configure_scene_materials()
    except Exception as exc:
        print(f"[Minesport Cutout] translation repair failed: {exc}")
    return result


def register():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is None:
        _ORIGINAL_TRANSLATE_SCENE = translate_module.translate_scene
        translate_module.translate_scene = _translate_scene_wrapper
    try:
        configure_scene_materials()
    except Exception:
        pass


def unregister():
    global _ORIGINAL_TRANSLATE_SCENE
    if _ORIGINAL_TRANSLATE_SCENE is not None:
        translate_module.translate_scene = _ORIGINAL_TRANSLATE_SCENE
        _ORIGINAL_TRANSLATE_SCENE = None
