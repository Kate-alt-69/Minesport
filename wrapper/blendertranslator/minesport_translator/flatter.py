import base64
import json
import math
import zlib

import bpy
from bpy.props import FloatProperty
from bpy_extras import view3d_utils
from mathutils import Vector


_DATA_KEY = "minesport_flatter_data"
_SELECTED_KEY = "minesport_flatter_selected_xyz"
_TYPE_FLATTER = "FLATTER"
_TYPE_MATERIALIZED = "MATERIALIZED_BLOCK"

_DIRECTIONS = ("north", "south", "east", "west", "up", "down")
_DELTA = {
    "north": (0, 0, -1),
    "south": (0, 0, 1),
    "east": (1, 0, 0),
    "west": (-1, 0, 0),
    "up": (0, 1, 0),
    "down": (0, -1, 0),
}


def attach_flatter_metadata(metadata, objects=None, asset_path=None):
    """Attach lossless FLATTER logical data to imported Blender objects.

    The compressed custom property is saved inside the .blend, so the original
    OBJ/glTF sidecar is not required after the scene has been imported.
    """
    if not isinstance(metadata, dict):
        return
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
            obj = next((item for item in candidates if item.name.startswith(mesh_name + ".")), None)
        if obj is None or obj.type != "MESH":
            print(f"[Minesport FLATTER] mesh object not found: {mesh_name}")
            continue

        payload = dict(record)
        if asset_path:
            payload["sourceAsset"] = str(asset_path)
        _store_payload(obj, payload)

        obj["minesport_type"] = _TYPE_FLATTER
        obj["minesport_flatter_id"] = str(record.get("id", mesh_name))
        obj["minesport_flatter_schema"] = int(metadata.get("flatterSchema", 1))
        obj["minesport_flatter_block_count"] = int(record.get("blockCount", 0))
        if hasattr(obj, "minesport"):
            obj.minesport.translated = True
            obj.minesport.is_flatter = True
            obj.minesport.flatter_id = str(record.get("id", mesh_name))
            obj.minesport.flatter_block_count = int(record.get("blockCount", 0))


def materialize_radius(center_xyz, radius, object_id=None):
    """Materialize logical FLATTER blocks within a Minecraft-space radius.

    Public API for future Minesport animation helpers (TNT/explosions, moving
    blocks, physics, etc.). Each affected FLATTER cell is rebuilt once after
    all requested blocks have been materialized.
    """
    if not isinstance(center_xyz, (list, tuple)) or len(center_xyz) < 3:
        raise ValueError("center_xyz must contain Minecraft x, y, z")
    radius = max(0.0, float(radius))
    radius_sq = radius * radius
    created = []

    for obj in list(bpy.context.scene.objects):
        if obj.get("minesport_type") != _TYPE_FLATTER:
            continue
        if object_id and obj.get("minesport_flatter_id") != object_id:
            continue
        payload = _load_payload(obj)
        if payload is None:
            continue
        grid = _decode_grid(payload)
        selected = []
        cx, cy, cz = map(float, center_xyz[:3])
        for xyz in grid:
            dx = xyz[0] + 0.5 - cx
            dy = xyz[1] + 0.5 - cy
            dz = xyz[2] + 0.5 - cz
            if dx * dx + dy * dy + dz * dz <= radius_sq:
                selected.append(xyz)
        if selected:
            created.extend(_materialize_many(obj, payload, grid, selected))
    return created


def _store_payload(obj, payload):
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    obj[_DATA_KEY] = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")


def _load_payload(obj):
    value = obj.get(_DATA_KEY)
    if not isinstance(value, str) or not value:
        return None
    try:
        raw = zlib.decompress(base64.b64decode(value.encode("ascii")))
        parsed = json.loads(raw.decode("utf-8"))
        return parsed if isinstance(parsed, dict) else None
    except Exception as exc:
        print(f"[Minesport FLATTER] invalid embedded data on {obj.name}: {exc}")
        return None


