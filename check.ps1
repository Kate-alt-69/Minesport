#!/usr/bin/env pwsh
param(
    # Kept for compatibility with build.ps1. Fast checks no longer need any
    # prebuilt runtime artifacts, so this switch is intentionally a no-op.
    [switch]$AllowMissingArtifacts
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'Rust/Cargo was not found. Install or repair rustup before running the fast check.'
}
if (-not (Get-Command rustc -ErrorAction SilentlyContinue)) {
    throw 'rustc was not found even though Cargo is available. Repair the Rust toolchain with rustup.'
}

$PreviousFastCheck = $env:MINESPORT_FAST_CHECK
$env:MINESPORT_FAST_CHECK = '1'

try {
    Write-Host '============================================' -ForegroundColor Cyan
    Write-Host ' Minesport FAST ERROR CHECK' -ForegroundColor Cyan
    Write-Host '============================================' -ForegroundColor Cyan
    Write-Host 'No Fabric/Forge/NeoForge/Quilt or Java-engine Gradle build.' -ForegroundColor DarkGray
    Write-Host 'Checking Rust + Slint + build.rs + -D warnings only.' -ForegroundColor DarkGray
    Write-Host ''

    Push-Location (Join-Path $Root 'desktop')
    try {
        & rustc --version
        & cargo --version
        Write-Host ''
        Write-Host 'Running cargo check --all-targets ...' -ForegroundColor Yellow
        & cargo check --all-targets
        if ($LASTEXITCODE -ne 0) {
            throw 'FAST ERROR CHECK FAILED. Fix the error above before running the full build.'
        }
    } finally {
        Pop-Location
    }

    Write-Host ''
    Write-Host 'FAST CHECK PASSED - safe to start expensive loader builds.' -ForegroundColor Green
} finally {
    if ($null -eq $PreviousFastCheck) {
        Remove-Item Env:MINESPORT_FAST_CHECK -ErrorAction SilentlyContinue
    } else {
        $env:MINESPORT_FAST_CHECK = $PreviousFastCheck
    }
}
