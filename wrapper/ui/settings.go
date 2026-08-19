package ui

import (
    "encoding/json"
    "os"
    "path/filepath"
    "strings"
)

// Settings holds global settings that persist across sessions.
type Settings struct {
    DebugMode bool `json:"debugMode"`
    SelectByModel bool `json:"selectByModel"`

    // OptimizeOutputEnabled keeps the historical JSON key for compatibility,
    // but in the current UI it specifically controls export-time face culling.
    // Mesh optimization (welding/atlas) is selected independently per export.
    OptimizeOutputEnabled bool `json:"optimizeOutputEnabled"`

    // HiddenBlockCullingEnabled enables the experimental world-visibility pass
    // that omits blocks proven to be completely enclosed by six neighboring
    // FULL_BLOCKs. It is independent of face culling.
    HiddenBlockCullingEnabled bool `json:"hiddenBlockCullingEnabled"`

    ResourcePackPaths []string `json:"resourcePackPaths"`
    DataPackPaths []string `json:"dataPackPaths"`
}

func DefaultSettings() Settings {
    return Settings{DebugMode:false,SelectByModel:false,OptimizeOutputEnabled:false,HiddenBlockCullingEnabled:false,ResourcePackPaths:nil,DataPackPaths:nil}
}

func settingsPath()(string,error){dir,err:=os.UserConfigDir();if err!=nil{return "",err};return filepath.Join(dir,"minesport","settings.json"),nil}
func LoadSettings()Settings{path,err:=settingsPath();if err!=nil{return DefaultSettings()};data,err:=os.ReadFile(path);if err!=nil{return DefaultSettings()};var s Settings;if err:=json.Unmarshal(data,&s);err!=nil{return DefaultSettings()};return s}
func(s Settings)Save()error{path,err:=settingsPath();if err!=nil{return err};if err:=os.MkdirAll(filepath.Dir(path),0o755);err!=nil{return err};data,err:=json.MarshalIndent(s,"","  ");if err!=nil{return err};return os.WriteFile(path,data,0o644)}
func PathListString(paths []string)string{return strings.Join(paths,";")}