def _decode_grid(payload):
    origin = _vec3i(payload.get("origin"), (0, 0, 0))
    size = _vec3i(payload.get("size"), (0, 0, 0))
    sx, sy, sz = size
    if sx <= 0 or sy <= 0 or sz <= 0:
        return {}

    grid = {}
    runs = payload.get("runs")
    if not isinstance(runs, list):
        return grid
    for run in runs:
        if not isinstance(run, (list, tuple)) or len(run) < 3:
            continue
        start, length, palette = int(run[0]), int(run[1]), int(run[2])
        for index in range(start, start + max(0, length)):
            x = index % sx
            rest = index // sx
            z = rest % sz
            y = rest // sz
            if 0 <= y < sy:
                grid[(origin[0] + x, origin[1] + y, origin[2] + z)] = palette
    return grid


def _encode_grid(obj, payload, grid):
    origin = _vec3i(payload.get("origin"), (0, 0, 0))
    size = _vec3i(payload.get("size"), (0, 0, 0))
    sx, sy, sz = size
    occupied = []
    for (x, y, z), palette in grid.items():
        rx, ry, rz = x - origin[0], y - origin[1], z - origin[2]
        if not (0 <= rx < sx and 0 <= ry < sy and 0 <= rz < sz):
            continue
        index = (ry * sz + rz) * sx + rx
        occupied.append((index, int(palette)))
    occupied.sort()

    runs = []
    start = previous = palette = None
    length = 0
    for index, value in occupied:
        if length and index == previous + 1 and value == palette:
            length += 1
            previous = index
            continue
        if length:
            runs.append([start, length, palette])
        start = previous = index
        palette = value
        length = 1
    if length:
        runs.append([start, length, palette])

    payload["runs"] = runs
    payload["blockCount"] = len(grid)
    _store_payload(obj, payload)
    obj["minesport_flatter_block_count"] = len(grid)
    if hasattr(obj, "minesport"):
        obj.minesport.flatter_block_count = len(grid)


def _palette(payload, index):
    palette = payload.get("palette")
    if not isinstance(palette, list) or index < 0 or index >= len(palette):
        return None
    value = palette[index]
    return value if isinstance(value, dict) else None


def _face_info(entry, direction):
    faces = entry.get("faces") if isinstance(entry, dict) else None
    value = faces.get(direction) if isinstance(faces, dict) else None
    return value if isinstance(value, dict) else None


def _rebuild_flatter(obj, payload=None, grid=None):
    payload = payload or _load_payload(obj)
    if payload is None:
        return False
    grid = grid if grid is not None else _decode_grid(payload)
    center = _vec3f(payload.get("center"), (0.0, 0.0, 0.0))

    grouped = {}
    for xyz, palette_index in grid.items():
        entry = _palette(payload, palette_index)
        if entry is None:
            continue
        x, y, z = xyz
        for direction in _DIRECTIONS:
            dx, dy, dz = _DELTA[direction]
            if (x + dx, y + dy, z + dz) in grid:
                continue
            face = _face_info(entry, direction)
            if face is None:
                continue
            local_vertices = _face_vertices(face)
            uv = _face_uv(face)
            if local_vertices is None or uv is None:
                continue
            edge1 = _axis_edge(_sub(local_vertices[1], local_vertices[0]))
            edge3 = _axis_edge(_sub(local_vertices[3], local_vertices[0]))
            if edge1 is None or edge3 is None or edge1[0] == edge3[0]:
                edge1, edge3 = _fallback_edges(direction)
            material = str(face.get("material") or "Minesport_Material")
            signature = (
                direction,
                material,
                tuple(round(value, 7) for pair in uv for value in pair),
                _plane(xyz, direction),
                edge1,
                edge3,
            )
            a = _axis_coord(xyz, edge1)
            b = _axis_coord(xyz, edge3)
            grouped.setdefault(signature, {})[(a, b)] = (xyz, face, local_vertices, uv)

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
                    (a0 + da, next_b) in face_grid and (a0 + da, next_b) not in used
                    for da in range(width)
                ):
                    height += 1
                else:
                    break
            for db in range(height):
                for da in range(width):
                    used.add((a0 + da, b0 + db))

            xyz, face, local_vertices, uv = face_grid[key]
            rendered.append(_expanded_face(
                xyz, face, local_vertices, uv, width, height, center
            ))

    _replace_mesh(obj, rendered)
    return True


