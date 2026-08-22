bl_info = {
    "name": "Minesport Dynamic Translator",
    "author": "Kastrick / Minesport",
    "version": (0, 2, 0),
    "blender": (4, 3, 0),
    "location": "File > Import / Object Properties / Node Editor / 3D View > Minesport",
    "description": "Imports/translates Minesport exports, FLATTER logical geometry and Minecraft lights into Blender-native data.",
    "category": "Import-Export",
}

import importlib

import bpy

from . import active_export
from . import flatter
from . import properties
from .metadata import gltf_asset_path, load_sidecar
from .translate import translate_gltf_import


_OPTIONAL_MODULES = {}
_MANUAL_GLTF_GUARD = "minesport_manual_gltf_import"


class glTF2ImportUserExtension:
    """Hook used by Blender's stock glTF importer.

    Normal imports keep the automatic translation hook. The dedicated
    Minesport glTF importer sets a short-lived scene guard so this hook does
    not translate the same asset twice.
    """

    def __init__(self):
        self.enabled = True

    def gather_import_scene_after_nodes_hook(self, gltf, *args):
        if not self.enabled:
            return
        scene = getattr(bpy.context, "scene", None)
        if scene is not None and bool(scene.get(_MANUAL_GLTF_GUARD, False)):
            return
        try:
            translate_gltf_import(gltf)
            path = gltf_asset_path(gltf)
            metadata = load_sidecar(path) if path else None
            flatter.attach_flatter_metadata(metadata, asset_path=path)
            active_export.activate(path, metadata)
        except Exception as exc:
            print(f"[Minesport Translator] translation failed: {exc}")


def _register_optional(name):
    """Load one experimental helper without taking down the whole addon."""
    try:
        module = importlib.import_module(f"{__package__}.{name}")
        module.register()
        _OPTIONAL_MODULES[name] = module
        return True
    except Exception as exc:
        print(f"[Minesport Translator] optional subsystem '{name}' disabled: {exc}")
        return False


def register():
    properties.register()
    flatter.register()

    # Lights and emissive repair patch translate.translate_scene before the
    # dedicated OBJ/glTF importers load, so both formats share the same scene
    # semantics and glowing blocks still look like sources instead of stickers.
    _register_optional("lights")
    _register_optional("emissive_materials")
    _register_optional("flatter_runtime")
    _register_optional("incremental_refresh")
    _register_optional("liquid_merge")
    _register_optional("selection_sets")
    _register_optional("liquid_ui")
    _register_optional("viewport_interaction")
    _register_optional("edit_mode")

    # Performance is intentionally installed after the logical editing systems
    # exist but before the generic FLATTER overlay registers. It replaces only
    # repeated bookkeeping paths (metadata/RLE decode, depsgraph polling,
    # selection redraw/rebuild work) and leaves FLATTER's lossless data model
    # unchanged. The draw handoff replaces Liquid Merge's already-registered GPU
    # callback with the optimized callback as well.
    _register_optional("performance")
    _register_optional("performance_draw")

    _register_optional("flatter_overlay")
    _register_optional("flatter_020_ui")

    # Load the batched map renderer before registering the map operator. The
    # operator's draw_handler_add call then captures the optimized callback from
    # its very first invocation instead of the thousands-of-draw-calls renderer.
    _register_optional("performance_map")
    _register_optional("flatter_map")
    _register_optional("flatter_map_live")
    _register_optional("obj_import")
    _register_optional("gltf_import")
    _register_optional("roundtrip")
    _register_optional("roundtrip_incremental")


def unregister():
    for name in (
        "roundtrip_incremental",
        "roundtrip",
        "gltf_import",
        "obj_import",
        "flatter_map_live",
        "flatter_map",
        "performance_map",
        "flatter_020_ui",
        "flatter_overlay",
        "performance_draw",
        "performance",
        "edit_mode",
        "viewport_interaction",
        "liquid_ui",
        "selection_sets",
        "liquid_merge",
        "incremental_refresh",
        "flatter_runtime",
        "emissive_materials",
        "lights",
    ):
        module = _OPTIONAL_MODULES.pop(name, None)
        if module is None:
            continue
        try:
            module.unregister()
        except Exception as exc:
            print(f"[Minesport Translator] optional subsystem '{name}' cleanup failed: {exc}")

    try:
        flatter.unregister()
    finally:
        properties.unregister()
