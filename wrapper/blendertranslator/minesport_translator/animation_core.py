"""Shared Minecraft texture-animation core for Minesport.

New exporter descriptors are material-scoped. A single animated material stays
shared by every object using it, receives one tiny Blender driver, and exposes
its first-cycle texture changes as ordinary Timeline markers. No frame-change
handler is installed, so scrubbing/rendering does not run a Python tick loop.
"""

import hashlib
import json
import math

import bpy
from bpy.props import BoolProperty, FloatProperty

from . import translate as translate_module


MARKER_PREFIX = "MS TEX · "
SCHEDULE_KEY = "minesport_texture_frames"
FRAME_COUNT_KEY = "minesport_texture_frame_count"
FRAMES_PER_TICK_KEY = "minesport_texture_frames_per_tick"
TEXTURE_KEY = "minesport_texture_source"
DRIVER_PREFIX = "minesport_tex_"
MAX_TIMELINE_MARKERS = 512

_ORIGINAL_TRANSLATE_TEXTURE = None
_ORIGINAL_TRANSLATE_SCENE = None


def _base_material_name(name):
    value = str(name or "")
    if len(value) > 4 and value[-4] == "." and value[-3:].isdigit():
        return value[:-4]
    return value


def _matching_materials(name):
    if not isinstance(name, str) or not name:
        return []
    return [
        material for material in bpy.data.materials
        if material is not None and _base_material_name(material.name) == name
    ]


def _image_node(material):
    tree = getattr(material, "node_tree", None)
    if tree is None:
        return None
    for node in tree.nodes:
        if node.bl_idname != "ShaderNodeBsdfPrincipled":
            continue
        base = node.inputs.get("Base Color")
        if base and base.is_linked:
            source = base.links[0].from_node
            if source.bl_idname == "ShaderNodeTexImage":
                return source
    return next((node for node in tree.nodes if node.bl_idname == "ShaderNodeTexImage"), None)


def _driver_name(material_name):
    digest = hashlib.sha1(str(material_name).encode("utf-8", "replace")).hexdigest()[:12]
    return DRIVER_PREFIX + digest


def _material_schedule(material):
    try:
        values = json.loads(str(material.get(SCHEDULE_KEY, "[]")))
        sequence = tuple(int(value) for value in values)
    except Exception:
        sequence = ()
    return sequence if sequence else (0,)


def _register_material_driver(material):
    if material is None or SCHEDULE_KEY not in material:
        return None
    sequence = _material_schedule(material)
    frame_count = max(1, int(material.get(FRAME_COUNT_KEY, 1)))
    frames_per_tick = max(1.0e-6, float(material.get(FRAMES_PER_TICK_KEY, 1.0)))
    function_name = _driver_name(material.name)

    def sample(frame, speed, enabled):
        if not enabled:
            return 0.0
        try:
            scaled = max(0.0, float(frame) - 1.0) * max(0.0, float(speed))
            index = int(math.floor(scaled / frames_per_tick)) % len(sequence)
            sprite = max(0, min(frame_count - 1, int(sequence[index])))
            return -(sprite / float(frame_count))
        except Exception:
            return 0.0

    bpy.app.driver_namespace[function_name] = sample
    return function_name


def _remove_existing_animation_nodes(material):
    tree = material.node_tree
    if tree is None:
        return
    for node in list(tree.nodes):
        if node.label in {"Minesport Animated Texture", "Minesport UV"}:
            try:
                tree.nodes.remove(node)
            except Exception:
                pass


