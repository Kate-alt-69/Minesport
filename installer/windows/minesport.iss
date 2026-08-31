#define MyAppName "Minesport"
#define MyAppVersion "0.2.1"
#define MyAppPublisher "Kastrick"
#ifndef SourceDir
  #define SourceDir "..\.."
#endif

[Setup]
AppId={{F85D8E6A-CA8A-4E87-9D52-3CB43804A322}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\kastrick's_software\minesport
DefaultGroupName=Minesport
DisableProgramGroupPage=yes
OutputDir={#SourceDir}\dist\installer
OutputBaseFilename=Minesport-{#MyAppVersion}-Inno-Setup-x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\minesport.exe
SetupLogging=yes
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "blender"; Description: "Install Blender 5.2 LTS (downloads from blender.org)"; GroupDescription: "Optional integrations:"; Flags: unchecked
Name: "translator"; Description: "Install the Minesport 0.2.1 Blender translator for detected Blender 4.3+ profiles"; GroupDescription: "Optional integrations:"; Flags: unchecked

[Dirs]
Name: "{app}\tools"

[Files]
; Loader Bridge JARs remain embedded in Minesport.exe. The Java engine runtime
; is owned by the independently replaceable minesport-engine.exe sidecar.
Source: "{#SourceDir}\dist\source\Minesport.exe"; DestDir: "{app}"; DestName: "minesport.exe"; Flags: ignoreversion
Source: "{#SourceDir}\dist\source\minesport-engine.exe"; DestDir: "{app}"; DestName: "minesport-engine.exe"; Flags: ignoreversion
Source: "{#SourceDir}\dist\source\minesport-engine.json"; DestDir: "{app}"; DestName: "minesport-engine.json"; Flags: ignoreversion
Source: "{#SourceDir}\installer\windows\install-blender.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Minesport"; Filename: "{app}\minesport.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Minesport"; Filename: "{app}\minesport.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\tools\install-blender.ps1"""; StatusMsg: "Installing Blender 5.2 LTS..."; Flags: waituntilterminated; Tasks: blender
Filename: "{app}\minesport.exe"; Parameters: "--install-blender-translator"; StatusMsg: "Installing Minesport 0.2.1 Blender translator..."; Flags: waituntilterminated runhidden runascurrentuser; Tasks: translator

[UninstallDelete]
; Remove rollback copies left by a completed engine-only repair/update.
Type: files; Name: "{app}\minesport-engine.exe.prev"
Type: files; Name: "{app}\minesport-engine.json.prev"
; Remove bridge-data left by older pre-embedded installs.
Type: filesandordirs; Name: "{commonpf64}\kastrick's_software\minesport\bridge-data"
Type: filesandordirs; Name: "{app}\tools"
