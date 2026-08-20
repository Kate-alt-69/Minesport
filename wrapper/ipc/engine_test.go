package ipc

import (
	"strings"
	"testing"
)

func TestIPCScannerAcceptsLargeHeightmapResponse(t *testing.T) {
	image := strings.Repeat("A", 1024*1024)
	scanner := newIPCScanner(strings.NewReader(`{"type":"heightmap","image":"` + image + `"}`))
	if !scanner.Scan() {
		t.Fatalf("scanner rejected a valid large IPC response: %v", scanner.Err())
	}
	if got := scanner.Text(); !strings.Contains(got, image) {
		t.Fatal("scanner truncated the heightmap response")
	}
	if scanner.Scan() {
		t.Fatal("scanner returned an unexpected second token")
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scanner failed after the large response: %v", err)
	}
}

func TestExpectedResponseType(t *testing.T) {
	tests := map[string]string{
		"heightmap":  "heightmap",
		"worldInfo":  "worldInfo",
		"listBlocks": "blocksReady",
		"ping":       "pong",
	}
	for command, want := range tests {
		if got := expectedResponseType(command); got != want {
			t.Errorf("expectedResponseType(%q) = %q, want %q", command, got, want)
		}
	}
}
