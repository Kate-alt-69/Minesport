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

	"github.com/kastrick/minesport/appdirs"
)

const (
	DefaultAddress       = "127.0.0.1:25590"
	SnapshotSchema       = 3
	maxMessageSize       = 128 << 20
	captureBridgePrefix  = "minesport-capture-bridge-"
	captureBridgeSuffix  = ".jar"
	registryFileName     = "registry.json"
	modHashCacheFileName = "mod-hash-cache-v1.json"
	modHashCacheSchema   = 1
)

type LightState struct {
	Properties map[string]string `json:"properties"`
	LightLevel int               `json:"lightLevel"`
}

type BakedQuad struct {
	Vertices  []float32 `json:"vertices"`
	TextureID string    `json:"textureId"`
	Face      int       `json:"face"`
	Shade     bool      `json:"shade"`
	TintIndex int       `json:"tintIndex"`
}

type BlockVariant struct {
	Properties map[string]string `json:"properties"`
	Quads      []BakedQuad       `json:"quads"`
}

type RuntimeBlock struct {
	VanillaMapping string         `json:"vanillaMapping,omitempty"`
	LoaderType     string         `json:"loaderType,omitempty"`
	Variants       []BlockVariant `json:"variants,omitempty"`
	Lights         []LightState   `json:"lights,omitempty"`
}

type Snapshot struct {
	Schema           int                     `json:"schema"`
	MinecraftVersion string                  `json:"minecraftVersion"`
	LoaderVersion    string                  `json:"loaderVersion,omitempty"`
	LoadedMods       []string                `json:"loadedMods,omitempty"`
	ModsFingerprint  string                  `json:"modsFingerprint"`
	Blocks           map[string]RuntimeBlock `json:"blocks"`
	CapturedAt       string                  `json:"capturedAt"`
}

type wireMessage struct {
	Type           string         `json:"type"`
	Message        string         `json:"message,omitempty"`
	MCVersion      string         `json:"mcVersion,omitempty"`
	LoaderVersion  string         `json:"loaderVersion,omitempty"`
	LoadedMods     []string       `json:"loadedMods,omitempty"`
	BlockID        string         `json:"blockId,omitempty"`
	VanillaMapping string         `json:"vanillaMapping,omitempty"`
	LoaderType     string         `json:"loaderType,omitempty"`
	Variants       []BlockVariant `json:"variants,omitempty"`
	States         []LightState   `json:"states,omitempty"`
}

type captureSession struct {
	Version         string
	ModsPath        string
	ModsFingerprint string
	StagedPath      string
}

type jarHashCacheEntry struct {
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"`
	SHA256  string `json:"sha256"`
}

type jarHashCacheDocument struct {
	Schema  int                          `json:"schema"`
	Entries map[string]jarHashCacheEntry `json:"entries"`
}

var captureSessions = struct {
	sync.Mutex
	byVersion map[string]captureSession
}{byVersion: make(map[string]captureSession)}

var modHashCacheMu sync.Mutex

type Server struct {
	listener net.Listener
	cacheDir string
	onLog    func(string)
	wg       sync.WaitGroup
	close    sync.Once
	connMu   sync.Mutex
	conns    map[net.Conn]struct{}
}

