from pathlib import Path

ROOT = Path('.')

RENAMES = {
    'desktop/src/bridge_build.rs': 'desktop/src/export_worker_build.rs',
    'desktop/src/bridge_cli.rs': 'desktop/src/export_worker_cli.rs',
    'desktop/src/bridge_compat.rs': 'desktop/src/export_worker_compat.rs',
    'desktop/src/bridge_family.rs': 'desktop/src/export_worker_family.rs',
    'desktop/src/bridge_java.rs': 'desktop/src/export_worker_java.rs',
    'desktop/src/bin/bridge-prepare.rs': 'desktop/src/bin/export-worker-prepare.rs',
    '.github/workflows/bridge-1.19.yml': '.github/workflows/export-worker-1.19.yml',
    '.github/workflows/bridge-1.20.yml': '.github/workflows/export-worker-1.20.yml',
}

RUST_REPLACEMENTS = [
    ('bridge_build', 'export_worker_build'),
    ('bridge_cli', 'export_worker_cli'),
    ('bridge_compat', 'export_worker_compat'),
    ('bridge_family', 'export_worker_family'),
    ('bridge_java', 'export_worker_java'),
    ('BridgeFamily', 'ExportWorkerFamily'),
    ('compile_bridge', 'compile_export_worker'),
    ('find_built_bridge', 'find_built_export_worker'),
    ('ensure_bridge', 'ensure_export_worker'),
    ('build_detected_bridges', 'build_detected_export_workers'),
    ('compiled_bridge_path', 'compiled_export_worker_path'),
]

RENAMED_RUST = [
    Path('desktop/src/export_worker_build.rs'),
    Path('desktop/src/export_worker_cli.rs'),
    Path('desktop/src/export_worker_compat.rs'),
    Path('desktop/src/export_worker_family.rs'),
    Path('desktop/src/export_worker_java.rs'),
    Path('desktop/src/bin/export-worker-prepare.rs'),
]


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f'missing required file: {path}')
    return path.read_text(encoding='utf-8')


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding='utf-8')


def required_replace(path: Path, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f'expected text not found in {path}: {old!r}')
    write(path, text.replace(old, new))


# Rename the active Rust identity and the two maintained compatibility workflows.
# Physical minesport-bridge-* source/recipe directories and the Java package path
# stay stable for 0.2.1; those are compatibility contracts, not runtime identity.
for old_name, new_name in RENAMES.items():
    old = Path(old_name)
    new = Path(new_name)
    if not old.is_file():
        raise SystemExit(f'rename source is missing: {old}')
    if new.exists():
        raise SystemExit(f'rename destination already exists: {new}')
    old.rename(new)

# Update Rust module/type/function references everywhere in the active desktop.
for path in [*Path('desktop/src').rglob('*.rs'), Path('desktop/build.rs')]:
    text = read(path)
    updated = text
    for old, new in RUST_REPLACEMENTS:
        updated = updated.replace(old, new)
    if updated != text:
        write(path, updated)

# The renamed modules should not describe themselves as Bridges anymore. Keep
# lowercase physical/cache paths and legacy CLI flag strings intact for this cycle.
for path in RENAMED_RUST:
    text = read(path)
    text = text.replace('bridge-prepare', 'export-worker-prepare')
    text = text.replace('Bridge', 'Export Worker')
    write(path, text)

# Finish build.rs internal identity without renaming persistent source/cache roots.
build_rs = Path('desktop/build.rs')
text = read(build_rs)
text = text.replace('stage_bridge_sources', 'stage_export_worker_sources')
text = text.replace('validate_bridge_recipes', 'validate_export_worker_recipes')
text = text.replace('bridge_staged', 'export_worker_staged')
text = text.replace('bridge_versions', 'export_worker_versions')
old_watch = 'println!("cargo:rerun-if-env-changed=MINESPORT_HEADLESS_BRIDGE_PREPARE");'
if old_watch not in text:
    raise SystemExit('legacy headless env watch was not found in desktop/build.rs')
text = text.replace(
    old_watch,
    'println!("cargo:rerun-if-env-changed=MINESPORT_HEADLESS_EXPORT_WORKER_PREPARE");\n    ' + old_watch,
)
old_headless = 'let headless_recipe = env::var_os("MINESPORT_HEADLESS_BRIDGE_PREPARE").is_some();'
if old_headless not in text:
    raise SystemExit('legacy headless env read was not found in desktop/build.rs')
