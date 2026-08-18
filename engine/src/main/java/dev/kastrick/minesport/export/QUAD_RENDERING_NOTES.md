# Quad rendering invariants

This document records the geometry invariants used by the exporter.

## Winding

`GeometryBuilder` currently constructs its six axis-aligned face quads in the opposite winding from the outward normals stored on the face definitions. The `Quad` exporter boundary therefore reverses the vertex order and derives the actual normal from the corrected world-space positions.

This matters for glTF/OBJ because the preview renderer deliberately disables face culling, while standard glTF materials are single-sided by default.

## UV orientation

Minecraft model UV rectangles are face-local. A single `[u1,v1,u2,v2]` ordering cannot be reused blindly for every face. `Quad.vertexUVs()` maps UVs according to the actual post-transform face direction so directional blocks (especially doors, trapdoors, and other facing-dependent models) do not get mirrored textures.

## Follow-up

Blockstate `uvlock` and model-specific non-axis-aligned element UV transforms should be handled by the upcoming geometry-template compiler rather than adding more ad-hoc logic to exporters.
