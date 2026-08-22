package bridgecapture

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"testing"
	"time"
)

func TestCaptureWritesReusableLightSnapshot(t *testing.T) {
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
	messages := []any{
		map[string]any{
			"type":          "hello",
			"mcVersion":     "1.21.10",
			"loaderVersion": "0.17.2",
			"loadedMods":    []string{"example@1.0.0"},
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

	var path string
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if found, ok := snapshotPathAt(dir, "1.21.10"); ok {
			path = found
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if path == "" {
		t.Fatal("bridge snapshot was not written")
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read bridge snapshot: %v", err)
	}
	var snapshot Snapshot
	if err := json.Unmarshal(data, &snapshot); err != nil {
		t.Fatalf("parse bridge snapshot: %v", err)
	}
	states := snapshot.Blocks["example:lamp"]
	if len(states) != 1 {
		t.Fatalf("expected one light state, got %d", len(states))
	}
	if states[0].LightLevel != 12 || states[0].Properties["lit"] != "true" {
		t.Fatalf("unexpected light state: %#v", states[0])
	}
}

func TestSnapshotLookupRejectsWrongVersionAndSchema(t *testing.T) {
	dir := t.TempDir()
	_, err := writeSnapshotAt(dir, Snapshot{
		Schema:           SnapshotSchema,
		MinecraftVersion: "1.21.10",
		Blocks:           map[string][]LightState{},
	})
	if err != nil {
		t.Fatalf("write snapshot: %v", err)
	}
	if _, ok := snapshotPathAt(dir, "1.21.11"); ok {
		t.Fatal("snapshot lookup should not reuse another Minecraft version")
	}

	path, ok := snapshotPathAt(dir, "1.21.10")
	if !ok {
		t.Fatal("expected matching snapshot")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatal(err)
	}
	raw["schema"] = float64(999)
	corrupt, _ := json.Marshal(raw)
	if err := os.WriteFile(path, corrupt, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, ok := snapshotPathAt(dir, "1.21.10"); ok {
		t.Fatal("snapshot lookup should reject unsupported schema")
	}
}