text = text.replace(
    old_headless,
    'let headless_recipe = env::var_os("MINESPORT_HEADLESS_EXPORT_WORKER_PREPARE").is_some()\n'
    '        || env::var_os("MINESPORT_HEADLESS_BRIDGE_PREPARE").is_some();',
)
write(build_rs, text)

# New canonical runtime code should use the loader-specific Fabric materializer;
# keep runtime.rs's legacy alias available for compatibility callers.
cli = Path('desktop/src/export_worker_cli.rs')
text = read(cli).replace('runtime::materialize_bundled_bridge()', 'runtime::materialize_bundled_fabric_bridge()')
write(cli, text)

# Every maintained workflow consumes the new Rust helper/module names and env.
for path in Path('.github/workflows').glob('*.yml'):
    text = read(path)
    updated = text
    updated = updated.replace('desktop/src/bridge_build.rs', 'desktop/src/export_worker_build.rs')
    updated = updated.replace('desktop/src/bridge_cli.rs', 'desktop/src/export_worker_cli.rs')
    updated = updated.replace('desktop/src/bridge_compat.rs', 'desktop/src/export_worker_compat.rs')
    updated = updated.replace('desktop/src/bridge_family.rs', 'desktop/src/export_worker_family.rs')
    updated = updated.replace('desktop/src/bridge_java.rs', 'desktop/src/export_worker_java.rs')
    updated = updated.replace('desktop/src/bin/bridge-prepare.rs', 'desktop/src/bin/export-worker-prepare.rs')
    updated = updated.replace('--bin bridge-prepare', '--bin export-worker-prepare')
    updated = updated.replace('MINESPORT_HEADLESS_BRIDGE_PREPARE', 'MINESPORT_HEADLESS_EXPORT_WORKER_PREPARE')
    if updated != text:
        write(path, updated)

# 1.19/1.20 remain valuable compatibility matrices; rename them instead of deleting them.
for version, suffix in [('1.19', '1-19'), ('1.20', '1-20')]:
    path = Path(f'.github/workflows/export-worker-{version}.yml')
    text = read(path)
    text = text.replace(f'Minesport Fabric Bridge {version} Compatibility', f'Minesport Fabric Export Worker {version} Compatibility')
    text = text.replace(f".github/workflows/bridge-{version}.yml", f".github/workflows/export-worker-{version}.yml")
    text = text.replace(f'bridge-{suffix}', f'export-worker-{suffix}')
    text = text.replace('minesport-bridge-fabric-119-', 'minesport-export-worker-fabric-119-')
    text = text.replace('minesport-bridge-fabric-120-', 'minesport-export-worker-fabric-120-')
    text = text.replace('Generate and compile from canonical Fabric Bridge with Rust', 'Generate and compile from canonical Fabric Export Worker with Rust')
    text = text.replace('dist/recipe-bridges', 'dist/recipe-export-workers')
    text = text.replace(
        'dist/recipe-export-workers/minesport-bridge-fabric-${{ matrix.minecraft }}.jar',
        'dist/recipe-export-workers/minesport_export_worker-fabric-${{ matrix.minecraft }}.jar',
    )
    text = text.replace(
        'name: minesport-bridge-fabric-${{ matrix.minecraft }}',
        'name: minesport-export-worker-fabric-${{ matrix.minecraft }}',
    )
    text = text.replace('Fabric Bridge matrix result', 'Fabric Export Worker matrix result')
    write(path, text)

