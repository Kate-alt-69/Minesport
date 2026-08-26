from pathlib import Path
import re

ROOT = Path('.')
VERSION = '0.2.1'
MC_VERSION = '1.21.10'
LOADERS = ('fabric', 'forge', 'neoforge', 'quilt')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_required(path, old, new, count=None):
    text = read(path)
    found = text.count(old)
    if found == 0:
        raise SystemExit(f'{path}: missing expected text: {old[:120]!r}')
    if count is not None and found != count:
        raise SystemExit(f'{path}: expected {count} matches, found {found}: {old[:120]!r}')
    write(path, text.replace(old, new))


def regex_required(path, pattern, replacement, count=1, flags=0):
    text = read(path)
    updated, found = re.subn(pattern, replacement, text, count=count, flags=flags)
    if found != count:
        raise SystemExit(f'{path}: expected {count} regex matches, found {found}: {pattern!r}')
    write(path, updated)


def set_property(path, key, value):
    text = read(path)
    pattern = rf'(?m)^{re.escape(key)}=.*$'
    updated, found = re.subn(pattern, f'{key}={value}', text, count=1)
    if found != 1:
        raise SystemExit(f'{path}: property {key!r} missing')
    write(path, updated)


def ensure_versionless_archives(path):
    text = read(path)
    marker = "archiveVersion = ''"
    if marker in text:
        return
    block = "\n// 0.2.1: the Minecraft target is part of the worker filename; the Minesport\n// release version remains mod metadata and must not be appended to the JAR name.\ntasks.withType(org.gradle.api.tasks.bundling.AbstractArchiveTask).configureEach {\n    archiveVersion = ''\n}\n"
    write(path, text.rstrip() + '\n' + block)


def replace_tree(root, replacements, suffixes=None):
    base = ROOT / root
    if not base.exists():
        return
    suffixes = suffixes or {'.java', '.json', '.toml', '.gradle', '.properties', '.md', '.rs', '.yml', '.yaml', '.sh', '.ps1'}
    for path in base.rglob('*'):
        if not path.is_file() or path.suffix.lower() not in suffixes:
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            continue
        updated = text
        for old, new in replacements:
            updated = updated.replace(old, new)
        if updated != text:
            path.write_text(updated, encoding='utf-8')


# ---- Product version 0.2.1 -------------------------------------------------
replace_required('desktop/Cargo.toml', 'version = "0.2.0"', 'version = "0.2.1"', 1)
regex_required(
    'desktop/Cargo.lock',
    r'(\[\[package\]\]\nname = "minesport-desktop"\nversion = ")0\.2\.0(")',
    r'\g<1>0.2.1\g<2>',
)
replace_required('desktop/src/app.rs', 'const VERSION: &str = "0.2.0";', 'const VERSION: &str = "0.2.1";', 1)
replace_required('engine/build.gradle', "version = '0.2.0'", "version = '0.2.1'", 1)

active_version_files = [
    'build.ps1', 'build.sh',
    'installer/windows/minesport.nsi',
    'installer/windows/minesport.iss',
    'installer/windows/Product.wxs',
    'installer/linux/build-deb.sh',
    'installer/linux/build-appimage.sh',
    'desktop/src/bridge_cli.rs',
    'desktop/src/toolchain.rs',
    'desktop/ui/main.slint',
    'engine/src/main/java/dev/kastrick/minesport/IpcMode.java',
    'engine/src/main/java/dev/kastrick/minesport/export/LitematicExporter.java',
]
for path in active_version_files:
    p = ROOT / path
    if p.exists():
        text = p.read_text(encoding='utf-8')
        text = text.replace('0.2.0', '0.2.1')
        p.write_text(text, encoding='utf-8')

# ---- Export Worker Gradle artifacts ----------------------------------------
for loader in LOADERS:
    props = f'minesport-bridge-{loader}/gradle.properties'
    set_property(props, 'mod_version', VERSION)
    set_property(props, 'archives_base_name', f'minesport_export_worker-{loader}-{MC_VERSION}')
    ensure_versionless_archives(f'minesport-bridge-{loader}/build.gradle')

