package bridgecapture

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	DefaultAddress = "127.0.0.1:25590"
	SnapshotSchema = 1
	maxMessageSize = 64 << 20
)

type LightState struct {
	Properties map[string]string `json:"properties"`
	LightLevel int               `json:"lightLevel"`
}

type Snapshot struct {
	Schema           int                     `json:"schema"`
	MinecraftVersion string                  `json:"minecraftVersion"`
	LoaderVersion    string                  `json:"loaderVersion,omitempty"`
	LoadedMods       []string                `json:"loadedMods,omitempty"`
	Blocks           map[string][]LightState `json:"blocks"`
	CapturedAt       string                  `json:"capturedAt"`
}

type wireMessage struct {
	Type          string                  `json:"type"`
	Message       string                  `json:"message,omitempty"`
	MCVersion     string                  `json:"mcVersion,omitempty"`
	LoaderVersion string                  `json:"loaderVersion,omitempty"`
	LoadedMods    []string                `json:"loadedMods,omitempty"`
	BlockID       string                  `json:"blockId,omitempty"`
	States        []LightState            `json:"states,omitempty"`
}

type Server struct {
	listener net.Listener
	cacheDir string
	onLog    func(string)
	wg       sync.WaitGroup
	close    sync.Once
}

// Start opens the local bridge receiver used by the Fabric bridge mod.
// Failure is non-fatal to the rest of Minesport; callers may continue with
// static asset resolution when the port is unavailable.
func Start(onLog func(string)) (*Server, error) {
	return start(DefaultAddress, cacheDir(), onLog)
}

func start(address, dir string, onLog func(string)) (*Server, error) {
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, fmt.Errorf("listen for Minecraft bridge on %s: %w", address, err)
	}
	server := &Server{listener: listener, cacheDir: dir, onLog: onLog}
	server.wg.Add(1)
	go server.acceptLoop()
	server.log("Bridge capture receiver listening on " + listener.Addr().String())
	return server, nil
}

func (s *Server) Addr() string {
	if s == nil || s.listener == nil {
		return ""
	}
	return s.listener.Addr().String()
}

func (s *Server) Close() error {
	if s == nil || s.listener == nil {
		return nil
	}
	var err error
	s.close.Do(func() {
		err = s.listener.Close()
		s.wg.Wait()
	})
	return err
}

func (s *Server) acceptLoop() {
	defer s.wg.Done()
	for {
		connection, err := s.listener.Accept()
		if err != nil {
			if !strings.Contains(strings.ToLower(err.Error()), "closed") {
				s.log("Bridge receiver stopped: " + err.Error())
			}
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.capture(connection)
		}()
	}
}

func (s *Server) capture(connection net.Conn) {
	defer connection.Close()
	remote := connection.RemoteAddr().String()
	s.log("Minecraft bridge connected from " + remote)

	snapshot := Snapshot{
		Schema: SnapshotSchema,
		Blocks: make(map[string][]LightState),
	}
	complete := false

	scanner := bufio.NewScanner(connection)
	scanner.Buffer(make([]byte, 64*1024), maxMessageSize)
	for scanner.Scan() {
		var message wireMessage
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			s.log("Ignoring malformed bridge message: " + err.Error())
			continue
		}

		switch message.Type {
		case "hello":
			snapshot.MinecraftVersion = strings.TrimSpace(message.MCVersion)
			snapshot.LoaderVersion = strings.TrimSpace(message.LoaderVersion)
			snapshot.LoadedMods = append([]string(nil), message.LoadedMods...)
		case "block_light":
			blockID := strings.TrimSpace(message.BlockID)
			if blockID == "" || len(message.States) == 0 {
				continue
			}
			states := make([]LightState, 0, len(message.States))
			for _, state := range message.States {
				level := state.LightLevel
				if level < 1 {
					continue
				}
				if level > 15 {
					level = 15
				}
				properties := make(map[string]string, len(state.Properties))
				for key, value := range state.Properties {
					properties[key] = value
				}
				states = append(states, LightState{Properties: properties, LightLevel: level})
			}
			if len(states) > 0 {
				snapshot.Blocks[blockID] = states
			}
		case "error":
			if message.Message != "" {
				s.log("Minecraft bridge reported: " + message.Message)
			}
		case "done":
			complete = true
		}

		if complete {
			break
		}
	}
	if err := scanner.Err(); err != nil {
		s.log("Bridge connection ended with an error: " + err.Error())
		return
	}
	if !complete {
		s.log("Bridge disconnected before completing its registry dump")
		return
	}
	if snapshot.MinecraftVersion == "" {
		s.log("Bridge dump completed without a Minecraft version; snapshot discarded")
		return
	}

	snapshot.CapturedAt = time.Now().UTC().Format(time.RFC3339)
	path, err := writeSnapshotAt(s.cacheDir, snapshot)
	if err != nil {
		s.log("Could not save bridge registry snapshot: " + err.Error())
		return
	}
	s.log(fmt.Sprintf(
		"Bridge registry cached for Minecraft %s: %d emitting block type(s) · %s",
		snapshot.MinecraftVersion,
		len(snapshot.Blocks),
		path,
	))
}

