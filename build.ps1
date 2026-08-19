#!/usr/bin/env pwsh
# Minesport Build Script

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Minesport Build Script" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Build Fabric bridge mod
Write-Host "[1/3] Building Fabric bridge mod..." -ForegroundColor Yellow
Push-Location bridge
& .\gradlew.bat jar
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Bridge build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}
Write-Host "Bridge mod built: bridge\build\libs\" -ForegroundColor Green
Pop-Location
Write-Host ""

# Build Java engine
Write-Host "[2/3] Building Java engine..." -ForegroundColor Yellow
Push-Location engine
& .\gradlew.bat jar
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Java build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}
Write-Host "Java engine built: engine\build\libs\" -ForegroundColor Green

# Copy jar to wrapper dir for easy distribution
Copy-Item -Path "build\libs\minesport-engine-*.jar" -Destination "..\wrapper\" -Force
Pop-Location
Write-Host ""

# Build Go wrapper
Write-Host "[3/3] Building Go wrapper..." -ForegroundColor Yellow
Push-Location wrapper

Write-Host "  -> go mod tidy..." -ForegroundColor DarkGray
go mod tidy
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: go mod tidy failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

# Fyne's desktop OpenGL backend needs CGO + a real C compiler. Without one,
# Go silently sets CGO_ENABLED=0 and cgo-based packages (go-gl) fail with a
# cryptic "build constraints exclude all Go files" instead of a clear
# "no C compiler found" — so check for gcc up front and say so plainly.
$gccCmd = Get-Command gcc -ErrorAction SilentlyContinue
if (-not $gccCmd -and -not $env:CC) {
    Write-Host ""
    Write-Host "WARNING: No C compiler (gcc) found on PATH." -ForegroundColor Yellow
    Write-Host "  Fyne needs CGO + a real C compiler to build its Windows OpenGL backend." -ForegroundColor Yellow
    Write-Host "  Quickest fix: grab w64devkit (no installer, just unzip):" -ForegroundColor Yellow
    Write-Host "    https://github.com/skeeto/w64devkit/releases" -ForegroundColor Yellow
    Write-Host "  Then add its bin\ folder to PATH and open a NEW terminal before retrying." -ForegroundColor Yellow
    Write-Host "  Attempting the build anyway in case a compiler is configured another way..." -ForegroundColor DarkGray
    Write-Host ""
} elseif ($gccCmd -and -not $env:CC) {
    # A gcc was found, but is it actually 64-bit capable? A 32-bit-only gcc
    # earlier on PATH than a correctly-installed 64-bit one is a very common
    # Windows conflict (Git for Windows / Strawberry Perl / RTools all ship
    # their own gcc) and produces the equally cryptic
    # "cc1.exe: sorry, unimplemented: 64-bit mode not compiled in".
    $machine = & gcc -dumpmachine 2>$null
    if ($machine -notmatch "x86_64|amd64|aarch64") {
        Write-Host ""
        Write-Host "WARNING: gcc at $($gccCmd.Source) reports target '$machine' — looks 32-bit-only." -ForegroundColor Yellow
        Write-Host "  Go needs a 64-bit-capable compiler (x86_64-w64-mingw32) for a windows/amd64 build." -ForegroundColor Yellow
        Write-Host "  This usually means an older gcc is earlier on PATH than your real one." -ForegroundColor Yellow
        Write-Host "  Run 'where gcc' to see every match in PATH order, then either reorder PATH so" -ForegroundColor Yellow
        Write-Host "  the 64-bit one comes first, or bypass PATH entirely with:" -ForegroundColor Yellow
        Write-Host "    `$env:CC = `"<full path to your 64-bit gcc.exe>`"" -ForegroundColor Yellow
        Write-Host ""
    }
}

# Release build: trim paths and strip DWARF/debug symbols to keep the
# distributable Windows binary small, matching build.sh's release settings.
go build -trimpath -ldflags="-H windowsgui -s -w" -o minesport.exe .
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Go build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}
Write-Host "Go wrapper built: wrapper\minesport.exe (stripped release build)" -ForegroundColor Green
Pop-Location
Write-Host ""

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Build complete!" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "Executables:" -ForegroundColor Green
Write-Host "  wrapper\minesport.exe          (Go UI, stripped release build)" -ForegroundColor Green
Write-Host "  wrapper\minesport-engine-*.jar (Java engine)" -ForegroundColor Green
Write-Host "  bridge\build\libs\*.jar        (Bridge mod)" -ForegroundColor Green
Write-Host ""
Write-Host "To run:" -ForegroundColor Cyan
Write-Host "  cd wrapper; .\minesport.exe" -ForegroundColor Cyan
Write-Host ""
Write-Host "Dev mode (Java UI directly):" -ForegroundColor Cyan
Write-Host "  cd engine; .\gradlew.bat run" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