// Start opens the local receiver for the temporary Minecraft/Fabric registry worker.
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
	server.log("Runtime registry receiver listening on " + listener.Addr().String())
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
				s.log("Runtime registry receiver stopped: " + err.Error())
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
	s.log("Minecraft runtime worker connected from " + connection.RemoteAddr().String())

	snapshot := Snapshot{
		Schema: SnapshotSchema,
		Blocks: make(map[string]RuntimeBlock),
	}
	complete := false

	scanner := bufio.NewScanner(connection)
	scanner.Buffer(make([]byte, 64*1024), maxMessageSize)
	for scanner.Scan() {
		var message wireMessage
		if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
			s.log("Ignoring malformed runtime-registry message: " + err.Error())
			continue
		}

		switch message.Type {
		case "hello":
			snapshot.MinecraftVersion = strings.TrimSpace(message.MCVersion)
			snapshot.LoaderVersion = strings.TrimSpace(message.LoaderVersion)
			snapshot.LoadedMods = append([]string(nil), message.LoadedMods...)
		case "block":
			blockID := strings.TrimSpace(message.BlockID)
			if blockID == "" {
				continue
			}
			entry := snapshot.Blocks[blockID]
			entry.VanillaMapping = strings.TrimSpace(message.VanillaMapping)
			entry.LoaderType = strings.TrimSpace(message.LoaderType)
			entry.Variants = sanitizeVariants(message.Variants)
			snapshot.Blocks[blockID] = entry
		case "block_light":
			blockID := strings.TrimSpace(message.BlockID)
			if blockID == "" {
				continue
			}
			entry := snapshot.Blocks[blockID]
			entry.Lights = sanitizeLights(message.States)
			snapshot.Blocks[blockID] = entry
		case "texture":
			// Legacy bridge packets may still send images. Runtime-registry schema 3
			// intentionally does not cache texture bytes; texture IDs on baked quads
			// are resolved later from resource packs/mod JARs/vanilla/Piston.
		case "error":
			if message.Message != "" {
				s.log("Minecraft runtime worker reported: " + message.Message)
			}
		case "done":
			complete = true
		}

		if complete {
			break
		}
	}
	if err := scanner.Err(); err != nil {
		s.log("Runtime registry connection ended with an error: " + err.Error())
		return
	}
	if !complete {
		s.log("Runtime worker disconnected before completing its registry dump")
		return
	}
	if snapshot.MinecraftVersion == "" {
		s.log("Runtime registry dump had no Minecraft version; snapshot discarded")
		return
	}

	if session, ok := sessionForVersion(snapshot.MinecraftVersion); ok {
		snapshot.ModsFingerprint = session.ModsFingerprint
	}
	if snapshot.ModsFingerprint == "" {
		// An externally launched bridge can still be inspected, but it is kept in
		// an unscoped bucket and will never pass SnapshotPathForMods validation.
		snapshot.ModsFingerprint = loadedModsFingerprint(snapshot.LoadedMods)
	}
	snapshot.CapturedAt = time.Now().UTC().Format(time.RFC3339)
	path, err := writeSnapshotAt(s.cacheDir, snapshot)
	if err != nil {
		s.log("Could not save runtime model registry: " + err.Error())
		return
	}
	cleanupSession(snapshot.MinecraftVersion)
	s.log(fmt.Sprintf(
		"Runtime model registry cached for Minecraft %s: %d block type(s) · %s",
		snapshot.MinecraftVersion,
		len(snapshot.Blocks),
		path,
	))
}

func sanitizeVariants(source []BlockVariant) []BlockVariant {
	result := make([]BlockVariant, 0, len(source))
	for _, variant := range source {
		properties := make(map[string]string, len(variant.Properties))
		for key, value := range variant.Properties {
			properties[key] = value
		}
		quads := make([]BakedQuad, 0, len(variant.Quads))
		for _, quad := range variant.Quads {
			if len(quad.Vertices) < 32 {
				continue
			}
			vertices := append([]float32(nil), quad.Vertices[:32]...)
			quads = append(quads, BakedQuad{
				Vertices:  vertices,
				TextureID: strings.TrimSpace(quad.TextureID),
				Face:      quad.Face,
				Shade:     quad.Shade,
				TintIndex: quad.TintIndex,
			})
		}
		result = append(result, BlockVariant{Properties: properties, Quads: quads})
	}
	return result
}

