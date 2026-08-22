#!/usr/bin/env pwsh
# Minesport 0.2.0 build + optional packaging script.

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppVersion = '0.2.0'
$BridgeVersion = '0.2.0'
$BuildNsisInstaller = $false
$BuildInnoInstaller = $false
$BuildMsiInstaller = $false
$DesktopOnly = $false

function Show-Help {
    @"
Minesport build script

Usage:
  .\build.ps1 [options]

Default behavior:
  Builds and tests the bundled Minecraft 1.21.10 Fabric bridge, Java engine,
  and the Rust + Slint Minesport desktop executable. The archived Go/Fyne UI
  is not part of the active build.
  No installer is built unless an installer flag is supplied.

Fast development:
  --desktop-only          Reuse the already-built engine + bundled Bridge and
                          run only Rust/Slint tests + release build. This is the
                          fast way to iterate on desktop/UI compiler errors.

Installer options (Windows):
  --build-installer       Build the default NSIS .exe installer
  --build-installer-all   Build the normal Windows formats (NSIS .exe + WiX .msi)
  --build-installer-exe   Build the default NSIS .exe installer
  --build-installer-nsis  Build the NSIS .exe installer
  --build-installer-inno  Build the optional Inno Setup .exe installer
  --build-installer-msi   Build the WiX 7 .msi installer (.NET SDK 6+ required)

Help:
  -h, --help              Show this help text

Requirements:
  Java/Gradle requirements are handled by the existing projects.
  Rust/Cargo is required for the desktop UI. The repository pins Rust 1.92.0
  in desktop/rust-toolchain.toml.
"@ | Write-Host
}

foreach ($arg in $args) {
    switch ($arg) {
        '-h' { Show-Help; exit 0 }
        '--help' { Show-Help; exit 0 }
        '--desktop-only' { $DesktopOnly = $true }
        '--build-installer' { $BuildNsisInstaller = $true }
        '--build-installer-all' { $BuildNsisInstaller = $true; $BuildMsiInstaller = $true }
        '--build-installer-exe' { $BuildNsisInstaller = $true }
        '--build-installer-nsis' { $BuildNsisInstaller = $true }
        '--build-installer-inno' { $BuildInnoInstaller = $true }
        '--build-installer-msi' { $BuildMsiInstaller = $true }
        '--build-installer-deb' { throw '--build-installer-deb is Linux-only; use build.sh on Linux.' }
        '--build-installer-appimage' { throw '--build-installer-appimage is Linux-only; use build.sh on Linux.' }
        default { throw "Unknown option: $arg`nRun .\build.ps1 --help for supported options." }
    }
}

Set-Location $Root
Write-Host '============================================' -ForegroundColor Cyan
Write-Host " Minesport $AppVersion Build Script" -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host 'Target: Windows / amd64' -ForegroundColor DarkGray
Write-Host 'Desktop: Rust + Slint 1.17.1' -ForegroundColor DarkGray
if ($DesktopOnly) {
    Write-Host 'Mode: desktop-only (reuse Bridge + engine artifacts)' -ForegroundColor DarkGray
}
if (-not $BuildNsisInstaller -and -not $BuildInnoInstaller -and -not $BuildMsiInstaller) {
    Write-Host 'Packaging: disabled (executable only)' -ForegroundColor DarkGray
} else {
    $formats = @()
    if ($BuildNsisInstaller) { $formats += 'NSIS EXE' }
    if ($BuildInnoInstaller) { $formats += 'Inno EXE' }
    if ($BuildMsiInstaller) { $formats += 'MSI' }
    Write-Host "Packaging: $($formats -join ' + ')" -ForegroundColor DarkGray
}
Write-Host ''

$bundledDir = Join-Path $Root 'dist\bundled-bridge'
$bundledBridge = Join-Path $bundledDir "minesport-bridge-$BridgeVersion.jar"
$engineJar = $null

