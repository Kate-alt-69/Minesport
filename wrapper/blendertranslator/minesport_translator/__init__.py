bl_info = {
    "name": "Minesport Dynamic Translator",
    "author": "Kastrick / Minesport",
    "version": (0, 1, 2),
    "blender": (4, 3, 0),
    "location": "Object Properties > Minesport",
    "description": "One-shot translator for Minesport metadata. Creates Blender-native collections, bones, actions and animated material nodes.",
    "category": "Import-Export",
}

import bpy

from . import properties
from . import obj_import
from .translate import translate_gltf_import


class glTF2ImportUserExtension:
    """Hook used by Blender's glTF importer.

    This is intentionally not a runtime animation system. The hook executes
    during glTF import, translates Minesport metadata into ordinary Blender
    data, and returns. No frame-change/timer/depsgraph handler is registered.
    """

    def __init__(self):
        self.enabled = True

    def gather_import_scene_after_nodes_hook(self, gltf, *args):
        if not self.enabled:
            return
        try:
            translate_gltf_import(gltf)
        except Exception as exc:  # never break Blender's normal glTF import
            print(f"[Minesport Translator] translation failed: {exc}")


def register():
    properties.register()
    obj_import.register()


def unregister():
    obj_import.unregister()
    properties.unregister()
