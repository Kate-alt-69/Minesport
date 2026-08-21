import bpy
from bpy.props import BoolProperty, FloatProperty, IntProperty, PointerProperty, StringProperty


class MinesportProperties(bpy.types.PropertyGroup):
    translated: BoolProperty(
        name="Translated",
        default=False,
        options={"HIDDEN"},
    )
    source_block: StringProperty(
        name="Minecraft block",
        default="",
    )
    continuous_animation: BoolProperty(
        name="Continuous Animation",
        description="Play continuously animated Minecraft textures/effects prepared by the Minesport translator",
        default=True,
    )
    animation_speed: FloatProperty(
        name="Animation Speed",
        description="Multiplier used by generated Blender-native animation drivers",
        default=1.0,
        min=0.0,
        soft_max=4.0,
    )
    is_flatter: BoolProperty(
        name="FLATTER",
        default=False,
        options={"HIDDEN"},
    )
    flatter_id: StringProperty(
        name="FLATTER ID",
        default="",
        options={"HIDDEN"},
    )
    flatter_block_count: IntProperty(
        name="Logical blocks",
        default=0,
        min=0,
        options={"HIDDEN"},
    )
    flatter_selected: StringProperty(
        name="Selected logical block",
        default="",
        options={"HIDDEN"},
    )


class MINESPORT_PT_properties(bpy.types.Panel):
    bl_label = "Minesport"
    bl_idname = "MINESPORT_PT_properties"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "object"

    @classmethod
    def poll(cls, context):
        obj = context.object
        return obj is not None and hasattr(obj, "minesport") and obj.minesport.translated

    def draw(self, context):
        layout = self.layout
        obj = context.object
        props = obj.minesport

        if props.source_block:
            layout.label(text=props.source_block)

        if props.is_flatter:
            box = layout.box()
            box.label(text="FLATTER", icon="MESH_GRID")
            box.label(text=f"{props.flatter_block_count:,} logical Minecraft blocks")
            if props.flatter_selected:
                box.label(text="Selected: " + props.flatter_selected)
            box.label(text="Use the Minesport tab in the 3D View to pick/materialize blocks.", icon="INFO")

        box = layout.box()
        box.label(text="Animation")
        box.prop(props, "continuous_animation")
        box.prop(props, "animation_speed")
        box.label(text="Generated animation is Blender-native; Minesport does not tick per frame.", icon="INFO")


_CLASSES = (
    MinesportProperties,
    MINESPORT_PT_properties,
)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Object.minesport = PointerProperty(type=MinesportProperties)


def unregister():
    if hasattr(bpy.types.Object, "minesport"):
        del bpy.types.Object.minesport
    for cls in reversed(_CLASSES):
        bpy.utils.unregister_class(cls)
