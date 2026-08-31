from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


path = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''            File[] mcaFiles = regionDir.listFiles((directory, name) -> name.endsWith(".mca"));
            if (mcaFiles == null || mcaFiles.length == 0) {
                error("No .mca region files found");
                return;
            }''',
    '''            File[] regionFiles = regionDir.listFiles((directory, name) -> isRegionFileName(name));
            if (regionFiles == null || regionFiles.length == 0) {
                error("No region files (.mca/.mcr) found");
                return;
            }
            Arrays.sort(regionFiles, Comparator.comparing(File::getName));''',
    "listBlocks legacy region discovery",
)
text = text.replace("for (File mca : mcaFiles) {", "for (File regionFile : regionFiles) {")
text = text.replace("RegionReader.readRegion(\n                                mca,", "RegionReader.readRegion(\n                                regionFile,")
text = text.replace("RegionReader.readRegion(\n                    mca,", "RegionReader.readRegion(\n                    regionFile,")
if "mcaFiles" in text[text.index("private static void handleListBlocks"):text.index("private static ResolverChain buildPreviewResolverChain")]:
    raise SystemExit("listBlocks still contains stale mcaFiles references")
path.write_text(text, encoding="utf-8")
print("BUG-073: listBlocks now discovers both Anvil .mca and legacy .mcr regions")
