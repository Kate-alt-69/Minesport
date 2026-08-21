"""FLATTER schema/runtime upgrades kept separate from the core operators.

Schema 2 allows multiple render layers per logical block face (for example an
opaque grass side plus its tinted overlay). The old schema-1 single-face shape
remains readable so existing .blend files keep working.
"""

import json

import bpy
from mathutils import Vector

from . import flatter


_ORIGINAL_ATTACH = None
_ORIGINAL_REBUILD = None
_ORIGINAL_CREATE = None


def _face_infos(entry, direction):
    faces = entry.get("faces") if isinstance(entry, dict) else None
    value = faces.get(direction) if isinstance(faces, dict) else None
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    return []


def _rebuild_flatter(obj, payload=None, grid=None):
    payload = payload or flatter._load_payload(obj)
    if payload is None:
        return False
    grid = grid if grid is not None else flatter._decode_grid(payload)
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))

    grouped = {}
    for xyz, palette_index in grid.items():
        entry = flatter._palette(payload, palette_index)
        if entry is None:
            continue
        x, y, z = xyz
        for direction in flatter._DIRECTIONS:
            dx, dy, dz = flatter._DELTA[direction]
            if (x + dx, y + dy, z + dz) in grid:
                continue

            for layer_index, face in enumerate(_face_infos(entry, direction)):
                local_vertices = flatter._face_vertices(face)
                uv = flatter._face_uv(face)
                if local_vertices is None or uv is None:
                    continue
                edge1 = flatter._axis_edge(flatter._sub(local_vertices[1], local_vertices[0]))
                edge3 = flatter._axis_edge(flatter._sub(local_vertices[3], local_vertices[0]))
                if edge1 is None or edge3 is None or edge1[0] == edge3[0]:
                    edge1, edge3 = flatter._fallback_edges(direction)
                material = str(face.get("material") or "Minesport_Material")
                signature = (
                    direction,
                    layer_index,
                    material,
                    str(face.get("texture") or ""),
                    int(face.get("tint", -1)),
                    tuple(round(value, 7) for pair in uv for value in pair),
                    flatter._plane(xyz, direction),
                    edge1,
                    edge3,
                )
                a = flatter._axis_coord(xyz, edge1)
                b = flatter._axis_coord(xyz, edge3)
                grouped.setdefault(signature, {})[(a, b)] = (
                    xyz, face, local_vertices, uv
                )

    rendered = []
    for _signature, face_grid in grouped.items():
        used = set()
        for key in sorted(face_grid, key=lambda item: (item[1], item[0])):
            if key in used:
                continue
            a0, b0 = key
            width = 1
            while (a0 + width, b0) in face_grid and (a0 + width, b0) not in used:
                width += 1
            height = 1
            while True:
                next_b = b0 + height
                if all(
                    (a0 + da, next_b) in face_grid
                    and (a0 + da, next_b) not in used
                    for da in range(width)
                ):
                    height += 1
                else:
                    break
            for db in range(height):
                for da in range(width):
                    used.add((a0 + da, b0 + db))

            xyz, face, local_vertices, uv = face_grid[key]
            rendered.append(
                flatter._expanded_face(
                    xyz, face, local_vertices, uv, width, height, center
                )
            )

    flatter._replace_mesh(obj, rendered)
    return True


