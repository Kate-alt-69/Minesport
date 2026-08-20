package ipc

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// ResolveOverworldRegion returns the directory that should be passed to the
// Java engine as the world root for Overworld region access, plus the selected
// region directory itself. Minecraft 26.1+ stores the Overworld under
// dimensions/minecraft/overworld, while older worlds use the world root.
// Modern storage is intentionally checked first so stale legacy data cannot
// win when both layouts exist.
func ResolveOverworldRegion(worldPath string) (storageRoot, regionDir string, err error) {
	worldPath = filepath.Clean(worldPath)
	candidates := []string{
		filepath.Join(worldPath, "dimensions", "minecraft", "overworld", "region"),
		filepath.Join(worldPath, "region"),
	}

	for _, candidate := range candidates {
		ok, checkErr := hasRegionFiles(candidate)
		if checkErr != nil && !os.IsNotExist(checkErr) {
			continue
		}
		if ok {
			return filepath.Dir(candidate), candidate, nil
		}
	}

	return "", "", fmt.Errorf(
		"no Overworld region files found; checked: %s",
		strings.Join(candidates, ", "),
	)
}

func hasRegionFiles(regionDir string) (bool, error) {
	entries, err := os.ReadDir(regionDir)
	if err != nil {
		return false, err
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(entry.Name()))
		if ext == ".mca" || ext == ".mcr" {
			return true, nil
		}
	}
	return false, nil
}

func prepareWorldStoragePayload(payload map[string]interface{}) (map[string]interface{}, error) {
	command, _ := payload["command"].(string)
	if command != "heightmap" {
		return payload, nil
	}

	worldPath, _ := payload["worldPath"].(string)
	if strings.TrimSpace(worldPath) == "" {
		return payload, nil
	}

	storageRoot, _, err := ResolveOverworldRegion(worldPath)
	if err != nil {
		return nil, err
	}

	prepared := make(map[string]interface{}, len(payload))
	for key, value := range payload {
		prepared[key] = value
	}
	prepared["worldPath"] = storageRoot
	return prepared, nil
}