func sanitizeLights(source []LightState) []LightState {
	states := make([]LightState, 0, len(source))
	for _, state := range source {
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
	return states
}

func (s *Server) log(message string) {
	if s != nil && s.onLog != nil {
		s.onLog(message)
	}
}

// BeginSession binds the next runtime dump for this Minecraft version to the
// exact mod-JAR set currently selected in Minesport. The worker may run from a
// disposable game directory; the original mods directory is only fingerprinted.
func BeginSession(version, modsPath string) (string, error) {
	version = strings.TrimSpace(version)
	modsPath = filepath.Clean(strings.TrimSpace(modsPath))
	if version == "" {
		return "", fmt.Errorf("minecraft version is required")
	}
	if modsPath == "" || modsPath == "." {
		return "", fmt.Errorf("mods folder path is required")
	}
	fingerprint, err := ModsFingerprint(modsPath)
	if err != nil {
		return "", err
	}
	captureSessions.Lock()
	captureSessions.byVersion[version] = captureSession{
		Version:         version,
		ModsPath:        modsPath,
		ModsFingerprint: fingerprint,
	}
	captureSessions.Unlock()
	return fingerprint, nil
}

// StageBridge is retained as a compatibility fallback for launchers that still
// require instance staging. New Minesport worker capture should prefer BeginSession
// plus a disposable worker directory and never touch the user's instance.
func StageBridge(bridgeJar, modsPath, version string) (string, error) {
	bridgeJar = filepath.Clean(strings.TrimSpace(bridgeJar))
	if bridgeJar == "" || bridgeJar == "." {
		return "", fmt.Errorf("bridge jar path is required")
	}
	if info, err := os.Stat(bridgeJar); err != nil || info.IsDir() {
		return "", fmt.Errorf("bridge jar is unavailable: %s", bridgeJar)
	}
	fingerprint, err := BeginSession(version, modsPath)
	if err != nil {
		return "", err
	}
	_ = fingerprint

	modsPath = filepath.Clean(strings.TrimSpace(modsPath))
	if err := os.MkdirAll(modsPath, 0o755); err != nil {
		return "", fmt.Errorf("prepare mods folder: %w", err)
	}
	_ = cleanupCaptureJars(modsPath)
	staged := filepath.Join(modsPath, captureBridgePrefix+safeVersion(version)+captureBridgeSuffix)
	if err := copyFile(bridgeJar, staged); err != nil {
		cleanupSession(version)
		return "", fmt.Errorf("stage capture bridge: %w", err)
	}

	captureSessions.Lock()
	session := captureSessions.byVersion[version]
	session.StagedPath = staged
	captureSessions.byVersion[version] = session
	captureSessions.Unlock()
	return staged, nil
}

func CleanupStaged() {
	captureSessions.Lock()
	sessions := make([]captureSession, 0, len(captureSessions.byVersion))
	for version, session := range captureSessions.byVersion {
		sessions = append(sessions, session)
		delete(captureSessions.byVersion, version)
	}
	captureSessions.Unlock()
	for _, session := range sessions {
		if session.StagedPath != "" {
			_ = os.Remove(session.StagedPath)
		}
	}
}

func CancelSession(version string) {
	cleanupSession(version)
}

func cleanupSession(version string) {
	captureSessions.Lock()
	session, ok := captureSessions.byVersion[version]
	if ok {
		delete(captureSessions.byVersion, version)
	}
	captureSessions.Unlock()
	if ok && session.StagedPath != "" {
		_ = os.Remove(session.StagedPath)
	}
}

func sessionForVersion(version string) (captureSession, bool) {
	captureSessions.Lock()
	defer captureSessions.Unlock()
	session, ok := captureSessions.byVersion[version]
	return session, ok
}

// SnapshotPath reports any valid cached registry for a Minecraft version. It is
// used only for UI stale/not-captured status; exports must use SnapshotPathForMods.
func SnapshotPath(version string) (string, bool) {
	version = strings.TrimSpace(version)
	if version == "" {
		return "", false
	}
	root := filepath.Join(cacheDir(), safeVersion(version))
	entries, err := os.ReadDir(root)
	if err != nil {
		return "", false
	}
	type candidate struct {
		path string
		mod  time.Time
	}
	var candidates []candidate
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		path := filepath.Join(root, entry.Name(), registryFileName)
		info, err := os.Stat(path)
		if err == nil && !info.IsDir() && validSnapshot(path, version, "") {
			candidates = append(candidates, candidate{path: path, mod: info.ModTime()})
		}
	}
	if len(candidates) == 0 {
		return "", false
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].mod.After(candidates[j].mod) })
	return candidates[0].path, true
}

