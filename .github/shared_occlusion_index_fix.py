from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# The IPC wrapper already owns the complete per-export neighbour map from
# ExportWorldContext. Base GeometryBuilder used to allocate and retain another
# HashMap with the same blocks whenever face culling was enabled. Expose a
# protected read-only-index hook so the wrapper can share that map instead.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/export/GeometryBuilder.java",
    '''    public void enableFaceCulling(List<BlockData> allBlocks) {
        ensureOcclusionIndex(allBlocks);
        faceCullingEnabled = true;
        fullFaceCache.clear();
        opaqueTextureCache.clear();
    }
''',
    '''    public void enableFaceCulling(List<BlockData> allBlocks) {
        ensureOcclusionIndex(allBlocks);
        enableFaceCullingWithIndex(occlusionIndex);
    }

    /**
     * Enable face culling against an already-built export spatial index.
     * Subclasses may share an immutable/read-only map; this class never mutates
     * the supplied index.
     */
    protected final void enableFaceCullingWithIndex(Map<Long,BlockData> index) {
        occlusionIndex = index == null ? Map.of() : index;
        faceCullingEnabled = true;
        fullFaceCache.clear();
        opaqueTextureCache.clear();
    }
''',
    "base shared occlusion hook",
)

replace_once(
    "engine/src/main/java/dev/kastrick/minesport/GeometryBuilder.java",
    '''    @Override
    public void enableFaceCulling(List<BlockData> allBlocks) {
        // The base class needs its own opaque-face occlusion index, but this
        // wrapper normally already owns the complete neighbour map published by
        // MultipartResolver. Do not build a second wrapper map for the same
        // hundreds of thousands of blocks.
        super.enableFaceCulling(allBlocks);
        ensureWorldIndex(allBlocks);
    }
''',
    '''    @Override
    public void enableFaceCulling(List<BlockData> allBlocks) {
        // MultipartResolver already published the complete export neighbour
        // map. Reuse it for both wrapper world rules and base opaque-face
        // culling instead of allocating a second full-world HashMap.
        ensureWorldIndex(allBlocks);
        enableFaceCullingWithIndex(worldIndex);
    }
''',
    "wrapper shared face-culling index",
)

print("Applied shared export/face-culling spatial index fix")
