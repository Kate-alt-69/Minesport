import math
from pathlib import Path

import bpy
from mathutils import Vector

from .metadata import gltf_asset_path, load_sidecar


def translate_gltf_import(gltf):
    path = gltf_asset_path(gltf)
    metadata = load_sidecar(path) if path else None
    translate_scene(metadata, asset_path=path)


def translate_scene(metadata=None, objects=None, asset_path=None):
    units = bpy.context.scene.unit_settings
    units.system = "METRIC"
    units.length_unit = "METERS"
    units.scale_length = 1.0

    if objects is None:
        objects = _find_minesport_objects()
    else:
        objects = list(objects)

    if not objects:
        return

    export_name = _metadata_export_name(metadata) or _detect_export_name(objects)
    collection = _ensure_collection(export_name)
    _move_into_collection(objects, collection)
    _configure_minecraft_materials(objects, asset_path)

    block_records = {}
    if isinstance(metadata, dict):
        for record in metadata.get("blocks", []):
            if not isinstance(record, dict):
                continue
            key = record.get("object")
            if isinstance(key, str) and key:
                block_records[key] = record

    for obj in objects:
        obj.minesport.translated = True
        obj["minesport_metres_per_block"] = 1.0
        record = block_records.get(obj.name)
        if record:
            obj.minesport.source_block = str(record.get("id", ""))
        elif isinstance(obj.get("minesportGroup"), str):
            obj.minesport.source_block = obj.get("minesportGroup", "")

    if isinstance(metadata, dict):
        for descriptor in metadata.get("animations", []):
            if not isinstance(descriptor, dict):
                continue
            kind = descriptor.get("kind")
            try:
                if kind == "rigid_bone":
                    _translate_rigid_bone(descriptor, collection)
                elif kind == "texture_frames":
                    _translate_texture_frames(descriptor)
            except Exception as exc:
                print(f"[Minesport Translator] descriptor {kind!r} failed: {exc}")

    collection["minesport_translated"] = True
    collection["minesport_schema"] = 1
    if isinstance(metadata, dict):
        collection["minesport_animation_mode"] = str(metadata.get("animationMode", "none"))


def _configure_minecraft_materials(objects, asset_path=None):
    """Repair image links, alpha cutouts and pixel filtering after import."""
    seen = set()
    for obj in objects:
        data = getattr(obj, "data", None)
        for material in getattr(data, "materials", ()) if data is not None else ():
            if material is None or material.as_pointer() in seen:
                continue
            seen.add(material.as_pointer())
            material.use_nodes = True
            if material.node_tree is None:
                continue
            nodes = material.node_tree.nodes
            links = material.node_tree.links
            image_nodes = [node for node in nodes if node.bl_idname == "ShaderNodeTexImage"]
            color_node = _base_color_image_node(material)
            if color_node is None:
                color_node = image_nodes[0] if image_nodes else nodes.new("ShaderNodeTexImage")
            _repair_material_image(material, color_node, asset_path)
            for node in image_nodes + ([color_node] if color_node not in image_nodes else []):
                node.interpolation = "Closest"
                node.extension = "REPEAT"

            bsdf = next((node for node in nodes if node.bl_idname == "ShaderNodeBsdfPrincipled"), None)
            if bsdf is not None and color_node.image is not None:
                if not bsdf.inputs["Base Color"].is_linked:
                    links.new(color_node.outputs["Color"], bsdf.inputs["Base Color"])
                alpha_input = bsdf.inputs.get("Alpha")
                if alpha_input is not None:
                    for link in list(alpha_input.links):
                        links.remove(link)
                    links.new(color_node.outputs["Alpha"], alpha_input)

            material.alpha_threshold = 0.5
            if hasattr(material, "surface_render_method"):
                material.surface_render_method = "DITHERED"
            elif hasattr(material, "blend_method"):
                material.blend_method = "CLIP"


def _base_color_image_node(material):
    for node in material.node_tree.nodes:
        if node.bl_idname != "ShaderNodeBsdfPrincipled":
            continue
        base = node.inputs.get("Base Color")
        if base and base.is_linked:
            source = base.links[0].from_node
            if source.bl_idname == "ShaderNodeTexImage":
                return source
    return None


def _repair_material_image(material, node, asset_path):
    if node.image is not None:
        try:
            if Path(bpy.path.abspath(node.image.filepath)).is_file():
                return
        except Exception:
            pass
    if not asset_path or material.name.startswith("MISSING_"):
        return
    base_name = material.name
    if len(base_name) > 4 and base_name[-4] == "." and base_name[-3:].isdigit():
        base_name = base_name[:-4]
    candidate = Path(asset_path).resolve().parent / "textures" / f"{base_name}.png"
    if candidate.is_file():
        node.image = bpy.data.images.load(str(candidate), check_existing=True)


def _find_minesport_objects():
    result = []
    for obj in bpy.context.scene.objects:
        if "minesport" in obj.keys() or "minesportGroup" in obj.keys() or "minesportObjectMode" in obj.keys():
            result.append(obj)
    return result


def _metadata_export_name(metadata):
    if not isinstance(metadata, dict):
        return None
    value = metadata.get("exportName")
    return value if isinstance(value, str) and value else None


def _detect_export_name(objects):
    for obj in objects:
        extra = obj.get("minesport")
        try:
            if extra and "exportName" in extra:
                return str(extra["exportName"])
        except Exception:
            pass
    return "Minesport_Export"


def _ensure_collection(name):
    name = name or "Minesport_Export"
    existing = bpy.data.collections.get(name)
    if existing is not None:
        return existing
    collection = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(collection)
    return collection


