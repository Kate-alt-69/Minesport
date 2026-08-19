#define MyAppName "Minesport"
#define MyAppVersion "0.1.0"
#define MyAppPublisher "Kastrick"
#ifndef SourceDir
  #define SourceDir "..\.."
#endif

[Setup]
AppId={{F85D8E6A-CA8A-4E87-9D52-3CB43804A322}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\Minesport
DefaultGroupName=Minesport
DisableProgramGroupPage=yes
OutputDir={#SourceDir}\dist\installer
OutputBaseFilename=Minesport-{#MyAppVersion}-Setup-x64
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
Name: "translator"; Description: "Install the Minesport Blender translator for detected Blender 4.3+ profiles"; GroupDescription: "Optional integrations:"; Flags: unchecked
Name: "fabricbridge"; Description: "Compile Minesport Fabric bridges for detected Minecraft 26.x Fabric installations"; GroupDescription: "Optional integrations:"; Flags: unchecked

[Dirs]
Name: "{app}\bridge26"
Name: "{app}\tools"

[Files]
Source: "{#SourceDir}\wrapper\dist\minesport.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\bridge26\*"; DestDir: "{app}\bridge26"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\installer\windows\install-blender.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Minesport"; Filename: "{app}\minesport.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Minesport"; Filename: "{app}\minesport.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\tools\install-blender.ps1"""; StatusMsg: "Installing Blender 5.2 LTS..."; Flags: waituntilterminated; Tasks: blender
Filename: "{app}\minesport.exe"; Parameters: "--install-blender-translator"; StatusMsg: "Installing Minesport Blender translator..."; Flags: waituntilterminated runhidden runascurrentuser; Tasks: translator
Filename: "{app}\minesport.exe"; Parameters: "--build-bridges-detected"; StatusMsg: "Compiling Minesport Fabric bridge(s)..."; Flags: waituntilterminated runhidden runascurrentuser; Tasks: fabricbridge

[UninstallDelete]
Type: filesandordirs; Name: "{app}\bridge26"
Type: filesandordirs; Name: "{app}\tools"
