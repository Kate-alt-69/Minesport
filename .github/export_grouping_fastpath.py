from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Object grouping is expensive on large selections: BlockGrouper builds a
# spatial index, resolves compounds and BFSes every connected component. The
# old exporters did that work for *every* export mode, then separately resolved
# compounds again. Only GROUPED needs BlockGrouper and only INDIVIDUAL needs the
# standalone compound map; ALL_MERGED needs neither.
old = '''        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);
        int progressBlocks = Math.max(blocks.size(), 1);
'''
new = '''        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = mode == ExportMode.GROUPED_BY_TYPE
            ? BlockGrouper.computeGroups(blocks)
            : Map.of();
        Map<BlockData,String> compoundIds = mode == ExportMode.INDIVIDUAL
            ? MultiBlockStructureResolver.resolve(blocks)
            : Map.of();
        int progressBlocks = Math.max(blocks.size(), 1);
'''
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/ObjExporter.java",
    old,
    new,
    "OBJ grouping fast path",
)

old_gltf = '''        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = BlockGrouper.computeGroups(blocks);
        Map<BlockData,String> compoundIds = MultiBlockStructureResolver.resolve(blocks);
        int progressBlocks = Math.max(blocks.size(), 1);
'''
new_gltf = '''        float[] center = BlockGrouper.boundingBoxCenter(blocks);
        Map<BlockData,String> groupedIds = mode == ObjExporter.ExportMode.GROUPED_BY_TYPE
            ? BlockGrouper.computeGroups(blocks)
            : Map.of();
        Map<BlockData,String> compoundIds = mode == ObjExporter.ExportMode.INDIVIDUAL
            ? MultiBlockStructureResolver.resolve(blocks)
            : Map.of();
        int progressBlocks = Math.max(blocks.size(), 1);
'''
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/GltfExporter.java",
    old_gltf,
    new_gltf,
    "glTF grouping fast path",
)

print("Applied export-mode grouping fast paths")