def _move_into_collection(objects, collection):
    for obj in objects:
        if collection.objects.get(obj.name) is None:
            collection.objects.link(obj)
        for old_collection in list(obj.users_collection):
            if old_collection == collection:
                continue
            try:
                old_collection.objects.unlink(obj)
            except RuntimeError:
                pass


def _find_object(name):
    if not isinstance(name, str) or not name:
        return None
    return bpy.data.objects.get(name)


def _translate_rigid_bone(descriptor, collection):
    target = _find_object(descriptor.get("object"))
    if target is None:
        return

    bone_name = str(descriptor.get("bone", target.name + "_Bone"))
    pivot = descriptor.get("pivot", [0.0, 0.0, 0.0])
    if not isinstance(pivot, (list, tuple)) or len(pivot) < 3:
        pivot = [0.0, 0.0, 0.0]

    armature_data = bpy.data.armatures.new(target.name + "_Rig")
    armature = bpy.data.objects.new(target.name + "_Rig", armature_data)
    collection.objects.link(armature)

    bpy.ops.object.select_all(action="DESELECT")
    bpy.context.view_layer.objects.active = armature
    armature.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    edit_bone = armature_data.edit_bones.new(bone_name)
    edit_bone.head = Vector((float(pivot[0]), float(pivot[1]), float(pivot[2])))
    edit_bone.tail = edit_bone.head + Vector((0.0, 0.25, 0.0))
    bpy.ops.object.mode_set(mode="POSE")

    pose_bone = armature.pose.bones.get(bone_name)
    if pose_bone is None:
        bpy.ops.object.mode_set(mode="OBJECT")
        return

    pose_bone.rotation_mode = "XYZ"
    action = bpy.data.actions.new(str(descriptor.get("action", target.name + "_Action")))
    armature.animation_data_create()
    armature.animation_data.action = action

    axis = str(descriptor.get("axis", "X")).upper()
    axis_index = {"X": 0, "Y": 1, "Z": 2}.get(axis, 0)
    keyframes = descriptor.get("keyframes", [])
    for keyframe in keyframes:
        if not isinstance(keyframe, dict):
            continue
        frame = float(keyframe.get("frame", 1.0))
        degrees = float(keyframe.get("degrees", 0.0))
        pose_bone.rotation_euler[axis_index] = math.radians(degrees)
        pose_bone.keyframe_insert(data_path="rotation_euler", index=axis_index, frame=frame)

    bpy.ops.object.mode_set(mode="OBJECT")

    world_matrix = target.matrix_world.copy()
    target.parent = armature
    target.parent_type = "BONE"
    target.parent_bone = bone_name
    target.matrix_world = world_matrix
    armature.minesport.translated = True
    target.minesport.translated = True


def _translate_texture_frames(descriptor):
    target = _find_object(descriptor.get("object"))
    if target is None or target.type != "MESH":
        return

    material_name = descriptor.get("material")
    material = bpy.data.materials.get(material_name) if isinstance(material_name, str) else None
    if material is None and target.data.materials:
        material = target.data.materials[0]
    if material is None:
        return

    material = material.copy()
    if target.data.materials:
        target.data.materials[0] = material
    else:
        target.data.materials.append(material)
    material.use_nodes = True
    nodes = material.node_tree.nodes
    links = material.node_tree.links

    image_node = next((node for node in nodes if node.bl_idname == "ShaderNodeTexImage"), None)
    if image_node is None:
        return

    frame_count = max(1, int(descriptor.get("frameCount", 1)))
    frame_time = max(1, int(descriptor.get("frameTime", 1)))
    frames = descriptor.get("frames")
    if not isinstance(frames, list) or not frames:
        frames = list(range(frame_count))
    frames = [int(value) for value in frames]

    texcoord = nodes.new("ShaderNodeTexCoord")
    mapping = nodes.new("ShaderNodeMapping")
    texcoord.label = "Minesport UV"
    mapping.label = "Minesport Animated Texture"
    mapping.inputs["Scale"].default_value[1] = 1.0 / frame_count

    for link in list(image_node.inputs["Vector"].links):
        links.remove(link)
    links.new(texcoord.outputs["UV"], mapping.inputs["Vector"])
    links.new(mapping.outputs["Vector"], image_node.inputs["Vector"])

    driver = mapping.inputs["Location"].driver_add("default_value", 1).driver
    driver.type = "SCRIPTED"

    scene_var = driver.variables.new()
    scene_var.name = "frame"
    scene_target = scene_var.targets[0]
    scene_target.id_type = "SCENE"
    scene_target.id = bpy.context.scene
    scene_target.data_path = "frame_current"

    enabled_var = driver.variables.new()
    enabled_var.name = "enabled"
    enabled_target = enabled_var.targets[0]
    enabled_target.id_type = "OBJECT"
    enabled_target.id = target
    enabled_target.data_path = "minesport.continuous_animation"

    speed_var = driver.variables.new()
    speed_var.name = "speed"
    speed_target = speed_var.targets[0]
    speed_target.id_type = "OBJECT"
    speed_target.id = target
    speed_target.data_path = "minesport.animation_speed"

    fps = max(1.0, float(bpy.context.scene.render.fps))
    frames_per_step = max(1.0, frame_time * fps / 20.0)
    sequence_literal = repr(tuple(frames))
    driver.expression = (
        f"(-({sequence_literal}[int(max(0,frame-1)*max(speed,0)/{frames_per_step:.6f})%{len(frames)}]/{float(frame_count):.6f})) if enabled else 0.0"
    )

    target.minesport.continuous_animation = True
    target.minesport.animation_speed = 1.0
    target.minesport.translated = True
