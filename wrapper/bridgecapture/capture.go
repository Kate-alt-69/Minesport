package bridgecapture

import (
	"bufio"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	DefaultAddress      = "127.0.0.1:25590"
	SnapshotSchema      = 1
	maxMessageSize      = 64 << 20
	captureBridgePrefix = "minesport-capture-bridge-"
	captureBridgeSuffix = ".jar"
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
	ModsFingerprint  string                  `json:"modsFingerprint,omitempty"`
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

type captureSession struct {
	Version         string
	ModsPath        string
	ModsFingerprint string
	StagedPath      string
}

var captureSessions = struct {
	sync.Mutex
	byVersion map[string]captureSession
}{byVersion: make(map[string]captureSession)}

type Server struct {
	listener net.Listener
	cacheDir string
	onLog    func(string)
	wg       sync.WaitGroup
	close    sync.Once
	connMu   sync.Mutex
	conns    map[net.Conn]struct{}
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
	server := &Server{
		listener: listener,
		cacheDir: dir,
		onLog:    onLog,
		conns:    make(map[net.Conn]struct{}),
	}
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
		CleanupStaged()
		return nil
	}
	var err error
	s.close.Do(func() {
		err = s.listener.Close()
		s.connMu.Lock()
		for connection := range s.conns {
			_ = connection.Close()
		}
		s.connMu.Unlock()
		s.wg.Wait()
		CleanupStaged()
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
		s.connMu.Lock()
		s.conns[connection] = struct{}{}
		s.connMu.Unlock()
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer func() {
				s.connMu.Lock()
				delete(s.conns, connection)
				s.connMu.Unlock()
			}()
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

	if session, ok := sessionForVersion(snapshot.MinecraftVersion); ok {
		snapshot.ModsFingerprint = session.ModsFingerprint
	}
	snapshot.CapturedAt = time.Now().UTC().Format(time.RFC3339)
	path, err := writeSnapshotAt(s.cacheDir, snapshot)
	if err != nil {
		s.log("Could not save bridge registry snapshot: " + err.Error())
		return
	}
	cleanupSession(snapshot.MinecraftVersion)
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

// StageBridge copies a prepared bridge into one Fabric instance for a capture
// session. Minesport uses a reserved filename and never overwrites arbitrary
// user mods. The file is removed after a successful dump or CleanupStaged.
func StageBridge(bridgeJar, modsPath, version string) (string, error) {
	bridgeJar = filepath.Clean(strings.TrimSpace(bridgeJar))
	rawModsPath := strings.TrimSpace(modsPath)
	version = strings.TrimSpace(version)
	if bridgeJar == "" || bridgeJar == "." {
		return "", fmt.Errorf("bridge jar path is required")
	}
	if rawModsPath == "" {
		return "", fmt.Errorf("mods folder path is required")
	}
	modsPath = filepath.Clean(rawModsPath)
	if version == "" {
		return "", fmt.Errorf("minecraft version is required")
	}
	info, err := os.Stat(bridgeJar)
	if err != nil || info.IsDir() {
		return "", fmt.Errorf("bridge jar is unavailable: %s", bridgeJar)
	}
	if err := os.MkdirAll(modsPath, 0o755); err != nil {
		return "", fmt.Errorf("prepare mods folder: %w", err)
	}

	// Remove only Minesport-owned leftovers from an interrupted older capture.
	_ = cleanupCaptureJars(modsPath)
	fingerprint, err := ModsFingerprint(modsPath)
	if err != nil {
		return "", err
	}

	staged := filepath.Join(
		modsPath,
		captureBridgePrefix+safeVersion(version)+captureBridgeSuffix,
	)
	if err := copyFile(bridgeJar, staged); err != nil {
		return "", fmt.Errorf("stage capture bridge: %w", err)
	}

	captureSessions.Lock()
	captureSessions.byVersion[version] = captureSession{
		Version:         version,
		ModsPath:        modsPath,
		ModsFingerprint: fingerprint,
		StagedPath:      staged,
	}
	captureSessions.Unlock()
	return staged, nil
}

// CleanupStaged removes every temporary bridge file still owned by this process.
func CleanupStaged() {
	captureSessions.Lock()
	sessions := make([]captureSession, 0, len(captureSessions.byVersion))
	for version, session := range captureSessions.byVersion {
		sessions = append(sessions, session)
		delete(captureSessions.byVersion, version)
	}
	captureSessions.Unlock()
	for _, session := range sessions {
		_ = os.Remove(session.StagedPath)
	}
}

func cleanupSession(version string) {
	captureSessions.Lock()
	session, ok := captureSessions.byVersion[version]
	if ok {
		delete(captureSessions.byVersion, version)
	}
	captureSessions.Unlock()
	if ok {
		_ = os.Remove(session.StagedPath)
	}
}

func sessionForVersion(version string) (captureSession, bool) {
	captureSessions.Lock()
	defer captureSessions.Unlock()
	session, ok := captureSessions.byVersion[version]
	return session, ok
}

func SnapshotPath(version string) (string, bool) {
	return snapshotPathAt(cacheDir(), version)
}

// SnapshotPathForMods only returns a registry captured from the same mod JAR
// set. This prevents stale light metadata surviving a mod upgrade/removal.
func SnapshotPathForMods(version, modsPath string) (string, bool) {
	path, ok := SnapshotPath(version)
	if !ok {
		return "", false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	var snapshot Snapshot
	if json.Unmarshal(data, &snapshot) != nil || snapshot.ModsFingerprint == "" {
		return "", false
	}
	fingerprint, err := ModsFingerprint(modsPath)
	if err != nil || fingerprint != snapshot.ModsFingerprint {
		return "", false
	}
	return path, true
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

// ModsFingerprint is intentionally fast: the mod filenames, sizes and mtimes
// are enough to invalidate normal add/remove/update workflows without hashing
// potentially gigabytes of JAR contents on every export.
func ModsFingerprint(modsPath string) (string, error) {
	modsPath = filepath.Clean(strings.TrimSpace(modsPath))
	entries, err := os.ReadDir(modsPath)
	if err != nil {
		return "", fmt.Errorf("read mods folder for bridge fingerprint: %w", err)
	}
	type item struct {
		name string
		size int64
		mod  int64
	}
	items := make([]item, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.EqualFold(filepath.Ext(entry.Name()), ".jar") {
			continue
		}
		if isCaptureBridgeName(entry.Name()) {
			continue
		}
		info, infoErr := entry.Info()
		if infoErr != nil {
			return "", infoErr
		}
		items = append(items, item{
			name: strings.ToLower(entry.Name()),
			size: info.Size(),
			mod:  info.ModTime().UnixNano(),
		})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].name < items[j].name })
	hash := sha256.New()
	for _, entry := range items {
		_, _ = fmt.Fprintf(hash, "%s\x00%d\x00%d\n", entry.name, entry.size, entry.mod)
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
}

func cleanupCaptureJars(modsPath string) error {
	entries, err := os.ReadDir(modsPath)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.IsDir() || !isCaptureBridgeName(entry.Name()) {
			continue
		}
		if err := os.Remove(filepath.Join(modsPath, entry.Name())); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}

func isCaptureBridgeName(name string) bool {
	lower := strings.ToLower(name)
	return strings.HasPrefix(lower, captureBridgePrefix)
		&& strings.HasSuffix(lower, captureBridgeSuffix)
}

func copyFile(source, destination string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	ok := false
	defer func() {
		_ = out.Close()
		if !ok {
			_ = os.Remove(destination)
		}
	}()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	ok = true
	return nil
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