if (-not $DesktopOnly) {
    Write-Host '[1/3] Building bundled Minecraft 1.21.10 Fabric bridge...' -ForegroundColor Yellow
    Push-Location (Join-Path $Root 'bridge')
    try {
        & .\gradlew.bat --no-daemon --stacktrace clean build
        if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed.' }
        $bridgeJar = Get-ChildItem -Path 'build\libs\*.jar' -File |
            Where-Object { $_.Name -notmatch 'sources' } |
            Select-Object -First 1
        if (-not $bridgeJar) { throw 'Bundled bridge JAR was not produced under bridge\build\libs.' }
        $bridgeJar = $bridgeJar.FullName
    } finally {
        Pop-Location
    }
    New-Item -ItemType Directory -Force -Path $bundledDir | Out-Null
    Copy-Item -Force $bridgeJar $bundledBridge
    Copy-Item -Force $bridgeJar (Join-Path $bundledDir 'minesport-bridge-0.1.0.jar')
    Write-Host "Bundled bridge staged: dist\bundled-bridge\minesport-bridge-$BridgeVersion.jar" -ForegroundColor Green
    Write-Host ''

    Write-Host '[2/3] Building Java engine...' -ForegroundColor Yellow
    Push-Location (Join-Path $Root 'engine')
    try {
        & .\gradlew.bat --no-daemon --stacktrace clean build
        if ($LASTEXITCODE -ne 0) { throw 'Java engine build failed.' }
        $engineJar = Get-ChildItem -Path 'build\libs\minesport-engine-*.jar' -File |
            Where-Object { $_.Name -notmatch 'sources' } |
            Select-Object -First 1
        if (-not $engineJar) { throw 'Java engine JAR was not produced.' }
        $engineJar = $engineJar.FullName
    } finally {
        Pop-Location
    }
    Write-Host "Java engine built: $engineJar" -ForegroundColor Green
    Write-Host ''
} else {
    Write-Host '[1/3] Reusing bundled Minecraft Bridge...' -ForegroundColor Yellow
    if (-not (Test-Path $bundledBridge)) {
        throw "--desktop-only requires $bundledBridge. Run a normal build once first."
    }
    Write-Host "Bundled bridge: $bundledBridge" -ForegroundColor Green
    Write-Host ''

    Write-Host '[2/3] Reusing Java engine...' -ForegroundColor Yellow
    $engineJar = Get-ChildItem -Path (Join-Path $Root 'engine\build\libs\minesport-engine-*.jar') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $engineJar) {
        throw '--desktop-only requires an existing engine/build/libs/minesport-engine-*.jar. Run a normal build once first.'
    }
    $engineJar = $engineJar.FullName
    Write-Host "Java engine: $engineJar" -ForegroundColor Green
    Write-Host ''
}

Write-Host '[3/3] Testing and building Rust + Slint desktop...' -ForegroundColor Yellow
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Rust/Cargo was not found. Install rustup from https://rustup.rs, reopen PowerShell, then run build.ps1 again.'
}
if (-not (Get-Command rustc -ErrorAction SilentlyContinue)) {
    throw 'rustc was not found even though Cargo is available. Repair the Rust toolchain with rustup.'
}

$previousEngineJar = $env:MINESPORT_ENGINE_JAR
$previousBridgeJar = $env:MINESPORT_BRIDGE_JAR
$env:MINESPORT_ENGINE_JAR = $engineJar
$env:MINESPORT_BRIDGE_JAR = $bundledBridge
try {
    Push-Location (Join-Path $Root 'desktop')
    try {
        Write-Host '  -> Rust toolchain...' -ForegroundColor DarkGray
        & rustc --version
        if ($LASTEXITCODE -ne 0) { throw 'Rust toolchain check failed.' }
        & cargo --version
        if ($LASTEXITCODE -ne 0) { throw 'Cargo toolchain check failed.' }

        Write-Host '  -> running Rust + Slint tests...' -ForegroundColor DarkGray
        & cargo test
        if ($LASTEXITCODE -ne 0) { throw 'Rust/Slint desktop tests failed.' }

        Write-Host '  -> building optimized Rust + Slint desktop...' -ForegroundColor DarkGray
        & cargo build --release
        if ($LASTEXITCODE -ne 0) { throw 'Rust/Slint desktop release build failed.' }

        $rustExe = Join-Path $Root 'desktop\target\release\minesport.exe'
        if (-not (Test-Path $rustExe)) {
            throw "Rust build completed but did not produce $rustExe"
        }

        $desktopDist = Join-Path $Root 'desktop\dist'
        New-Item -ItemType Directory -Force -Path $desktopDist | Out-Null
        Copy-Item -Force $rustExe (Join-Path $desktopDist 'minesport.exe')
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousEngineJar) { Remove-Item Env:MINESPORT_ENGINE_JAR -ErrorAction SilentlyContinue } else { $env:MINESPORT_ENGINE_JAR = $previousEngineJar }
    if ($null -eq $previousBridgeJar) { Remove-Item Env:MINESPORT_BRIDGE_JAR -ErrorAction SilentlyContinue } else { $env:MINESPORT_BRIDGE_JAR = $previousBridgeJar }
}

