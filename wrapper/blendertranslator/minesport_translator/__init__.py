bl_info = {
    "name": "Minesport Dynamic Translator",
    "author": "Kastrick / Minesport",
    "version": (0, 1, 4),
    "blender": (4, 3, 0),
    "location": "Object Properties / 3D View > Minesport",
    "description": "Translates Minesport metadata and FLATTER logical block geometry into Blender-native data.",
    "category": "Import-Export",
}

import bpy

from . import flatter
from . import flatter_overlay
from . import flatter_runtime
from . import properties
from . import obj_import
from .metadata import gltf_asset_path, load_sidecar
from .translate import translate_gltf_import


class glTF2ImportUserExtension:
    """Hook used by Blender's glTF importer.

    Normal Minesport metadata is translated one-shot into Blender-native data.
    FLATTER keeps a lightweight Blender helper layer for logical block
    picking/materialization and a GPU-only logical-block overview; there is
    still no per-frame Minesport simulation runtime.
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
        except Exception as exc:  # never break Blender's normal glTF import
            print(f"[Minesport Translator] translation failed: {exc}")


def register():
    properties.register()
    flatter.register()
    flatter_runtime.register()
    flatter_overlay.register()
    obj_import.register()


def unregister():
    obj_import.unregister()
    flatter_overlay.unregister()
    flatter_runtime.unregister()
    flatter.unregister()
    properties.unregister()