# Normal compile CI was still staging 0.2.0 Bridge artifacts. Align it with the
# production 0.2.1 Export Worker contract rather than letting the migration gate
# and release CI disagree with each other.
build_yml = Path('.github/workflows/build.yml')
text = read(build_yml)
text = text.replace('loader-bridges-bundled', 'loader-export-workers-bundled')
text = text.replace('loader-bridges-1.21.10', 'loader-export-workers-1.21.10')
text = text.replace('Bundled loader Bridges', 'Bundled loader Export Workers')
text = text.replace('Build canonical 1.21.10 loader Bridges', 'Build canonical 1.21.10 Export Workers')
text = text.replace('build_bridge', 'build_export_worker')
text = text.replace('${label} Bridge', '${label} Export Worker')
text = text.replace(
    'dist/bundled-export-worker/minesport-bridge-${slug}-0.2.0.jar',
    'dist/bundled-export-worker/minesport_export_worker-${slug}-1.21.10.jar',
)
text = text.replace('fabric-bridge-recipes', 'fabric-export-worker-recipes')
text = text.replace('compatibility Fabric Bridge with Rust', 'compatibility Fabric Export Worker with Rust')
text = text.replace('dist/recipe-bridges', 'dist/recipe-export-workers')
text = text.replace(
    'dist/recipe-export-workers/minesport-bridge-fabric-${{ matrix.minecraft }}.jar',
    'dist/recipe-export-workers/minesport_export_worker-fabric-${{ matrix.minecraft }}.jar',
)
text = text.replace(
    'name: minesport-bridge-fabric-${{ matrix.minecraft }}',
    'name: minesport-export-worker-fabric-${{ matrix.minecraft }}',
)
text = text.replace('0.2.0', '0.2.1')
text = text.replace('Fabric + Forge + NeoForge + Quilt Bridges', 'Fabric + Forge + NeoForge + Quilt Export Workers')
write(build_yml, text)

# Loader-family workflow already has the public Export Worker name; fix its
# helper/env/path references (generic pass above) and remove old wording.
loader_yml = Path('.github/workflows/export-worker-loaders.yml')
text = read(loader_yml)
text = text.replace('Bridge', 'Export Worker')
# Restore physical source-directory path fragments if the display-only pass ever
# touched one (it should not, because those are lowercase).
write(loader_yml, text)

# Packaging producers and comments must agree with the 0.2.1 release contract.
required_replace(Path('installer/windows/Minesport.wixproj'), '<OutputName>Minesport-0.2.0-x64</OutputName>', '<OutputName>Minesport-0.2.1-x64</OutputName>')
for name in [
    'installer/windows/minesport.nsi',
    'installer/windows/minesport.iss',
    'installer/windows/Product.wxs',
    'installer/linux/build-deb.sh',
    'installer/linux/build-appimage.sh',
]:
    path = Path(name)
    if not path.is_file():
        continue
    text = read(path)
    text = text.replace('Bridge JARs', 'Export Worker JARs')
    text = text.replace('runtime Bridges', 'runtime Export Workers')
    text = text.replace('loose Bridge copies', 'loose Export Worker copies')
    write(path, text)

# Fail closed on the identities this pass owns. Lowercase minesport-bridge-* paths,
# dev.kastrick.minesport.bridge, legacy CLI flags, old env fallback, and the
# persistent "bridge-build" cache root are intentionally allowed for 0.2.1.
for old, new in RENAMES.items():
    if Path(old).exists():
        raise SystemExit(f'old path still exists after rename: {old}')
    if not Path(new).exists():
        raise SystemExit(f'new path is missing after rename: {new}')

for path in Path('desktop/src').rglob('*.rs'):
    body = read(path)
    for token in ('bridge_build', 'bridge_cli', 'bridge_compat', 'bridge_family', 'bridge_java', 'BridgeFamily', 'compile_bridge', 'ensure_bridge', 'build_detected_bridges', 'compiled_bridge_path'):
        if token in body:
            raise SystemExit(f'legacy Rust Export Worker identity {token!r} remains in {path}')

for path in Path('.github/workflows').glob('*.yml'):
    body = read(path)
    for token in ('--bin bridge-prepare', 'MINESPORT_HEADLESS_BRIDGE_PREPARE', 'desktop/src/bridge_build.rs', 'desktop/src/bridge_compat.rs', 'desktop/src/bridge_family.rs', 'desktop/src/bridge_java.rs'):
        if token in body:
            raise SystemExit(f'legacy maintained-workflow identity {token!r} remains in {path}')

build_body = read(build_yml)
for token in ('Minesport-0.2.0', 'minesport-bridge-${slug}-0.2.0.jar', 'recipe-bridges', 'loader-bridges-1.21.10'):
    if token in build_body:
        raise SystemExit(f'stale normal CI contract {token!r} remains in build.yml')

if 'Minesport-0.2.1-x64' not in read(Path('installer/windows/Minesport.wixproj')):
    raise SystemExit('WiX output is not versioned as Minesport 0.2.1')

print('Applied Export Worker Rust/CI identity cleanup')