def _expanded_face(xyz, face, local_vertices, uv, width, height, center):
    world = [
        (xyz[0] + v[0], xyz[1] + v[1], xyz[2] + v[2])
        for v in local_vertices
    ]
    e1 = _sub(world[1], world[0])
    e3 = _sub(world[3], world[0])
    p0 = world[0]
    p1 = _add(p0, _scale(e1, width))
    p3 = _add(p0, _scale(e3, height))
    p2 = _add(p1, _scale(e3, height))

    du1 = (uv[1][0] - uv[0][0], uv[1][1] - uv[0][1])
    du3 = (uv[3][0] - uv[0][0], uv[3][1] - uv[0][1])
    uv0 = uv[0]
    uv1 = (uv0[0] + du1[0] * width, uv0[1] + du1[1] * width)
    uv3 = (uv0[0] + du3[0] * height, uv0[1] + du3[1] * height)
    uv2 = (uv1[0] + du3[0] * height, uv1[1] + du3[1] * height)

    return {
        "vertices": [
            _mc_to_blender(p0, center),
            _mc_to_blender(p1, center),
            _mc_to_blender(p2, center),
            _mc_to_blender(p3, center),
        ],
        "uv": [uv0, uv1, uv2, uv3],
        "material": str(face.get("material") or "Minesport_Material"),
    }


