from pathlib import Path

import bpy
from bpy.props import StringProperty
from bpy_extras.io_utils import ImportHelper

from . import active_export
from .flatter import attach_flatter_metadata
from .metadata import load_sidecar
from .translate import translate_scene


_MANUAL_IMPORT_GUARD = "minesport_manual_gltf_import"


class MINESPORT_OT_import_gltf(bpy.types.Operator, ImportHelper):
    bl_idname = "import_scene.minesport_gltf"
    bl_label = "Minesport glTF / GLB (.gltf/.glb)"
    bl_description = (
        "Import a Minesport glTF/GLB and translate FLATTER/Minesport metadata "
        "into Blender-native data in one pass"
    )
    bl_options = {"REGISTER", "UNDO"}

    # Keep this empty because the same operator accepts both .gltf and .glb.
    # ImportHelper otherwise tries to force one extension when a filename is typed.
    filename_ext = ""
    filter_glob: StringProperty(default="*.gltf;*.glb", options={"HIDDEN"})

    def execute(self, context):
        path = str(Path(self.filepath))
        if Path(path).suffix.lower() not in {".gltf", ".glb"}:
            self.report({"ERROR"}, "Choose a .gltf or .glb Minesport export")
            return {"CANCELLED"}
        if not hasattr(bpy.ops.import_scene, "gltf"):
            self.report({"ERROR"}, "Blender's glTF importer is unavailable")
            return {"CANCELLED"}

        before = {obj.as_pointer() for obj in bpy.data.objects}
        scene = context.scene
        previous_guard = scene.get(_MANUAL_IMPORT_GUARD, None)
        scene[_MANUAL_IMPORT_GUARD] = True
        try:
            result = bpy.ops.import_scene.gltf(filepath=path)
        finally:
            if previous_guard is None:
                try:
                    del scene[_MANUAL_IMPORT_GUARD]
                except Exception:
                    pass
            else:
                scene[_MANUAL_IMPORT_GUARD] = previous_guard

        if "FINISHED" not in result:
            return result

        imported = [obj for obj in bpy.data.objects if obj.as_pointer() not in before]
        _prepare_scene(imported)
        metadata = load_sidecar(path)
        translate_scene(metadata, imported, asset_path=path)
        attach_flatter_metadata(metadata, imported, asset_path=path)
        active_export.activate(path, metadata, imported)

        bpy.ops.object.select_all(action="DESELECT")
        selectable = [obj for obj in imported if obj.type in {"MESH", "EMPTY", "ARMATURE"}]
        for obj in selectable:
            obj.select_set(True)
        if selectable:
            context.view_layer.objects.active = selectable[0]

        flatter_count = sum(1 for obj in imported if obj.get("minesport_type") == "FLATTER")
        self.report(
            {"INFO"},
            f"Minesport import complete · {len(imported)} object(s) · {flatter_count} FLATTER cell(s)",
        )
        return {"FINISHED"}


def _prepare_scene(imported):
    units = bpy.context.scene.unit_settings
    units.system = "METRIC"
    units.length_unit = "METERS"
    units.scale_length = 1.0
    for obj in imported:
        if hasattr(obj, "minesport"):
            obj.minesport.translated = True


def _menu_import(self, context):
    self.layout.operator(
        MINESPORT_OT_import_gltf.bl_idname,
        text="Minesport glTF / GLB (.gltf/.glb)",
    )


_CLASSES = (MINESPORT_OT_import_gltf,)


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
