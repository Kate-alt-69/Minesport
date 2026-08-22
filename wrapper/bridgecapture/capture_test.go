package bridgecapture

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCaptureWritesReusableRuntimeModelRegistry(t *testing.T) {
	dir := t.TempDir()
	server, err := start("127.0.0.1:0", dir, nil)
	if err != nil {
		t.Fatalf("start bridge capture: %v", err)
	}
	defer server.Close()

	connection, err := net.Dial("tcp", server.Addr())
	if err != nil {
		t.Fatalf("connect bridge capture: %v", err)
	}
	writer := bufio.NewWriter(connection)
	quadVertices := make([]float32, 32)
	quadVertices[0], quadVertices[1], quadVertices[2] = 0, 0, 0
	quadVertices[8], quadVertices[9], quadVertices[10] = 1, 0, 0
	quadVertices[16], quadVertices[17], quadVertices[18] = 1, 1, 0
	quadVertices[24], quadVertices[25], quadVertices[26] = 0, 1, 0
	messages := []any{
		map[string]any{
			"type":          "hello",
			"mcVersion":     "1.21.10",
			"loaderVersion": "0.17.2",
			"loadedMods":    []string{"example@1.0.0"},
		},
		map[string]any{
			"type":       "block",
			"blockId":    "example:lamp",
			"loaderType": "fabric",
			"variants": []any{
				map[string]any{
					"properties": map[string]string{"lit": "true"},
					"quads": []any{
						map[string]any{
							"vertices":  quadVertices,
							"textureId": "example:block/lamp",
							"face":      2,
							"shade":     true,
							"tintIndex": -1,
						},
					},
				},
			},
		},
		map[string]any{
			"type":    "block_light",
			"blockId": "example:lamp",
			"states": []any{
				map[string]any{
					"properties": map[string]string{"lit": "true"},
					"lightLevel": 12,
				},
			},
		},
		map[string]any{
			"type":      "texture",
			"textureId": "example:block/lamp",
			"pngBase64": "do-not-cache-me",
		},
		map[string]any{"type": "done"},
	}
	for _, message := range messages {
		data, marshalErr := json.Marshal(message)
		if marshalErr != nil {
			t.Fatalf("marshal message: %v", marshalErr)
		}
		if _, err := writer.Write(append(data, '\n')); err != nil {
			t.Fatalf("write message: %v", err)
		}
	}
	if err := writer.Flush(); err != nil {
		t.Fatalf("flush bridge messages: %v", err)
	}
	_ = connection.Close()

	fingerprint := loadedModsFingerprint([]string{"example@1.0.0"})
	path := snapshotPathAt(dir, "1.21.10", fingerprint)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if validSnapshot(path, "1.21.10", fingerprint) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !validSnapshot(path, "1.21.10", fingerprint) {
		t.Fatal("runtime model registry was not written")
	}
	if filepath.Base(path) != "registry.data" {
		t.Fatalf("runtime registry path = %q, want registry.data", path)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read runtime registry: %v", err)
	}
	if !strings.HasPrefix(string(data), registryDataMagic) {
		t.Fatalf("runtime registry is missing binary magic %q", registryDataMagic)
	}
	snapshot, err := readSnapshotFile(path)
	if err != nil {
		t.Fatalf("parse runtime registry: %v", err)
	}
	block, ok := snapshot.Blocks["example:lamp"]
	if !ok {
		t.Fatal("expected example:lamp in runtime registry")
	}
	if len(block.Variants) != 1 || len(block.Variants[0].Quads) != 1 {
		t.Fatalf("unexpected cached geometry: %#v", block.Variants)
	}
	if got := block.Variants[0].Quads[0].TextureID; got != "example:block/lamp" {
		t.Fatalf("expected texture reference only, got %q", got)
	}
	if len(block.Lights) != 1 || block.Lights[0].LightLevel != 12 || block.Lights[0].Properties["lit"] != "true" {
		t.Fatalf("unexpected light state: %#v", block.Lights)
	}
	if strings.Contains(string(data), "do-not-cache-me") {
		t.Fatal("texture image payload must not be cached")
	}
}

