!ifndef SourceDir
  !define SourceDir "..\.."
!endif

!define APP_NAME "Minesport"
!define APP_VERSION "0.2.1"
!define APP_PUBLISHER "Kastrick"
!define APP_EXE "minesport.exe"
!define ENGINE_EXE "minesport-engine.exe"
!define ENGINE_MANIFEST "minesport-engine.json"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Minesport"

Unicode true
Name "${APP_NAME}"
OutFile "${SourceDir}\dist\installer\Minesport-${APP_VERSION}-Setup-x64.exe"
InstallDir "$PROGRAMFILES64\kastrick's_software\minesport"
InstallDirRegKey HKLM "${UNINSTALL_KEY}" "InstallLocation"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
CRCCheck force
BrandingText "${APP_PUBLISHER}"

VIProductVersion "0.2.1.0"
VIAddVersionKey /LANG=1033 "ProductName" "${APP_NAME}"
VIAddVersionKey /LANG=1033 "ProductVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "CompanyName" "${APP_PUBLISHER}"
VIAddVersionKey /LANG=1033 "FileDescription" "Minesport Setup"
VIAddVersionKey /LANG=1033 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=1033 "LegalCopyright" "Copyright ${APP_PUBLISHER}"

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "Sections.nsh"
!include "WinVer.nsh"
!include "x64.nsh"
!include "FileFunc.nsh"

Var EngineOnly

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_NOTCHECKED
!define MUI_FINISHPAGE_NOAUTOCLOSE

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${SourceDir}\installer\windows\license.rtf"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "English"

Function .onInit
  StrCpy $EngineOnly "0"
  ${GetParameters} $R0

  ClearErrors
  ${GetOptions} $R0 "--installonly-engine" $R1
  ${IfNot} ${Errors}
    StrCpy $EngineOnly "1"
  ${EndIf}

  ClearErrors
  ${GetOptions} $R0 "--nogui" $R1
  ${IfNot} ${Errors}
    SetSilent silent
  ${EndIf}

  ; Engine repair/update is deliberately non-interactive even when callers
  ; omit --nogui. This keeps the repair contract safe for GUI startup use.
  ${If} $EngineOnly == "1"
    SetSilent silent
  ${EndIf}

  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "Minesport requires 64-bit Windows."
    Abort
  ${EndIf}
FunctionEnd

Function InstallEngineSidecar
  SetOutPath "$INSTDIR"

  ; Keep exactly one rollback generation. Each replacement rotates the
  ; currently installed engine into .prev; Minesport intentionally preserves that
  ; generation after verification so a later recovery still has a known fallback.
  Delete "$INSTDIR\${ENGINE_EXE}.prev"
  Delete "$INSTDIR\${ENGINE_MANIFEST}.prev"

  ClearErrors
  IfFileExists "$INSTDIR\${ENGINE_EXE}" engine_backup engine_manifest_backup
engine_backup:
  Rename "$INSTDIR\${ENGINE_EXE}" "$INSTDIR\${ENGINE_EXE}.prev"
  IfErrors engine_install_failed

engine_manifest_backup:
  ClearErrors
  IfFileExists "$INSTDIR\${ENGINE_MANIFEST}" manifest_backup engine_extract
manifest_backup:
  Rename "$INSTDIR\${ENGINE_MANIFEST}" "$INSTDIR\${ENGINE_MANIFEST}.prev"
  IfErrors engine_restore

engine_extract:
  ClearErrors
  File /oname=${ENGINE_EXE} "${SourceDir}\dist\source\minesport-engine.exe"
  File /oname=${ENGINE_MANIFEST} "${SourceDir}\dist\source\minesport-engine.json"
  IfErrors engine_restore
  DetailPrint "Installed Minesport engine sidecar."
  Return

