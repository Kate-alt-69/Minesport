package blendertranslator

import (
	"bytes"
	"embed"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
)

const Version = "0.1.7"

// The translator is bundled into Minesport so release builds do not depend on
// loose Python files beside minesport.exe.
//
//go:embed minesport_translator/*.py
var translatorFiles embed.FS

type Target struct {
	Version   string
	AddonsDir string
}

type InstallationStatus struct {
	Detected  int
	Installed int
	UpToDate  int
}

func (status InstallationStatus) Complete() bool {
	return status.Detected > 0 && status.UpToDate == status.Detected
}

func CurrentStatus() InstallationStatus {
	targets := DiscoverTargets()
	status := InstallationStatus{Detected: len(targets)}
	for _, target := range targets {
		destination := filepath.Join(target.AddonsDir, "minesport_translator")
		if _, err := os.Stat(filepath.Join(destination, "__init__.py")); err == nil {
			status.Installed++
			if translatorFilesCurrent(destination) {
				status.UpToDate++
			}
		}
	}
	return status
}

func DiscoverTargets() []Target {
	roots := blenderProfileRoots()
	seen := map[string]bool{}
	var targets []Target

	for _, root := range roots {
		entries, err := os.ReadDir(root)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if !entry.IsDir() || !supportedVersion(entry.Name()) {
				continue
			}
			addons := filepath.Join(root, entry.Name(), "scripts", "addons")
			key := filepath.Clean(addons)
			if seen[key] {
				continue
			}
			seen[key] = true
			targets = append(targets, Target{Version: entry.Name(), AddonsDir: addons})
		}
	}

	if runtime.GOOS == "windows" {
		programFiles := os.Getenv("ProgramFiles")
		appData := os.Getenv("APPDATA")
		if programFiles != "" && appData != "" {
			installRoot := filepath.Join(programFiles, "Blender Foundation")
			entries, _ := os.ReadDir(installRoot)
			for _, entry := range entries {
				if !entry.IsDir() {
					continue
				}
				version := strings.TrimSpace(strings.TrimPrefix(entry.Name(), "Blender "))
				if version == entry.Name() || !supportedVersion(version) {
					continue
				}
				addons := filepath.Join(appData, "Blender Foundation", "Blender", version, "scripts", "addons")
				key := filepath.Clean(addons)
				if !seen[key] {
					seen[key] = true
					targets = append(targets, Target{Version: version, AddonsDir: addons})
				}
			}
		}
	}

	sort.Slice(targets, func(i, j int) bool { return targets[i].Version < targets[j].Version })
	return targets
}

func Install() ([]string, error) {
	targets := DiscoverTargets()
	var installed []string

	for _, target := range targets {
		destination := filepath.Join(target.AddonsDir, "minesport_translator")
		if err := os.MkdirAll(destination, 0o755); err != nil {
			return installed, fmt.Errorf("create Blender %s add-on folder: %w", target.Version, err)
		}

		err := fs.WalkDir(translatorFiles, "minesport_translator", func(path string, entry fs.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if entry.IsDir() {
				return nil
			}
			data, err := translatorFiles.ReadFile(path)
			if err != nil {
				return err
			}
			return os.WriteFile(filepath.Join(destination, filepath.Base(path)), data, 0o644)
		})
		if err != nil {
			return installed, fmt.Errorf("install Blender %s translator: %w", target.Version, err)
		}
		installed = append(installed, destination)
	}

	return installed, nil
}

func StatusText() string {
	status := CurrentStatus()
	if status.Detected == 0 {
		return fmt.Sprintf("Blender translator %s: ✕ no Blender 4.3+ profile detected", Version)
	}
	if status.Complete() {
		return fmt.Sprintf("Blender translator %s: ✓ current for %d profile(s)", Version, status.UpToDate)
	}
	if status.Installed == status.Detected {
		return fmt.Sprintf("Blender translator %s: ✕ update required for %d profile(s)", Version, status.Detected-status.UpToDate)
	}
	return fmt.Sprintf("Blender translator %s: ✕ current for %d / %d detected profile(s)", Version, status.UpToDate, status.Detected)
}

func translatorFilesCurrent(destination string) bool {
	current := true
	err := fs.WalkDir(translatorFiles, "minesport_translator", func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		expected, err := translatorFiles.ReadFile(path)
		if err != nil {
			return err
		}
		actual, err := os.ReadFile(filepath.Join(destination, filepath.Base(path)))
		if err != nil || !bytes.Equal(actual, expected) {
			current = false
		}
		return nil
	})
	return err == nil && current
}

func blenderProfileRoots() []string {
	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "windows":
		if appData := os.Getenv("APPDATA"); appData != "" {
			return []string{filepath.Join(appData, "Blender Foundation", "Blender")}
		}
	case "darwin":
		if home != "" {
			return []string{filepath.Join(home, "Library", "Application Support", "Blender")}
		}
	default:
		if xdg := os.Getenv("XDG_CONFIG_HOME"); xdg != "" {
			return []string{filepath.Join(xdg, "blender")}
		}
		if home != "" {
			return []string{filepath.Join(home, ".config", "blender")}
		}
	}
	return nil
}

func supportedVersion(value string) bool {
	value = strings.TrimSpace(value)
	parts := strings.Split(value, ".")
	if len(parts) < 2 {
		return false
	}
	major, err1 := strconv.Atoi(parts[0])
	minor, err2 := strconv.Atoi(parts[1])
	if err1 != nil || err2 != nil {
		return false
	}
	return major > 4 || (major == 4 && minor >= 3)
}
