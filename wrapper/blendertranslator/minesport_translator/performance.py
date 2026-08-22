"""Responsiveness layer for Minesport's Blender runtime.

This module intentionally optimizes *bookkeeping*, not Minecraft semantics.  A
FLATTER cell remains lossless and editable, but repeated viewport/depsgraph work
is cached so an older CPU does not repeatedly decompress the same metadata,
decode the same RLE voxel grid, rebuild an unchanged mesh, or scan every object
in the scene on every frame.
"""

import base64
import json
import zlib

import bpy
from bpy.app.handlers import persistent
from mathutils import Vector

from . import edit_mode
from . import flatter
from . import flatter_overlay
from . import liquid_merge
from . import translate as translate_module
from . import viewport_interaction


# Large selections are still fully logical.  Only their viewport helper changes
# to one bounding cage so drawing the helper cannot become more expensive than
# the Minecraft geometry it is meant to help edit.
_MAX_DETAILED_SELECTION_OVERLAY = 2048

_PAYLOAD_CACHE = {}
_GRID_CACHE = {}
_WORLD_VERTEX_CACHE = {}
_FLATTER_OBJECTS = {}
_REVISIONS = {}

_ORIGINALS = {}
_REGISTERED = False


def _pointer(obj):
    try:
        return int(obj.as_pointer())
    except Exception:
        return 0


def _remember_flatter(obj):
    if obj is None:
        return
    try:
        if obj.get("minesport_type") != flatter._TYPE_FLATTER:
            return
        _FLATTER_OBJECTS[_pointer(obj)] = obj
    except (ReferenceError, RuntimeError):
        pass


def _forget_dead_flatters():
    for pointer, obj in list(_FLATTER_OBJECTS.items()):
        try:
            if obj.name not in bpy.data.objects or obj.get("minesport_type") != flatter._TYPE_FLATTER:
                _FLATTER_OBJECTS.pop(pointer, None)
        except (ReferenceError, RuntimeError):
            _FLATTER_OBJECTS.pop(pointer, None)


def _payload_stamp(obj):
    """Cheap mutation token; never hash the complete compressed FLATTER blob."""
    pointer = _pointer(obj)
    revision = _REVISIONS.get(pointer, 0)
    try:
        raw = obj.get(flatter._DATA_KEY, "")
    except (ReferenceError, RuntimeError):
        return pointer, revision, 0, "", ""
    if not isinstance(raw, str):
        return pointer, revision, 0, "", ""
    # The prefix/suffix catch external edits that did not pass through our store
    # helper without paying O(payload-size) hashing cost every viewport redraw.
    return pointer, revision, len(raw), raw[:20], raw[-20:]


def _fast_store_payload(obj, payload):
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    # Level 3 is dramatically cheaper during interactive edits than level 9 and
    # RLE-heavy FLATTER JSON still compresses very well.  The .blend remains
    # self-contained; this changes storage CPU cost only.
    encoded = base64.b64encode(zlib.compress(raw, 3)).decode("ascii")
    obj[flatter._DATA_KEY] = encoded

    pointer = _pointer(obj)
    _REVISIONS[pointer] = _REVISIONS.get(pointer, 0) + 1
    stamp = _payload_stamp(obj)
    _PAYLOAD_CACHE[pointer] = (stamp, payload)
    _GRID_CACHE.pop(id(payload), None)
    _WORLD_VERTEX_CACHE.pop(pointer, None)
    _remember_flatter(obj)


