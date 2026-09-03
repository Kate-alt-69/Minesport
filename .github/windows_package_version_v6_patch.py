from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


nsis = Path("installer/windows/minesport.nsi")
replace_once(
    nsis,
    '  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_CORE} "Install Minesport 0.2.1, its independently replaceable engine sidecar, and embedded loader Export Workers."',
    '  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_CORE} "Install Minesport ${APP_VERSION}, its independently replaceable engine sidecar, and embedded loader Export Workers."',
    "NSIS core description",
)
replace_once(
    nsis,
    '  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_TRANSLATOR} "Install the Minesport 0.2.1 Blender translator into detected Blender 4.3+ profiles."',
    '  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_TRANSLATOR} "Install the Minesport Blender translator into detected Blender 4.3+ profiles."',
    "NSIS translator description",
)

inno = Path("installer/windows/minesport.iss")
replace_once(
    inno,
    'Filename: "{app}\\minesport.exe"; Parameters: "--install-blender-translator"; StatusMsg: "Installing Minesport 0.2.1 Blender translator..."; Flags: waituntilterminated runhidden runascurrentuser; Tasks: translator',
    'Filename: "{app}\\minesport.exe"; Parameters: "--install-blender-translator"; StatusMsg: "Installing Minesport Blender translator..."; Flags: waituntilterminated runhidden runascurrentuser; Tasks: translator',
    "Inno translator status",
)

product = Path("installer/windows/Product.wxs")
replace_once(
    product,
    '    <Feature Id="CoreFeature" Title="Minesport 0.2.1" Level="1" AllowAbsent="no">',
    '    <Feature Id="CoreFeature" Title="Minesport $(var.AppVersion)" Level="1" AllowAbsent="no">',
    "WiX core feature title",
)
replace_once(
    product,
    '    <Feature Id="TranslatorFeature" Title="Minesport 0.2.1 Blender translator" Description="Installs the Minesport 0.2.1 translator into detected Blender 4.3+ profiles." Level="2">',
    '    <Feature Id="TranslatorFeature" Title="Minesport Blender translator" Description="Installs the Minesport translator into detected Blender 4.3+ profiles." Level="2">',
    "WiX translator feature metadata",
)
replace_once(
    product,
    '''          <Component Id="EngineSidecar" Guid="*">
            <File
                Id="MinesportEngineExe"
                Source="$(var.SourceDir)\\dist\\source\\minesport-engine.exe"
                Name="minesport-engine.exe"
                KeyPath="yes" />
            <File
                Id="MinesportEngineManifest"
                Source="$(var.SourceDir)\\dist\\source\\minesport-engine.json"
                Name="minesport-engine.json" />
          </Component>''',
    '''          <Component Id="EngineSidecar" Guid="*">
            <File
                Id="MinesportEngineExe"
                Source="$(var.SourceDir)\\dist\\source\\minesport-engine.exe"
                Name="minesport-engine.exe"
                KeyPath="yes" />
          </Component>

          <Component Id="EngineManifest" Guid="*">
            <File
                Id="MinesportEngineManifest"
                Source="$(var.SourceDir)\\dist\\source\\minesport-engine.json"
                Name="minesport-engine.json"
                KeyPath="yes" />
          </Component>''',
    "WiX engine sidecar split",
)
replace_once(
    product,
    '      <ComponentRef Id="EngineSidecar" />',
    '      <ComponentRef Id="EngineSidecar" />\n      <ComponentRef Id="EngineManifest" />',
    "WiX engine manifest feature reference",
)
