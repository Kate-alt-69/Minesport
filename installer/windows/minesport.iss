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
DefaultDirName={userprofile}\kastrick's_software\minesport
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

[Dirs]
Name: "{app}\tools"
Name: "{commonpf64}\kastrick's_software\minesport\bridge-data\bundled\1.21.10"
Name: "{commonpf64}\kastrick's_software\minesport\bridge-data\compiled"; Permissions: users-modify

[Files]
Source: "{#SourceDir}\wrapper\dist\minesport.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\installer\windows\install-blender.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion
Source: "{#SourceDir}\bridge-versions\manifest.json"; DestDir: "{commonpf64}\kastrick's_software\minesport\bridge-data"; Flags: ignoreversion
Source: "{#SourceDir}\dist\bundled-bridge\minesport-bridge-0.1.0.jar"; DestDir: "{commonpf64}\kastrick's_software\minesport\bridge-data\bundled\1.21.10"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Minesport"; Filename: "{app}\minesport.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Minesport"; Filename: "{app}\minesport.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\tools\install-blender.ps1"""; StatusMsg: "Installing Blender 5.2 LTS..."; Flags: waituntilterminated; Tasks: blender
Filename: "{app}\minesport.exe"; Parameters: "--install-blender-translator"; StatusMsg: "Installing Minesport Blender translator..."; Flags: waituntilterminated runhidden runascurrentuser; Tasks: translator

[UninstallDelete]
Type: filesandordirs; Name: "{commonpf64}\kastrick's_software\minesport\bridge-data"
Type: filesandordirs; Name: "{app}\tools"
