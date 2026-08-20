package ipc

import (
	"strings"
	"sync"
	"testing"
	"time"
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

type signalWriteCloser struct {
	once  sync.Once
	wrote chan struct{}
}

func (writer *signalWriteCloser) Write(data []byte) (int, error) {
	writer.once.Do(func() { close(writer.wrote) })
	return len(data), nil
}

func (*signalWriteCloser) Close() error { return nil }

func TestPingWaitsForPong(t *testing.T) {
	engine := NewEngine("")
	writer := &signalWriteCloser{wrote: make(chan struct{})}
	engine.stdin = writer
	engine.ready = true
	done := make(chan struct{})
	go func() { engine.dispatch(); close(done) }()
	go func() {
		<-writer.wrote
		engine.msgCh <- Response{Type: "pong", Message: "pong"}
	}()

	if err := engine.Ping(time.Second); err != nil {
		t.Fatalf("Ping returned an error after pong: %v", err)
	}
	close(engine.msgCh)
	<-done
}

func TestPingTimesOutWithoutPong(t *testing.T) {
	engine := NewEngine("")
	engine.stdin = &signalWriteCloser{wrote: make(chan struct{})}
	engine.ready = true

	err := engine.Ping(5 * time.Millisecond)
	if err == nil || !strings.Contains(err.Error(), "did not respond") {
		t.Fatalf("Ping error = %v, want timeout", err)
	}
}
