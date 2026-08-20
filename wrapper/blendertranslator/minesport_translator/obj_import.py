from pathlib import Path

import bpy
from bpy.props import StringProperty
from bpy_extras.io_utils import ImportHelper

from .metadata import load_sidecar
from .translate import translate_scene


class MINESPORT_OT_import_obj(bpy.types.Operator, ImportHelper):
    bl_idname = "import_scene.minesport_obj"
    bl_label = "Minesport OBJ (.obj)"
    bl_description = "Import a Minesport OBJ and immediately translate its metadata into Blender-native data"
    bl_options = {"REGISTER", "UNDO"}

    filename_ext = ".obj"
    filter_glob: StringProperty(default="*.obj", options={"HIDDEN"})

    def execute(self, context):
        path = str(Path(self.filepath))
        before = {obj.as_pointer() for obj in bpy.data.objects}

        result = bpy.ops.wm.obj_import(
            filepath=path,
            use_split_objects=True,
            use_split_groups=False,
            validate_meshes=True,
        )
        if "FINISHED" not in result:
            return result

        imported = [obj for obj in bpy.data.objects if obj.as_pointer() not in before]
        _prepare_blender_scene(imported)
        metadata = load_sidecar(path)
        translate_scene(metadata, imported, asset_path=path)
        return {"FINISHED"}


def _prepare_blender_scene(imported):
    # One Minecraft block unit is one metre. Make that explicit in Blender and
    # bake the OBJ importer's axis conversion so the Transform panel reports
    # zero rotation / unit scale without changing the world geometry.
    units = bpy.context.scene.unit_settings
    units.system = "METRIC"
    units.length_unit = "METERS"
    units.scale_length = 1.0

    meshes = [obj for obj in imported if obj.type == "MESH"]
    if not meshes:
        return
    bpy.ops.object.select_all(action="DESELECT")
    for obj in meshes:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)


def _menu_import(self, context):
    self.layout.operator(MINESPORT_OT_import_obj.bl_idname, text="Minesport OBJ (.obj)")


_CLASSES = (MINESPORT_OT_import_obj,)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.TOPBAR_MT_file_import.append(_menu_import)


def unregister():
    try:
        bpy.types.TOPBAR_MT_file_import.remove(_menu_import)
    except Exception:
        pass
    for cls in reversed(_CLASSES):
        bpy.utils.unregister_class(cls)
