package ui

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestStampProjectMetadata(t *testing.T) {
	dir := t.TempDir()
	exportPath := filepath.Join(dir, "scene.gltf")
	sidecar := minesportSidecarPath(exportPath)
	if err := os.WriteFile(sidecar, []byte(`{"schema":1,"generator":"Minesport","blockCount":42}`), 0o644); err != nil {
		t.Fatal(err)
	}

	projectPath := filepath.Join(dir, "scene.minesport-project")
	if err := stampProjectMetadata(exportPath, "0123456789abcdef", projectPath); err != nil {
		t.Fatal(err)
	}

	data, err := os.ReadFile(sidecar)
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := json.Unmarshal(data, &root); err != nil {
		t.Fatal(err)
	}
	if root["projectId"] != "0123456789abcdef" {
		t.Fatalf("projectId = %#v", root["projectId"])
	}
	if root["projectPath"] != filepath.Clean(projectPath) {
		t.Fatalf("projectPath = %#v", root["projectPath"])
	}
	if root["generator"] != "Minesport" {
		t.Fatal("existing sidecar metadata was lost")
	}
}

func TestStampProjectMetadataAllowsMissingSidecar(t *testing.T) {
	path := filepath.Join(t.TempDir(), "raw.obj")
	if err := stampProjectMetadata(path, "project-id", ""); err != nil {
		t.Fatalf("missing optional sidecar should not fail: %v", err)
	}
}
