package bridgecompat

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func TestCopyWorkerModsSkipsCrashAssistantButKeepsContentMods(t *testing.T) {
	source := t.TempDir()
	target := t.TempDir()

	writeFabricTestJar(t, filepath.Join(source, "CrashAssistant-fabric-test.jar"), "crash_assistant")
	writeFabricTestJar(t, filepath.Join(source, "example-content.jar"), "example_content")
	writeFabricTestJar(t, filepath.Join(source, "dependency.jar"), "example_dependency")

	count, err := copyWorkerMods(source, target)
	if err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatalf("copied %d mods, want 2", count)
	}
	if _, err := os.Stat(filepath.Join(target, "CrashAssistant-fabric-test.jar")); !os.IsNotExist(err) {
		t.Fatalf("Crash Assistant must not enter the runtime worker; stat err=%v", err)
	}
	for _, name := range []string{"example-content.jar", "dependency.jar"} {
		if _, err := os.Stat(filepath.Join(target, name)); err != nil {
			t.Fatalf("expected %s to remain available to the registry worker: %v", name, err)
		}
	}
}

func TestCrashAssistantFilenameFallback(t *testing.T) {
	path := filepath.Join(t.TempDir(), "CrashAssistant-fabric-26.2.jar")
	if err := os.WriteFile(path, []byte("not a zip"), 0o644); err != nil {
		t.Fatal(err)
	}
	if !shouldSkipRuntimeWorkerMod(path, filepath.Base(path)) {
		t.Fatal("Crash Assistant filename fallback was not recognized")
	}
}

func TestPreserveRuntimeWorkerDiagnosticsCopiesUsefulLogs(t *testing.T) {
	workspace := t.TempDir()
	if err := os.WriteFile(filepath.Join(workspace, "runtime-worker.log"), []byte("gradle worker output"), 0o600); err != nil {
		t.Fatal(err)
	}
	minecraftLogs := filepath.Join(workspace, "run", "logs")
	if err := os.MkdirAll(minecraftLogs, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(minecraftLogs, "latest.log"), []byte("minecraft output"), 0o600); err != nil {
		t.Fatal(err)
	}

	destination := preserveRuntimeWorkerDiagnostics(workspace, "26.2")
	if destination == "" {
		t.Fatal("expected runtime worker diagnostics to be preserved")
	}
	defer os.RemoveAll(destination)

	for name, want := range map[string]string{
		"runtime-worker.log":  "gradle worker output",
		"minecraft-latest.log": "minecraft output",
	} {
		data, err := os.ReadFile(filepath.Join(destination, name))
		if err != nil {
			t.Fatalf("read preserved %s: %v", name, err)
		}
		if string(data) != want {
			t.Fatalf("preserved %s = %q, want %q", name, string(data), want)
		}
	}
}

func writeFabricTestJar(t *testing.T, path, id string) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	entry, err := archive.Create("fabric.mod.json")
	if err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if _, err := entry.Write([]byte(`{"schemaVersion":1,"id":"` + id + `","version":"1.0.0"}`)); err != nil {
		_ = archive.Close()
		_ = file.Close()
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}
