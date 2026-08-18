package ipc

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
)

// Request types sent to Java engine
type Request struct {
	Command    string            `json:"command"`
	WorldPath  string            `json:"worldPath,omitempty"`
	OutputPath string            `json:"outputPath,omitempty"`
	Format     string            `json:"format,omitempty"`
	MinX       int               `json:"minX,omitempty"`
	MinY       int               `json:"minY,omitempty"`
	MinZ       int               `json:"minZ,omitempty"`
	MaxX       int               `json:"maxX,omitempty"`
	MaxY       int               `json:"maxY,omitempty"`
	MaxZ       int               `json:"maxZ,omitempty"`
	ExportMode string            `json:"exportMode,omitempty"`
	Options    map[string]string `json:"options,omitempty"`

	// Bubble/radius selection — a center point plus an outward reach on
	// each axis, carving an ellipsoid out of the MinX..MaxZ bounding box
	// above (which is still sent, computed as the ellipsoid's own bounds,
	// since the engine scans region files by bounding box either way).
	// Nil = box selection only, no ellipsoid narrowing.
	CenterX *int `json:"centerX,omitempty"`
	CenterY *int `json:"centerY,omitempty"`
	CenterZ *int `json:"centerZ,omitempty"`
	RadiusX *int `json:"radiusX,omitempty"`
	RadiusY *int `json:"radiusY,omitempty"`
	RadiusZ *int `json:"radiusZ,omitempty"`
}

// Response received from Java engine
type Response struct {
	Type    string `json:"type"`
	Message string `json:"message,omitempty"`
	Percent int    `json:"percent,omitempty"`
	Version string `json:"version,omitempty"`
	Output  string `json:"output,omitempty"`
	// Heightmap fields
	Image   string `json:"image,omitempty"`
	MinX    int    `json:"minX,omitempty"`
	MinZ    int    `json:"minZ,omitempty"`
	MaxX    int    `json:"maxX,omitempty"`
	MaxZ    int    `json:"maxZ,omitempty"`
	Scale   int    `json:"scale,omitempty"`
	// listBlocks fields (3D preview viewer)
	File  string `json:"file,omitempty"`
	Count int    `json:"count,omitempty"`
	// export "done" stats
	BlockCount  int `json:"blockCount,omitempty"`
	QuadCount   int `json:"quadCount,omitempty"`
	VertexCount int `json:"vertexCount,omitempty"`
}

// Engine manages the Java subprocess + IPC
type Engine struct {
	cmd   *exec.Cmd
	stdin io.WriteCloser
	mu    sync.Mutex
	ready bool

	// Channel-based dispatcher: readLoop sends all messages here.
	// Consumers either listen via pendingReply or via the On* callbacks.
	msgCh chan Response

	// When SendCommand is waiting, it registers a reply channel here.
	pendingMu    sync.Mutex
	pendingReply chan Response // non-nil when a SendCommand is active

	// Regular async callbacks
	OnLog      func(string)
	OnProgress func(int, string)
	OnDone     func(Response)
	OnError    func(string)
}

func NewEngine(jarPath string) *Engine {
	e := &Engine{
		msgCh:      make(chan Response, 64),
		OnLog:      func(s string) { fmt.Println("[engine]", s) },
		OnProgress: func(p int, s string) {},
		OnDone:     func(r Response) {},
		OnError:    func(s string) { fmt.Println("[engine ERROR]", s) },
	}
	return e
}

// Start launches the Java engine subprocess.
func (e *Engine) Start(jarPath string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	javaExe := "java"
	if javaHome := os.Getenv("JAVA_HOME"); javaHome != "" {
		javaExe = javaHome + "/bin/java"
	}

	e.cmd = exec.Command(javaExe, "-jar", jarPath, "--ipc")

	var err error
	e.stdin, err = e.cmd.StdinPipe()
	if err != nil {
		return fmt.Errorf("stdin pipe: %w", err)
	}

	stdout, err := e.cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("stdout pipe: %w", err)
	}

	e.cmd.Stderr = os.Stderr

	if err := e.cmd.Start(); err != nil {
		return fmt.Errorf("start java: %w", err)
	}

	go e.readLoop(bufio.NewScanner(stdout))
	go e.dispatch()

	e.ready = true
	return nil
}

// readLoop reads raw lines from stdout and pushes parsed responses to msgCh.
// This is the ONLY goroutine that reads from stdout — no more races.
func (e *Engine) readLoop(scanner *bufio.Scanner) {
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		var resp Response
		if err := json.Unmarshal([]byte(line), &resp); err != nil {
			// Raw non-JSON line — treat as log
			resp = Response{Type: "log", Message: line}
		}
		e.msgCh <- resp
	}
	close(e.msgCh)
}

