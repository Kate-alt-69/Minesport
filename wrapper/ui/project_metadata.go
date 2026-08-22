package ui

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func minesportSidecarPath(exportPath string) string {
	ext := filepath.Ext(exportPath)
	return strings.TrimSuffix(exportPath, ext) + ".minesport.json"
}

// stampProjectMetadata adds the stable Minesport project identity to an
// existing export sidecar. Exports without a sidecar are valid (for example a
// raw non-Blender export), so a missing sidecar is intentionally not an error.
func stampProjectMetadata(exportPath, projectID, projectPath string) error {
	projectID = strings.TrimSpace(projectID)
	if exportPath == "" || projectID == "" {
		return nil
	}

	sidecar := minesportSidecarPath(exportPath)
	data, err := os.ReadFile(sidecar)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var root map[string]any
	if err := json.Unmarshal(data, &root); err != nil {
		return fmt.Errorf("decode %s: %w", filepath.Base(sidecar), err)
	}
	root["projectId"] = projectID
	if strings.TrimSpace(projectPath) != "" {
		root["projectPath"] = filepath.Clean(projectPath)
	} else {
		delete(root, "projectPath")
	}

	updated, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return err
	}
	updated = append(updated, '\n')

	temp, err := os.CreateTemp(filepath.Dir(sidecar), ".minesport-sidecar-*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	removeTemp := true
	defer func() {
		_ = temp.Close()
		if removeTemp {
			_ = os.Remove(tempPath)
		}
	}()

	if _, err := temp.Write(updated); err != nil {
		return err
	}
	if err := temp.Sync(); err != nil {
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tempPath, sidecar); err != nil {
		return err
	}
	removeTemp = false
	return nil
}

func (ms *MinesportApp) stampCurrentProjectMetadata(exportPath string) error {
	state := ms.currentProjectState()
	if state == nil {
		return nil
	}
	return stampProjectMetadata(exportPath, state.id, state.path)
}
