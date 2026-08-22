package ui

import (
	"testing"

	"github.com/kastrick/minesport/ipc"
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

func TestAddPreviewContextPadsSmallSelection(t *testing.T) {
	input := ipc.ListBlocksParams{MinX: 10, MaxX: 20, MinY: 50, MaxY: 70, MinZ: -4, MaxZ: 8}
	got := addPreviewContext(input)
	if got.MinX != -22 || got.MaxX != 52 || got.MinY != 34 || got.MaxY != 86 || got.MinZ != -36 || got.MaxZ != 40 {
		t.Fatalf("padded bounds = X %d..%d Y %d..%d Z %d..%d", got.MinX, got.MaxX, got.MinY, got.MaxY, got.MinZ, got.MaxZ)
	}
}

func TestAddPreviewContextLeavesLargeSelectionUnchanged(t *testing.T) {
	input := ipc.ListBlocksParams{MinX: -256, MaxX: 256, MinY: -64, MaxY: 320, MinZ: -256, MaxZ: 256}
	got := addPreviewContext(input)
	if got.MinX != input.MinX || got.MaxX != input.MaxX || got.MinY != input.MinY || got.MaxY != input.MaxY || got.MinZ != input.MinZ || got.MaxZ != input.MaxZ {
		t.Fatalf("large preview bounds changed: %#v", got)
	}
}
