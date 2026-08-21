bl_info = {
    "name": "Minesport Dynamic Translator",
    "author": "Kastrick / Minesport",
    "version": (0, 1, 5),
    "blender": (4, 3, 0),
    "location": "Object Properties / Node Editor / 3D View > Minesport",
    "description": "Translates Minesport metadata and FLATTER logical block geometry into Blender-native data.",
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


class glTF2ImportUserExtension:
    """Hook used by Blender's glTF importer.

    Normal Minesport metadata is translated one-shot into Blender-native data.
    FLATTER keeps lightweight Blender helper layers for logical block picking,
    Liquid Merge animation, materialization, and GPU-only voxel overviews.
    """

    def __init__(self):
        self.enabled = True

    def gather_import_scene_after_nodes_hook(self, gltf, *args):
        if not self.enabled:
            return
        try:
            translate_gltf_import(gltf)
            path = gltf_asset_path(gltf)
            metadata = load_sidecar(path) if path else None
            flatter.attach_flatter_metadata(metadata, asset_path=path)
            active_export.activate(path, metadata)
        except Exception as exc:  # never break Blender's normal glTF import
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

    # Keep experimental pieces independent. Registration order matters here:
    # schema/runtime upgrades wrap the core first, Liquid Merge wraps that
    # result, then UI/overlay/OBJ helpers consume the enriched objects.
    _register_optional("flatter_runtime")
    _register_optional("liquid_merge")
    _register_optional("liquid_ui")
    _register_optional("flatter_overlay")
    _register_optional("obj_import")


def unregister():
    for name in (
        "obj_import",
        "flatter_overlay",
        "liquid_ui",
        "liquid_merge",
        "flatter_runtime",
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
