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
$oldRoot = $env:MINESPORT_TOOLCHAIN_ROOT
$oldCache = $env:MINESPORT_CACHE_DIR

function Write-SetupLog([string]$Message) {
    $line = "$(Get-Date -Format o) $Message"
    Add-Content -LiteralPath $log -Value $line -Encoding UTF8
    Write-Host $Message
}

# Start-Process in Windows PowerShell 5.1 may lose ExitCode after WaitForExit.
# Own the process handle and drain both pipes asynchronously instead.
function Invoke-SetupProcess([string]$Executable, [string]$Arguments, [int]$TimeoutSeconds) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Executable
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $start.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $process = [System.Diagnostics.Process]::Start($start)
    try {
        $out = $process.StandardOutput.ReadToEndAsync()
        $err = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $null = $process.WaitForExit(5000)
            throw "Runtime process timed out: $Executable"
        }
        foreach ($text in @($out.Result, $err.Result)) {
            foreach ($line in ($text -split '\r?\n')) {
                if ($line.Length -gt 0) { Write-SetupLog $line }
            }
        }
        return [int]$process.ExitCode
    } finally { $process.Dispose() }
}

function Get-VisualCppVersion {
    $registry = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::Registry64)
    try {
        $key = $registry.OpenSubKey('SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64')
        if ($null -eq $key) { return $null }
        try {
            $version = ([string]$key.GetValue('Version', '')).TrimStart('v')
            if ([int]$key.GetValue('Installed', 0) -eq 1 -and $version -match '^\d+\.\d+\.\d+\.\d+$') { return [version]$version }
            return $null
        } finally { $key.Dispose() }
    } finally { $registry.Dispose() }
}

function Test-VisualCpp {
    $version = Get-VisualCppVersion
    if ($null -eq $version -or $version -lt [version]'14.44.0.0') { return $false }
    $system = if ([Environment]::Is64BitProcess) { Join-Path $env:SystemRoot 'System32' } else { Join-Path $env:SystemRoot 'Sysnative' }
    foreach ($dll in @('vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll')) {
        if (-not (Test-Path -LiteralPath (Join-Path $system $dll) -PathType Leaf)) { return $false }
    }
    return $true
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
        $installed = Get-VisualCppVersion
        $mode = if ($null -ne $installed -and $installed -ge [version]'14.44.0.0') { '/repair' } else { '/install' }
        $code = Invoke-SetupProcess $download "$mode /quiet /norestart" 300
        if ($code -notin @(0, 1638, 3010) -or -not (Test-VisualCpp)) { throw "Visual C++ installation failed (exit $code)." }
        if ($code -eq 3010) { Write-SetupLog 'Visual C++ installation requests a Windows restart.' }
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
    $code = Invoke-SetupProcess $engine $mode 900
    if ($code -ne 0) { throw "Runtime preparation failed (exit $code). Check the internet connection and run setup again. Details: $log" }
    Write-SetupLog 'Required runtime is ready. Future loader-specific Java versions are prepared automatically when needed.'
    exit 0
} catch {
    Write-SetupLog "Runtime setup could not finish: $($_.Exception.Message)"
    exit 21
} finally {
    $env:MINESPORT_TOOLCHAIN_ROOT = $oldRoot
    $env:MINESPORT_CACHE_DIR = $oldCache
}
