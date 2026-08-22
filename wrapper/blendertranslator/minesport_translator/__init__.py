bl_info = {
    "name": "Minesport Dynamic Translator",
    "author": "Kastrick / Minesport",
    "version": (0, 1, 6),
    "blender": (4, 3, 0),
    "location": "File > Import / Object Properties / Node Editor / 3D View > Minesport",
    "description": "Imports/translates Minesport exports and FLATTER logical block geometry into Blender-native data.",
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

    _register_optional("flatter_runtime")
    _register_optional("liquid_merge")
    _register_optional("selection_sets")
    _register_optional("liquid_ui")
    _register_optional("viewport_interaction")
    _register_optional("flatter_overlay")
    _register_optional("flatter_map")
    _register_optional("obj_import")
    _register_optional("gltf_import")


def unregister():
    for name in (
        "gltf_import",
        "obj_import",
        "flatter_map",
        "flatter_overlay",
        "viewport_interaction",
        "liquid_ui",
        "selection_sets",
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
