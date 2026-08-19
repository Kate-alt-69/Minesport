#!/usr/bin/env pwsh
# Minesport Build Script

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Minesport Build Script" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "Target: Windows / amd64" -ForegroundColor DarkGray
Write-Host ""

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

Write-Host "[2/3] Building Java engine..." -ForegroundColor Yellow
Push-Location engine
& .\gradlew.bat jar
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Java build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}
$engineJar = Get-ChildItem -Path "build\libs\minesport-engine-*.jar" -File | Select-Object -First 1
Pop-Location
if (-not $engineJar) {
    Write-Host "ERROR: Java engine JAR was not produced!" -ForegroundColor Red
    exit 1
}
Write-Host "Java engine built: $($engineJar.FullName)" -ForegroundColor Green
Write-Host ""

Write-Host "[3/3] Building Go wrapper..." -ForegroundColor Yellow
Push-Location wrapper
Write-Host "  -> go mod tidy..." -ForegroundColor DarkGray
go mod tidy
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: go mod tidy failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host "  -> embedding Java engine into Minesport..." -ForegroundColor DarkGray
go run ./cmd/embed-engine -input $engineJar.FullName -output embedded_engine_generated.go
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: engine embedding failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

$gccCmd = Get-Command gcc -ErrorAction SilentlyContinue
if (-not $gccCmd -and -not $env:CC) {
    Write-Host "WARNING: No C compiler (gcc) found on PATH. Fyne requires CGO." -ForegroundColor Yellow
}

# GUI subsystem prevents a console window from appearing alongside the release EXE.
go build -tags minesport_embedded_engine -trimpath -ldflags="-H windowsgui -s -w" -o minesport.exe .
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Go build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}
Write-Host "Go wrapper built: wrapper\minesport.exe (engine embedded)" -ForegroundColor Green
Pop-Location
Write-Host ""

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " Build complete!" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  wrapper\minesport.exe       (engine embedded)" -ForegroundColor Green
Write-Host "  bridge\build\libs\*.jar    (Bridge mod)" -ForegroundColor Green
Write-Host ""
Write-Host "Run:" -ForegroundColor Cyan
Write-Host "  cd wrapper; .\minesport.exe" -ForegroundColor Cyan
Write-Host "  .\minesport.exe --java-e" -ForegroundColor Cyan
Write-Host "Diagnostics: wrapper\minesport.log" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