# NeoForge exposes name/id via Gradle properties. Keep the legacy mod id for the
# 0.2.1 compatibility cycle, but migrate the visible component name.
set_property('minesport-bridge-neoforge/gradle.properties', 'mod_name', 'Minesport Export Worker')

# Worker-facing metadata gets the new name while Java package/class names stay
# compatible with the existing version recipe system for now.
metadata_files = [
    'minesport-bridge-fabric/src/main/resources/fabric.mod.json',
    'minesport-bridge-quilt/src/main/resources/fabric.mod.json',
    'minesport-bridge-forge/src/main/resources/META-INF/mods.toml',
    'minesport-bridge-neoforge/src/main/resources/META-INF/neoforge.mods.toml',
]
for path in metadata_files:
    p = ROOT / path
    if not p.exists():
        continue
    text = p.read_text(encoding='utf-8')
    text = text.replace('Minesport Bridge', 'Minesport Export Worker')
    text = text.replace('runtime-registry bridge', 'runtime-model export worker')
    text = text.replace('runtime registry bridge', 'runtime model export worker')
    text = text.replace('version="0.2.0"', 'version="0.2.1"')
    p.write_text(text, encoding='utf-8')

# Canonical + compatibility Java workers move to the new runtime env/log names.
# Internal package/class names are retained temporarily so old version patches do
# not all have to be rewritten in the same commit.
worker_roots = [
    'minesport-bridge-fabric',
    'minesport-bridge-forge',
    'minesport-bridge-neoforge',
    'minesport-bridge-quilt',
    'minesport-bridge-fabric-versions',
]
for root in worker_roots:
    replace_tree(root, [
        ('MINESPORT_BRIDGE_WORKER', 'MINESPORT_EXPORT_WORKER'),
        ('MINESPORT_BRIDGE_MODE', 'MINESPORT_EXPORT_WORKER_MODE'),
        ('MINESPORT_BRIDGE_NS', 'MINESPORT_EXPORT_WORKER_NS'),
        ('MINESPORT_BRIDGE_PORT', 'MINESPORT_EXPORT_WORKER_PORT'),
        ('[MinesportBridge]', '[MinesportExportWorker]'),
        ('Minesport Bridge', 'Minesport Export Worker'),
    ])

# Worker exits as soon as the terminal packet is flushed; Rust already owns the
# receiver and validates/commits the snapshot independently.
for root in worker_roots:
    base = ROOT / root
    if not base.exists():
        continue
    for path in base.rglob('MinesportBridge.java'):
        text = path.read_text(encoding='utf-8')
        text = re.sub(r'\n\s*Thread\.sleep\(250\);', '', text)
        path.write_text(text, encoding='utf-8')

# Compatibility-generated Fabric workers get target-version filenames as well.
for path in (ROOT / 'minesport-bridge-fabric-versions').rglob('*.gradle'):
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    if 'archivesName' in text or 'archives_base_name' in text:
        marker = "archiveVersion = ''"
        if marker not in text:
            text = text.rstrip() + "\n\ntasks.withType(org.gradle.api.tasks.bundling.AbstractArchiveTask).configureEach {\n    archiveVersion = ''\n}\n"
            path.write_text(text, encoding='utf-8')

# Force every dynamically materialized Fabric target to carry its Minecraft
# version in the worker artifact name regardless of compatibility recipe.
compat = read('desktop/src/bridge_compat.rs')
needle = '''    let metadata = serde_json::json!({\n        "minecraft": version.clone(),'''
insert = '''    set_property(\n        &safe_join(workspace, Path::new("gradle.properties"))?,\n        "mod_version",\n        "0.2.1",\n    )?;\n    set_property(\n        &safe_join(workspace, Path::new("gradle.properties"))?,\n        "archives_base_name",\n        &format!("minesport_export_worker-fabric-{version}"),\n    )?;\n\n    let metadata = serde_json::json!({\n        "minecraft": version.clone(),'''
if needle not in compat:
    raise SystemExit('desktop/src/bridge_compat.rs: metadata insertion point missing')
write('desktop/src/bridge_compat.rs', compat.replace(needle, insert, 1))

