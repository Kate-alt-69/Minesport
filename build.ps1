#!/usr/bin/env pwsh
# Minesport 0.2.0 smart build + optional packaging script.

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppVersion = '0.2.0'
$BridgeVersion = '0.2.0'
$BuildNsisInstaller = $false
$BuildInnoInstaller = $false
$BuildMsiInstaller = $false
$DesktopOnly = $false
$Fresh = $false
$CheckOnly = $false

function Show-Help {
    @"
Minesport build script

Usage:
  .\build.ps1 [options]

Default behavior:
  Runs a fast Rust/Slint error check first.
  Then rebuilds ONLY components whose source/config fingerprint changed.
  Unchanged Fabric/Forge/NeoForge/Quilt Bridges and the Java engine are reused
  without invoking Gradle at all. The final Minesport.exe embeds all four
  canonical Minecraft 1.21.10 loader Bridges.

Fast development:
  --check                 Rust + Slint compile/error check only. Never runs Gradle.
  --desktop-only          Reuse the already-built engine + all four loader Bridges
                          and run only the Rust/Slint desktop stage.
  --fresh                 Delete Minesport build outputs/state and rebuild everything.
                          Gradle/Cargo download caches are deliberately preserved.

Installer options (Windows):
  --build-installer       Build the default NSIS .exe installer
  --build-installer-all   Build NSIS .exe + WiX .msi
  --build-installer-exe   Build the default NSIS .exe installer
  --build-installer-nsis  Build the NSIS .exe installer
  --build-installer-inno  Build the optional Inno Setup .exe installer
  --build-installer-msi   Build the WiX 7 .msi installer

Help:
  -h, --help              Show this help text
"@ | Write-Host
}

