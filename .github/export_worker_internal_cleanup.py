from pathlib import Path

ROOT = Path('.')

TEXT_SUFFIXES = {'.java', '.json', '.rs', '.md', '.toml', '.gradle', '.properties'}
SKIP_PARTS = {'.git', '.gradle', 'build', 'target', 'dist'}
WORKER_ROOTS = [
    Path('minesport-bridge-fabric'),
    Path('minesport-bridge-forge'),
    Path('minesport-bridge-neoforge'),
    Path('minesport-bridge-quilt'),
    Path('minesport-bridge-fabric-versions'),
    Path('minesport-bridge-forge-versions'),
    Path('minesport-bridge-neoforge-versions'),
    Path('minesport-bridge-quilt-versions'),
]
IDENTIFIERS = [
    ('MinesportBridge', 'MinesportExportWorker'),
    ('BridgeProtocol', 'ExportWorkerProtocol'),
    ('BridgeSender', 'ExportWorkerSender'),
]


def editable(path: Path) -> bool:
    return path.is_file() and path.suffix.lower() in TEXT_SUFFIXES and not any(part in SKIP_PARTS for part in path.parts)


def replace_identifiers(path: Path) -> None:
    text = path.read_text(encoding='utf-8')
    updated = text
    for old, new in IDENTIFIERS:
        updated = updated.replace(old, new)
    updated = updated.replace(
        'Wire format sent from bridge mod → Minesport engine over local socket.',
        'Wire format sent from the Minesport Export Worker → Minesport engine over local socket.',
    )
    if updated != text:
        path.write_text(updated, encoding='utf-8')


# Rename the Java identity everywhere that the compatibility recipe system can
# address it. Package paths deliberately stay dev.kastrick.minesport.bridge for
# 0.2.1 so this is not also an on-disk/package compatibility migration.
for root in WORKER_ROOTS:
    if not root.exists():
        continue
    for path in list(root.rglob('*')):
        if editable(path):
            replace_identifiers(path)

# Rust embeds canonical Java files by exact path and compatibility manifests by
# exact filenames, so update those references too without renaming Rust modules.
for path in Path('desktop/src').rglob('*.rs'):
    if editable(path):
        replace_identifiers(path)

# Compatibility helpers have composite names too (for example
# BridgeProtocolAnimated.java). Rename any Java filename containing one of the
# migrated identifiers instead of only three exact canonical filenames.
for root in WORKER_ROOTS:
    if not root.exists():
        continue
    java_files = [path for path in root.rglob('*.java') if path.is_file()]
    for old_path in java_files:
        new_name = old_path.name
        for old, new in IDENTIFIERS:
            new_name = new_name.replace(old, new)
        if new_name == old_path.name:
            continue
        new_path = old_path.with_name(new_name)
        if new_path.exists():
            raise SystemExit(f'rename collision: {new_path}')
        old_path.rename(new_path)

# Finish the 0.2.1 runtime materialization contract. The embedded engine version
# and temporary worker filenames must agree with the artifacts that produced them.
runtime = Path('desktop/src/runtime.rs')
text = runtime.read_text(encoding='utf-8')
text = text.replace('minesport-engine-0.2.0.jar', 'minesport-engine-0.2.1.jar')
text = text.replace('.minesport-engine-0.2.0.tmp', '.minesport-engine-0.2.1.tmp')
for loader in ('fabric', 'forge', 'neoforge', 'quilt'):
    text = text.replace(
        f'.minesport-bridge-{loader}-0.2.0.tmp',
        f'.minesport_export_worker-{loader}-1.21.10.tmp',
    )
runtime.write_text(text, encoding='utf-8')

# Fail closed if an active worker/recipe/Rust embedding path still names the old
# Java identities. Package/folder names containing lowercase "bridge" are
# intentionally retained for this compatibility cycle.
legacy = tuple(old for old, _ in IDENTIFIERS)
for root in WORKER_ROOTS:
    if not root.exists():
        continue
    for path in root.rglob('*'):
        if not editable(path):
            continue
        body = path.read_text(encoding='utf-8')
        for token in legacy:
            if token in body or token in path.name:
                raise SystemExit(f'legacy worker identifier {token!r} remains in {path}')

for path in Path('desktop/src').rglob('*.rs'):
    if not editable(path):
        continue
    body = path.read_text(encoding='utf-8')
    for token in legacy:
        if token in body:
            raise SystemExit(f'legacy worker identifier {token!r} remains in {path}')

print('Applied Export Worker internal identity cleanup')
