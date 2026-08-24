!ifndef SourceDir
  !define SourceDir "..\.."
!endif

!define APP_NAME "Minesport"
!define APP_VERSION "0.2.0"
!define APP_PUBLISHER "Kastrick"
!define APP_EXE "minesport.exe"
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

VIProductVersion "0.2.0.0"
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
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "Minesport requires 64-bit Windows."
    Abort
  ${EndIf}
FunctionEnd

Section "!Minesport core (required)" SEC_CORE
  SectionIn RO
  SetShellVarContext all
  SetRegView 64

  ; Fabric, Forge, NeoForge and Quilt Bridge JARs are embedded in Minesport.exe.
  SetOutPath "$INSTDIR"
  File /oname=minesport.exe "${SourceDir}\dist\source\Minesport.exe"

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
SectionEnd

Section /o "Desktop shortcut" SEC_DESKTOP
  SetShellVarContext all
  CreateShortcut "$DESKTOP\Minesport.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
SectionEnd

Section /o "Install Blender 5.2 LTS" SEC_BLENDER
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\tools\install-blender.ps1"' $0
  ${If} $0 != 0
    DetailPrint "WARNING: Blender installation returned exit code $0."
  ${EndIf}
SectionEnd

Section /o "Install Minesport Blender translator" SEC_TRANSLATOR
  ExecWait '"$INSTDIR\${APP_EXE}" --install-blender-translator' $0
  ${If} $0 != 0
    DetailPrint "WARNING: Blender translator installation returned exit code $0."
  ${EndIf}
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_CORE} "Install Minesport 0.2.0 with embedded Fabric, Forge, NeoForge and Quilt runtime Bridges."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DESKTOP} "Create a Minesport shortcut on the desktop."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_BLENDER} "Download and install Blender 5.2 LTS from the official Blender Foundation mirror."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_TRANSLATOR} "Install the Minesport 0.2.0 Blender translator into detected Blender 4.3+ profiles."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  SetShellVarContext all
  SetRegView 64
  Delete "$DESKTOP\Minesport.lnk"
  Delete "$SMPROGRAMS\Minesport.lnk"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\tools\install-blender.ps1"
  Delete "$INSTDIR\Uninstall.exe"
  ; Clean up bridge-data left by pre-embedded Minesport installs.
  RMDir /r "$INSTDIR\bridge-data"
  RMDir "$INSTDIR\tools"
  RMDir "$INSTDIR"
  DeleteRegKey HKLM "${UNINSTALL_KEY}"
SectionEnd
