package ui

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestHeightmapFingerprintChangesWithLegacyRegion(t *testing.T) {
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
		t.Fatal("heightmap fingerprint did not change after the legacy region changed")
	}
}

func TestHeightmapFingerprintUsesModernOverworldRegion(t *testing.T) {
	world := t.TempDir()
	modern := filepath.Join(world, "dimensions", "minecraft", "overworld", "region")
	legacy := filepath.Join(world, "region")
	if err := os.MkdirAll(modern, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(legacy, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world, "level.dat"), []byte("level"), 0o644); err != nil {
		t.Fatal(err)
	}
	modernRegion := filepath.Join(modern, "r.0.0.mca")
	legacyRegion := filepath.Join(legacy, "r.0.0.mca")
	if err := os.WriteFile(modernRegion, []byte("modern"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(legacyRegion, []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}

	first, err := heightmapFingerprint(world)
	if err != nil {
		t.Fatal(err)
	}

	// Mutating stale legacy data must not invalidate the 26.x cache when the
	// modern Overworld directory is present.
	stamp := time.Now().Add(2 * time.Second)
	if err := os.WriteFile(legacyRegion, []byte("stale changed"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(legacyRegion, stamp, stamp); err != nil {
		t.Fatal(err)
	}
	second, err := heightmapFingerprint(world)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatal("stale legacy region unexpectedly changed the modern-world fingerprint")
	}

	if err := os.WriteFile(modernRegion, []byte("modern changed"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(modernRegion, stamp.Add(2*time.Second), stamp.Add(2*time.Second)); err != nil {
		t.Fatal(err)
	}
	third, err := heightmapFingerprint(world)
	if err != nil {
		t.Fatal(err)
	}
	if second == third {
		t.Fatal("modern 26.x region change did not invalidate the heightmap fingerprint")
	}
}
