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
//	Windows: %AppData%\minesport\settings.json
//	Linux:   ~/.config/minesport/settings.json
//	macOS:   ~/Library/Application Support/minesport/settings.json
type Settings struct {
	// Debug mode: when true, a separate debug console window opens showing
	// the raw engine log stream. When false (default), the main window
	// stays clean — no console output visible anywhere in the primary UI.
	DebugMode bool `json:"debugMode"`

	// SelectByModel: click a block in the map view and Minesport reports
	// whether it looks player-placed vs. world-gen/mod-generated. This is a
	// best-effort heuristic, not a guarantee — surfaced here as an
	// explicit opt-in so nobody mistakes it for ground truth. The actual
	// detection logic isn't implemented yet; this flag exists so the UI
	// and settings plumbing are ready when it is.
	SelectByModel bool `json:"selectByModel"`

	// OptimizeOutputEnabled is the global gate for the export-time face
	// culling/geometry optimization switch. The actual per-export checkbox
	// lives in the Export card, but the master switch is intentionally in
	// Basic Settings so the user can turn face culling availability on/off
	// without digging through an Advanced menu.
	//
	// Kept under the old JSON key for backwards compatibility with existing
	// settings files.
	OptimizeOutputEnabled bool `json:"optimizeOutputEnabled"`

	// Resource pack paths (folders or .zip files), highest priority first.
	// These override vanilla AND mod-provided block visuals, same as
	// applying them in-game.
	ResourcePackPaths []string `json:"resourcePackPaths"`

	// Data pack paths (folders or .zip files). Only used for block tags —
	// see the DataPackBlockTagReader doc comment on the engine side for
	// why data packs can't contribute geometry/textures. Leave empty to
	// auto-discover packs already bundled in the world's own datapacks/ folder.
	DataPackPaths []string `json:"dataPackPaths"`
}

// DefaultSettings returns the settings a fresh install starts with.
func DefaultSettings() Settings {
	return Settings{
		DebugMode:            false,
		SelectByModel:        false,
		OptimizeOutputEnabled: false,
		ResourcePackPaths:    nil,
		DataPackPaths:        nil,
	}
}

func settingsPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "minesport", "settings.json"), nil
}

// LoadSettings reads settings from disk, returning defaults if the file
// doesn't exist yet or can't be parsed (never fails the app over this).
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

// Save writes settings to disk, creating the config directory if needed.
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

// PathListString joins paths with ';' — the separator the Java engine's
// IPC options map expects for resourcePacks/dataPacks (see IpcMode.getPathList).
func PathListString(paths []string) string {
	return strings.Join(paths, ";")
}
