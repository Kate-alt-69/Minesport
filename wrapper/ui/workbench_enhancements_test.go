package ui

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAnalyzePreviewBlockFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "blocks.json")
	data := `[
		{"x":0,"y":64,"z":0,"id":"minecraft:stone","textureTop":"stone.png"},
		{"x":1,"y":64,"z":0,"id":"minecraft:stone"},
		{"x":2,"y":64,"z":0,"id":"minecraft:dirt","textureSide":"dirt.png"}
	]`
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}

	result, err := analyzePreviewBlockFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if result.Blocks != 3 {
		t.Fatalf("blocks = %d, want 3", result.Blocks)
	}
	if result.UniqueTypes != 2 {
		t.Fatalf("unique types = %d, want 2", result.UniqueTypes)
	}
	if result.UnresolvedTextures != 1 {
		t.Fatalf("unresolved textures = %d, want 1", result.UnresolvedTextures)
	}
	if len(result.TopTypes) != 2 {
		t.Fatalf("top types = %v, want 2 entries", result.TopTypes)
	}
	if result.TopTypes[0] != "minecraft:stone × 2" {
		t.Fatalf("first top type = %q, want stone × 2", result.TopTypes[0])
	}
	if result.TopTypes[1] != "minecraft:dirt × 1" {
		t.Fatalf("second top type = %q, want dirt × 1", result.TopTypes[1])
	}
}