def _replace_mesh(obj, rendered_faces):
    vertices = []
    faces = []
    uvs = []
    materials = []
    material_index = {}

    for rendered in rendered_faces:
        start = len(vertices)
        vertices.extend(rendered["vertices"])
        faces.append((start, start + 1, start + 2, start + 3))
        uvs.append(rendered["uv"])
        name = rendered["material"]
        if name not in material_index:
            material_index[name] = len(materials)
            materials.append(_material(name))

    mesh = bpy.data.meshes.new(obj.name + "_FLATTER_Mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    for material in materials:
        mesh.materials.append(material)

    layer = mesh.uv_layers.new(name="UVMap") if faces else None
    for polygon_index, polygon in enumerate(mesh.polygons):
        material_name = rendered_faces[polygon_index]["material"]
        polygon.material_index = material_index.get(material_name, 0)
        if layer is not None:
            face_uv = uvs[polygon_index]
            for loop_index, uv_value in zip(polygon.loop_indices, face_uv):
                layer.data[loop_index].uv = (float(uv_value[0]), 1.0 - float(uv_value[1]))

    old = obj.data
    obj.data = mesh
    if old is not None and old.users == 0:
        bpy.data.meshes.remove(old)


def _materialize_many(parent, payload, grid, coordinates):
    created = []
    for xyz in coordinates:
        palette_index = grid.get(tuple(xyz))
        if palette_index is None:
            continue
        entry = _palette(payload, palette_index)
        if entry is None:
            continue
        block = _create_materialized_block(parent, payload, tuple(xyz), palette_index, entry)
        if block is not None:
            created.append(block)
            grid.pop(tuple(xyz), None)

    _encode_grid(parent, payload, grid)
    _rebuild_flatter(parent, payload, grid)
    return created


def _create_materialized_block(parent, payload, xyz, palette_index, entry):
    center = _vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    vertices = []
    faces = []
    uvs = []
    material_names = []

    for direction in _DIRECTIONS:
        face = _face_info(entry, direction)
        if face is None:
            continue
        local = _face_vertices(face)
        face_uv = _face_uv(face)
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
            mesh.materials.append(_material(material_name))

    layer = mesh.uv_layers.new(name="UVMap")
    for polygon_index, polygon in enumerate(mesh.polygons):
        polygon.material_index = material_index[material_names[polygon_index]]
        for loop_index, uv_value in zip(polygon.loop_indices, uvs[polygon_index]):
            layer.data[loop_index].uv = (float(uv_value[0]), 1.0 - float(uv_value[1]))

    obj = bpy.data.objects.new(name, mesh)
    collection = parent.users_collection[0] if parent.users_collection else bpy.context.scene.collection
    collection.objects.link(obj)
    obj.location = Vector(_mc_to_blender(xyz, center))
    obj["minesport_type"] = _TYPE_MATERIALIZED
    obj["minesport_flatter_parent"] = str(parent.get("minesport_flatter_id") or parent.name)
    obj["minesport_flatter_palette"] = int(palette_index)
    obj["minesport_flatter_xyz"] = json.dumps(list(xyz))
    obj["minesport_source_block"] = block_id
    if hasattr(obj, "minesport"):
        obj.minesport.translated = True
        obj.minesport.source_block = block_id
    return obj


def _material(name):
    existing = bpy.data.materials.get(name)
    if existing is not None:
        return existing
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    return material


def _find_flatter_parent(identifier):
    if not identifier:
        return None
    for obj in bpy.context.scene.objects:
        if obj.get("minesport_type") != _TYPE_FLATTER:
            continue
        if obj.get("minesport_flatter_id") == identifier or obj.name == identifier:
            return obj
    return None


def _return_materialized(obj):
    identifier = obj.get("minesport_flatter_parent")
    parent = _find_flatter_parent(identifier)
    if parent is None:
        raise RuntimeError("FLATTER parent is missing from this .blend")
    if any(abs(float(value) - 1.0) > 1e-4 for value in obj.scale):
        raise RuntimeError("Apply/reset scale before returning this block to FLATTER")
    if any(abs(float(value)) > 1e-4 for value in obj.rotation_euler):
        raise RuntimeError("Reset rotation before returning this block to FLATTER")

    payload = _load_payload(parent)
    if payload is None:
        raise RuntimeError("FLATTER parent has no embedded logical data")
    center = _vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    location = obj.matrix_world.translation
    target = (
        int(round(location.x + center[0])),
        int(round(location.z + center[1])),
        int(round(-location.y + center[2])),
    )

    origin = _vec3i(payload.get("origin"), (0, 0, 0))
    size = _vec3i(payload.get("size"), (0, 0, 0))
    if not all(origin[i] <= target[i] < origin[i] + size[i] for i in range(3)):
        raise RuntimeError("Moved block is outside its original FLATTER cell; keep it materialized")

    grid = _decode_grid(payload)
    if target in grid:
        raise RuntimeError("Target Minecraft grid cell is already occupied")
    grid[target] = int(obj.get("minesport_flatter_palette", 0))
    _encode_grid(parent, payload, grid)
    _rebuild_flatter(parent, payload, grid)

    bpy.data.objects.remove(obj, do_unlink=True)
    return parent


def _pick_xyz(obj, location, normal):
    payload = _load_payload(obj)
    if payload is None:
        return None
    center = _vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    inside_world = location - normal * 1e-4
    local = obj.matrix_world.inverted() @ inside_world
    xyz = (
        math.floor(local.x + center[0]),
        math.floor(local.z + center[1]),
        math.floor(-local.y + center[2]),
    )
    return xyz if xyz in _decode_grid(payload) else None


class MINESPORT_OT_flatter_pick(bpy.types.Operator):
    bl_idname = "minesport.flatter_pick"
    bl_label = "Pick FLATTER Block"
    bl_description = "Click a logical Minecraft block inside FLATTER geometry"
    bl_options = {"REGISTER", "UNDO"}

    def invoke(self, context, event):
        if context.area is None or context.area.type != "VIEW_3D":
            self.report({"ERROR"}, "Run FLATTER picking from a 3D View")
            return {"CANCELLED"}
        context.window_manager.modal_handler_add(self)
        context.workspace.status_text_set("Minesport FLATTER: click a block · Esc to cancel")
        return {"RUNNING_MODAL"}

    def modal(self, context, event):
        if event.type in {"ESC", "RIGHTMOUSE"}:
            context.workspace.status_text_set(None)
            return {"CANCELLED"}
        if event.type != "LEFTMOUSE" or event.value != "PRESS":
            return {"RUNNING_MODAL"}

        region = context.region
        rv3d = context.space_data.region_3d
        coord = (event.mouse_region_x, event.mouse_region_y)
        origin = view3d_utils.region_2d_to_origin_3d(region, rv3d, coord)
        direction = view3d_utils.region_2d_to_vector_3d(region, rv3d, coord)
        hit, location, normal, _face, obj, _matrix = context.scene.ray_cast(
            context.view_layer.depsgraph, origin, direction
        )
        if not hit or obj is None or obj.get("minesport_type") != _TYPE_FLATTER:
            self.report({"WARNING"}, "That is not FLATTER geometry")
            return {"RUNNING_MODAL"}

        xyz = _pick_xyz(obj, location, normal)
        if xyz is None:
            self.report({"WARNING"}, "No logical block exists at that point")
            return {"RUNNING_MODAL"}

        obj[_SELECTED_KEY] = json.dumps(list(xyz))
        payload = _load_payload(obj)
        palette_index = _decode_grid(payload).get(xyz) if payload else None
        entry = _palette(payload, palette_index) if payload is not None and palette_index is not None else None
        block_id = str(entry.get("id")) if isinstance(entry, dict) else "minecraft:unknown"
        if hasattr(obj, "minesport"):
            obj.minesport.flatter_selected = f"{block_id} @ {xyz[0]}, {xyz[1]}, {xyz[2]}"
        bpy.ops.object.select_all(action="DESELECT")
        obj.select_set(True)
        context.view_layer.objects.active = obj
        context.workspace.status_text_set(None)
        self.report({"INFO"}, f"Selected {block_id} @ {xyz}")
        return {"FINISHED"}


class MINESPORT_OT_flatter_materialize_selected(bpy.types.Operator):
    bl_idname = "minesport.flatter_materialize_selected"
    bl_label = "Materialize Selected Block"
    bl_description = "Turn the picked logical block into a real Blender mesh object"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        parent = context.object
        if parent is None or parent.get("minesport_type") != _TYPE_FLATTER:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        try:
            xyz = tuple(json.loads(parent.get(_SELECTED_KEY, "")))
        except Exception:
            self.report({"ERROR"}, "Pick a logical block first")
            return {"CANCELLED"}
        payload = _load_payload(parent)
        grid = _decode_grid(payload) if payload else {}
        if xyz not in grid:
            self.report({"ERROR"}, "The picked logical block no longer exists")
            return {"CANCELLED"}
        created = _materialize_many(parent, payload, grid, [xyz])
        if created:
            bpy.ops.object.select_all(action="DESELECT")
            created[0].select_set(True)
            context.view_layer.objects.active = created[0]
            return {"FINISHED"}
        return {"CANCELLED"}


class MINESPORT_OT_flatter_materialize_radius(bpy.types.Operator):
    bl_idname = "minesport.flatter_materialize_radius"
    bl_label = "Materialize Radius"
    bl_description = "Materialize logical blocks around the picked block for physics/explosion work"
    bl_options = {"REGISTER", "UNDO"}

    radius: FloatProperty(name="Radius", default=2.5, min=0.0, soft_max=16.0)

    def execute(self, context):
        parent = context.object
        if parent is None or parent.get("minesport_type") != _TYPE_FLATTER:
            self.report({"ERROR"}, "Select a FLATTER object first")
            return {"CANCELLED"}
        try:
            xyz = tuple(json.loads(parent.get(_SELECTED_KEY, "")))
        except Exception:
            self.report({"ERROR"}, "Pick a logical block first")
            return {"CANCELLED"}
        created = materialize_radius(
            (xyz[0] + 0.5, xyz[1] + 0.5, xyz[2] + 0.5),
            self.radius,
            parent.get("minesport_flatter_id"),
        )
        self.report({"INFO"}, f"Materialized {len(created)} logical block(s)")
        return {"FINISHED"}


class MINESPORT_OT_flatter_rebuild(bpy.types.Operator):
    bl_idname = "minesport.flatter_rebuild"
    bl_label = "Rebuild FLATTER Cell"
    bl_description = "Regenerate disposable greedy render geometry from the embedded logical block grid"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        obj = context.object
        if obj is None or obj.get("minesport_type") != _TYPE_FLATTER:
            return {"CANCELLED"}
        if not _rebuild_flatter(obj):
            self.report({"ERROR"}, "FLATTER data could not be decoded")
            return {"CANCELLED"}
        return {"FINISHED"}


class MINESPORT_OT_flatter_return(bpy.types.Operator):
    bl_idname = "minesport.flatter_return"
    bl_label = "Return Block to FLATTER"
    bl_description = "Reinsert this materialized block into its logical FLATTER cell"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        obj = context.object
        if obj is None or obj.get("minesport_type") != _TYPE_MATERIALIZED:
            return {"CANCELLED"}
        try:
            parent = _return_materialized(obj)
        except Exception as exc:
            self.report({"ERROR"}, str(exc))
            return {"CANCELLED"}
        bpy.ops.object.select_all(action="DESELECT")
        parent.select_set(True)
        context.view_layer.objects.active = parent
        return {"FINISHED"}


class MINESPORT_PT_flatter_view(bpy.types.Panel):
    bl_label = "Minesport FLATTER"
    bl_idname = "MINESPORT_PT_flatter_view"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Minesport"

    @classmethod
    def poll(cls, context):
        obj = context.object
        return obj is not None and obj.get("minesport_type") in {
            _TYPE_FLATTER, _TYPE_MATERIALIZED
        }

    def draw(self, context):
        layout = self.layout
        obj = context.object
        kind = obj.get("minesport_type")

        if kind == _TYPE_FLATTER:
            count = int(obj.get("minesport_flatter_block_count", 0))
            layout.label(text=f"{count:,} logical blocks", icon="MESH_GRID")
            layout.operator(MINESPORT_OT_flatter_pick.bl_idname, icon="EYEDROPPER")
            selected = obj.get(_SELECTED_KEY)
            if selected:
                layout.operator(MINESPORT_OT_flatter_materialize_selected.bl_idname, icon="CUBE")
                layout.operator(MINESPORT_OT_flatter_materialize_radius.bl_idname, icon="FORCE_TURBULENCE")
            layout.operator(MINESPORT_OT_flatter_rebuild.bl_idname, icon="FILE_REFRESH")
            layout.label(text="Mesh = render cache. Logical grid = truth.", icon="INFO")
        else:
            layout.label(text=str(obj.get("minesport_source_block", "Minecraft block")), icon="CUBE")
            layout.operator(MINESPORT_OT_flatter_return.bl_idname, icon="IMPORT")


_CLASSES = (
    MINESPORT_OT_flatter_pick,
    MINESPORT_OT_flatter_materialize_selected,
    MINESPORT_OT_flatter_materialize_radius,
    MINESPORT_OT_flatter_rebuild,
    MINESPORT_OT_flatter_return,
    MINESPORT_PT_flatter_view,
)


def register():
    for cls in _CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(_CLASSES):
        bpy.utils.unregister_class(cls)


def _face_vertices(face):
    values = face.get("vertices")
    if not isinstance(values, list) or len(values) < 12:
        return None
    return [
        (float(values[i]), float(values[i + 1]), float(values[i + 2]))
        for i in range(0, 12, 3)
    ]


def _face_uv(face):
    values = face.get("uv")
    if not isinstance(values, list) or len(values) < 8:
        return None
    return [(float(values[i]), float(values[i + 1])) for i in range(0, 8, 2)]


def _axis_edge(vector):
    epsilon = 1e-5
    axis = None
    sign = 0
    for index, value in enumerate(vector):
        if abs(value) < epsilon:
            continue
        if axis is not None or abs(abs(value) - 1.0) > epsilon:
            return None
        axis = index
        sign = 1 if value > 0 else -1
    return None if axis is None else (axis, sign)


def _fallback_edges(direction):
    if direction == "up":
        return (2, 1), (0, 1)
    if direction == "down":
        return (0, 1), (2, 1)
    if direction == "south":
        return (1, 1), (0, -1)
    if direction == "east":
        return (1, 1), (2, 1)
    if direction == "west":
        return (1, 1), (2, -1)
    return (1, 1), (0, 1)


def _axis_coord(xyz, edge):
    return int(xyz[edge[0]]) * int(edge[1])


def _plane(xyz, direction):
    x, y, z = xyz
    if direction == "west":
        return x
    if direction == "east":
        return x + 1
    if direction == "down":
        return y
    if direction == "up":
        return y + 1
    if direction == "north":
        return z
    return z + 1


def _mc_to_blender(point, center):
    return (
        float(point[0]) - center[0],
        -(float(point[2]) - center[2]),
        float(point[1]) - center[1],
    )


def _vec3i(value, fallback):
    if isinstance(value, (list, tuple)) and len(value) >= 3:
        try:
            return int(value[0]), int(value[1]), int(value[2])
        except Exception:
            pass
    return fallback


def _vec3f(value, fallback):
    if isinstance(value, (list, tuple)) and len(value) >= 3:
        try:
            return float(value[0]), float(value[1]), float(value[2])
        except Exception:
            pass
    return fallback


def _sub(a, b):
    return a[0] - b[0], a[1] - b[1], a[2] - b[2]


def _add(a, b):
    return a[0] + b[0], a[1] + b[1], a[2] + b[2]


def _scale(a, factor):
    return a[0] * factor, a[1] * factor, a[2] * factor
