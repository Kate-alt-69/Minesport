param(
    [Parameter(Mandatory = $true)][string]$AppDirectory,
    [string]$RuntimeRoot = (Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'Minesport\toolchains'),
    [switch]$CheckOnly,
    [switch]$SkipVisualCpp
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$logs = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'Minesport\logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$log = Join-Path $logs 'runtime-setup.log'
$stdout = Join-Path $logs "runtime-$PID.stdout.log"
$stderr = Join-Path $logs "runtime-$PID.stderr.log"
$oldRoot = $env:MINESPORT_TOOLCHAIN_ROOT
$oldCache = $env:MINESPORT_CACHE_DIR

function Write-SetupLog([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    Add-Content -LiteralPath $log -Value $line -Encoding UTF8
    Write-Host $Message
}

function Test-VisualCpp {
    $registry = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::Registry64)
    try {
        $key = $registry.OpenSubKey('SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64')
        if ($null -eq $key) { return $false }
        try {
            $version = [string]$key.GetValue('Version', '')
            return ([int]$key.GetValue('Installed', 0) -eq 1 -and $version.TrimStart('v') -match '^\d+\.\d+\.\d+\.\d+$' -and [version]$version.TrimStart('v') -ge [version]'14.44.0.0')
        } finally { $key.Dispose() }
    } finally { $registry.Dispose() }
}

function Install-VisualCpp {
    if (Test-VisualCpp) { Write-SetupLog 'Compatible Visual C++ x64 runtime is installed.'; return }
    if ($CheckOnly) { throw 'Visual C++ x64 runtime is missing. Run setup again to repair it.' }
    $download = Join-Path $env:TEMP "minesport-vc-redist-$PID.exe"
    try {
        Write-SetupLog 'Downloading the Microsoft Visual C++ x64 runtime...'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://aka.ms/vs/17/release/vc_redist.x64.exe' -OutFile $download -TimeoutSec 120
        $signature = Get-AuthenticodeSignature -LiteralPath $download
        if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Subject -notmatch '(?:^|,\s*)O=Microsoft Corporation(?:,|$)') {
            throw 'Downloaded Visual C++ runtime does not have a valid Microsoft signature.'
        }
        $process = Start-Process -FilePath $download -ArgumentList '/install', '/quiet', '/norestart' -PassThru
        if (-not $process.WaitForExit(300000)) { $process.Kill(); throw 'Visual C++ installation timed out.' }
        if ($process.ExitCode -notin @(0, 1638, 3010) -or -not (Test-VisualCpp)) { throw "Visual C++ installation failed (exit $($process.ExitCode))." }
        if ($process.ExitCode -eq 3010) { Write-SetupLog 'Visual C++ installation requests a Windows restart.' }
    } finally { Remove-Item -LiteralPath $download -Force -ErrorAction SilentlyContinue }
}

try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'Minesport requires 64-bit Windows.' }
    if (-not $SkipVisualCpp) { Install-VisualCpp }
    $engine = Join-Path $AppDirectory 'minesport-engine.exe'
    if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) { throw 'Minesport engine is missing. Run setup again.' }
    $env:MINESPORT_TOOLCHAIN_ROOT = $RuntimeRoot
    $env:MINESPORT_CACHE_DIR = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'Minesport\setup-cache'
    $mode = if ($CheckOnly) { '--check-runtime' } else { '--prepare-runtime' }
    Write-SetupLog "Preparing required Minesport runtime ($mode)..."
    $process = Start-Process -FilePath $engine -ArgumentList $mode -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    if (-not $process.WaitForExit(900000)) { $process.Kill(); throw 'Java runtime preparation timed out. Check your internet connection and run setup again.' }
    foreach ($output in @($stdout, $stderr)) {
        if (Test-Path -LiteralPath $output) { Get-Content -LiteralPath $output | ForEach-Object { Write-SetupLog $_ } }
    }
    if ($process.ExitCode -ne 0) { throw "Runtime preparation failed (exit $($process.ExitCode)). Check the internet connection and run setup again. Details: $log" }
    Write-SetupLog 'Required runtime is ready. Future loader-specific Java versions are prepared automatically when needed.'
    exit 0
} catch {
    Write-SetupLog "Runtime setup could not finish: $($_.Exception.Message)"
    exit 21
} finally {
    $env:MINESPORT_TOOLCHAIN_ROOT = $oldRoot
    $env:MINESPORT_CACHE_DIR = $oldCache
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
}