foreach ($arg in $args) {
    switch ($arg) {
        '-h' { Show-Help; exit 0 }
        '--help' { Show-Help; exit 0 }
        '--check' { $CheckOnly = $true }
        '--fresh' { $Fresh = $true }
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

if ($CheckOnly -and $Fresh) {
    throw '--check and --fresh cannot be combined. --check does not mutate build outputs.'
}
if ($CheckOnly -and $DesktopOnly) {
    throw '--check and --desktop-only cannot be combined.'
}
if ($Fresh -and $DesktopOnly) {
    throw '--fresh and --desktop-only cannot be combined. --fresh rebuilds every component.'
}

Set-Location $Root

$bundledDir = Join-Path $Root 'dist\bundled-bridge'
$stateFile = Join-Path $Root 'dist\.minesport-build-state'
$bridgeSpecs = @(
    @{ Name = 'Fabric'; Slug = 'fabric'; Project = 'minesport-bridge-fabric'; Env = 'MINESPORT_BRIDGE_FABRIC_JAR' },
    @{ Name = 'Forge'; Slug = 'forge'; Project = 'minesport-bridge-forge'; Env = 'MINESPORT_BRIDGE_FORGE_JAR' },
    @{ Name = 'NeoForge'; Slug = 'neoforge'; Project = 'minesport-bridge-neoforge'; Env = 'MINESPORT_BRIDGE_NEOFORGE_JAR' },
    @{ Name = 'Quilt'; Slug = 'quilt'; Project = 'minesport-bridge-quilt'; Env = 'MINESPORT_BRIDGE_QUILT_JAR' }
)
$bundledBridges = @{}
foreach ($bridge in $bridgeSpecs) {
    $bundledBridges[$bridge.Slug] = Join-Path $bundledDir "minesport-bridge-$($bridge.Slug)-$BridgeVersion.jar"
}

function Get-TrackedFiles([string]$Path) {
    $rootPath = [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\','/'))
    if (-not (Test-Path $rootPath)) { return @() }
    return @(Get-ChildItem -LiteralPath $rootPath -Recurse -File -Force | Where-Object {
        $relative = $_.FullName.Substring($rootPath.Length).TrimStart([char[]]@('\','/'))
        $segments = $relative -split '[\\/]'
        -not ($segments -contains '.gradle') -and
        -not ($segments -contains 'build') -and
        -not ($segments -contains 'target') -and
        -not ($segments -contains 'dist') -and
        -not ($segments -contains '.git')
    } | Sort-Object FullName)
}

function Get-StringSha256([string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-TreeFingerprint([string]$Path) {
    $rootPath = [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\','/'))
    $lines = foreach ($file in (Get-TrackedFiles $rootPath)) {
        $relative = $file.FullName.Substring($rootPath.Length).TrimStart([char[]]@('\','/')).Replace('\','/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative=$hash"
    }
    return Get-StringSha256 ($lines -join "`n")
}

function Get-CompositeFingerprint([string[]]$Paths, [string[]]$ExtraFiles = @()) {
    $parts = @()
    foreach ($path in $Paths) {
        $parts += "$path=$(Get-TreeFingerprint $path)"
    }
    foreach ($file in $ExtraFiles) {
        if (Test-Path $file) {
            $parts += "$file=$((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())"
        } else {
            $parts += "$file=MISSING"
        }
    }
    return Get-StringSha256 ($parts -join "`n")
}

$BuildState = @{}
if (Test-Path $stateFile) {
    foreach ($line in Get-Content -LiteralPath $stateFile -ErrorAction SilentlyContinue) {
        if ($line -match '^([^=]+)=(.+)$') {
            $BuildState[$matches[1]] = $matches[2]
        }
    }
}

function Save-BuildState {
    $parent = Split-Path -Parent $stateFile
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $lines = @($BuildState.Keys | Sort-Object | ForEach-Object { "$_=$($BuildState[$_])" })
    Set-Content -LiteralPath $stateFile -Value $lines -Encoding UTF8
}

function Get-NewestInputTime([string]$Path) {
    $files = @(Get-TrackedFiles $Path)
    if ($files.Count -eq 0) { return [DateTime]::MinValue }
    return ($files | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
}

function Test-NeedsRebuild(
    [string]$Key,
    [string]$Fingerprint,
    [string]$Output,
    [string]$SourceRoot
) {
    if ($Fresh) { return $true }
    if (-not (Test-Path $Output)) { return $true }
    if ($BuildState.ContainsKey($Key) -and $BuildState[$Key] -eq $Fingerprint) {
        return $false
    }

    # Adoption path for outputs produced before smart-build state existed.
    # Once adopted, all later decisions are content-hash based.
    $outputTime = (Get-Item -LiteralPath $Output).LastWriteTimeUtc
    $newestInput = Get-NewestInputTime $SourceRoot
    if ($outputTime -ge $newestInput) {
        $BuildState[$Key] = $Fingerprint
        Save-BuildState
        return $false
    }
    return $true
}

function Remove-BuildOutputs {
    Write-Host '[FRESH] Removing Minesport build outputs only...' -ForegroundColor Yellow
    $paths = @(
        (Join-Path $Root 'minesport-bridge-fabric\build'),
        (Join-Path $Root 'minesport-bridge-forge\build'),
        (Join-Path $Root 'minesport-bridge-neoforge\build'),
        (Join-Path $Root 'minesport-bridge-quilt\build'),
        (Join-Path $Root 'engine\build'),
        (Join-Path $Root 'desktop\target'),
        (Join-Path $Root 'desktop\dist'),
        (Join-Path $Root 'dist\bundled-bridge'),
        (Join-Path $Root 'dist\source'),
        (Join-Path $Root 'dist\installer'),
        $stateFile
    )
    foreach ($path in $paths) {
        if (Test-Path $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }
    Write-Host '  Preserved: project .gradle caches, ~/.gradle, Cargo registry/git caches, downloaded JDKs.' -ForegroundColor DarkGray
    $BuildState.Clear()
    Write-Host ''
}

function Invoke-FastCheck([switch]$AllowMissingArtifacts) {
    $checkScript = Join-Path $Root 'check.ps1'
    if (-not (Test-Path $checkScript)) { throw "Missing fast checker: $checkScript" }
    if ($AllowMissingArtifacts) {
        & $checkScript -AllowMissingArtifacts
    } else {
        & $checkScript
    }
}

if ($CheckOnly) {
    Invoke-FastCheck
    exit 0
}

Write-Host '============================================' -ForegroundColor Cyan
Write-Host " Minesport $AppVersion Smart Build" -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host 'Target: Windows / amd64' -ForegroundColor DarkGray
Write-Host 'Desktop: Rust + Slint 1.17.1' -ForegroundColor DarkGray
Write-Host 'Mode: content-aware incremental build' -ForegroundColor DarkGray
if ($Fresh) { Write-Host 'Fresh: YES (outputs only; caches preserved)' -ForegroundColor Yellow }
if ($DesktopOnly) { Write-Host 'Desktop-only: YES' -ForegroundColor DarkGray }
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

# Run before --fresh removes old outputs. On an existing development tree this
# catches Rust/Slint/build.rs errors before any expensive loader toolchain.
Write-Host '[0/3] ERROR CHECK...' -ForegroundColor Yellow
Invoke-FastCheck -AllowMissingArtifacts
Write-Host ''

if ($Fresh) {
    Remove-BuildOutputs
}

New-Item -ItemType Directory -Force -Path $bundledDir | Out-Null

function Build-Bridge($bridge) {
    $projectPath = Join-Path $Root $bridge.Project
    $destination = $bundledBridges[$bridge.Slug]
    $key = "bridge-$($bridge.Slug)"
    $fingerprint = Get-TreeFingerprint $projectPath

    if (-not (Test-NeedsRebuild $key $fingerprint $destination $projectPath)) {
        Write-Host "  -> $($bridge.Name): unchanged, SKIP Gradle" -ForegroundColor Green
        return
    }

    Write-Host "  -> $($bridge.Name): changed/missing, building..." -ForegroundColor Yellow
    Push-Location $projectPath
    try {
        # No clean: if a rebuild is needed, preserve Gradle's local incremental work.
        & .\gradlew.bat --no-daemon --stacktrace build
        if ($LASTEXITCODE -ne 0) { throw "$($bridge.Name) Bridge build failed." }
        $bridgeJar = Get-ChildItem -Path 'build\libs\*.jar' -File |
            Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
            Sort-Object Name |
            Select-Object -First 1
        if (-not $bridgeJar) {
            throw "$($bridge.Name) Bridge JAR was not produced under $($bridge.Project)\build\libs."
        }
        $bridgeJar = $bridgeJar.FullName
    } finally {
        Pop-Location
    }

    Copy-Item -Force $bridgeJar $destination
    if ((Get-Item $destination).Length -le 0) { throw "Staged $($bridge.Name) Bridge is empty: $destination" }
    $BuildState[$key] = $fingerprint
    Save-BuildState
    Write-Host "     staged: dist\bundled-bridge\$(Split-Path -Leaf $destination)" -ForegroundColor Green
}

if (-not $DesktopOnly) {
    Write-Host '[1/3] Bundled Minecraft 1.21.10 loader Bridges...' -ForegroundColor Yellow
    foreach ($bridge in $bridgeSpecs) {
        Build-Bridge $bridge
    }
    Write-Host ''
} else {
    Write-Host '[1/3] Reusing bundled Minecraft loader Bridges...' -ForegroundColor Yellow
    foreach ($bridge in $bridgeSpecs) {
        $jar = $bundledBridges[$bridge.Slug]
        if (-not (Test-Path $jar)) {
            throw "--desktop-only requires $jar. Run a normal build once first."
        }
        Write-Host "  $($bridge.Name): $jar" -ForegroundColor Green
        $BuildState["bridge-$($bridge.Slug)"] = Get-TreeFingerprint (Join-Path $Root $bridge.Project)
    }
    Save-BuildState
    Write-Host ''
}

$engineProject = Join-Path $Root 'engine'
$engineJar = $null
if (-not $DesktopOnly) {
    Write-Host '[2/3] Java engine...' -ForegroundColor Yellow
    $engineFingerprint = Get-TreeFingerprint $engineProject
    $existingEngine = Get-ChildItem -Path (Join-Path $engineProject 'build\libs\minesport-engine-*.jar') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $existingEnginePath = if ($existingEngine) { $existingEngine.FullName } else { Join-Path $engineProject 'build\libs\minesport-engine-0.2.0.jar' }

    if (Test-NeedsRebuild 'engine' $engineFingerprint $existingEnginePath $engineProject) {
        Write-Host '  -> changed/missing, building...' -ForegroundColor Yellow
        Push-Location $engineProject
        try {
            & .\gradlew.bat --no-daemon --stacktrace build
            if ($LASTEXITCODE -ne 0) { throw 'Java engine build failed.' }
            $engineJar = Get-ChildItem -Path 'build\libs\minesport-engine-*.jar' -File |
                Where-Object { $_.Name -notmatch 'sources' } |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if (-not $engineJar) { throw 'Java engine JAR was not produced.' }
            $engineJar = $engineJar.FullName
        } finally {
            Pop-Location
        }
        $BuildState['engine'] = $engineFingerprint
        Save-BuildState
    } else {
        Write-Host '  -> unchanged, SKIP Gradle' -ForegroundColor Green
        $engineJar = $existingEnginePath
    }
    Write-Host "  Engine: $engineJar" -ForegroundColor Green
    Write-Host ''
} else {
    Write-Host '[2/3] Reusing Java engine...' -ForegroundColor Yellow
    $engineJar = Get-ChildItem -Path (Join-Path $engineProject 'build\libs\minesport-engine-*.jar') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $engineJar) {
        throw '--desktop-only requires an existing engine/build/libs/minesport-engine-*.jar. Run a normal build once first.'
    }
    $engineJar = $engineJar.FullName
    $BuildState['engine'] = Get-TreeFingerprint $engineProject
    Save-BuildState
    Write-Host "  Engine: $engineJar" -ForegroundColor Green
    Write-Host ''
}

Write-Host '[3/3] Rust + Slint desktop...' -ForegroundColor Yellow
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Rust/Cargo was not found. Install rustup from https://rustup.rs, reopen PowerShell, then rerun.'
}
if (-not (Get-Command rustc -ErrorAction SilentlyContinue)) {
    throw 'rustc was not found even though Cargo is available. Repair the Rust toolchain with rustup.'
}

$desktopOut = Join-Path $Root 'desktop\dist\minesport.exe'
$desktopFingerprint = Get-CompositeFingerprint @(
    (Join-Path $Root 'desktop'),
    (Join-Path $Root 'doc')
) @(
    $engineJar,
    $bundledBridges['fabric'],
    $bundledBridges['forge'],
    $bundledBridges['neoforge'],
    $bundledBridges['quilt']
)

$previousEngineJar = $env:MINESPORT_ENGINE_JAR
$previousLegacyBridgeJar = $env:MINESPORT_BRIDGE_JAR
$previousBridgeEnv = @{}
foreach ($bridge in $bridgeSpecs) {
    $previousBridgeEnv[$bridge.Env] = [Environment]::GetEnvironmentVariable($bridge.Env)
}
$env:MINESPORT_ENGINE_JAR = $engineJar
$env:MINESPORT_BRIDGE_JAR = $bundledBridges['fabric']
foreach ($bridge in $bridgeSpecs) {
    [Environment]::SetEnvironmentVariable($bridge.Env, $bundledBridges[$bridge.Slug])
}

try {
    if (-not (Test-NeedsRebuild 'desktop' $desktopFingerprint $desktopOut (Join-Path $Root 'desktop'))) {
        Write-Host '  -> desktop inputs unchanged, SKIP Cargo test/build' -ForegroundColor Green
    } else {
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
            & cargo build --release --bin minesport
            if ($LASTEXITCODE -ne 0) { throw 'Rust/Slint desktop release build failed.' }

            $rustExe = Join-Path $Root 'desktop\target\release\minesport.exe'
            if (-not (Test-Path $rustExe)) {
                throw "Rust build completed but did not produce $rustExe"
            }

            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $desktopOut) | Out-Null
            Copy-Item -Force $rustExe $desktopOut
        } finally {
            Pop-Location
        }
        $BuildState['desktop'] = $desktopFingerprint
        Save-BuildState
    }
} finally {
    if ($null -eq $previousEngineJar) { Remove-Item Env:MINESPORT_ENGINE_JAR -ErrorAction SilentlyContinue } else { $env:MINESPORT_ENGINE_JAR = $previousEngineJar }
    if ($null -eq $previousLegacyBridgeJar) { Remove-Item Env:MINESPORT_BRIDGE_JAR -ErrorAction SilentlyContinue } else { $env:MINESPORT_BRIDGE_JAR = $previousLegacyBridgeJar }
    foreach ($bridge in $bridgeSpecs) {
        [Environment]::SetEnvironmentVariable($bridge.Env, $previousBridgeEnv[$bridge.Env])
    }
}

if (-not (Test-Path $desktopOut)) { throw "Desktop output is missing: $desktopOut" }
$sourceOut = Join-Path $Root 'dist\source'
New-Item -ItemType Directory -Force -Path $sourceOut | Out-Null
$standaloneExe = Join-Path $sourceOut 'Minesport.exe'
Copy-Item -Force $desktopOut $standaloneExe
Write-Host '  Minesport.exe ready.' -ForegroundColor Green

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
        Write-Host "  NSIS: $nsisOutput" -ForegroundColor Green
    }

    if ($BuildInnoInstaller) {
        $innoOutput = Join-Path $installerOut "Minesport-$AppVersion-Inno-Setup-x64.exe"
        Remove-Item -Force -ErrorAction SilentlyContinue $innoOutput
        $iscc = Find-InnoSetup
        if (-not $iscc) { throw 'Inno Setup 6/7 was not found.' }
        & $iscc "/DSourceDir=$Root" (Join-Path $Root 'installer\windows\minesport.iss')
        if ($LASTEXITCODE -ne 0) { throw 'Inno Setup compilation failed.' }
        if (-not (Test-Path $innoOutput)) { throw "Inno Setup completed but did not produce $innoOutput" }
        Write-Host "  Inno: $innoOutput" -ForegroundColor Green
    }

    if ($BuildMsiInstaller) {
        if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
            throw 'WiX 7 MSI builds require the .NET SDK 6 or newer.'
        }
        $wixProject = Join-Path $Root 'installer\windows\Minesport.wixproj'
        $msiOutput = Join-Path $installerOut "Minesport-$AppVersion-x64.msi"
        Remove-Item -Force -ErrorAction SilentlyContinue $msiOutput
        & dotnet build $wixProject --configuration Release --output $installerOut --no-incremental "-p:SourceDir=$Root"
        if ($LASTEXITCODE -ne 0) { throw 'WiX 7 MSI build failed.' }
        if (-not (Test-Path $msiOutput)) { throw "WiX 7 completed but did not produce $msiOutput" }
        Write-Host "  MSI: $msiOutput" -ForegroundColor Green
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
Write-Host 'Fast check:   .\build.bat --check' -ForegroundColor Cyan
Write-Host 'Force rebuild: .\build.bat --fresh' -ForegroundColor Cyan