def _fast_load_payload(obj):
    pointer = _pointer(obj)
    stamp = _payload_stamp(obj)
    cached = _PAYLOAD_CACHE.get(pointer)
    if cached is not None and cached[0] == stamp:
        return cached[1]

    try:
        value = obj.get(flatter._DATA_KEY)
    except (ReferenceError, RuntimeError):
        return None
    if not isinstance(value, str) or not value:
        return None
    try:
        raw = zlib.decompress(base64.b64decode(value.encode("ascii")))
        parsed = json.loads(raw.decode("utf-8"))
        if not isinstance(parsed, dict):
            return None
    except Exception as exc:
        print(f"[Minesport FLATTER] invalid embedded data on {getattr(obj, 'name', '?')}: {exc}")
        return None

    _PAYLOAD_CACHE[pointer] = (stamp, parsed)
    _remember_flatter(obj)
    return parsed


def _grid_signature(payload):
    runs = payload.get("runs") if isinstance(payload, dict) else None
    origin = payload.get("origin") if isinstance(payload, dict) else None
    size = payload.get("size") if isinstance(payload, dict) else None
    try:
        origin_key = tuple(int(value) for value in origin[:3])
    except Exception:
        origin_key = (0, 0, 0)
    try:
        size_key = tuple(int(value) for value in size[:3])
    except Exception:
        size_key = (0, 0, 0)
    return id(runs), len(runs) if isinstance(runs, list) else -1, origin_key, size_key


def _fast_decode_grid(payload):
    if not isinstance(payload, dict):
        return {}
    signature = _grid_signature(payload)
    cached = _GRID_CACHE.get(id(payload))
    if cached is not None and cached[0] == signature:
        return cached[1]

    origin = flatter._vec3i(payload.get("origin"), (0, 0, 0))
    sx, sy, sz = flatter._vec3i(payload.get("size"), (0, 0, 0))
    if sx <= 0 or sy <= 0 or sz <= 0:
        grid = {}
        _GRID_CACHE[id(payload)] = (signature, grid)
        return grid

    grid = {}
    runs = payload.get("runs")
    if isinstance(runs, list):
        ox, oy, oz = origin
        for run in runs:
            if not isinstance(run, (list, tuple)) or len(run) < 3:
                continue
            try:
                start, length, palette = int(run[0]), int(run[1]), int(run[2])
            except Exception:
                continue
            end = start + max(0, length)
            for index in range(start, end):
                x = index % sx
                rest = index // sx
                z = rest % sz
                y = rest // sz
                if 0 <= y < sy:
                    grid[(ox + x, oy + y, oz + z)] = palette

    _GRID_CACHE[id(payload)] = (signature, grid)
    return grid


def _select_only(context, obj):
    """Avoid Blender's scene-wide Select All operator for one logical click."""
    for selected in tuple(getattr(context, "selected_objects", ()) or ()):
        if selected == obj:
            continue
        try:
            selected.select_set(False)
        except (ReferenceError, RuntimeError):
            pass
    try:
        obj.select_set(True)
        context.view_layer.objects.active = obj
    except (ReferenceError, RuntimeError):
        pass


def _fast_sync_control(obj):
    # Creating/assigning the Geometry Nodes modifier is relatively expensive and
    # can itself dirty the depsgraph.  Only ensure it when it is actually absent.
    modifier = obj.modifiers.get(liquid_merge.MODIFIER_NAME)
    socket_id = str(obj.get(liquid_merge.SOCKET_KEY, ""))
    if modifier is None or modifier.type != "NODES" or not socket_id:
        liquid_merge.ensure_control_node(obj)

    prop, node, modifier = liquid_merge._modifier_state(obj)
    last_prop = bool(obj.get(liquid_merge.LAST_PROP_KEY, prop))
    last_node = bool(obj.get(
        liquid_merge.LAST_NODE_KEY,
        node if node is not None else prop,
    ))
    prop_changed = prop != last_prop
    node_changed = node is not None and node != last_node

    if node_changed and not prop_changed:
        obj[liquid_merge.MERGE_KEY] = bool(node)
        prop = bool(node)
    elif prop_changed and node is not None:
        socket_id = str(obj.get(liquid_merge.SOCKET_KEY, ""))
        try:
            modifier[socket_id] = bool(prop)
            node = bool(prop)
        except Exception:
            pass
    elif node is not None and node != prop:
        socket_id = str(obj.get(liquid_merge.SOCKET_KEY, ""))
        try:
            modifier[socket_id] = bool(prop)
            node = bool(prop)
        except Exception:
            pass

    final_node = bool(node if node is not None else prop)
    if bool(obj.get(liquid_merge.LAST_PROP_KEY, not prop)) != bool(prop):
        obj[liquid_merge.LAST_PROP_KEY] = bool(prop)
    if bool(obj.get(liquid_merge.LAST_NODE_KEY, not final_node)) != final_node:
        obj[liquid_merge.LAST_NODE_KEY] = final_node
    return bool(prop)


