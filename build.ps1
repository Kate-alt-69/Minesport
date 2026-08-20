#!/usr/bin/env pwsh
# Minesport build + optional packaging script.

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildExeInstaller = $false
$BuildMsiInstaller = $false

function Show-Help {
    @"
Minesport build script

Usage:
  .\build.ps1 [options]

Default behavior:
  Builds the bundled 1.21.10 Fabric bridge, Java engine, and wrapper executable.
  No installer is built unless an installer flag is supplied.

Installer options (Windows):
  --build-installer       Build the default Windows installer (.exe)
  --build-installer-all   Build all Windows installer formats (.exe + .msi)
  --build-installer-exe   Build the Inno Setup .exe installer
  --build-installer-msi   Build the WiX .msi installer

Help:
  -h, --help              Show this help text

Linux equivalents are available through build.sh:
  --build-installer       .deb
  --build-installer-all   .deb + .AppImage
  --build-installer-deb
  --build-installer-appimage
"@ | Write-Host
}

foreach ($arg in $args) {
    switch ($arg) {
        '-h' { Show-Help; exit 0 }
        '--help' { Show-Help; exit 0 }
        '--build-installer' { $BuildExeInstaller = $true }
        '--build-installer-all' { $BuildExeInstaller = $true; $BuildMsiInstaller = $true }
        '--build-installer-exe' { $BuildExeInstaller = $true }
        '--build-installer-msi' { $BuildMsiInstaller = $true }
        '--build-installer-deb' { throw '--build-installer-deb is Linux-only; use build.sh on Linux.' }
        '--build-installer-appimage' { throw '--build-installer-appimage is Linux-only; use build.sh on Linux.' }
        default { throw "Unknown option: $arg`nRun .\build.ps1 --help for supported options." }
    }
}

Set-Location $Root
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ' Minesport Build Script' -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host 'Target: Windows / amd64' -ForegroundColor DarkGray
if (-not $BuildExeInstaller -and -not $BuildMsiInstaller) {
    Write-Host 'Packaging: disabled (executable only)' -ForegroundColor DarkGray
} else {
    $formats = @()
    if ($BuildExeInstaller) { $formats += 'EXE' }
    if ($BuildMsiInstaller) { $formats += 'MSI' }
    Write-Host "Packaging: $($formats -join ' + ')" -ForegroundColor DarkGray
}
Write-Host ''

Write-Host '[1/3] Building bundled Fabric bridge mod...' -ForegroundColor Yellow
Push-Location (Join-Path $Root 'bridge')
try {
    & .\gradlew.bat jar
    if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed.' }
    $bridgeJar = Get-ChildItem -Path 'build\libs\*.jar' -File |
        Where-Object { $_.Name -notmatch 'sources' } |
        Select-Object -First 1
    if (-not $bridgeJar) { throw 'Bundled bridge JAR was not produced.' }
    $bridgeJar = $bridgeJar.FullName
} finally {
    Pop-Location
}
$bundledDir = Join-Path $Root 'dist\bundled-bridge'
New-Item -ItemType Directory -Force -Path $bundledDir | Out-Null
Copy-Item -Force $bridgeJar (Join-Path $bundledDir 'minesport-bridge-0.1.0.jar')
Write-Host 'Bundled bridge staged: dist\bundled-bridge\minesport-bridge-0.1.0.jar' -ForegroundColor Green
Write-Host ''

Write-Host '[2/3] Building Java engine...' -ForegroundColor Yellow
Push-Location (Join-Path $Root 'engine')
try {
    & .\gradlew.bat jar
    if ($LASTEXITCODE -ne 0) { throw 'Java engine build failed.' }
    $engineJar = Get-ChildItem -Path 'build\libs\minesport-engine-*.jar' -File | Select-Object -First 1
    if (-not $engineJar) { throw 'Java engine JAR was not produced.' }
    $engineJar = $engineJar.FullName
} finally {
    Pop-Location
}
Write-Host "Java engine built: $engineJar" -ForegroundColor Green
Write-Host ''

