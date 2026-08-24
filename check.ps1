#!/usr/bin/env pwsh
param(
    [switch]$AllowMissingArtifacts
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgeVersion = '0.2.0'
$BundledDir = Join-Path $Root 'dist\bundled-bridge'

$BridgeSpecs = @(
    @{ Name = 'Fabric'; Env = 'MINESPORT_BRIDGE_FABRIC_JAR'; Path = (Join-Path $BundledDir "minesport-bridge-fabric-$BridgeVersion.jar") },
    @{ Name = 'Forge'; Env = 'MINESPORT_BRIDGE_FORGE_JAR'; Path = (Join-Path $BundledDir "minesport-bridge-forge-$BridgeVersion.jar") },
    @{ Name = 'NeoForge'; Env = 'MINESPORT_BRIDGE_NEOFORGE_JAR'; Path = (Join-Path $BundledDir "minesport-bridge-neoforge-$BridgeVersion.jar") },
    @{ Name = 'Quilt'; Env = 'MINESPORT_BRIDGE_QUILT_JAR'; Path = (Join-Path $BundledDir "minesport-bridge-quilt-$BridgeVersion.jar") }
)

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Rust/Cargo was not found. Install or repair rustup before running the fast check.'
}
if (-not (Get-Command rustc -ErrorAction SilentlyContinue)) {
    throw 'rustc was not found even though Cargo is available. Repair the Rust toolchain with rustup.'
}

$EngineJar = Get-ChildItem -Path (Join-Path $Root 'engine\build\libs\minesport-engine-*.jar') -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

$Missing = @()
if (-not $EngineJar) {
    $Missing += 'engine\build\libs\minesport-engine-*.jar'
}
foreach ($Bridge in $BridgeSpecs) {
    if (-not (Test-Path $Bridge.Path)) {
        $Missing += $Bridge.Path
    }
}

if ($Missing.Count -gt 0) {
    if ($AllowMissingArtifacts) {
        Write-Host '[FAST CHECK] skipped: reusable engine/Bridge artifacts do not exist yet.' -ForegroundColor DarkGray
        exit 0
    }
    $List = $Missing -join "`n  - "
    throw "Fast check needs previously built runtime artifacts, but these are missing:`n  - $List`nRun one normal build first. After that, check.bat never rebuilds Gradle projects."
}

$EngineJar = $EngineJar.FullName
$PreviousEngineJar = $env:MINESPORT_ENGINE_JAR
$PreviousLegacyBridgeJar = $env:MINESPORT_BRIDGE_JAR
$PreviousBridgeEnv = @{}
foreach ($Bridge in $BridgeSpecs) {
    $PreviousBridgeEnv[$Bridge.Env] = [Environment]::GetEnvironmentVariable($Bridge.Env)
}

$env:MINESPORT_ENGINE_JAR = $EngineJar
$env:MINESPORT_BRIDGE_JAR = $BridgeSpecs[0].Path
foreach ($Bridge in $BridgeSpecs) {
    [Environment]::SetEnvironmentVariable($Bridge.Env, $Bridge.Path)
}

try {
    Write-Host '============================================' -ForegroundColor Cyan
    Write-Host ' Minesport FAST ERROR CHECK' -ForegroundColor Cyan
    Write-Host '============================================' -ForegroundColor Cyan
    Write-Host 'No Minecraft/Forge/NeoForge/Quilt Gradle build.' -ForegroundColor DarkGray
    Write-Host 'Checking Rust + Slint + build.rs + -D warnings only.' -ForegroundColor DarkGray
    Write-Host ''

    Push-Location (Join-Path $Root 'desktop')
    try {
        & rustc --version
        & cargo --version
        Write-Host ''
        Write-Host 'Running cargo check --bin minesport ...' -ForegroundColor Yellow
        & cargo check --bin minesport
        if ($LASTEXITCODE -ne 0) {
            throw 'FAST ERROR CHECK FAILED. Fix the error above before running the full build.'
        }
    } finally {
        Pop-Location
    }

    Write-Host ''
    Write-Host 'FAST CHECK PASSED - safe to start the expensive loader builds.' -ForegroundColor Green
} finally {
    if ($null -eq $PreviousEngineJar) {
        Remove-Item Env:MINESPORT_ENGINE_JAR -ErrorAction SilentlyContinue
    } else {
        $env:MINESPORT_ENGINE_JAR = $PreviousEngineJar
    }
    if ($null -eq $PreviousLegacyBridgeJar) {
        Remove-Item Env:MINESPORT_BRIDGE_JAR -ErrorAction SilentlyContinue
    } else {
        $env:MINESPORT_BRIDGE_JAR = $PreviousLegacyBridgeJar
    }
    foreach ($Bridge in $BridgeSpecs) {
        [Environment]::SetEnvironmentVariable($Bridge.Env, $PreviousBridgeEnv[$Bridge.Env])
    }
}
