# Multi-block structure model

Minesport treats multi-block structures as a world-level relationship, not as hard-coded special cases in the exporter.

The first pass recognizes complementary block-state values such as:

- `half=lower` ↔ `half=upper`
- `part=bottom` ↔ `part=top`
- `part=foot` ↔ `part=head`
- `section=first` ↔ `section=second`

A companion only exists when the expected neighboring block is actually present. A lone lower half therefore does not invent missing geometry.

Directional boolean properties such as `north=true` are also treated as explicit connection signals. This lets modded/custom blocks form connected structures without a list of mod IDs or block names.

The resolver returns a compound structure ID for connected members. Normal/grouped exports can use that ID to keep the structure together and separate from unrelated nearby blocks. Individual export mode still keeps every physical block as its own object.

This is intentionally only the relationship layer. Geometry remains asset-driven: the resolver can later compile the correct model/texture template for every member without special-casing `door`, `bed`, `chain`, or a particular mod.
