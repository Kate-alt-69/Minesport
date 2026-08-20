package ipc

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"

	"github.com/kastrick/minesport/processutil"
)

// Bridge manages a running Java engine process and its IPC channels.
type Bridge struct {
	cmd       *exec.Cmd
	stdin     *json.Encoder
	stdout    *bufio.Scanner
	mu        sync.Mutex
	running   bool
	OnMessage func(msg EngineMessage) // called for every message from engine
}

// NewBridge creates a bridge but does not start the engine yet.
func NewBridge(onMessage func(msg EngineMessage)) *Bridge {
	return &Bridge{OnMessage: onMessage}
}

// Start launches the Java engine jar as a subprocess.
// jarPath should point to the minesport-engine fat jar.
func (b *Bridge) Start(jarPath string) error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.running {
		return fmt.Errorf("engine already running")
	}

	// Find java executable
	javaExe, err := findJava()
	if err != nil {
		return fmt.Errorf("java not found: %w", err)
	}

	absJar, err := filepath.Abs(jarPath)
	if err != nil {
		return fmt.Errorf("bad jar path: %w", err)
	}

	if _, err := os.Stat(absJar); os.IsNotExist(err) {
		return fmt.Errorf("engine jar not found: %s", absJar)
	}

	b.cmd = exec.Command(javaExe, "-jar", absJar, "--ipc")
	processutil.HideWindow(b.cmd)
	b.cmd.Stderr = os.Stderr // engine logs go to our stderr

	stdinPipe, err := b.cmd.StdinPipe()
	if err != nil {
		return fmt.Errorf("stdin pipe: %w", err)
	}

	stdoutPipe, err := b.cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("stdout pipe: %w", err)
	}

	b.stdin = json.NewEncoder(stdinPipe)
	b.stdout = bufio.NewScanner(stdoutPipe)
	b.stdout.Buffer(make([]byte, 1024*1024), 1024*1024) // 1MB line buffer

	if err := b.cmd.Start(); err != nil {
		return fmt.Errorf("start engine: %w", err)
	}

	b.running = true

	// Read messages from engine on a goroutine
	go b.readLoop()

	// Send ping to verify it's alive
	return b.Send(PingRequest{Command: "ping"})
}

// Send encodes and sends a message to the engine via stdin.
func (b *Bridge) Send(v any) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.running {
		return fmt.Errorf("engine not running")
	}
	return b.stdin.Encode(v)
}

// Stop kills the engine process.
func (b *Bridge) Stop() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cmd != nil && b.cmd.Process != nil {
		b.cmd.Process.Kill()
	}
	b.running = false
}

// IsRunning returns whether the engine process is alive.
func (b *Bridge) IsRunning() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.running
}

// readLoop continuously reads JSON lines from the engine's stdout.
func (b *Bridge) readLoop() {
	for b.stdout.Scan() {
		line := b.stdout.Text()
		if line == "" {
			continue
		}
		var msg EngineMessage
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			// Not a JSON message — treat as raw log
			msg = EngineMessage{Type: "log", Message: line}
		}
		if b.OnMessage != nil {
			b.OnMessage(msg)
		}
	}
	// Engine exited
	b.mu.Lock()
	b.running = false
	b.mu.Unlock()
	if b.OnMessage != nil {
		b.OnMessage(EngineMessage{Type: "log", Message: "[engine process exited]"})
	}
}

// ── Java finder ───────────────────────────────────────────────────────────────

func findJava() (string, error) {
	// 1. Check JAVA_HOME
	if jh := os.Getenv("JAVA_HOME"); jh != "" {
		exe := filepath.Join(jh, "bin", javaExeName())
		if _, err := os.Stat(exe); err == nil {
			return exe, nil
		}
	}

	// 2. Check PATH
	if path, err := exec.LookPath(javaExeName()); err == nil {
		return path, nil
	}

	// 3. FreesmLauncher bundled Java (Kastrick's setup)
	appdata := os.Getenv("APPDATA")
	if appdata != "" {
		// FreesmLauncher uses java-runtime-delta
		candidates := []string{
			filepath.Join(appdata, "FreesmLauncher", "java", "java-runtime-delta", "bin", javaExeName()),
			filepath.Join(appdata, "FreesmLauncher", "java", "java-runtime-gamma", "bin", javaExeName()),
		}
		for _, c := range candidates {
			if _, err := os.Stat(c); err == nil {
				return c, nil
			}
		}
	}

	// 4. Standard Windows JDK paths
	if runtime.GOOS == "windows" {
		programFiles := os.Getenv("PROGRAMFILES")
		if programFiles != "" {
			jdkBase := filepath.Join(programFiles, "Java")
			if entries, err := os.ReadDir(jdkBase); err == nil {
				for _, e := range entries {
					candidate := filepath.Join(jdkBase, e.Name(), "bin", "java.exe")
					if _, err := os.Stat(candidate); err == nil {
						return candidate, nil
					}
				}
			}
		}
	}

	return "", fmt.Errorf("java executable not found — install Java 22+ or set JAVA_HOME")
}

func javaExeName() string {
	if runtime.GOOS == "windows" {
		return "java.exe"
	}
	return "java"
}