Write-Host '[3/3] Building Go wrapper...' -ForegroundColor Yellow
Push-Location (Join-Path $Root 'wrapper')
try {
    Write-Host '  -> go mod tidy...' -ForegroundColor DarkGray
    & go mod tidy
    if ($LASTEXITCODE -ne 0) { throw 'go mod tidy failed.' }

    Write-Host '  -> embedding Java engine...' -ForegroundColor DarkGray
    & go run ./cmd/embed-engine -input $engineJar -output embedded_engine_generated.go
    if ($LASTEXITCODE -ne 0) { throw 'engine embedding failed.' }

    if (-not (Get-Command gcc -ErrorAction SilentlyContinue) -and -not $env:CC) {
        Write-Host 'WARNING: No gcc/CC detected. Fyne requires CGO.' -ForegroundColor Yellow
    }

    New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
    & go build -tags minesport_embedded_engine -trimpath '-ldflags=-H windowsgui -s -w' -o 'dist\minesport.exe' .
    if ($LASTEXITCODE -ne 0) { throw 'Go wrapper build failed.' }
    Copy-Item -Force 'dist\minesport.exe' 'minesport.exe'
} finally {
    Pop-Location
}
Write-Host 'Minesport built: wrapper\dist\minesport.exe' -ForegroundColor Green

function Find-InnoSetup {
    $candidates = @(
        'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
        'C:\Program Files\Inno Setup 6\ISCC.exe'
    )
    return $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

function Find-WixBin {
    $candidates = @(
        'C:\Program Files (x86)\WiX Toolset v3.14\bin',
        'C:\Program Files (x86)\WiX Toolset v3.11\bin'
    )
    return $candidates | Where-Object {
        (Test-Path (Join-Path $_ 'candle.exe')) -and (Test-Path (Join-Path $_ 'light.exe'))
    } | Select-Object -First 1
}

if ($BuildExeInstaller -or $BuildMsiInstaller) {
    $installerOut = Join-Path $Root 'dist\installer'
    New-Item -ItemType Directory -Force -Path $installerOut | Out-Null
    Write-Host ''
    Write-Host 'Packaging installers...' -ForegroundColor Yellow

    if ($BuildExeInstaller) {
        $iscc = Find-InnoSetup
        if (-not $iscc) { throw 'Inno Setup 6 was not found. Install it or use --build-installer-msi instead.' }
        & $iscc "/DSourceDir=$Root" (Join-Path $Root 'installer\windows\minesport.iss')
        if ($LASTEXITCODE -ne 0) { throw 'Inno Setup compilation failed.' }
        Write-Host 'EXE installer built: dist\installer\Minesport-0.1.0-Setup-x64.exe' -ForegroundColor Green
    }

    if ($BuildMsiInstaller) {
        $wix = Find-WixBin
        if (-not $wix) { throw 'WiX Toolset 3.x was not found. Install WiX 3.11/3.14 to build the MSI.' }
        Push-Location (Join-Path $Root 'installer\windows')
        try {
            & (Join-Path $wix 'candle.exe') -arch x64 -ext WixUtilExtension "-dSourceDir=$Root" Product.wxs
            if ($LASTEXITCODE -ne 0) { throw 'WiX candle failed.' }
            & (Join-Path $wix 'light.exe') -ext WixUIExtension -ext WixUtilExtension -out (Join-Path $installerOut 'Minesport-0.1.0-x64.msi') Product.wixobj
            if ($LASTEXITCODE -ne 0) { throw 'WiX light failed.' }
        } finally {
            Pop-Location
        }
        Write-Host 'MSI installer built: dist\installer\Minesport-0.1.0-x64.msi' -ForegroundColor Green
    }
}

Write-Host ''
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ' Build complete!' -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ' wrapper\dist\minesport.exe' -ForegroundColor Green
if ($BuildExeInstaller) { Write-Host ' dist\installer\Minesport-0.1.0-Setup-x64.exe' -ForegroundColor Green }
if ($BuildMsiInstaller) { Write-Host ' dist\installer\Minesport-0.1.0-x64.msi' -ForegroundColor Green }
Write-Host ''
Write-Host 'Run .\build.ps1 --help to see packaging options.' -ForegroundColor Cyan
