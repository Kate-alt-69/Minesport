package ui

import (
	"testing"

	"github.com/kastrick/minesport/viewer"
)

func TestNormalizedLoader(t *testing.T) {
	tests := map[string]string{
		"Fabric":          "fabric",
		"Forge":           "forge",
		"NeoForge 21.1":   "neoforge",
		"Quilt":           "quilt",
		"Vanilla release": "vanilla",
	}
	for input, want := range tests {
		if got := normalizedLoader(input); got != want {
			t.Errorf("normalizedLoader(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestOrderedBounds(t *testing.T) {
	minB, maxB := orderedBounds([3]int{8, -4, 12}, [3]int{-2, 9, 3})
	if minB != [3]int{-2, -4, 3} || maxB != [3]int{8, 9, 12} {
		t.Fatalf("orderedBounds = %v..%v", minB, maxB)
	}
}

func TestEmbeddedViewerCullsSharedFacesAndRenders(t *testing.T) {
	preview, err := NewEmbeddedViewer([]viewer.Block{
		{X: 0, Y: 0, Z: 0, R: 120, G: 160, B: 90},
		{X: 1, Y: 0, Z: 0, R: 120, G: 160, B: 90},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := len(preview.faces); got != 10 {
		t.Fatalf("visible faces = %d, want 10", got)
	}
	if got := preview.render(320, 240).Bounds(); got.Dx() != 320 || got.Dy() != 240 {
		t.Fatalf("render bounds = %v", got)
	}
	if len(preview.hits) == 0 {
		t.Fatal("render did not produce selectable faces")
	}
}
