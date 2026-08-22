package cacheclean

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRemoveAllDeletesRegenerableCachesButKeepsBundledBridge(t *testing.T) {
	root := t.TempDir()
	cacheRoot := filepath.Join(root, "cache")
	dataRoot := filepath.Join(root, "data")
	bridgeData := filepath.Join(root, "bridge-data")

	t.Setenv("MINESPORT_CACHE_DIR", cacheRoot)
	t.Setenv("MINESPORT_DATA_DIR", dataRoot)
	t.Setenv("MINESPORT_BRIDGE_DATA", bridgeData)

	mustWrite := func(path string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("cache"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	mustWrite(filepath.Join(cacheRoot, "runtime-registry", "1.21.10", "registry.json"))
	mustWrite(filepath.Join(cacheRoot, "heightmaps", "test.cache"))
	mustWrite(filepath.Join(cacheRoot, "toolchains", "jdk-21", "bin", "javac"))
	mustWrite(filepath.Join(cacheRoot, "bridge-build", "1.21.11", "build.tmp"))
	mustWrite(filepath.Join(cacheRoot, "bridges", "1.21.11", "minesport-bridge-1.21.11.jar"))
	mustWrite(filepath.Join(bridgeData, "compiled", "26.2", "minesport-bridge-26.2.jar"))
	bundled := filepath.Join(bridgeData, "bundled", "1.21.10", "minesport-bridge-0.1.0.jar")
	mustWrite(bundled)

	result, err := RemoveAll()
	if err != nil {
		t.Fatalf("RemoveAll: %v", err)
	}
	if len(result.RemovedPaths) == 0 {
		t.Fatal("expected at least one cache path to be removed")
	}
	if _, err := os.Stat(cacheRoot); !os.IsNotExist(err) {
		t.Fatalf("Minesport cache root should be gone, stat err=%v", err)
	}
	if _, err := os.Stat(filepath.Join(bridgeData, "compiled")); !os.IsNotExist(err) {
		t.Fatalf("compiled Bridge cache should be gone, stat err=%v", err)
	}
	if _, err := os.Stat(bundled); err != nil {
		t.Fatalf("bundled Bridge seed must survive cache cleanup: %v", err)
	}
}

func TestValidateCacheRootRejectsDangerousDirectories(t *testing.T) {
	if err := validateCacheRoot(string(filepath.Separator)); err == nil {
		t.Fatal("filesystem root must never be accepted as a cache root")
	}
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		if err := validateCacheRoot(home); err == nil {
			t.Fatal("user home must never be accepted as a cache root")
		}
	}
}
