from pathlib import Path
import re
import subprocess

ROOT = Path('.')

# The primary migration intentionally keeps canonical source directories and
# Rust compatibility module names stable for 0.2.1. Repair any display-only
# workflow rename so it does not invent source paths that do not exist yet.
original = subprocess.check_output(
    ['git', 'show', 'HEAD:.github/workflows/bridge-loaders.yml'],
    text=True,
)
workflow = original
workflow = workflow.replace('name: Minesport Loader Bridges', 'name: Minesport Export Workers')
workflow = workflow.replace("      - '.github/workflows/bridge-loaders.yml'", "      - '.github/workflows/export-worker-loaders.yml'")
workflow = workflow.replace(' Bridge · ', ' Export Worker · ')
workflow = workflow.replace('Build canonical bridge', 'Build canonical Export Worker')
workflow = workflow.replace('Verify bridge JAR', 'Verify Export Worker JAR')
workflow = workflow.replace('Upload bridge JAR', 'Upload Export Worker JAR')
workflow = workflow.replace('Generate and compile 1.21.5 Bridge with Rust patcher', 'Generate and compile 1.21.5 Export Worker with Rust patcher')
workflow = workflow.replace('Upload 1.21.5 Bridge', 'Upload 1.21.5 Export Worker')
workflow = workflow.replace('name: minesport-bridge-${{ matrix.slug }}-1.21.10', 'name: minesport-export-worker-${{ matrix.slug }}-1.21.10')
workflow = workflow.replace('name: minesport-bridge-${{ matrix.slug }}-1.21.5', 'name: minesport-export-worker-${{ matrix.slug }}-1.21.5')
for loader in ('fabric', 'forge', 'neoforge', 'quilt'):
    workflow = workflow.replace(
        f'jar: minesport-bridge-{loader}-1.21.5.jar',
        f'jar: minesport_export_worker-{loader}-1.21.5.jar',
    )
Path('.github/workflows/export-worker-loaders.yml').write_text(workflow, encoding='utf-8')
Path('.github/workflows/bridge-loaders.yml').unlink(missing_ok=True)

# Compatibility helper output is part of the public artifact contract too.
prepare = Path('desktop/src/bin/bridge-prepare.rs')
text = prepare.read_text(encoding='utf-8')
text = text.replace('Minesport Rust Bridge prepare helper', 'Minesport Rust Export Worker prepare helper')
text = text.replace('Bridge family (default: fabric)', 'Export Worker loader (default: fabric)')
text = text.replace('compiled Bridge JAR', 'compiled Export Worker JAR')
text = text.replace('unsupported Bridge loader', 'unsupported Export Worker loader')
text = text.replace('copy compiled Bridge', 'copy compiled Export Worker')
text = text.replace('compiled compatibility Bridge is missing or empty', 'compiled compatibility Export Worker is missing or empty')
text = text.replace(
    '"minesport-bridge-{}-{}.jar",\n        family.label().to_ascii_lowercase(),\n        safe(version)',
    '"minesport_export_worker-{}-{}.jar",\n        family.label().to_ascii_lowercase(),\n        safe(version)',
)
text = text.replace('"minesport-bridge-fabric-1.21.5.jar"', '"minesport_export_worker-fabric-1.21.5.jar"')
text = text.replace('"minesport-bridge-forge-1.21.5.jar"', '"minesport_export_worker-forge-1.21.5.jar"')
text = text.replace('"minesport-bridge-neoforge-1.21.7.jar"', '"minesport_export_worker-neoforge-1.21.7.jar"')
text = text.replace('"minesport-bridge-quilt-1.21.6.jar"', '"minesport_export_worker-quilt-1.21.6.jar"')
prepare.write_text(text, encoding='utf-8')

