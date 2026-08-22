package ui

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMinesportProjectRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "house"+minesportProjectExtension)
	want := MinesportProject{
		Schema:    minesportProjectSchema,
		ProjectID: "0123456789abcdef0123456789abcdef",
		World: ProjectWorld{
			Path:             filepath.Join(dir, "world"),
			Name:             "Creative Chinese",
			MinecraftVersion: "1.21.10",
			Loader:           "fabric",
			ModsPath:         filepath.Join(dir, "mods"),
		},
		Selection: ProjectSelection{
			Mode:   "bubble",
			Min:    [3]int{-32, 40, -64},
			Max:    [3]int{32, 120, 64},
			Center: [3]int{0, 80, 0},
			Radius: [3]int{32, 40, 64},
		},
		Export: ProjectExport{
			Name:       "house_export",
			OutputPath: filepath.Join(dir, "exports"),
			Format:     "glTF 2.0",
			Objects:    "Grouped",
			Optimize:   true,
		},
		Pipeline: ProjectPipeline{
			FaceCulling:        true,
			HiddenBlockCulling: true,
			Flatter:            true,
			Blender:            true,
			SelectByModel:      true,
			ResourcePacks:      []string{"pack-a", "pack-b"},
			DataPacks:          []string{"data-a"},
		},
		Preset: "Blender Lightweight",
	}

	if err := writeMinesportProject(path, want); err != nil {
		t.Fatal(err)
	}
	got, err := readMinesportProject(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.ProjectID != want.ProjectID {
		t.Fatalf("project id = %q, want %q", got.ProjectID, want.ProjectID)
	}
	if got.World.Name != want.World.Name || got.Selection != want.Selection || got.Export != want.Export {
		t.Fatalf("project round trip changed core state: %#v", got)
	}
	if !got.Pipeline.Flatter || !got.Pipeline.Blender || len(got.Pipeline.ResourcePacks) != 2 {
		t.Fatalf("project round trip changed pipeline state: %#v", got.Pipeline)
	}
}

func TestReadMinesportProjectRejectsUnknownSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "future"+minesportProjectExtension)
	if err := os.WriteFile(path, []byte(`{"schema":99,"projectId":"abcd","world":{"path":"x"}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := readMinesportProject(path); err == nil {
		t.Fatal("future project schema should be rejected")
	}
}

func TestReadMinesportProjectGeneratesMissingID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy"+minesportProjectExtension)
	if err := os.WriteFile(path, []byte(`{"schema":1,"world":{"path":"x"}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	project, err := readMinesportProject(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(project.ProjectID) < 8 {
		t.Fatalf("generated project id is unexpectedly short: %q", project.ProjectID)
	}
}