def _fast_sync_proxies(parent, payload, full_grid, selected, merged):
    existing = liquid_merge._proxies(parent)
    for xyz, proxy in list(existing.items()):
        if xyz not in selected or xyz not in full_grid:
            bpy.data.objects.remove(proxy, do_unlink=True)
            existing.pop(xyz, None)

    for xyz in selected:
        palette_index = full_grid.get(xyz)
        if palette_index is None:
            continue
        proxy = existing.get(xyz)

        # While merged, selected voxels are still represented by the parent
        # FLATTER mesh.  Do not create thousands of empty Blender proxy objects
        # merely to remember a logical selection.
        if merged:
            if proxy is not None and getattr(proxy, "data", None) is not None:
                if len(proxy.data.polygons):
                    liquid_merge._clear_mesh(proxy)
            continue

        if proxy is None:
            proxy = liquid_merge._create_empty_proxy(parent, payload, xyz, palette_index)
            existing[xyz] = proxy
        if getattr(proxy, "data", None) is not None and len(proxy.data.polygons) == 0:
            liquid_merge._fill_proxy(parent, proxy, payload, xyz, palette_index)
        proxy.hide_viewport = False
        proxy.hide_render = False


def _fast_apply_liquid_state(obj, force=False):
    if obj is None or obj.get("minesport_type") != flatter._TYPE_FLATTER:
        return False
    pointer = _pointer(obj)
    if pointer in liquid_merge._APPLYING:
        return False

    merged = _fast_sync_control(obj)
    raw_selection = str(obj.get(liquid_merge.SELECTION_KEY, "[]"))
    had_last_state = liquid_merge.LAST_APPLIED_KEY in obj
    last_state = bool(obj.get(liquid_merge.LAST_APPLIED_KEY, not merged))
    last_selection = str(obj.get(liquid_merge.LAST_SELECTION_KEY, ""))

    # This is the critical fast path for Blender's very chatty depsgraph.  No
    # base64, zlib, JSON, RLE decode, object scan or mesh rebuild is needed when
    # the control and logical selection did not change.
    if not force and had_last_state and last_state == merged and last_selection == raw_selection:
        return False

    payload = flatter._load_payload(obj)
    if payload is None:
        return False
    full_grid = flatter._decode_grid(payload)
    selected = {xyz for xyz in liquid_merge._selection(obj) if xyz in full_grid}
    encoded = liquid_merge._selection_json(selected)
    if raw_selection != encoded:
        obj[liquid_merge.SELECTION_KEY] = encoded

    liquid_merge._APPLYING.add(pointer)
    try:
        _fast_sync_proxies(obj, payload, full_grid, selected, merged)

        # Selection-only edits while Liquid Merge is ON do not change visible
        # geometry, so rebuilding the entire FLATTER cell would be pure waste.
        # Turning ON after an OFF frame restores the complete grid; while OFF,
        # only selection/control changes require the cut-out parent mesh.
        rebuild = False
        render_grid = full_grid
        if not merged:
            rebuild = force or last_state != merged or last_selection != encoded
            render_grid = {
                xyz: palette for xyz, palette in full_grid.items()
                if xyz not in selected
            }
        elif not had_last_state or last_state is False:
            rebuild = True

        if rebuild:
            flatter._rebuild_flatter(obj, payload, render_grid)

        if not had_last_state or bool(obj.get(liquid_merge.LAST_APPLIED_KEY, not merged)) != bool(merged):
            obj[liquid_merge.LAST_APPLIED_KEY] = bool(merged)
        if str(obj.get(liquid_merge.LAST_SELECTION_KEY, "")) != encoded:
            obj[liquid_merge.LAST_SELECTION_KEY] = encoded
        if int(obj.get("minesport_liquid_selected_count", -1)) != len(selected):
            obj["minesport_liquid_selected_count"] = len(selected)
        if bool(obj.get("minesport_liquid_merge_active", not merged)) != bool(merged):
            obj["minesport_liquid_merge_active"] = bool(merged)
        liquid_merge._tag_redraw()
        return True
    finally:
        liquid_merge._APPLYING.discard(pointer)