Write-Host 'Minesport built: desktop\dist\minesport.exe' -ForegroundColor Green
$sourceOut = Join-Path $Root 'dist\source'
New-Item -ItemType Directory -Force -Path $sourceOut | Out-Null
$standaloneExe = Join-Path $sourceOut 'Minesport.exe'
Copy-Item -Force (Join-Path $Root 'desktop\dist\minesport.exe') $standaloneExe
Write-Host 'Standalone executable staged: dist\source\Minesport.exe' -ForegroundColor Green

function Find-NSIS {
    $command = Get-Command makensis.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @('C:\Program Files (x86)\NSIS\makensis.exe', 'C:\Program Files\NSIS\makensis.exe')
    return $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

function Find-InnoSetup {
    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @(
        'C:\Program Files (x86)\Inno Setup 7\ISCC.exe',
        'C:\Program Files\Inno Setup 7\ISCC.exe',
        'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
        'C:\Program Files\Inno Setup 6\ISCC.exe'
    )
    return $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if ($BuildNsisInstaller -or $BuildInnoInstaller -or $BuildMsiInstaller) {
    $installerOut = Join-Path $Root 'dist\installer'
    New-Item -ItemType Directory -Force -Path $installerOut | Out-Null
    Write-Host ''
    Write-Host 'Packaging installers...' -ForegroundColor Yellow

    if ($BuildNsisInstaller) {
        $nsisOutput = Join-Path $installerOut "Minesport-$AppVersion-Setup-x64.exe"
        Remove-Item -Force -ErrorAction SilentlyContinue $nsisOutput
        $makensis = Find-NSIS
        if (-not $makensis) { throw 'NSIS was not found. Install NSIS or use --build-installer-msi instead.' }
        & $makensis '/V2' "/DSourceDir=$Root" (Join-Path $Root 'installer\windows\minesport.nsi')
        if ($LASTEXITCODE -ne 0) { throw 'NSIS compilation failed.' }
        if (-not (Test-Path $nsisOutput)) { throw "NSIS completed but did not produce $nsisOutput" }
        Write-Host "NSIS installer built fresh: dist\installer\Minesport-$AppVersion-Setup-x64.exe" -ForegroundColor Green
    }

    if ($BuildInnoInstaller) {
        $innoOutput = Join-Path $installerOut "Minesport-$AppVersion-Inno-Setup-x64.exe"
        Remove-Item -Force -ErrorAction SilentlyContinue $innoOutput
        $iscc = Find-InnoSetup
        if (-not $iscc) { throw 'Inno Setup 6/7 was not found. Install it or use the default NSIS installer.' }
        & $iscc "/DSourceDir=$Root" (Join-Path $Root 'installer\windows\minesport.iss')
        if ($LASTEXITCODE -ne 0) { throw 'Inno Setup compilation failed.' }
        if (-not (Test-Path $innoOutput)) { throw "Inno Setup completed but did not produce $innoOutput" }
        Write-Host "Inno installer built fresh: dist\installer\Minesport-$AppVersion-Inno-Setup-x64.exe" -ForegroundColor Green
    }

    if ($BuildMsiInstaller) {
        if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
            throw 'WiX 7 MSI builds require the .NET SDK 6 or newer. Install the .NET SDK and try again.'
        }
        $wixProject = Join-Path $Root 'installer\windows\Minesport.wixproj'
        $msiOutput = Join-Path $installerOut "Minesport-$AppVersion-x64.msi"
        Remove-Item -Force -ErrorAction SilentlyContinue $msiOutput
        & dotnet build $wixProject --configuration Release --output $installerOut --no-incremental "-p:SourceDir=$Root"
        if ($LASTEXITCODE -ne 0) { throw 'WiX 7 MSI build failed.' }
        if (-not (Test-Path $msiOutput)) { throw "WiX 7 completed but did not produce $msiOutput" }
        Write-Host "MSI installer built fresh with WiX 7: dist\installer\Minesport-$AppVersion-x64.msi" -ForegroundColor Green
    }
}

Write-Host ''
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ' Build complete!' -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ' desktop\dist\minesport.exe' -ForegroundColor Green
Write-Host ' dist\source\Minesport.exe' -ForegroundColor Green
if ($BuildNsisInstaller) { Write-Host " dist\installer\Minesport-$AppVersion-Setup-x64.exe" -ForegroundColor Green }
if ($BuildInnoInstaller) { Write-Host " dist\installer\Minesport-$AppVersion-Inno-Setup-x64.exe" -ForegroundColor Green }
if ($BuildMsiInstaller) { Write-Host " dist\installer\Minesport-$AppVersion-x64.msi" -ForegroundColor Green }
Write-Host ''
Write-Host 'Run .\build.ps1 --help to see build and packaging options.' -ForegroundColor Cyan
