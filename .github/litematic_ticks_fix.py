from pathlib import Path

path = Path("engine/src/main/java/dev/kastrick/minesport/IpcMode.java")
text = path.read_text()

replacements = [
    (
        '''allBlockTicks.removeIf(tick -> !insideEllipsoid(\n                    tick.x(), tick.y(), tick.z(),''',
        '''allBlockTicks.removeIf(tick -> !insideEllipsoidPoint(\n                    tick.x() + 0.5, tick.y() + 0.5, tick.z() + 0.5,'''
    ),
    (
        '''allFluidTicks.removeIf(tick -> !insideEllipsoid(\n                    tick.x(), tick.y(), tick.z(),''',
        '''allFluidTicks.removeIf(tick -> !insideEllipsoidPoint(\n                    tick.x() + 0.5, tick.y() + 0.5, tick.z() + 0.5,'''
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one tick bubble-filter match, found {count}: {old.splitlines()[0]}")
    text = text.replace(old, new, 1)

path.write_text(text)