def _create_materialized_block(parent, payload, xyz, palette_index, entry):
    vertices = []
    faces = []
    uvs = []
    material_names = []

    for direction in flatter._DIRECTIONS:
        for face in _face_infos(entry, direction):
            local = flatter._face_vertices(face)
            face_uv = flatter._face_uv(face)
            if local is None or face_uv is None:
                continue
            start = len(vertices)
            vertices.extend((v[0], -v[2], v[1]) for v in local)
            faces.append((start, start + 1, start + 2, start + 3))
            uvs.append(face_uv)
            material_names.append(str(face.get("material") or "Minesport_Material"))

    if not faces:
        return None

    block_id = str(entry.get("id") or "minecraft:unknown")
    name = "Minesport_Block_" + block_id.replace(":", "_").replace("/", "_")
    mesh = bpy.data.meshes.new(name + "_Mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.update()

    material_index = {}
    for material_name in material_names:
        if material_name not in material_index:
            material_index[material_name] = len(mesh.materials)
            mesh.materials.append(flatter._material(material_name))

    layer = mesh.uv_layers.new(name="UVMap")
    for polygon_index, polygon in enumerate(mesh.polygons):
        polygon.material_index = material_index[material_names[polygon_index]]
        for loop_index, uv_value in zip(polygon.loop_indices, uvs[polygon_index]):
            layer.data[loop_index].uv = (
                float(uv_value[0]),
                1.0 - float(uv_value[1]),
            )

    obj = bpy.data.objects.new(name, mesh)
    collection = (
        parent.users_collection[0]
        if parent.users_collection
        else bpy.context.scene.collection
    )
    collection.objects.link(obj)
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    obj.location = Vector(flatter._mc_to_blender(xyz, center))
    obj["minesport_type"] = flatter._TYPE_MATERIALIZED
    obj["minesport_flatter_parent"] = str(
        parent.get("minesport_flatter_id") or parent.name
    )
    obj["minesport_flatter_palette"] = int(palette_index)
    obj["minesport_flatter_xyz"] = json.dumps(list(xyz))
    obj["minesport_source_block"] = block_id
    if hasattr(obj, "minesport"):
        obj.minesport.translated = True
        obj.minesport.source_block = block_id
    return obj


def _attach_flatter_metadata(metadata, objects=None, asset_path=None):
    _ORIGINAL_ATTACH(metadata, objects=objects, asset_path=asset_path)
    if not isinstance(metadata, dict):
        return

    object_mode = str(metadata.get("objectMode") or "")
    records = metadata.get("flatterObjects")
    if not isinstance(records, list):
        return

    candidates = list(objects) if objects is not None else list(bpy.context.scene.objects)
    by_name = {obj.name: obj for obj in candidates}

    for record in records:
        if not isinstance(record, dict):
            continue
        mesh_name = str(record.get("meshObject") or record.get("id") or "")
        if not mesh_name:
            continue
        obj = by_name.get(mesh_name) or bpy.data.objects.get(mesh_name)
        if obj is None:
            obj = next(
                (item for item in candidates if item.name.startswith(mesh_name + ".")),
                None,
            )
        if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
            continue

        obj["minesport_display_type"] = "minesport_FLATTER_object"
        obj["minesport_object_mode"] = str(record.get("objectMode") or object_mode)
        payload = flatter._load_payload(obj)
        if payload is not None:
            payload["objectMode"] = obj["minesport_object_mode"]
            flatter._store_payload(obj, payload)
            _rebuild_flatter(obj, payload, flatter._decode_grid(payload))


def register():
    global _ORIGINAL_ATTACH, _ORIGINAL_REBUILD, _ORIGINAL_CREATE
    if _ORIGINAL_ATTACH is not None:
        return
    _ORIGINAL_ATTACH = flatter.attach_flatter_metadata
    _ORIGINAL_REBUILD = flatter._rebuild_flatter
    _ORIGINAL_CREATE = flatter._create_materialized_block
    flatter.attach_flatter_metadata = _attach_flatter_metadata
    flatter._rebuild_flatter = _rebuild_flatter
    flatter._create_materialized_block = _create_materialized_block


def unregister():
    global _ORIGINAL_ATTACH, _ORIGINAL_REBUILD, _ORIGINAL_CREATE
    if _ORIGINAL_ATTACH is None:
        return
    flatter.attach_flatter_metadata = _ORIGINAL_ATTACH
    flatter._rebuild_flatter = _ORIGINAL_REBUILD
    flatter._create_materialized_block = _ORIGINAL_CREATE
    _ORIGINAL_ATTACH = None
    _ORIGINAL_REBUILD = None
    _ORIGINAL_CREATE = None
