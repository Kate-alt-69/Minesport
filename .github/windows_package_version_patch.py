from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


build = Path("build.ps1")
replace_once(
    build,
    '''# Minesport 0.2.1 smart build + optional packaging script.\n\n$ErrorActionPreference = 'Stop'\n$Root = Split-Path -Parent $MyInvocation.MyCommand.Path\n$AppVersion = '0.2.1'\n''',
    '''# Minesport smart build + optional packaging script.\n\n$ErrorActionPreference = 'Stop'\n$Root = Split-Path -Parent $MyInvocation.MyCommand.Path\n\nfunction Get-DesktopPackageVersion {\n    $manifest = Join-Path $Root 'desktop\\Cargo.toml'\n    $inPackage = $false\n    foreach ($line in Get-Content -LiteralPath $manifest) {\n        $trimmed = $line.Trim()\n        if ($trimmed -match '^\\[(.+)\\]$') {\n            $inPackage = $Matches[1] -eq 'package'\n            continue\n        }\n        if ($inPackage -and $trimmed -match '^version\\s*=\\s*"([^"]+)"$') {\n            $version = $Matches[1]\n            if ($version -notmatch '^\\d+\\.\\d+\\.\\d+$') {\n                throw "desktop/Cargo.toml package version must be x.y.z for Windows packaging: $version"\n            }\n            return $version\n        }\n    }\n    throw 'Could not resolve [package] version from desktop/Cargo.toml.'\n}\n\n$AppVersion = Get-DesktopPackageVersion\n''',
    "build package version source",
)
replace_once(
    build,
    '''        & $makensis '/V2' "/DSourceDir=$Root" (Join-Path $Root 'installer\\windows\\minesport.nsi')\n''',
    '''        & $makensis '/V2' "/DSourceDir=$Root" "/DAppVersion=$AppVersion" (Join-Path $Root 'installer\\windows\\minesport.nsi')\n''',
    "build NSIS AppVersion",
)
replace_once(
    build,
    '''        & $iscc "/DSourceDir=$Root" (Join-Path $Root 'installer\\windows\\minesport.iss')\n''',
    '''        & $iscc "/DSourceDir=$Root" "/DAppVersion=$AppVersion" (Join-Path $Root 'installer\\windows\\minesport.iss')\n''',
    "build Inno AppVersion",
)
replace_once(
    build,
    '''        & dotnet build $wixProject --configuration Release --output $installerOut --no-incremental "-p:SourceDir=$Root"\n''',
    '''        & dotnet build $wixProject --configuration Release --output $installerOut --no-incremental "-p:SourceDir=$Root" "-p:AppVersion=$AppVersion"\n''',
    "build WiX AppVersion",
)

nsis = Path("installer/windows/minesport.nsi")
replace_once(
    nsis,
    '''!define APP_NAME "Minesport"\n!define APP_VERSION "0.2.1"\n!define APP_PUBLISHER "Kastrick"\n''',
    '''!ifndef AppVersion\n  !error "AppVersion must be supplied from desktop/Cargo.toml"\n!endif\n\n!define APP_NAME "Minesport"\n!define APP_VERSION "${AppVersion}"\n!define APP_PUBLISHER "Kastrick"\n''',
    "NSIS dynamic AppVersion",
)
replace_once(
    nsis,
    '''VIProductVersion "0.2.1.0"\n''',
    '''VIProductVersion "${APP_VERSION}.0"\n''',
    "NSIS file version",
)

inno = Path("installer/windows/minesport.iss")
replace_once(
    inno,
    '''#define MyAppName "Minesport"\n#define MyAppVersion "0.2.1"\n#define MyAppPublisher "Kastrick"\n''',
    '''#ifndef AppVersion\n  #error AppVersion must be supplied from desktop/Cargo.toml\n#endif\n#define MyAppName "Minesport"\n#define MyAppVersion AppVersion\n#define MyAppPublisher "Kastrick"\n''',
    "Inno dynamic AppVersion",
)
replace_once(
    inno,
    '''Name: "translator"; Description: "Install the Minesport 0.2.1 Blender translator for detected Blender 4.3+ profiles"; GroupDescription: "Optional integrations:"; Flags: unchecked\n''',
    '''Name: "translator"; Description: "Install the Minesport Blender translator for detected Blender 4.3+ profiles"; GroupDescription: "Optional integrations:"; Flags: unchecked\n''',
    "Inno translator wording",
)

wixproj = Path("installer/windows/Minesport.wixproj")
replace_once(
    wixproj,
    '''    <InstallerPlatform>x64</InstallerPlatform>\n    <OutputName>Minesport-0.2.1-x64</OutputName>\n    <OutputType>Package</OutputType>\n    <DefineConstants>SourceDir=$(SourceDir)</DefineConstants>\n''',
    '''    <InstallerPlatform>x64</InstallerPlatform>\n    <OutputName>Minesport-$(AppVersion)-x64</OutputName>\n    <OutputType>Package</OutputType>\n    <DefineConstants>SourceDir=$(SourceDir);AppVersion=$(AppVersion)</DefineConstants>\n''',
    "WiX dynamic package output",
)
replace_once(
    wixproj,
    '''  <ItemGroup>\n''',
    '''  <Target Name="ValidatePackageVersion" BeforeTargets="Build">\n    <Error Condition="'$(AppVersion)' == ''" Text="AppVersion must be supplied from desktop/Cargo.toml." />\n  </Target>\n\n  <ItemGroup>\n''',
    "WiX package version validation",
)

product = Path("installer/windows/Product.wxs")
replace_once(
    product,
    '''      Version="0.2.1"\n''',
    '''      Version="$(var.AppVersion)"\n''',
    "WiX product version",
)

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