engine_restore:
  Delete "$INSTDIR\${ENGINE_EXE}"
  Delete "$INSTDIR\${ENGINE_MANIFEST}"
  IfFileExists "$INSTDIR\${ENGINE_EXE}.prev" 0 +2
    Rename "$INSTDIR\${ENGINE_EXE}.prev" "$INSTDIR\${ENGINE_EXE}"
  IfFileExists "$INSTDIR\${ENGINE_MANIFEST}.prev" 0 +2
    Rename "$INSTDIR\${ENGINE_MANIFEST}.prev" "$INSTDIR\${ENGINE_MANIFEST}"

engine_install_failed:
  SetErrorLevel 20
  Quit
FunctionEnd

Section "!Minesport core (required)" SEC_CORE
  SectionIn RO
  SetShellVarContext all
  SetRegView 64
  SetOutPath "$INSTDIR"

  ${If} $EngineOnly == "1"
    Call InstallEngineSidecar
    Goto core_done
  ${EndIf}

  ; Fabric, Forge, NeoForge and Quilt Export Worker JARs remain embedded in
  ; Minesport.exe during the migration. Java engine ownership moves to the
  ; independently replaceable minesport-engine.exe sidecar.
  File /oname=minesport.exe "${SourceDir}\dist\source\Minesport.exe"
  Call InstallEngineSidecar

  SetOutPath "$INSTDIR\tools"
  File "${SourceDir}\installer\windows\install-blender.ps1"

  CreateDirectory "$SMPROGRAMS"
  CreateShortcut "$SMPROGRAMS\Minesport.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0

  WriteUninstaller "$INSTDIR\Uninstall.exe"

  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "Publisher" "${APP_PUBLISHER}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\${APP_EXE}"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "${UNINSTALL_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoRepair" 1

core_done:
SectionEnd

Section /o "Desktop shortcut" SEC_DESKTOP
  ${If} $EngineOnly == "1"
    Goto desktop_done
  ${EndIf}
  SetShellVarContext all
  CreateShortcut "$DESKTOP\Minesport.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
desktop_done:
SectionEnd

Section /o "Install Blender 5.2 LTS" SEC_BLENDER
  ${If} $EngineOnly == "1"
    Goto blender_done
  ${EndIf}
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\tools\install-blender.ps1"' $0
  ${If} $0 != 0
    DetailPrint "WARNING: Blender installation returned exit code $0."
  ${EndIf}
blender_done:
SectionEnd

Section /o "Install Minesport Blender translator" SEC_TRANSLATOR
  ${If} $EngineOnly == "1"
    Goto translator_done
  ${EndIf}
  ExecWait '"$INSTDIR\${APP_EXE}" --install-blender-translator' $0
  ${If} $0 != 0
    DetailPrint "WARNING: Blender translator installation returned exit code $0."
  ${EndIf}
translator_done:
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_CORE} "Install Minesport 0.2.1, its independently replaceable engine sidecar, and embedded loader Export Workers."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DESKTOP} "Create a Minesport shortcut on the desktop."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_BLENDER} "Download and install Blender 5.2 LTS from the official Blender Foundation mirror."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_TRANSLATOR} "Install the Minesport 0.2.1 Blender translator into detected Blender 4.3+ profiles."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  SetShellVarContext all
  SetRegView 64
  Delete "$DESKTOP\Minesport.lnk"
  Delete "$SMPROGRAMS\Minesport.lnk"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\${ENGINE_EXE}"
  Delete "$INSTDIR\${ENGINE_EXE}.prev"
  Delete "$INSTDIR\${ENGINE_MANIFEST}"
  Delete "$INSTDIR\${ENGINE_MANIFEST}.prev"
  Delete "$INSTDIR\tools\install-blender.ps1"
  Delete "$INSTDIR\Uninstall.exe"
  ; Clean up bridge-data left by pre-embedded Minesport installs.
  RMDir /r "$INSTDIR\bridge-data"
  RMDir "$INSTDIR\tools"
  RMDir "$INSTDIR"
  DeleteRegKey HKLM "${UNINSTALL_KEY}"
SectionEnd