def _fast_find_flatter_parent(identifier):
    if not identifier:
        return None
    for pointer, obj in list(_FLATTER_OBJECTS.items()):
        try:
            if obj.get("minesport_flatter_id") == identifier or obj.name == identifier:
                return obj
        except (ReferenceError, RuntimeError):
            _FLATTER_OBJECTS.pop(pointer, None)
    return _ORIGINALS["find_parent"](identifier)


def _tag_current_view_redraw(fallback):
    area = getattr(bpy.context, "area", None)
    if area is not None and area.type == "VIEW_3D":
        area.tag_redraw()
        return
    # Timers/frame handlers may not have an area context.  Preserve the original
    # multi-window fallback only for those uncommon paths.
    fallback()


def _fast_flatter_tag_redraw():
    _tag_current_view_redraw(_ORIGINALS["flatter_tag_redraw"])


def _fast_liquid_tag_redraw():
    _tag_current_view_redraw(_ORIGINALS["liquid_tag_redraw"])


def _matrix_key(matrix):
    try:
        return tuple(round(float(value), 6) for row in matrix for value in row)
    except Exception:
        return ()


def _fast_world_vertices(obj, local_vertices):
    # Selected-block helpers contain only 24 points and are cheaper to transform
    # directly.  Cache the large FULL logical-grid overlay, which can contain
    # tens of thousands of line vertices.
    if len(local_vertices) <= 48:
        matrix = obj.matrix_world
        return [tuple(matrix @ Vector(vertex)) for vertex in local_vertices]

    pointer = _pointer(obj)
    key = (id(local_vertices), len(local_vertices), _matrix_key(obj.matrix_world))
    cached = _WORLD_VERTEX_CACHE.get(pointer)
    if cached is not None and cached[0] == key:
        return cached[1]
    matrix = obj.matrix_world
    result = [tuple(matrix @ Vector(vertex)) for vertex in local_vertices]
    _WORLD_VERTEX_CACHE[pointer] = (key, result)
    return result


def _selection_bounds_edges(obj, coords):
    if not coords:
        return []
    xs = [xyz[0] for xyz in coords]
    ys = [xyz[1] for xyz in coords]
    zs = [xyz[2] for xyz in coords]
    x0, x1 = min(xs), max(xs) + 1
    y0, y1 = min(ys), max(ys) + 1
    z0, z1 = min(zs), max(zs) + 1
    corners = (
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
    )
    payload = flatter._load_payload(obj)
    if payload is None:
        return []
    center = flatter._vec3f(payload.get("center"), (0.0, 0.0, 0.0))
    matrix = obj.matrix_world
    result = []
    for a, b in liquid_merge._CUBE_EDGES:
        result.append(tuple(matrix @ Vector(flatter._mc_to_blender(corners[a], center))))
        result.append(tuple(matrix @ Vector(flatter._mc_to_blender(corners[b], center))))
    return result


