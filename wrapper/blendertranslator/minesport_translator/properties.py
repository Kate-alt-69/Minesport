import bpy
from bpy.props import BoolProperty, EnumProperty, FloatProperty, IntProperty, PointerProperty, StringProperty


class MinesportProperties(bpy.types.PropertyGroup):
    translated: BoolProperty(name="Translated", default=False, options={"HIDDEN"})
    source_block: StringProperty(name="Minecraft block", default="")
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
    is_flatter: BoolProperty(name="FLATTER", default=False, options={"HIDDEN"})
    flatter_id: StringProperty(name="FLATTER ID", default="", options={"HIDDEN"})
    flatter_version: StringProperty(name="FLATTER version", default="", options={"HIDDEN"})
    flatter_block_count: IntProperty(name="Logical blocks", default=0, min=0, options={"HIDDEN"})
    flatter_width: IntProperty(name="Width", default=0, min=0, options={"HIDDEN"})
    flatter_height: IntProperty(name="Height", default=0, min=0, options={"HIDDEN"})
    flatter_depth: IntProperty(name="Depth", default=0, min=0, options={"HIDDEN"})
    flatter_selected: StringProperty(name="Selected logical block", default="", options={"HIDDEN"})

    flatter_interaction_mode: EnumProperty(
        name="Interaction",
        description="How Minesport interprets logical FLATTER interaction in the viewport",
        items=(
            ("INSPECT", "Inspect", "Inspect logical blocks without changing the liquid selection"),
            ("SELECT", "Select", "Select individual logical blocks"),
            ("BOX", "Box Select", "Select occupied logical blocks between two picked corners"),
            ("MATERIALIZE", "Materialize", "Turn the clicked logical block into a Blender mesh object"),
        ),
        default="SELECT",
    )
    flatter_overlay_mode: EnumProperty(
        name="Logical overlay",
        description="How much of the virtual FLATTER voxel grid is drawn in the 3D viewport",
        items=(
            ("OFF", "Off", "Do not draw logical voxel wireframes"),
            ("SELECTED", "Selected only", "Draw only the focused/selected logical block"),
            ("FULL", "Full cell", "Diagnostic view of the complete logical voxel grid"),
        ),
        default="SELECTED",
    )
    flatter_overlay_opacity: FloatProperty(
        name="Overlay opacity",
        description="Opacity of the logical voxel wireframe overlay",
        default=0.32,
        min=0.05,
        max=1.0,
        subtype="FACTOR",
    )
    flatter_overlay_xray: BoolProperty(
        name="X-Ray logical blocks",
        description="Draw logical voxel lines through the greedy render mesh",
        default=False,
    )
    flatter_materialization_limit: IntProperty(
        name="Materialization limit",
        description="Maximum logical blocks a single bulk materialization request may create",
        default=1024,
        min=1,
        max=1000000,
    )
    flatter_active_set: StringProperty(name="Active selection set", default="", options={"HIDDEN"})

    light_level: IntProperty(
        name="Light Level",
        description="Minecraft block-light level. A level N source has N logical one-block decay steps",
        default=15,
        min=1,
        max=15,
    )
    light_snap_face: EnumProperty(
        name="Light Snap",
        description="Where a user-created Minesport light snaps relative to the focused logical block",
        items=(
            ("TOP", "Top face", "Snap to the top face of the focused Minecraft block"),
            ("BOTTOM", "Bottom face", "Snap to the bottom face of the focused Minecraft block"),
            ("CENTER", "Block center", "Place the light in the center of the focused Minecraft block"),
        ),
        default="TOP",
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
            version = props.flatter_version or "legacy"
            box.label(text=f"minesport_FLATTER_object · v{version}", icon="MESH_GRID")
            box.label(text=f"{props.flatter_block_count:,} logical Minecraft blocks")
            box.label(text=f"3D dimensions: {props.flatter_width} × {props.flatter_height} × {props.flatter_depth}")
            if props.flatter_selected:
                box.label(text="Focused: " + props.flatter_selected)
            box.label(text="Render geometry is greedy; logical voxels remain addressable.", icon="INFO")

            controls = layout.box()
            controls.label(text="FLATTER Interaction", icon="RESTRICT_SELECT_OFF")
            controls.prop(props, "flatter_interaction_mode", text="")
            controls.prop(props, "flatter_overlay_mode")
            if props.flatter_overlay_mode != "OFF":
                controls.prop(props, "flatter_overlay_opacity")
                controls.prop(props, "flatter_overlay_xray")
            controls.prop(props, "flatter_materialization_limit")
            if props.flatter_overlay_mode == "FULL":
                controls.label(
                    text="Full cell is a diagnostic grid; selected blocks use a stronger outline.",
                    icon="INFO",
                )

        box = layout.box()
        box.label(text="Animation")
        box.prop(props, "continuous_animation")
        box.prop(props, "animation_speed")
        box.label(text="Generated animation is Blender-native; Minesport does not tick per frame.", icon="INFO")


_CLASSES = (MinesportProperties, MINESPORT_PT_properties)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Object.minesport = PointerProperty(type=MinesportProperties)


def unregister():
    if hasattr(bpy.types.Object, "minesport"):
        del bpy.types.Object.minesport
    for cls in reversed(_CLASSES):
        bpy.utils.unregister_class(cls)
