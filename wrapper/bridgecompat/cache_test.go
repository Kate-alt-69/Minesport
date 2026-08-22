package bridgecompat

import (
	"os"
	"path/filepath"
	"testing"
)

func TestClearGeneratedCacheRefusesWhileRuntimeCacheIsLeased(t *testing.T) {
	root := t.TempDir()
	cacheRoot := filepath.Join(root, "cache")
	bridgeData := filepath.Join(root, "bridge-data")
	t.Setenv("MINESPORT_CACHE_DIR", cacheRoot)
	t.Setenv("MINESPORT_BRIDGE_DATA", bridgeData)

	compiled := filepath.Join(bridgeData, "compiled", "1.21.11", "minesport-bridge-1.21.11.jar")
	if err := os.MkdirAll(filepath.Dir(compiled), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(compiled, []byte("bridge"), 0o644); err != nil {
		t.Fatal(err)
	}

	generatedCacheUseMu.RLock()
	_, err := ClearGeneratedCache()
	generatedCacheUseMu.RUnlock()
	if err == nil {
		t.Fatal("cleanup should refuse while a runtime worker/cache user holds a lease")
	}
	if _, statErr := os.Stat(compiled); statErr != nil {
		t.Fatalf("cache was modified despite active lease: %v", statErr)
	}
}

func TestClearGeneratedCacheValidatesWholePlanBeforeDeleting(t *testing.T) {
	root := t.TempDir()
	cacheRoot := filepath.Join(root, "cache")
	t.Setenv("MINESPORT_CACHE_DIR", cacheRoot)

	valid := filepath.Join(cacheRoot, "bridges", "1.21.11", "minesport-bridge-1.21.11.jar")
	if err := os.MkdirAll(filepath.Dir(valid), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(valid, []byte("bridge"), 0o644); err != nil {
		t.Fatal(err)
	}

	// A filesystem-root override would make supportRoot()/compiled dangerously
	// broad. The whole cleanup must abort before touching the otherwise-valid
	// Minesport cache root.
	volume := filepath.VolumeName(os.TempDir())
	t.Setenv("MINESPORT_BRIDGE_DATA", volume+string(filepath.Separator))
	if _, err := ClearGeneratedCache(); err == nil {
		t.Fatal("dangerous Bridge data root should abort cleanup")
	}
	if _, err := os.Stat(valid); err != nil {
		t.Fatalf("valid cache was deleted before the full plan was validated: %v", err)
	}
}

func TestValidateGeneratedCachePathAllowsOnlyDedicatedGeneratedRoots(t *testing.T) {
	root := t.TempDir()
	for _, path := range []string{
		filepath.Join(root, "bridge-data", "compiled"),
		filepath.Join(root, "cache", "bridges"),
		filepath.Join(root, "cache", "bridge-build"),
	} {
		if err := validateGeneratedCachePath(path); err != nil {
			t.Fatalf("expected %s to be accepted: %v", path, err)
		}
	}
	if err := validateGeneratedCachePath(filepath.Join(root, "cache", "something-else")); err == nil {
		t.Fatal("unexpected generated-cache directory name should be rejected")
	}
}