func TestSnapshotLookupRejectsWrongVersionAndSchema(t *testing.T) {
	dir := t.TempDir()
	fingerprint := "abc123"
	path, err := writeSnapshotAt(dir, Snapshot{
		Schema:           SnapshotSchema,
		MinecraftVersion: "1.21.10",
		ModsFingerprint:  fingerprint,
		Blocks:           map[string]RuntimeBlock{},
	})
	if err != nil {
		t.Fatalf("write snapshot: %v", err)
	}
	if validSnapshot(path, "1.21.11", fingerprint) {
		t.Fatal("snapshot lookup should not reuse another Minecraft version")
	}
	if !validSnapshot(path, "1.21.10", fingerprint) {
		t.Fatal("expected matching snapshot")
	}

	wrongSchemaPath := filepath.Join(dir, "wrong-schema.data")
	wrongSchemaFile, err := os.Create(wrongSchemaPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := writeSnapshotData(wrongSchemaFile, Snapshot{
		Schema:           999,
		MinecraftVersion: "1.21.10",
		ModsFingerprint:  fingerprint,
		Blocks:           map[string]RuntimeBlock{},
	}); err != nil {
		wrongSchemaFile.Close()
		t.Fatal(err)
	}
	if err := wrongSchemaFile.Close(); err != nil {
		t.Fatal(err)
	}
	if validSnapshot(wrongSchemaPath, "1.21.10", fingerprint) {
		t.Fatal("snapshot lookup should reject unsupported schema")
	}
}

func TestNewSnapshotPrunesOlderFingerprintForSameMinecraftVersion(t *testing.T) {
	dir := t.TempDir()
	oldPath, err := writeSnapshotAt(dir, Snapshot{
		Schema:           SnapshotSchema,
		MinecraftVersion: "1.21.10",
		ModsFingerprint:  "old-fingerprint",
		Blocks:           map[string]RuntimeBlock{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(oldPath); err != nil {
		t.Fatalf("old snapshot was not written: %v", err)
	}

	newPath, err := writeSnapshotAt(dir, Snapshot{
		Schema:           SnapshotSchema,
		MinecraftVersion: "1.21.10",
		ModsFingerprint:  "new-fingerprint",
		Blocks:           map[string]RuntimeBlock{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(newPath); err != nil {
		t.Fatalf("new snapshot missing: %v", err)
	}
	if _, err := os.Stat(oldPath); !os.IsNotExist(err) {
		t.Fatalf("stale fingerprint directory should be removed, stat err=%v", err)
	}
}

func TestStageBridgeDoesNotChangeModsFingerprintAndCleansUp(t *testing.T) {
	CleanupStaged()
	defer CleanupStaged()

	root := t.TempDir()
	mods := filepath.Join(root, "mods")
	if err := os.MkdirAll(mods, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(mods, "example-mod.jar"), []byte("example mod"), 0o644); err != nil {
		t.Fatal(err)
	}
	bridge := filepath.Join(root, "minesport-bridge.jar")
	if err := os.WriteFile(bridge, []byte("bridge"), 0o644); err != nil {
		t.Fatal(err)
	}

	before, err := ModsFingerprint(mods)
	if err != nil {
		t.Fatalf("fingerprint before stage: %v", err)
	}
	staged, err := StageBridge(bridge, mods, "1.21.10")
	if err != nil {
		t.Fatalf("stage bridge: %v", err)
	}
	if _, err := os.Stat(staged); err != nil {
		t.Fatalf("staged bridge missing: %v", err)
	}
	after, err := ModsFingerprint(mods)
	if err != nil {
		t.Fatalf("fingerprint after stage: %v", err)
	}
	if before != after {
		t.Fatalf("temporary Minesport bridge changed mod fingerprint: %s != %s", before, after)
	}

	CleanupStaged()
	if _, err := os.Stat(staged); !os.IsNotExist(err) {
		t.Fatalf("staged bridge should be removed, stat err=%v", err)
	}
}

func TestModsFingerprintChangesWhenJarContentsChange(t *testing.T) {
	root := t.TempDir()
	mods := filepath.Join(root, "mods")
	if err := os.MkdirAll(mods, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(mods, "lamp.jar")
	cache := filepath.Join(root, "hash-cache.json")
	if err := os.WriteFile(path, []byte("v1"), 0o644); err != nil {
		t.Fatal(err)
	}
	first, err := modsFingerprintAt(mods, cache)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("v2"), 0o644); err != nil {
		t.Fatal(err)
	}
	second, err := modsFingerprintAt(mods, cache)
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("content fingerprint should change after a mod JAR changes")
	}
	if _, err := os.Stat(cache); err != nil {
		t.Fatalf("per-JAR digest cache should be persisted: %v", err)
	}
}

func TestModsFingerprintIgnoresTimestampOnlyChanges(t *testing.T) {
	root := t.TempDir()
	mods := filepath.Join(root, "mods")
	if err := os.MkdirAll(mods, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(mods, "same.jar")
	cache := filepath.Join(root, "hash-cache.json")
	if err := os.WriteFile(path, []byte("same-content"), 0o644); err != nil {
		t.Fatal(err)
	}
	first, err := modsFingerprintAt(mods, cache)
	if err != nil {
		t.Fatal(err)
	}
	future := time.Now().Add(2 * time.Hour)
	if err := os.Chtimes(path, future, future); err != nil {
		t.Fatal(err)
	}
	second, err := modsFingerprintAt(mods, cache)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("timestamp-only change should not invalidate a content fingerprint: %s != %s", first, second)
	}
}

func TestStageBridgeRejectsMissingModsPath(t *testing.T) {
	bridge := filepath.Join(t.TempDir(), "bridge.jar")
	if err := os.WriteFile(bridge, []byte("bridge"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := StageBridge(bridge, "", "1.21.10"); err == nil {
		t.Fatal("empty mods path should be rejected")
	}
}
