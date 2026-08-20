package ui

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestHeightmapFingerprintChangesWithRegion(t *testing.T) {
	world := t.TempDir()
	if err := os.Mkdir(filepath.Join(world, "region"), 0o755); err != nil {
		t.Fatal(err)
	}
	level := filepath.Join(world, "level.dat")
	region := filepath.Join(world, "region", "r.0.0.mca")
	if err := os.WriteFile(level, []byte("level"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(region, []byte("one"), 0o644); err != nil {
		t.Fatal(err)
	}
	first, err := heightmapFingerprint(world)
	if err != nil {
		t.Fatal(err)
	}
	stamp := time.Now().Add(2 * time.Second)
	if err := os.WriteFile(region, []byte("changed"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(region, stamp, stamp); err != nil {
		t.Fatal(err)
	}
	second, err := heightmapFingerprint(world)
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("heightmap fingerprint did not change after the region changed")
	}
}