def _fast_liquid_draw_overlay():
    obj = liquid_merge._active_flatter()
    if obj is None or not obj.select_get():
        return
    selected = liquid_merge._selection(obj)
    if selected:
        vertices = (
            liquid_merge._world_edges(obj, selected)
            if len(selected) <= _MAX_DETAILED_SELECTION_OVERLAY
            else _selection_bounds_edges(obj, selected)
        )
        liquid_merge._draw_lines(vertices, liquid_merge._SELECTION_GREEN, 2.0)
    anchor = liquid_merge._box_anchor(obj)
    if anchor is not None:
        liquid_merge._draw_lines(
            liquid_merge._world_edges(obj, {anchor}),
            liquid_merge._ANCHOR_GREEN,
            3.0,
        )


def _fast_direct_interaction(context, event, parent, xyz, normal=None):
    if parent is None or xyz is None:
        return False
    _select_only(context, parent)
    viewport_interaction._focus(parent, xyz, normal=normal)

    props = getattr(parent, "minesport", None)
    mode = str(getattr(props, "flatter_interaction_mode", "SELECT") or "SELECT")
    if mode == "INSPECT":
        return True
    if mode == "SELECT":
        liquid_merge._apply_click_selection(parent, xyz, event)
        return True
    if mode == "BOX":
        first = liquid_merge._box_anchor(parent)
        if first is None:
            liquid_merge._set_box_anchor(parent, xyz)
            try:
                context.workspace.status_text_set(
                    f"FLATTER Box Select · first corner {xyz} · click second corner"
                )
            except Exception:
                pass
            return True
        liquid_merge._apply_box_selection(parent, first, xyz, event)
        liquid_merge._set_box_anchor(parent, None)
        return True
    if mode == "MATERIALIZE":
        payload = flatter._load_payload(parent)
        grid = flatter._decode_grid(payload) if payload else {}
        created = flatter._materialize_many(parent, payload, grid, [xyz])
        if created:
            _select_only(context, created[0])
        return bool(created)
    return True


def _fast_edit_materialize(parent, xyz, context):
    payload = flatter._load_payload(parent)
    grid = flatter._decode_grid(payload) if payload else {}
    created = flatter._materialize_many(parent, payload, grid, [xyz])
    if not created:
        return False
    _select_only(context, created[0])
    return True


def _fast_edit_interact(context, event, parent, xyz):
    props = getattr(parent, "minesport", None)
    mode = str(getattr(props, "flatter_interaction_mode", "SELECT") or "SELECT")
    viewport_interaction._focus(parent, xyz)
    _select_only(context, parent)

    if mode == "INSPECT":
        return True
    if mode == "SELECT":
        liquid_merge._apply_click_selection(parent, xyz, event)
        return True
    if mode == "BOX":
        first = liquid_merge._box_anchor(parent)
        if first is None:
            liquid_merge._set_box_anchor(parent, xyz)
            try:
                context.workspace.status_text_set(
                    f"FLATTER Box Select · first corner {xyz} · click second corner"
                )
            except Exception:
                pass
            return True
        liquid_merge._apply_box_selection(parent, first, xyz, event)
        liquid_merge._set_box_anchor(parent, None)
        return True
    if mode == "MATERIALIZE":
        return _fast_edit_materialize(parent, xyz, context)
    return False


