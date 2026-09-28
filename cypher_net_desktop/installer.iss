#define MyAppName "CYPHER_NET"
#define MyAppVersion "3.4.0"
#define MyAppPublisher "CYPHER_NET"
#define MyAppExeName "CYPHER_NET.exe"

[Setup]
AppId={{9F6C6571-0BB5-4D5D-9A11-5D1E3B340000}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\CYPHER_NET
DefaultGroupName=CYPHER_NET
DisableProgramGroupPage=yes
OutputDir=installer_out
OutputBaseFilename=CYPHER_NET_3_4_0_SETUP
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=CYPHER_NET.ico
UninstallDisplayIcon={app}\{#MyAppExeName}

[Files]
Source: "dist\CYPHER_NET.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\CYPHER_NET"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\CYPHER_NET"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch CYPHER_NET"; Flags: nowait postinstall skipifsilent
