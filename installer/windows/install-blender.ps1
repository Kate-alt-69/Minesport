param(
    [string]$Version = "5.2.0"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Test-SupportedBlenderInstalled {
    $roots = @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )

    foreach ($root in $roots) {
        $items = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like "Blender*" }
        foreach ($item in $items) {
            if ($item.DisplayVersion -match '^([0-9]+)\.([0-9]+)') {
                $major = [int]$Matches[1]
                $minor = [int]$Matches[2]
                if ($major -gt 4 -or ($major -eq 4 -and $minor -ge 3)) {
                    Write-Host "Compatible Blender $($item.DisplayVersion) is already installed."
                    return $true
                }
            }
        }
    }

    return $false
}

if (Test-SupportedBlenderInstalled) {
    exit 0
}

if (-not [Environment]::Is64BitOperatingSystem) {
    throw "Minesport's Blender installer currently requires 64-bit Windows."
}

$series = ($Version -split '\.')[0..1] -join '.'
$fileName = "blender-$Version-windows-x64.msi"
$baseUrl = "https://download.blender.org/release/Blender$series"
$msiUrl = "$baseUrl/$fileName"
$shaUrl = "$baseUrl/blender-$Version.sha256"

$tempRoot = Join-Path $env:TEMP "Minesport-Blender"
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$msiPath = Join-Path $tempRoot $fileName

try {
    Write-Host "Downloading Blender $Version from the official Blender mirror..."
    Invoke-WebRequest -UseBasicParsing -Uri $msiUrl -OutFile $msiPath

    Write-Host "Verifying Blender SHA-256..."
    $checksumText = (Invoke-WebRequest -UseBasicParsing -Uri $shaUrl).Content
    $escapedName = [regex]::Escape($fileName)
    $match = [regex]::Match($checksumText, "(?im)^([0-9a-f]{64})\s+\*?$escapedName\s*$")
    if (-not $match.Success) {
        throw "The official checksum file did not contain $fileName."
    }

    $expected = $match.Groups[1].Value.ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -Path $msiPath).Hash.ToLowerInvariant()
    if ($expected -ne $actual) {
        throw "Blender installer checksum mismatch."
    }

    Write-Host "Installing Blender $Version..."
    $process = Start-Process -FilePath "msiexec.exe" -ArgumentList @(
        "/i", "`"$msiPath`"",
        "/passive",
        "/norestart"
    ) -Wait -PassThru

    if ($process.ExitCode -notin @(0, 3010)) {
        throw "Blender MSI exited with code $($process.ExitCode)."
    }

    Write-Host "Blender $Version installed successfully."
} finally {
    Remove-Item $msiPath -Force -ErrorAction SilentlyContinue
}