def _fast_configure_minecraft_materials(objects, asset_path=None):
    """Keep pixel/cutout semantics without forcing alpha on every material.

    Blender's glTF/OBJ importers already know which materials were alpha-masked.
    Preserve that intent instead of wiring image Alpha into every Principled BSDF,
    which needlessly puts opaque terrain into the transparent render path.
    """
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
            color_node = translate_module._base_color_image_node(material)
            if color_node is None:
                color_node = image_nodes[0] if image_nodes else nodes.new("ShaderNodeTexImage")
            translate_module._repair_material_image(material, color_node, asset_path)
            for node in image_nodes + ([color_node] if color_node not in image_nodes else []):
                node.interpolation = "Closest"
                node.extension = "REPEAT"

            bsdf = next(
                (node for node in nodes if node.bl_idname == "ShaderNodeBsdfPrincipled"),
                None,
            )
            alpha_linked = False
            if bsdf is not None:
                base_input = bsdf.inputs.get("Base Color")
                if base_input is not None and color_node.image is not None and not base_input.is_linked:
                    links.new(color_node.outputs["Color"], base_input)
                alpha_input = bsdf.inputs.get("Alpha")
                alpha_linked = bool(alpha_input is not None and alpha_input.is_linked)
                if alpha_input is not None and not alpha_linked:
                    alpha_input.default_value = 1.0

            material.alpha_threshold = 0.5
            if alpha_linked:
                if hasattr(material, "surface_render_method"):
                    material.surface_render_method = "DITHERED"
                elif hasattr(material, "blend_method"):
                    material.blend_method = "CLIP"


def _attach_and_remember(metadata, objects=None, asset_path=None):
    _ORIGINALS["attach"](metadata, objects=objects, asset_path=asset_path)
    candidates = list(objects) if objects is not None else list(bpy.context.scene.objects)
    for obj in candidates:
        _remember_flatter(obj)


@persistent
def _fast_frame_change(scene, _depsgraph=None):
    for pointer, obj in list(_FLATTER_OBJECTS.items()):
        try:
            if obj.get("minesport_type") != flatter._TYPE_FLATTER:
                _FLATTER_OBJECTS.pop(pointer, None)
                continue
            _fast_apply_liquid_state(obj, force=False)
        except (ReferenceError, RuntimeError):
            _FLATTER_OBJECTS.pop(pointer, None)
        except Exception as exc:
            print(f"[Minesport FLATTER] optimized frame update failed on {getattr(obj, 'name', '?')}: {exc}")


@persistent
def _fast_depsgraph_update(_scene, depsgraph):
    active = liquid_merge._active_flatter()
    active_updated = False
    try:
        updates = tuple(depsgraph.updates)
    except Exception:
        updates = ()
    for update in updates:
        target = getattr(update, "id", None)
        if not isinstance(target, bpy.types.Object):
            continue
        try:
            if target.get("minesport_type") == flatter._TYPE_FLATTER:
                _remember_flatter(target)
                if active is not None and target == active:
                    active_updated = True
        except (ReferenceError, RuntimeError):
            pass
    if active is None or not active_updated:
        return
    try:
        _fast_apply_liquid_state(active, force=False)
    except Exception as exc:
        print(f"[Minesport FLATTER] optimized live update failed on {active.name}: {exc}")