func (s *Server) log(message string) {
	if s != nil && s.onLog != nil {
		s.onLog(message)
	}
}

func SnapshotPath(version string) (string, bool) {
	return snapshotPathAt(cacheDir(), version)
}

func snapshotPathAt(dir, version string) (string, bool) {
	version = strings.TrimSpace(version)
	if version == "" {
		return "", false
	}
	path := filepath.Join(dir, safeVersion(version)+".json")
	data, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	var snapshot Snapshot
	if json.Unmarshal(data, &snapshot) != nil || snapshot.Schema != SnapshotSchema {
		return "", false
	}
	if strings.TrimSpace(snapshot.MinecraftVersion) != version {
		return "", false
	}
	return path, true
}

func writeSnapshotAt(dir string, snapshot Snapshot) (string, error) {
	if strings.TrimSpace(snapshot.MinecraftVersion) == "" {
		return "", fmt.Errorf("minecraftVersion is required")
	}
	if snapshot.Schema == 0 {
		snapshot.Schema = SnapshotSchema
	}
	if snapshot.Schema != SnapshotSchema {
		return "", fmt.Errorf("unsupported bridge snapshot schema %d", snapshot.Schema)
	}
	if snapshot.Blocks == nil {
		snapshot.Blocks = map[string][]LightState{}
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	data, err := json.MarshalIndent(snapshot, "", "  ")
	if err != nil {
		return "", err
	}
	path := filepath.Join(dir, safeVersion(snapshot.MinecraftVersion)+".json")
	temporary, err := os.CreateTemp(dir, ".bridge-registry-*.tmp")
	if err != nil {
		return "", err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return "", err
	}
	if err := temporary.Close(); err != nil {
		return "", err
	}
	// Windows cannot always rename over an existing destination.
	_ = os.Remove(path)
	if err := os.Rename(temporaryPath, path); err != nil {
		return "", err
	}
	return path, nil
}

func cacheDir() string {
	base, err := os.UserCacheDir()
	if err != nil || strings.TrimSpace(base) == "" {
		base = os.TempDir()
	}
	return filepath.Join(base, "kastrick_software", "minesport", "bridge-registry")
}

func safeVersion(version string) string {
	var builder strings.Builder
	for _, char := range strings.TrimSpace(version) {
		switch {
		case char >= 'a' && char <= 'z':
			builder.WriteRune(char)
		case char >= 'A' && char <= 'Z':
			builder.WriteRune(char)
		case char >= '0' && char <= '9':
			builder.WriteRune(char)
		case char == '.', char == '-', char == '_':
			builder.WriteRune(char)
		default:
			builder.WriteRune('_')
		}
	}
	if builder.Len() == 0 {
		return "unknown"
	}
	return builder.String()
}
