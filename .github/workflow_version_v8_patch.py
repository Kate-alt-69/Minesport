from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


ci = Path(".github/workflows/build.yml")
replace_once(
    ci,
    '''      - name: Install and locate NSIS\n''',
    '''      - name: Resolve Windows package version\n        shell: pwsh\n        run: |\n          $ErrorActionPreference = 'Stop'\n          $inPackage = $false\n          $version = $null\n          foreach ($line in Get-Content -LiteralPath .\\desktop\\Cargo.toml) {\n            $trimmed = $line.Trim()\n            if ($trimmed -match '^\\[(.+)\\]$') { $inPackage = $Matches[1] -eq 'package'; continue }\n            if ($inPackage -and $trimmed -match '^version\\s*=\\s*"([^"]+)"$') { $version = $Matches[1]; break }\n          }\n          if ([string]::IsNullOrWhiteSpace($version) -or $version -notmatch '^\\d+\\.\\d+\\.\\d+$') {\n            throw 'desktop/Cargo.toml must contain an x.y.z [package] version.'\n          }\n          "MINESPORT_VERSION=$version" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append\n          Write-Host "Windows package version: $version"\n      - name: Install and locate NSIS\n''',
    "CI package version resolver",
)
replace_once(
    ci,
    '''          & dotnet build $project --configuration Release --output $output --no-incremental "-p:SourceDir=$env:GITHUB_WORKSPACE"\n''',
    '''          & dotnet build $project --configuration Release --output $output --no-incremental "-p:SourceDir=$env:GITHUB_WORKSPACE" "-p:AppVersion=$env:MINESPORT_VERSION"\n''',
    "CI WiX AppVersion",
)
replace_once(
    ci,
    '''          & $env:NSIS_EXE '/V2' "/DSourceDir=$env:GITHUB_WORKSPACE" (Join-Path $env:GITHUB_WORKSPACE 'installer\\windows\\minesport.nsi')\n''',
    '''          & $env:NSIS_EXE '/V2' "/DSourceDir=$env:GITHUB_WORKSPACE" "/DAppVersion=$env:MINESPORT_VERSION" (Join-Path $env:GITHUB_WORKSPACE 'installer\\windows\\minesport.nsi')\n''',
    "CI NSIS AppVersion",
)
replace_once(
    ci,
    '''          $msi = Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-x64.msi'\n          $exe = Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-Setup-x64.exe'\n''',
    '''          $msi = Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-x64.msi"\n          $exe = Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-Setup-x64.exe"\n''',
    "CI dynamic installer verification",
)
replace_once(
    ci,
    '''            dist/installer/Minesport-0.2.1-x64.msi\n            dist/installer/Minesport-0.2.1-Setup-x64.exe\n''',
    '''            dist/installer/Minesport-*-x64.msi\n            dist/installer/Minesport-*-Setup-x64.exe\n''',
    "CI dynamic installer upload",
)
replace_once(
    ci,
    '''            echo "## Minesport 0.2.1 compile report"\n''',
    '''            echo "## Minesport compile report"\n''',
    "CI report heading",
)
replace_once(
    ci,
    '''            echo '✅ Rust/Slint Minesport 0.2.1 compiled, tested, and packaged cleanly.' | tee -a "$GITHUB_STEP_SUMMARY"\n''',
    '''            echo '✅ Rust/Slint Minesport compiled, tested, and packaged cleanly.' | tee -a "$GITHUB_STEP_SUMMARY"\n''',
    "CI report success",
)

release = Path(".github/workflows/release-windows.yml")
replace_once(
    release,
    '''      - uses: actions/download-artifact@v7\n''',
    '''      - name: Resolve Windows package version\n        shell: pwsh\n        run: |\n          $ErrorActionPreference = 'Stop'\n          $inPackage = $false\n          $version = $null\n          foreach ($line in Get-Content -LiteralPath .\\desktop\\Cargo.toml) {\n            $trimmed = $line.Trim()\n            if ($trimmed -match '^\\[(.+)\\]$') { $inPackage = $Matches[1] -eq 'package'; continue }\n            if ($inPackage -and $trimmed -match '^version\\s*=\\s*"([^"]+)"$') { $version = $Matches[1]; break }\n          }\n          if ([string]::IsNullOrWhiteSpace($version) -or $version -notmatch '^\\d+\\.\\d+\\.\\d+$') {\n            throw 'desktop/Cargo.toml must contain an x.y.z [package] version.'\n          }\n          "MINESPORT_VERSION=$version" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append\n          Write-Host "Signed package version: $version"\n\n      - uses: actions/download-artifact@v7\n''',
    "release package version resolver",
)
replace_once(
    release,
    '''          & dotnet build $project --configuration Release --output $output --no-incremental "-p:SourceDir=$env:GITHUB_WORKSPACE"\n''',
    '''          & dotnet build $project --configuration Release --output $output --no-incremental "-p:SourceDir=$env:GITHUB_WORKSPACE" "-p:AppVersion=$env:MINESPORT_VERSION"\n''',
    "release WiX AppVersion",
)
replace_once(
    release,
    '''          & $env:NSIS_EXE '/V2' "/DSourceDir=$env:GITHUB_WORKSPACE" (Join-Path $env:GITHUB_WORKSPACE 'installer\\windows\\minesport.nsi')\n''',
    '''          & $env:NSIS_EXE '/V2' "/DSourceDir=$env:GITHUB_WORKSPACE" "/DAppVersion=$env:MINESPORT_VERSION" (Join-Path $env:GITHUB_WORKSPACE 'installer\\windows\\minesport.nsi')\n''',
    "release NSIS AppVersion",
)
replace_once(
    release,
    '''            (Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-Setup-x64.exe'),\n            (Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-x64.msi')\n''',
    '''            (Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-Setup-x64.exe"),\n            (Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-x64.msi")\n''',
    "release signed installer paths",
)
replace_once(
    release,
    '''          $setup = Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-Setup-x64.exe'\n          $msi = Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-x64.msi'\n''',
    '''          $setup = Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-Setup-x64.exe"\n          $msi = Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-x64.msi"\n''',
    "release verify installer paths",
)
replace_once(
    release,
    '''            (Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-Setup-x64.exe'),\n            (Join-Path $env:GITHUB_WORKSPACE 'dist\\installer\\Minesport-0.2.1-x64.msi'),\n''',
    '''            (Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-Setup-x64.exe"),\n            (Join-Path $env:GITHUB_WORKSPACE "dist\\installer\\Minesport-$env:MINESPORT_VERSION-x64.msi"),\n''',
    "release upload installer paths",
)
