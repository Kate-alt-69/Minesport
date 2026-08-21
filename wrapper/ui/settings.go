package ui

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// Settings holds global settings that persist across sessions.
type Settings struct {
	DebugMode     bool `json:"debugMode"`
	SelectByModel bool `json:"selectByModel"`

	// OptimizeOutputEnabled keeps the historical JSON key for compatibility,
	// but in the current UI it specifically controls export-time face culling.
	// Mesh optimization (welding/atlas) is selected independently per export.
	OptimizeOutputEnabled bool `json:"optimizeOutputEnabled"`

	// HiddenBlockCullingEnabled enables the experimental world-visibility pass
	// that omits blocks proven to be completely enclosed by six neighboring
	// full faces. It is independent of face culling.
	HiddenBlockCullingEnabled bool `json:"hiddenBlockCullingEnabled"`

	// FlatterOptimizationEnabled enables the lossless FLATTER geometry compiler.
	// Safe full cubes are stored as logical blocks but exported as chunk-local
	// greedy surfaces that Minesport Translator 0.1.4 can materialize on demand.
	FlatterOptimizationEnabled bool `json:"flatterOptimizationEnabled"`

	// BlenderExportEnabled exposes Blender translation metadata controls in the
	// World Inspector. The translator itself is one-shot: it creates Blender-
	// native collections, actions, bones and nodes, then does not run per-frame.
	BlenderExportEnabled bool `json:"blenderExportEnabled"`

	// BlenderTranslatorPrompted prevents the first-launch installer prompt from
	// nagging after the user explicitly answers it. Install/repair remains
	// available from Settings -> Advanced -> Model -> Blender Export.
	BlenderTranslatorPrompted bool `json:"blenderTranslatorPrompted"`

	ResourcePackPaths []string `json:"resourcePackPaths"`
	DataPackPaths     []string `json:"dataPackPaths"`
}

func DefaultSettings() Settings {
	return Settings{
		DebugMode:                  false,
		SelectByModel:              false,
		OptimizeOutputEnabled:      false,
		HiddenBlockCullingEnabled:  false,
		FlatterOptimizationEnabled: false,
		BlenderExportEnabled:       false,
		BlenderTranslatorPrompted:  false,
		ResourcePackPaths:          nil,
		DataPackPaths:              nil,
	}
}

func settingsPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "minesport", "settings.json"), nil
}

func LoadSettings() Settings {
	path, err := settingsPath()
	if err != nil {
		return DefaultSettings()
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return DefaultSettings()
	}
	var settings Settings
	if err := json.Unmarshal(data, &settings); err != nil {
		return DefaultSettings()
	}
	return settings
}

func (settings Settings) Save() error {
	path, err := settingsPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}

func PathListString(paths []string) string {
	return strings.Join(paths, ";")
}
