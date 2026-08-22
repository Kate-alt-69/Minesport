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
	if result.TypeCounts["minecraft:stone"] != 2 || result.TypeCounts["minecraft:dirt"] != 1 {
		t.Fatalf("unexpected type counts: %#v", result.TypeCounts)
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

func TestBucketOptimizationPressureSeparatesRiskyGeometry(t *testing.T) {
	buckets := bucketOptimizationPressure(map[string]int{
		"minecraft:stone":       100,
		"minecraft:oak_leaves": 20,
		"minecraft:oak_stairs": 10,
		"modded:machine":        5,
	})
	if buckets.TerrainLike != 100 {
		t.Fatalf("terrain = %d, want 100", buckets.TerrainLike)
	}
	if buckets.TransparentLike != 20 {
		t.Fatalf("transparent = %d, want 20", buckets.TransparentLike)
	}
	if buckets.ShapeHeavy != 10 {
		t.Fatalf("shape-heavy = %d, want 10", buckets.ShapeHeavy)
	}
	if buckets.Other != 5 {
		t.Fatalf("other = %d, want 5", buckets.Other)
	}
}
