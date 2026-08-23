# Minesport Blender Dynamic Translator

A Blender 4.3+ translation layer bundled with Minesport.

This is deliberately **not** an animation runtime. It does not register frame-change, timer, or dependency-graph loops. During import it reads Minesport metadata, creates ordinary Blender collections/bones/actions/material-node drivers, and returns control to Blender.

## Import paths

- **glTF:** Blender's glTF importer discovers `glTF2ImportUserExtension` and runs the translator automatically after imported nodes exist.
- **OBJ:** use **File → Import → Minesport OBJ (.obj)**. Blender's native OBJ importer has no equivalent glTF user-extension hook, so the Minesport operator wraps the native importer and then translates the `.minesport.json` sidecar.

## Blender controls

Translated objects expose **Object Properties → Minesport**:

- Continuous Animation
- Animation Speed

These controls drive Blender-native nodes/drivers generated during translation. Minesport itself is not consulted during timeline playback.

## Metadata schema

The translator accepts schema `1` sidecars named `<export>.minesport.json` and generic `animations` descriptors. Initial descriptor kinds are:

- `rigid_bone` — creates a rigid armature bone/action from a supplied object, pivot, axis and keyframes.
- `texture_frames` — creates sprite-sheet UV animation driven by Blender's scene frame plus the object's Continuous Animation/Speed properties.

The descriptor format is intentionally renderer-agnostic so the Fabric bridge can populate it dynamically instead of the exporter hardcoding Minecraft block IDs.