# ---- Desktop embedding/staging contract ------------------------------------
replacements = [
    ('MINESPORT_BRIDGE_FABRIC_JAR', 'MINESPORT_EXPORT_WORKER_FABRIC_JAR'),
    ('MINESPORT_BRIDGE_FORGE_JAR', 'MINESPORT_EXPORT_WORKER_FORGE_JAR'),
    ('MINESPORT_BRIDGE_NEOFORGE_JAR', 'MINESPORT_EXPORT_WORKER_NEOFORGE_JAR'),
    ('MINESPORT_BRIDGE_QUILT_JAR', 'MINESPORT_EXPORT_WORKER_QUILT_JAR'),
    ('MINESPORT_BRIDGE_JAR', 'MINESPORT_EXPORT_WORKER_JAR'),
    ('MINESPORT_BRIDGE_WORKER', 'MINESPORT_EXPORT_WORKER'),
    ('MINESPORT_BRIDGE_MODE', 'MINESPORT_EXPORT_WORKER_MODE'),
    ('MINESPORT_BRIDGE_NS', 'MINESPORT_EXPORT_WORKER_NS'),
    ('MINESPORT_BRIDGE_PORT', 'MINESPORT_EXPORT_WORKER_PORT'),
    ('dist\\bundled-bridge', 'dist\\bundled-export-worker'),
    ('dist/bundled-bridge', 'dist/bundled-export-worker'),
    ('bundled-bridge', 'bundled-export-worker'),
    ('minesport-bridge-fabric-0.2.1.jar', 'minesport_export_worker-fabric-1.21.10.jar'),
    ('minesport-bridge-forge-0.2.1.jar', 'minesport_export_worker-forge-1.21.10.jar'),
    ('minesport-bridge-neoforge-0.2.1.jar', 'minesport_export_worker-neoforge-1.21.10.jar'),
    ('minesport-bridge-quilt-0.2.1.jar', 'minesport_export_worker-quilt-1.21.10.jar'),
    ('minesport-bridge-fabric-0.2.0.jar', 'minesport_export_worker-fabric-1.21.10.jar'),
    ('minesport-bridge-forge-0.2.0.jar', 'minesport_export_worker-forge-1.21.10.jar'),
    ('minesport-bridge-neoforge-0.2.0.jar', 'minesport_export_worker-neoforge-1.21.10.jar'),
    ('minesport-bridge-quilt-0.2.0.jar', 'minesport_export_worker-quilt-1.21.10.jar'),
    ('minesport-bridge-fabric.jar', 'minesport_export_worker-fabric.jar'),
    ('minesport-bridge-forge.jar', 'minesport_export_worker-forge.jar'),
    ('minesport-bridge-neoforge.jar', 'minesport_export_worker-neoforge.jar'),
    ('minesport-bridge-quilt.jar', 'minesport_export_worker-quilt.jar'),
]
for path in [
    'desktop/build.rs',
    'desktop/src/runtime.rs',
    'desktop/src/runtime_worker.rs',
    'desktop/src/registry.rs',
    'desktop/src/bridge_cli.rs',
    'build.ps1',
    'build.sh',
    '.github/workflows/build.yml',
    '.github/workflows/bridge-loaders.yml',
]:
    p = ROOT / path
    if not p.exists():
        continue
    text = p.read_text(encoding='utf-8')
    for old, new in replacements:
        text = text.replace(old, new)
    p.write_text(text, encoding='utf-8')

# Build script variable/UX cleanup without changing source project directory names.
ps = read('build.ps1')
ps = ps.replace("$BridgeVersion = '0.2.1'", "$WorkerMinecraftVersion = '1.21.10'")
ps = ps.replace('"minesport-bridge-$($bridge.Slug)-$BridgeVersion.jar"', '"minesport_export_worker-$($bridge.Slug)-$WorkerMinecraftVersion.jar"')
ps = ps.replace('loader Bridges', 'loader Export Workers')
ps = ps.replace('loader Bridge', 'loader Export Worker')
ps = ps.replace(' Bridge build failed.', ' Export Worker build failed.')
ps = ps.replace(' Bridge JAR was not produced', ' Export Worker JAR was not produced')
ps = ps.replace(' Bridge is empty', ' Export Worker is empty')
ps = ps.replace('Bundled Minecraft 1.21.10 loader Bridges', 'Bundled Minecraft 1.21.10 Export Workers')
ps = ps.replace('Reusing bundled Minecraft loader Bridges', 'Reusing bundled Minecraft Export Workers')
write('build.ps1', ps)

