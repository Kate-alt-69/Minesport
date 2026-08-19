param(
    [Parameter(Mandatory = $true)]
    [string]$MinecraftVersion,

    [string]$OutputDirectory = "",

    [string]$GradleVersion = "9.5.1",

    [string]$LoomVersion = "1.17.17"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Write-Step([string]$Message) {
    Write-Host "[Minesport Bridge] $Message"
}

function Get-JavaMajor([string]$JavaExe) {
    try {
        $line = (& $JavaExe -version 2>&1 | Select-Object -First 1).ToString()
        if ($line -match '"([0-9]+)(?:\.([0-9]+))?') {
            return [int]$Matches[1]
        }
    } catch {}
    return 0
}

function Find-Java25 {
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command -and (Get-JavaMajor $command.Source) -ge 25) {
        return $command.Source
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe"))
    }
    if ($env:APPDATA) {
        $runtimeRoot = Join-Path $env:APPDATA ".minecraft\runtime"
        if (Test-Path $runtimeRoot) {
            Get-ChildItem -Path $runtimeRoot -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    if ($env:ProgramFiles) {
        Get-ChildItem -Path $env:ProgramFiles -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ((Test-Path $candidate) -and (Get-JavaMajor $candidate) -ge 25) {
            return $candidate
        }
    }
    return $null
}

function Resolve-LoaderVersion([string]$GameVersion) {
    $url = "https://meta.fabricmc.net/v2/versions/loader/$([uri]::EscapeDataString($GameVersion))"
    $entries = Invoke-RestMethod -Uri $url -Headers @{ "User-Agent" = "Minesport-BridgeBuilder/0.2" }
    if (-not $entries -or $entries.Count -eq 0) {
        throw "Fabric Loader has no build for Minecraft $GameVersion."
    }

    $stable = $entries | Where-Object { $_.loader.stable -eq $true } | Select-Object -First 1
    if ($stable) { return $stable.loader.version }
    return ($entries | Select-Object -First 1).loader.version
}

function Resolve-FabricApiVersion([string]$GameVersion) {
    $indexUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/"
    $html = (Invoke-WebRequest -UseBasicParsing -Uri $indexUrl -Headers @{
        "User-Agent" = "Minesport-BridgeBuilder/0.2"
    }).Content

    $compat = New-Object System.Collections.Generic.List[string]
    $compat.Add($GameVersion)
    if ($GameVersion -match '^([0-9]+\.[0-9]+)') {
        if (-not $compat.Contains($Matches[1])) {
            $compat.Add($Matches[1])
        }
    }

    $all = [regex]::Matches($html, 'href="([^"/]+(?:\+|%2B)[^"/]+)/"') |
        ForEach-Object { [uri]::UnescapeDataString($_.Groups[1].Value) } |
        Select-Object -Unique

    foreach ($target in $compat) {
        $suffix = "+$target"
        $matching = $all | Where-Object { $_.EndsWith($suffix, [StringComparison]::OrdinalIgnoreCase) }
        if ($matching) {
            return $matching |
                Sort-Object {
                    $prefix = ($_ -split '\+', 2)[0]
                    try { [version]$prefix } catch { [version]"0.0.0" }
                } |
                Select-Object -Last 1
        }
    }

    throw "No Fabric API artifact was found for Minecraft $GameVersion."
}

function Ensure-Gradle([string]$Version) {
    $existing = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if (-not $existing) {
        $existing = Get-Command gradle.exe -ErrorAction SilentlyContinue
    }
    if ($existing) {
        return $existing.Source
    }

    $base = if ($env:LOCALAPPDATA) {
        Join-Path $env:LOCALAPPDATA "Minesport\tools"
    } else {
        Join-Path $env:TEMP "Minesport\tools"
    }
    $home = Join-Path $base "gradle-$Version"
    $exe = Join-Path $home "bin\gradle.bat"
    if (Test-Path $exe) {
        return $exe
    }

    New-Item -ItemType Directory -Force -Path $base | Out-Null
    $zip = Join-Path $base "gradle-$Version-bin.zip"
    $url = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
    Write-Step "Downloading Gradle $Version..."
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $base -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path $exe)) {
        throw "Gradle $Version was downloaded but gradle.bat was not found."
    }
    return $exe
}

if ($MinecraftVersion -notmatch '^26\.') {
    throw "The dynamic bridge builder targets Minecraft 26.x. Use the legacy bridge for 1.21.x worlds."
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
if (-not (Test-Path (Join-Path $projectDir "build.gradle"))) {
    throw "Could not locate the bridge26 Gradle project."
}

$javaExe = Find-Java25
if (-not $javaExe) {
    throw "Java 25+ is required for Minecraft 26.x Fabric builds. Install Java 25 or launch Minecraft 26.x once so its runtime is available."
}
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
Write-Step "Java: $javaExe"

$loader = Resolve-LoaderVersion $MinecraftVersion
$fabricApi = Resolve-FabricApiVersion $MinecraftVersion
$gradle = Ensure-Gradle $GradleVersion

Write-Step "Minecraft:  $MinecraftVersion"
Write-Step "Loader:     $loader"
Write-Step "Fabric API: $fabricApi"
Write-Step "Loom:       $LoomVersion"

& $gradle `
    -p $projectDir `
    clean build `
    "-Pminecraft_version=$MinecraftVersion" `
    "-Ploader_version=$loader" `
    "-Pfabric_api_version=$fabricApi" `
    "-Ploom_version=$LoomVersion" `
    --no-daemon `
    --stacktrace

if ($LASTEXITCODE -ne 0) {
    throw "Fabric bridge compilation failed with exit code $LASTEXITCODE."
}

$jar = Get-ChildItem -Path (Join-Path $projectDir "build\libs") -Filter "*.jar" -File |
    Where-Object { $_.Name -notmatch '-sources\.jar$' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Gradle completed but no bridge JAR was produced."
}

if (-not $OutputDirectory) {
    $base = if ($env:LOCALAPPDATA) {
        Join-Path $env:LOCALAPPDATA "Minesport\bridges"
    } else {
        Join-Path $env:TEMP "Minesport\bridges"
    }
    $OutputDirectory = Join-Path $base $MinecraftVersion
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$destination = Join-Path $OutputDirectory $jar.Name
Copy-Item -Path $jar.FullName -Destination $destination -Force
Write-Step "Built: $destination"
Write-Output $destination
