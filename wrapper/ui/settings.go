package ui

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

// Settings holds every setting that applies GLOBALLY across all sessions —
// as opposed to the per-export toggles in the sidebar (format, mode, region
// bounds), which only apply to the export you're about to run.
//
// Persisted as JSON under the OS config directory:
//
//	Windows: %AppData%\\minesport\\settings.json
//	Linux:   ~/.config/minesport/settings.json
//	macOS:   ~/Library/Application Support/minesport/settings.json
type Settings struct {
	DebugMode bool `json:"debugMode"`

	// SelectByModel: click a block in the map view and Minesport reports
	// whether it looks player-placed vs. world-gen/mod-generated. This is a
	// best-effort heuristic, not a guarantee.
	SelectByModel bool `json:"selectByModel"`

	// OptimizeOutputEnabled is the global gate for the export-time face
	// culling/geometry optimization switch. Kept under the old JSON key for
	// backwards compatibility with existing settings files.
	OptimizeOutputEnabled bool `json:"optimizeOutputEnabled"`

	// HiddenBlockCullingEnabled enables the experimental world-visibility pass
	// that omits blocks proven to be completely enclosed by six neighboring
	// FULL_BLOCKs. The original block occupancy is retained for face culling,
	// so removing an interior block cannot create bogus exposed faces.
	//
	// This pass is intentionally conservative: only blocks with all six
	// neighboring positions present AND classified as FULL_BLOCK are removed.
	// Uncertain/modded/partial geometry is kept. It currently runs through
	// the existing Optimize Output pipeline; enable Optimize Output for an
	// export when this experimental option is on.
	HiddenBlockCullingEnabled bool `json:"hiddenBlockCullingEnabled"`

	// Resource pack paths (folders or .zip files), highest priority first.
	ResourcePackPaths []string `json:"resourcePackPaths"`

	// Data pack paths (folders or .zip files). Only used for block tags.
	DataPackPaths []string `json:"dataPackPaths"`
}

func DefaultSettings() Settings {
	return Settings{
		DebugMode:                 false,
		SelectByModel:             false,
		OptimizeOutputEnabled:     false,
		HiddenBlockCullingEnabled: false,
		ResourcePackPaths:         nil,
		DataPackPaths:             nil,
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
	var s Settings
	if err := json.Unmarshal(data, &s); err != nil {
		return DefaultSettings()
	}
	return s
}

func (s Settings) Save() error {
	path, err := settingsPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}

func PathListString(paths []string) string {
	return strings.Join(paths, ";")
}