sh = read('build.sh')
sh = sh.replace('BRIDGE_VERSION="0.2.1"', 'WORKER_MC_VERSION="1.21.10"')
sh = sh.replace('minesport-bridge-fabric-${BRIDGE_VERSION}.jar', 'minesport_export_worker-fabric-${WORKER_MC_VERSION}.jar')
sh = sh.replace('minesport-bridge-forge-${BRIDGE_VERSION}.jar', 'minesport_export_worker-forge-${WORKER_MC_VERSION}.jar')
sh = sh.replace('minesport-bridge-neoforge-${BRIDGE_VERSION}.jar', 'minesport_export_worker-neoforge-${WORKER_MC_VERSION}.jar')
sh = sh.replace('minesport-bridge-quilt-${BRIDGE_VERSION}.jar', 'minesport_export_worker-quilt-${WORKER_MC_VERSION}.jar')
sh = sh.replace('loader Bridges', 'loader Export Workers')
sh = sh.replace('loader Bridge', 'loader Export Worker')
sh = sh.replace(' Bridge jar not found.', ' Export Worker jar not found.')
sh = sh.replace('Bundled Minecraft 1.21.10 loader Bridges', 'Bundled Minecraft 1.21.10 Export Workers')
write('build.sh', sh)

# build.rs internal names can now reflect the new runtime contract.
br = read('desktop/build.rs')
br = br.replace('BUNDLED_BRIDGES', 'BUNDLED_EXPORT_WORKERS')
br = br.replace('find_bridge_jar', 'find_export_worker_jar')
br = br.replace('FABRIC Bridge', 'Fabric Export Worker')
br = br.replace('Bridge JAR', 'Export Worker JAR')
write('desktop/build.rs', br)

# runtime.rs embeds/materializes the new names. Keep old public function names as
# compatibility wrappers for now; changing all callers/classes belongs to the
# later internal package rename, not the artifact migration.
rt = read('desktop/src/runtime.rs')
rt = rt.replace('FABRIC_BRIDGE_BYTES', 'FABRIC_EXPORT_WORKER_BYTES')
rt = rt.replace('FORGE_BRIDGE_BYTES', 'FORGE_EXPORT_WORKER_BYTES')
rt = rt.replace('NEOFORGE_BRIDGE_BYTES', 'NEOFORGE_EXPORT_WORKER_BYTES')
rt = rt.replace('QUILT_BRIDGE_BYTES', 'QUILT_EXPORT_WORKER_BYTES')
rt = rt.replace('Bridge when they asked for the single bundled Bridge', 'Export Worker when they asked for the legacy single bundled Bridge')
write('desktop/src/runtime.rs', rt)

# Update visible worker logs/diagnostics while leaving compatibility type names
# such as BridgeFamily and bridge_compat untouched for now.
for path in ['desktop/src/runtime_worker.rs', 'desktop/src/bridge_cli.rs']:
    p = ROOT / path
    if not p.exists():
        continue
    text = p.read_text(encoding='utf-8')
    text = text.replace('runtime Bridge', 'Export Worker')
    text = text.replace('Bridge loader', 'Export Worker loader')
    text = text.replace('Bridge worker', 'Export Worker')
    text = text.replace('Bridge source', 'Export Worker source')
    p.write_text(text, encoding='utf-8')

# Rename the loader validation workflow itself; the compatibility source folders
# keep their old names until the recipe migration is done.
old_workflow = ROOT / '.github/workflows/bridge-loaders.yml'
new_workflow = ROOT / '.github/workflows/export-worker-loaders.yml'
if old_workflow.exists():
    text = old_workflow.read_text(encoding='utf-8')
    text = text.replace('Bridge', 'Export Worker').replace('bridge', 'export-worker')
    new_workflow.write_text(text, encoding='utf-8')
    old_workflow.unlink()

print('Applied Minesport 0.2.1 Export Worker migration patch')