def _configure_shared_material(material, descriptor):
    if material is None:
        return False
    material.use_nodes = True
    tree = material.node_tree
    if tree is None:
        return False
    image_node = _image_node(material)
    if image_node is None:
        return False

    try:
        frame_count = max(1, int(descriptor.get("frameCount", 1)))
        frames = descriptor.get("frames")
        if not isinstance(frames, list) or not frames:
            frames = list(range(frame_count))
        frames = [max(0, min(frame_count - 1, int(value))) for value in frames]
    except Exception:
        return False
    if frame_count <= 1 or not frames:
        return False

    scene = bpy.context.scene
    fps = max(1.0e-6, float(scene.render.fps) / max(1.0e-6, float(scene.render.fps_base)))
    # Exporter expands custom .mcmeta durations into one sequence entry per
    # Minecraft tick, so frameTime is normally 1. Keep the field for backwards
    # compatibility with older descriptors.
    frame_time = max(1, int(descriptor.get("frameTime", 1)))
    frames_per_tick = frame_time * fps / 20.0

    _remove_existing_animation_nodes(material)
    nodes = tree.nodes
    links = tree.links
    texcoord = nodes.new("ShaderNodeTexCoord")
    mapping = nodes.new("ShaderNodeMapping")
    texcoord.label = "Minesport UV"
    mapping.label = "Minesport Animated Texture"
    mapping.inputs["Scale"].default_value[1] = 1.0 / float(frame_count)

    for link in list(image_node.inputs["Vector"].links):
        links.remove(link)
    links.new(texcoord.outputs["UV"], mapping.inputs["Vector"])
    links.new(mapping.outputs["Vector"], image_node.inputs["Vector"])

    material[SCHEDULE_KEY] = json.dumps(frames, separators=(",", ":"))
    material[FRAME_COUNT_KEY] = frame_count
    material[FRAMES_PER_TICK_KEY] = float(frames_per_tick)
    material[TEXTURE_KEY] = str(descriptor.get("texture") or "")
    material["minesport_texture_cycle_ticks"] = int(descriptor.get("cycleTicks", len(frames)))
    material["minesport_texture_interpolate"] = bool(descriptor.get("interpolate", False))

    function_name = _register_material_driver(material)
    if not function_name:
        return False

    driver = mapping.inputs["Location"].driver_add("default_value", 1).driver
    driver.type = "SCRIPTED"

    frame_var = driver.variables.new()
    frame_var.name = "frame"
    frame_target = frame_var.targets[0]
    frame_target.id_type = "SCENE"
    frame_target.id = scene
    frame_target.data_path = "frame_current"

    enabled_var = driver.variables.new()
    enabled_var.name = "enabled"
    enabled_target = enabled_var.targets[0]
    enabled_target.id_type = "SCENE"
    enabled_target.id = scene
    enabled_target.data_path = "minesport_texture_animation_enabled"

    speed_var = driver.variables.new()
    speed_var.name = "speed"
    speed_target = speed_var.targets[0]
    speed_target.id_type = "SCENE"
    speed_target.id = scene
    speed_target.data_path = "minesport_texture_animation_speed"

    driver.expression = f"{function_name}(frame,speed,enabled)"
    return True


def _translate_texture_frames_v2(descriptor):
    if not isinstance(descriptor, dict):
        return
    # Preserve old object-scoped descriptors produced by older exporters.
    if descriptor.get("object"):
        return _ORIGINAL_TRANSLATE_TEXTURE(descriptor)

    material_name = descriptor.get("material")
    for material in _matching_materials(material_name):
        _configure_shared_material(material, descriptor)


def _texture_descriptors(metadata):
    if not isinstance(metadata, dict):
        return []
    return [
        item for item in metadata.get("animations", [])
        if isinstance(item, dict) and item.get("kind") == "texture_frames"
    ]


def _timeline_entries(descriptor):
    timeline = descriptor.get("timeline")
    if isinstance(timeline, list) and timeline:
        result = []
        for item in timeline:
            if not isinstance(item, dict):
                continue
            try:
                result.append((int(item.get("tick", 0)), int(item.get("textureFrame", 0))))
            except Exception:
                pass
        return result

    frames = descriptor.get("frames")
    if not isinstance(frames, list) or not frames:
        return []
    result = []
    previous = None
    for tick, value in enumerate(frames):
        try:
            value = int(value)
        except Exception:
            continue
        if value == previous:
            continue
        result.append((tick, value))
        previous = value
    return result


def clear_texture_markers(scene=None):
    scene = scene or bpy.context.scene
    removed = 0
    for marker in list(scene.timeline_markers):
        if marker.name.startswith(MARKER_PREFIX):
            scene.timeline_markers.remove(marker)
            removed += 1
    scene["minesport_texture_marker_count"] = 0
    return removed


def install_texture_markers(metadata, scene=None):
    scene = scene or bpy.context.scene
    clear_texture_markers(scene)
    descriptors = _texture_descriptors(metadata)
    scene["minesport_texture_timeline_json"] = json.dumps(descriptors, separators=(",", ":"))
    if not descriptors:
        scene["minesport_texture_animation_count"] = 0
        return 0

    fps = max(1.0e-6, float(scene.render.fps) / max(1.0e-6, float(scene.render.fps_base)))
    count = 0
    for descriptor in descriptors:
        texture = str(descriptor.get("texture") or descriptor.get("material") or "texture")
        short = texture.split(":")[-1].split("/")[-1]
        for tick, texture_frame in _timeline_entries(descriptor):
            if count >= MAX_TIMELINE_MARKERS:
                break
            blender_frame = max(1, int(round(1.0 + tick * fps / 20.0)))
            marker = scene.timeline_markers.new(
                f"{MARKER_PREFIX}{short} → {texture_frame}",
                frame=blender_frame,
            )
            marker["minesport_texture"] = texture
            marker["minesport_texture_frame"] = texture_frame
            marker["minesport_tick"] = tick
            count += 1
        if count >= MAX_TIMELINE_MARKERS:
            break

    scene["minesport_texture_marker_count"] = count
    scene["minesport_texture_animation_count"] = len(descriptors)
    scene["minesport_texture_marker_limit_hit"] = count >= MAX_TIMELINE_MARKERS
    return count


