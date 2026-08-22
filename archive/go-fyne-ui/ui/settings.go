package ui

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"

	"github.com/kastrick/minesport/appdirs"
)

const defaultFlatterCellSize = 16

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
	// Minecraft blocks remain logical voxels while safe repeated geometry is
	// compiled into rebuildable FLATTER objects for rendering and Blender edits.
	FlatterOptimizationEnabled bool `json:"flatterOptimizationEnabled"`

	// FlatterCellSize controls the maximum spatial cell used to build one
	// FLATTER object. Smaller cells rebuild more locally; larger cells normally
	// compress more aggressively and create fewer Blender objects.
	FlatterCellSize int `json:"flatterCellSize"`

	// BlenderExportEnabled exposes Blender translation metadata controls in the
	// Workbench. The translator itself is one-shot: it creates Blender-native
	// collections, actions, bones and nodes, then does not run per-frame.
	BlenderExportEnabled bool `json:"blenderExportEnabled"`

	// BlenderTranslatorPrompted prevents the first-launch installer prompt from
	// nagging after the user explicitly answers it. Install/repair remains
	// available from the Blender/advanced integration controls.
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
		FlatterCellSize:            defaultFlatterCellSize,
		BlenderExportEnabled:       false,
		BlenderTranslatorPrompted:  false,
		ResourcePackPaths:          nil,
		DataPackPaths:              nil,
	}
}

func settingsPath() (string, error) {
	return appdirs.SettingsPath(), nil
}

func legacySettingsPath() (string, error) {
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
		// One-time compatibility migration from the old unscoped config folder.
		// The old file is left untouched so downgrades remain safe.
		if legacy, legacyErr := legacySettingsPath(); legacyErr == nil && filepath.Clean(legacy) != filepath.Clean(path) {
			if legacyData, readErr := os.ReadFile(legacy); readErr == nil {
				data = legacyData
				err = nil
			}
		}
	}
	if err != nil {
		return DefaultSettings()
	}
	var settings Settings
	if err := json.Unmarshal(data, &settings); err != nil {
		return DefaultSettings()
	}
	settings.FlatterCellSize = normalizeFlatterCellSize(settings.FlatterCellSize)

	// Persist migrated settings into the canonical LocalAppData/app-data root.
	if _, statErr := os.Stat(path); os.IsNotExist(statErr) {
		_ = settings.Save()
	}
	return settings
}

func normalizeFlatterCellSize(size int) int {
	switch size {
	case 8, 16, 32, 64:
		return size
	default:
		return defaultFlatterCellSize
	}
}

func (settings Settings) Save() error {
	settings.FlatterCellSize = normalizeFlatterCellSize(settings.FlatterCellSize)
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