def register():
    global _REGISTERED
    if _REGISTERED:
        return
    _REGISTERED = True

    _ORIGINALS.update({
        "store_payload": flatter._store_payload,
        "load_payload": flatter._load_payload,
        "decode_grid": flatter._decode_grid,
        "find_parent": flatter._find_flatter_parent,
        "attach": flatter.attach_flatter_metadata,
        "liquid_sync_control": liquid_merge._sync_control,
        "liquid_sync_proxies": liquid_merge._sync_proxies,
        "liquid_apply": liquid_merge._apply_liquid_state,
        "liquid_draw": liquid_merge._draw_overlay,
        "liquid_tag_redraw": liquid_merge._tag_redraw,
        "flatter_stamp": flatter_overlay._payload_stamp,
        "flatter_world_vertices": flatter_overlay._world_vertices,
        "flatter_tag_redraw": flatter_overlay.tag_redraw,
        "direct_interaction": viewport_interaction._apply_direct_interaction,
        "edit_materialize": edit_mode._materialize,
        "edit_interact": edit_mode._interact,
        "materials": translate_module._configure_minecraft_materials,
    })

    flatter._store_payload = _fast_store_payload
    flatter._load_payload = _fast_load_payload
    flatter._decode_grid = _fast_decode_grid
    flatter._find_flatter_parent = _fast_find_flatter_parent
    flatter.attach_flatter_metadata = _attach_and_remember

    liquid_merge._sync_control = _fast_sync_control
    liquid_merge._sync_proxies = _fast_sync_proxies
    liquid_merge._apply_liquid_state = _fast_apply_liquid_state
    liquid_merge._draw_overlay = _fast_liquid_draw_overlay
    liquid_merge._tag_redraw = _fast_liquid_tag_redraw

    flatter_overlay._payload_stamp = _payload_stamp
    flatter_overlay._world_vertices = _fast_world_vertices
    flatter_overlay.tag_redraw = _fast_flatter_tag_redraw

    viewport_interaction._apply_direct_interaction = _fast_direct_interaction
    edit_mode._materialize = _fast_edit_materialize
    edit_mode._interact = _fast_edit_interact
    translate_module._configure_minecraft_materials = _fast_configure_minecraft_materials

    for obj in list(bpy.context.scene.objects):
        _remember_flatter(obj)

    # Replace the expensive handler objects already registered by liquid_merge.
    if liquid_merge._frame_change in bpy.app.handlers.frame_change_post:
        bpy.app.handlers.frame_change_post.remove(liquid_merge._frame_change)
    if liquid_merge._depsgraph_update in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.remove(liquid_merge._depsgraph_update)
    if _fast_frame_change not in bpy.app.handlers.frame_change_post:
        bpy.app.handlers.frame_change_post.append(_fast_frame_change)
    if _fast_depsgraph_update not in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.append(_fast_depsgraph_update)


def unregister():
    global _REGISTERED
    if not _REGISTERED:
        return

    if _fast_frame_change in bpy.app.handlers.frame_change_post:
        bpy.app.handlers.frame_change_post.remove(_fast_frame_change)
    if _fast_depsgraph_update in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.remove(_fast_depsgraph_update)
    if liquid_merge._frame_change not in bpy.app.handlers.frame_change_post:
        bpy.app.handlers.frame_change_post.append(liquid_merge._frame_change)
    if liquid_merge._depsgraph_update not in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.append(liquid_merge._depsgraph_update)

    for name, target in (
        ("store_payload", (flatter, "_store_payload")),
        ("load_payload", (flatter, "_load_payload")),
        ("decode_grid", (flatter, "_decode_grid")),
        ("find_parent", (flatter, "_find_flatter_parent")),
        ("attach", (flatter, "attach_flatter_metadata")),
        ("liquid_sync_control", (liquid_merge, "_sync_control")),
        ("liquid_sync_proxies", (liquid_merge, "_sync_proxies")),
        ("liquid_apply", (liquid_merge, "_apply_liquid_state")),
        ("liquid_draw", (liquid_merge, "_draw_overlay")),
        ("liquid_tag_redraw", (liquid_merge, "_tag_redraw")),
        ("flatter_stamp", (flatter_overlay, "_payload_stamp")),
        ("flatter_world_vertices", (flatter_overlay, "_world_vertices")),
        ("flatter_tag_redraw", (flatter_overlay, "tag_redraw")),
        ("direct_interaction", (viewport_interaction, "_apply_direct_interaction")),
        ("edit_materialize", (edit_mode, "_materialize")),
        ("edit_interact", (edit_mode, "_interact")),
        ("materials", (translate_module, "_configure_minecraft_materials")),
    ):
        original = _ORIGINALS.get(name)
        if original is not None:
            setattr(target[0], target[1], original)

    _PAYLOAD_CACHE.clear()
    _GRID_CACHE.clear()
    _WORLD_VERTEX_CACHE.clear()
    _FLATTER_OBJECTS.clear()
    _REVISIONS.clear()
    _ORIGINALS.clear()
    _REGISTERED = False