def _translate_scene_wrapper(metadata=None, objects=None, asset_path=None):
    result = _ORIGINAL_TRANSLATE_SCENE(metadata, objects=objects, asset_path=asset_path)
    try:
        install_texture_markers(metadata)
    except Exception as exc:
        print(f"[Minesport Animation] timeline markers failed: {exc}")
    return result


class MINESPORT_OT_texture_markers_refresh(bpy.types.Operator):
    bl_idname = "minesport.texture_markers_refresh"
    bl_label = "Rebuild Texture Markers"
    bl_description = "Rebuild first-cycle Minecraft texture-change markers on the Blender Timeline"

    def execute(self, context):
        try:
            descriptors = json.loads(str(context.scene.get("minesport_texture_timeline_json", "[]")))
        except Exception:
            descriptors = []
        count = install_texture_markers({"animations": descriptors}, context.scene)
        self.report({"INFO"}, f"Built {count} Minecraft texture marker(s)")
        return {"FINISHED"}


class MINESPORT_PT_texture_animation(bpy.types.Panel):
    bl_label = "Texture Animation"
    bl_idname = "MINESPORT_PT_texture_animation"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"

    def draw(self, context):
        layout = self.layout
        scene = context.scene
        count = int(scene.get("minesport_texture_animation_count", 0))
        markers = int(scene.get("minesport_texture_marker_count", 0))
        box = layout.box()
        box.label(text=f"{count} animated Minecraft texture(s)", icon="SEQUENCE")
        box.prop(scene, "minesport_texture_animation_enabled", text="Play Texture Animation")
        box.prop(scene, "minesport_texture_animation_speed", text="Speed")
        box.label(text=f"{markers} first-cycle Timeline marker(s)", icon="MARKER_HLT")
        if bool(scene.get("minesport_texture_marker_limit_hit", False)):
            box.label(text="Marker display capped at 512; animation itself is not capped.", icon="INFO")
        box.operator(MINESPORT_OT_texture_markers_refresh.bl_idname, icon="FILE_REFRESH")
        box.label(text="Markers show exactly when the Minecraft sprite frame changes.", icon="INFO")
        box.label(text="Animation uses shared material drivers, not a Python frame loop.", icon="INFO")


_CLASSES = (
    MINESPORT_OT_texture_markers_refresh,
    MINESPORT_PT_texture_animation,
)


def register():
    global _ORIGINAL_TRANSLATE_TEXTURE, _ORIGINAL_TRANSLATE_SCENE

    if not hasattr(bpy.types.Scene, "minesport_texture_animation_enabled"):
        bpy.types.Scene.minesport_texture_animation_enabled = BoolProperty(
            name="Minecraft Texture Animation",
            description="Enable continuously animated Minecraft texture materials",
            default=True,
        )
    if not hasattr(bpy.types.Scene, "minesport_texture_animation_speed"):
        bpy.types.Scene.minesport_texture_animation_speed = FloatProperty(
            name="Texture Animation Speed",
            description="Global speed multiplier for Minesport Minecraft texture animation",
            default=1.0,
            min=0.0,
            soft_max=4.0,
        )

    for cls in _CLASSES:
        bpy.utils.register_class(cls)

    if _ORIGINAL_TRANSLATE_TEXTURE is None:
        _ORIGINAL_TRANSLATE_TEXTURE = translate_module._translate_texture_frames
        translate_module._translate_texture_frames = _translate_texture_frames_v2
    if _ORIGINAL_TRANSLATE_SCENE is None:
        _ORIGINAL_TRANSLATE_SCENE = translate_module.translate_scene
        translate_module.translate_scene = _translate_scene_wrapper

    # Driver namespace is not saved in .blend files. Rehydrate all existing
    # Minesport animated materials when the add-on is enabled or a file reopens.
    for material in bpy.data.materials:
        if material is not None and SCHEDULE_KEY in material:
            _register_material_driver(material)


def unregister():
    global _ORIGINAL_TRANSLATE_TEXTURE, _ORIGINAL_TRANSLATE_SCENE

    if _ORIGINAL_TRANSLATE_SCENE is not None:
        translate_module.translate_scene = _ORIGINAL_TRANSLATE_SCENE
        _ORIGINAL_TRANSLATE_SCENE = None
    if _ORIGINAL_TRANSLATE_TEXTURE is not None:
        translate_module._translate_texture_frames = _ORIGINAL_TRANSLATE_TEXTURE
        _ORIGINAL_TRANSLATE_TEXTURE = None

    for cls in reversed(_CLASSES):
        try:
            bpy.utils.unregister_class(cls)
        except Exception:
            pass

    if hasattr(bpy.types.Scene, "minesport_texture_animation_speed"):
        del bpy.types.Scene.minesport_texture_animation_speed
    if hasattr(bpy.types.Scene, "minesport_texture_animation_enabled"):
        del bpy.types.Scene.minesport_texture_animation_enabled

    for name in list(bpy.app.driver_namespace.keys()):
        if str(name).startswith(DRIVER_PREFIX):
            bpy.app.driver_namespace.pop(name, None)