func SnapshotPathForMods(version, modsPath string) (string, bool) {
	fingerprint, err := ModsFingerprint(modsPath)
	if err != nil {
		return "", false
	}
	path := snapshotPathAt(cacheDir(), version, fingerprint)
	if !validSnapshot(path, strings.TrimSpace(version), fingerprint) {
		return "", false
	}
	return path, true
}

func snapshotPathAt(dir, version, fingerprint string) string {
	return filepath.Join(
		dir,
		safeVersion(version),
		safeFingerprint(fingerprint),
		registryFileName,
	)
}

func validSnapshot(path, version, fingerprint string) bool {
	data, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	var snapshot Snapshot
	if json.Unmarshal(data, &snapshot) != nil || snapshot.Schema != SnapshotSchema {
		return false
	}
	if strings.TrimSpace(snapshot.MinecraftVersion) != strings.TrimSpace(version) {
		return false
	}
	if fingerprint != "" && snapshot.ModsFingerprint != fingerprint {
		return false
	}
	return true
}

func writeSnapshotAt(dir string, snapshot Snapshot) (string, error) {
	if strings.TrimSpace(snapshot.MinecraftVersion) == "" {
		return "", fmt.Errorf("minecraftVersion is required")
	}
	if snapshot.Schema == 0 {
		snapshot.Schema = SnapshotSchema
	}
	if snapshot.Schema != SnapshotSchema {
		return "", fmt.Errorf("unsupported runtime registry schema %d", snapshot.Schema)
	}
	if snapshot.Blocks == nil {
		snapshot.Blocks = map[string]RuntimeBlock{}
	}
	if snapshot.ModsFingerprint == "" {
		return "", fmt.Errorf("modsFingerprint is required")
	}
	path := snapshotPathAt(dir, snapshot.MinecraftVersion, snapshot.ModsFingerprint)
	folder := filepath.Dir(path)
	if err := os.MkdirAll(folder, 0o755); err != nil {
		return "", err
	}
	data, err := json.MarshalIndent(snapshot, "", "  ")
	if err != nil {
		return "", err
	}
	temporary, err := os.CreateTemp(folder, ".runtime-registry-*.tmp")
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
	_ = os.Remove(path)
	if err := os.Rename(temporaryPath, path); err != nil {
		return "", err
	}

	// A new successful capture supersedes every older mod-set fingerprint for
	// this exact Minecraft version. Keeping those siblings wastes disk and makes
	// cache inspection confusing; current exports can only use this fingerprint.
	_ = pruneSiblingFingerprints(dir, snapshot.MinecraftVersion, snapshot.ModsFingerprint)
	return path, nil
}

func pruneSiblingFingerprints(dir, version, keepFingerprint string) error {
	root := filepath.Join(dir, safeVersion(version))
	entries, err := os.ReadDir(root)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	keep := safeFingerprint(keepFingerprint)
	for _, entry := range entries {
		if !entry.IsDir() || entry.Name() == keep {
			continue
		}
		if err := os.RemoveAll(filepath.Join(root, entry.Name())); err != nil {
			return err
		}
	}
	return nil
}