# Main CLI cache filenames must carry the Minecraft target, not the Minesport
# release number. Keep legacy --build-bridge flags as aliases for this release;
# new Export Worker flags are added without breaking scripts.
cli = Path('desktop/src/bridge_cli.rs')
text = cli.read_text(encoding='utf-8')
text = text.replace(
    '[flag, version] if flag == "--build-bridge" => {',
    '[flag, version] if flag == "--build-export-worker" || flag == "--build-bridge" => {',
)
text = text.replace(
    '[flag, loader_flag, loader, version] if flag == "--build-bridge" && loader_flag == "--loader" => {',
    '[flag, loader_flag, loader, version] if (flag == "--build-export-worker" || flag == "--build-bridge") && loader_flag == "--loader" => {',
)
text = text.replace(
    '[flag, ..] if flag == "--build-bridge" => {',
    '[flag, ..] if flag == "--build-export-worker" || flag == "--build-bridge" => {',
)
text = text.replace(
    '[flag] if flag == "--build-bridges-detected" => {',
    '[flag] if flag == "--build-export-workers-detected" || flag == "--build-bridges-detected" => {',
)
text = text.replace(
    '[flag, ..] if flag == "--build-bridges-detected" => {',
    '[flag, ..] if flag == "--build-export-workers-detected" || flag == "--build-bridges-detected" => {',
)
text = text.replace('usage: minesport --build-bridge [--loader fabric|forge|neoforge|quilt] <minecraft-version>', 'usage: minesport --build-export-worker [--loader fabric|forge|neoforge|quilt] <minecraft-version>')
text = text.replace('usage: minesport --build-bridges-detected', 'usage: minesport --build-export-workers-detected')
text = text.replace('Bridge ready:', 'Export Worker ready:')
text = text.replace(' Bridge ready:', ' Export Worker ready:')
text = text.replace('cached Bridge reused', 'cached Export Worker reused')
text = text.replace('compatibility Bridge', 'compatibility Export Worker')
text = text.replace(' Bridge with ', ' Export Worker with ')
text = text.replace(' Bridge compiled', ' Export Worker compiled')
text = text.replace('Bridge preparation failed', 'Export Worker preparation failed')
text = text.replace('Bridge build(s) failed', 'Export Worker build(s) failed')
text = text.replace(
    '.join(format!("minesport-bridge-{}-0.2.1.jar", family.label().to_ascii_lowercase()))',
    '.join(format!("minesport_export_worker-{}-{}.jar", family.label().to_ascii_lowercase(), safe_version(version)))',
)
text = text.replace('minesport --build-bridge VERSION', 'minesport --build-export-worker VERSION')
text = text.replace('minesport --build-bridge --loader LOADER VERSION', 'minesport --build-export-worker --loader LOADER VERSION')
text = text.replace('minesport --build-bridges-detected', 'minesport --build-export-workers-detected')
text = text.replace('Prepare/cache the Fabric Bridge', 'Prepare/cache the Fabric Export Worker')
text = text.replace('Prepare/cache Fabric, Forge, NeoForge or Quilt Bridge', 'Prepare/cache Fabric, Forge, NeoForge or Quilt Export Worker')
text = text.replace('Prepare Bridges for detected mod-loader instances', 'Prepare Export Workers for detected mod-loader instances')
text = text.replace('Each loader family owns a canonical Minecraft 1.21.10 Bridge.', 'Each loader family owns a canonical Minecraft 1.21.10 Export Worker.')
cli.write_text(text, encoding='utf-8')

# New env vars are the contract; retain old env vars as read-only fallbacks for
# one release so external development scripts do not break immediately.
for path in [
    'desktop/build.rs',
    'minesport-bridge-fabric/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java',
    'minesport-bridge-forge/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java',
    'minesport-bridge-neoforge/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java',
    'minesport-bridge-quilt/src/main/java/dev/kastrick/minesport/bridge/socket/BridgeSender.java',
]:
    p = Path(path)
    if not p.exists():
        continue
    text = p.read_text(encoding='utf-8')
    if path == 'desktop/build.rs':
        # Build.rs already consumes the new names after the primary patch. The
        # build scripts export them; no extra mutation needed here.
        pass
    else:
        text = text.replace(
            'String envPort = System.getenv("MINESPORT_EXPORT_WORKER_PORT");',
            'String envPort = System.getenv("MINESPORT_EXPORT_WORKER_PORT");\n        if (envPort == null) envPort = System.getenv("MINESPORT_BRIDGE_PORT");',
        )
    p.write_text(text, encoding='utf-8')

print('Applied Export Worker migration corrections')
