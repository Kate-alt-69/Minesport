from pathlib import Path

path = Path("build.ps1")
text = path.read_text(encoding="utf-8")
old = "$AppVersion = Get-DesktopPackageVersion\n$WorkerMinecraftVersion = '1.21.10'\n"
new = '''$AppVersion = Get-DesktopPackageVersion
$engineVersionFile = Join-Path $Root 'engine\\VERSION'
$EngineVersion = (Get-Content -LiteralPath $engineVersionFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($EngineVersion) -or $EngineVersion -notmatch '^[0-9A-Za-z._+-]+$') {
    throw "engine/VERSION contains an invalid engine version: $EngineVersion"
}
$WorkerMinecraftVersion = '1.21.10'
'''
if text.count(old) != 1:
    raise SystemExit(f"engine version source anchor count: {text.count(old)}")
text = text.replace(old, new, 1)
old = "Join-Path $engineProject 'build\\libs\\minesport-engine-0.2.1.jar'"
new = 'Join-Path $engineProject "build\\libs\\minesport-engine-$EngineVersion.jar"'
if text.count(old) != 1:
    raise SystemExit(f"engine fallback JAR anchor count: {text.count(old)}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