// ModsFingerprint uses SHA-256 of the actual JAR contents. To avoid re-reading
// every mod on every export, per-JAR content digests are cached by absolute path
// plus size/mtime. Normal add/remove/update workflows therefore remain cheap,
// while the registry identity itself is content-based rather than timestamp-based.
func ModsFingerprint(modsPath string) (string, error) {
	return modsFingerprintAt(modsPath, filepath.Join(cacheDir(), modHashCacheFileName))
}

func modsFingerprintAt(modsPath, hashCachePath string) (string, error) {
	modsPath = filepath.Clean(strings.TrimSpace(modsPath))
	if modsPath == "" || modsPath == "." {
		return "", fmt.Errorf("mods folder path is required")
	}
	entries, err := os.ReadDir(modsPath)
	if err != nil {
		return "", fmt.Errorf("read mods folder for runtime-registry fingerprint: %w", err)
	}

	type item struct {
		name string
		path string
		size int64
		mod  int64
		hash string
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
			path: filepath.Join(modsPath, entry.Name()),
			size: info.Size(),
			mod:  info.ModTime().UnixNano(),
		})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].name < items[j].name })

	modHashCacheMu.Lock()
	cache := readJarHashCache(hashCachePath)
	dirty := false
	for index := range items {
		key := jarHashCacheKey(items[index].path)
		cached, ok := cache.Entries[key]
		if ok && cached.Size == items[index].size && cached.ModTime == items[index].mod && len(cached.SHA256) == 64 {
			items[index].hash = cached.SHA256
			continue
		}
		digest, hashErr := hashFileSHA256(items[index].path)
		if hashErr != nil {
			modHashCacheMu.Unlock()
			return "", fmt.Errorf("hash mod JAR %s: %w", items[index].path, hashErr)
		}
		items[index].hash = digest
		cache.Entries[key] = jarHashCacheEntry{
			Size:    items[index].size,
			ModTime: items[index].mod,
			SHA256:  digest,
		}
		dirty = true
	}
	if dirty {
		// Digest caching is an optimization only. A cache write failure must not
		// prevent a correct content fingerprint from being returned.
		_ = writeJarHashCache(hashCachePath, cache)
	}
	modHashCacheMu.Unlock()

	hash := sha256.New()
	for _, entry := range items {
		_, _ = fmt.Fprintf(hash, "%s\x00%d\x00%s\n", entry.name, entry.size, entry.hash)
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
}

func jarHashCacheKey(path string) string {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return filepath.Clean(path)
	}
	return filepath.Clean(absolute)
}

func readJarHashCache(path string) jarHashCacheDocument {
	document := jarHashCacheDocument{
		Schema:  modHashCacheSchema,
		Entries: make(map[string]jarHashCacheEntry),
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return document
	}
	var decoded jarHashCacheDocument
	if json.Unmarshal(data, &decoded) != nil || decoded.Schema != modHashCacheSchema || decoded.Entries == nil {
		return document
	}
	return decoded
}

func writeJarHashCache(path string, document jarHashCacheDocument) error {
	if document.Entries == nil {
		document.Entries = make(map[string]jarHashCacheEntry)
	}
	document.Schema = modHashCacheSchema
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".mod-hash-cache-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	_ = os.Remove(path)
	return os.Rename(temporaryPath, path)
}

func hashFileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
}

func loadedModsFingerprint(mods []string) string {
	values := append([]string(nil), mods...)
	sort.Strings(values)
	hash := sha256.New()
	for _, value := range values {
		_, _ = fmt.Fprintln(hash, value)
	}
	return "unscoped-" + fmt.Sprintf("%x", hash.Sum(nil))
}

func safeFingerprint(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "unknown"
	}
	// Real mod fingerprints are SHA-256. Keep unscoped values readable but safe.
	if len(value) > 80 {
		value = value[:80]
	}
	return safeVersion(value)
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
	return strings.HasPrefix(lower, captureBridgePrefix) &&
		strings.HasSuffix(lower, captureBridgeSuffix)
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
	return filepath.Join(appdirs.CacheRoot(), "runtime-registry")
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