// dispatch routes messages from msgCh either to a waiting SendCommand
// or to the regular On* callbacks.
func (e *Engine) dispatch() {
	for resp := range e.msgCh {
		// Check if a SendCommand is waiting for a reply
		e.pendingMu.Lock()
		pending := e.pendingReply
		e.pendingMu.Unlock()

		// log and progress always go to callbacks regardless
		switch resp.Type {
		case "log":
			if e.OnLog != nil {
				e.OnLog(resp.Message)
			}
			continue
		case "progress":
			if e.OnProgress != nil {
				e.OnProgress(resp.Percent, resp.Message)
			}
			continue
		case "info":
			if e.OnLog != nil {
				e.OnLog("Engine version: " + resp.Version)
			}
			continue
		}

		// For all other types (done, error, heightmap, pong, ...):
		// if SendCommand is waiting, give it the response
		if pending != nil {
			pending <- resp
			continue
		}

		// Otherwise route to normal callbacks
		switch resp.Type {
		case "done":
			if e.OnDone != nil {
				e.OnDone(resp)
			}
		case "error":
			if e.OnError != nil {
				e.OnError(resp.Message)
			}
		}
	}
}

// Send dispatches a fire-and-forget request (export, ping).
func (e *Engine) Send(req Request) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if !e.ready {
		return fmt.Errorf("engine not started")
	}
	data, err := json.Marshal(req)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintln(e.stdin, string(data))
	return err
}

// SendCommand sends a command and blocks until it receives a non-log response.
// Safe to call concurrently with the export readLoop.
func (e *Engine) SendCommand(payload map[string]interface{}) (*Response, error) {
	// Register our reply channel BEFORE sending so we don't miss the response
	reply := make(chan Response, 1)
	e.pendingMu.Lock()
	e.pendingReply = reply
	e.pendingMu.Unlock()

	defer func() {
		e.pendingMu.Lock()
		e.pendingReply = nil
		e.pendingMu.Unlock()
	}()

	// Send the command
	e.mu.Lock()
	data, err := json.Marshal(payload)
	if err != nil {
		e.mu.Unlock()
		return nil, err
	}
	_, err = fmt.Fprintln(e.stdin, string(data))
	e.mu.Unlock()
	if err != nil {
		return nil, err
	}

	// Wait for dispatcher to route a non-log response to us
	resp, ok := <-reply
	if !ok {
		return nil, fmt.Errorf("engine closed before response")
	}
	return &resp, nil
}

// ExportParams bundles everything an export can be configured with.
// Grouped into a struct (rather than a long positional parameter list)
// because most of the ellipsoid/options fields are optional and adding
// them as more positional params would make call sites unreadable.
type ExportParams struct {
	WorldPath  string
	OutputPath string
	Format     string
	ExportMode string
	MinX, MinY, MinZ int
	MaxX, MaxY, MaxZ int

	// Options: generic string options the engine reads out of req.options,
	// e.g. "resourcePacks" / "dataPacks" as ';'-separated path lists.
	Options map[string]string

	// Bubble/radius selection — see Request's field comments.
	CenterX, CenterY, CenterZ *int
	RadiusX, RadiusY, RadiusZ *int
}

// Export kicks off an async world export.
func (e *Engine) Export(p ExportParams) error {
	return e.Send(Request{
		Command:    "export",
		WorldPath:  p.WorldPath,
		OutputPath: p.OutputPath,
		Format:     p.Format,
		MinX: p.MinX, MinY: p.MinY, MinZ: p.MinZ,
		MaxX: p.MaxX, MaxY: p.MaxY, MaxZ: p.MaxZ,
		ExportMode: p.ExportMode,
		Options:    p.Options,
		CenterX: p.CenterX, CenterY: p.CenterY, CenterZ: p.CenterZ,
		RadiusX: p.RadiusX, RadiusY: p.RadiusY, RadiusZ: p.RadiusZ,
	})
}

// ListBlocksParams selects the region the 3D preview viewer should load —
// same shape as ExportParams' bounds, minus the export-only fields.
type ListBlocksParams struct {
	WorldPath        string
	MinX, MinY, MinZ int
	MaxX, MaxY, MaxZ int
	CenterX, CenterY, CenterZ *int
	RadiusX, RadiusY, RadiusZ *int
}

// ListBlocks asks the engine for block positions+colors within the given
// bounds (no geometry building — see IpcMode.handleListBlocks) and blocks
// until the response arrives. Returns the temp file path the blocks were
// written to (see viewer.LoadBlocks) and how many were found.
func (e *Engine) ListBlocks(p ListBlocksParams) (path string, count int, err error) {
	resp, err := e.SendCommand(map[string]interface{}{
		"command":  "listBlocks",
		"worldPath": p.WorldPath,
		"minX": p.MinX, "minY": p.MinY, "minZ": p.MinZ,
		"maxX": p.MaxX, "maxY": p.MaxY, "maxZ": p.MaxZ,
		"centerX": p.CenterX, "centerY": p.CenterY, "centerZ": p.CenterZ,
		"radiusX": p.RadiusX, "radiusY": p.RadiusY, "radiusZ": p.RadiusZ,
	})
	if err != nil {
		return "", 0, err
	}
	if resp.Type == "error" {
		return "", 0, fmt.Errorf("%s", resp.Message)
	}
	return resp.File, resp.Count, nil
}

// Ping checks the engine is alive.
func (e *Engine) Ping() error {
	return e.Send(Request{Command: "ping"})
}

// Stop shuts down the engine.
func (e *Engine) Stop() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stdin != nil {
		_ = json.NewEncoder(e.stdin).Encode(Request{Command: "quit"})
		_ = e.stdin.Close()
	}
	if e.cmd != nil && e.cmd.Process != nil {
		_ = e.cmd.Process.Kill()
	}
	e.ready = false
}
