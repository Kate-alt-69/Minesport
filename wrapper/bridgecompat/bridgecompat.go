package bridgecompat

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/kastrick/minesport/processutil"
)

const (
	DefaultRepository = "Kate-alt-69/Minesport"
	DefaultRef        = "main"
	BundledVersion    = "1.21.10"
)

const maxModuleBytes = 8 << 20

var sha256Pattern = regexp.MustCompile(`^[A-Fa-f0-9]{64}$`)

type Progress struct {
	Percent int
	Stage   string
	Detail  string
}

type ProgressFunc func(Progress)

type Manifest struct {
	Schema     int       `json:"schema"`
	Repository string    `json:"repository"`
	Ref        string    `json:"ref"`
	Base       BaseSpec  `json:"base"`
	Profiles   []Profile `json:"profiles"`
}

type BaseSpec struct {
	Version        string   `json:"version"`
	Compatible     []string `json:"compatible"`
	SourceRoot     string   `json:"source_root"`
	Files          []string `json:"files"`
	BundledJarName string   `json:"bundled_jar"`
	Java           int      `json:"java"`
}

type Profile struct {
	ID        string `json:"id"`
	Match     string `json:"match"`
	Patch     string `json:"patch"`
	Java      int    `json:"java"`
	Gradle    string `json:"gradle"`
	Loom      string `json:"loom"`
	Loader    string `json:"loader"`
	FabricAPI string `json:"fabric_api"`
}

type PatchSet struct {
	Schema      int              `json:"schema"`
	Description string           `json:"description"`
	Operations  []PatchOperation `json:"operations"`
}

type PatchOperation struct {
	Op          string   `json:"op"`
	Name        string   `json:"name,omitempty"`
	File        string   `json:"file,omitempty"`
	Root        string   `json:"root,omitempty"`
	Extensions  []string `json:"extensions,omitempty"`
	From        string   `json:"from,omitempty"`
	To          string   `json:"to,omitempty"`
	Pattern     string   `json:"pattern,omitempty"`
	Replacement string   `json:"replacement,omitempty"`
	Source      string   `json:"source,omitempty"`
	Target      string   `json:"target,omitempty"`
	URL         string   `json:"url,omitempty"`
	SHA256      string   `json:"sha256,omitempty"`
	Key         string   `json:"key,omitempty"`
	Value       string   `json:"value,omitempty"`
	Line        int      `json:"line,omitempty"`
	Column      int      `json:"column,omitempty"`
}

type PreparedSource struct {
	Version   string
	Workspace string
	Profile   Profile
	Variables map[string]string
}

var minecraftVersionPattern = regexp.MustCompile(`(?:^|[^0-9])((?:1\.[0-9]+(?:\.[0-9]+)?)|(?:2[0-9]\.[0-9]+(?:\.[0-9]+)?(?:-snapshot-[0-9]+)?))`)

func NormalizeVersion(raw string) string {
	matches := minecraftVersionPattern.FindAllStringSubmatch(strings.TrimSpace(raw), -1)
	if len(matches) == 0 {
		return ""
	}
	return matches[len(matches)-1][1]
}

func LoadManifest() (Manifest, error) {
	var manifest Manifest
	data, err := readRepoResource(DefaultRepository, DefaultRef, "bridge-versions/manifest.json")
	if err != nil {
		if local := installedManifestPath(); local != "" {
			if fallback, readErr := os.ReadFile(local); readErr == nil {
				data = fallback
				err = nil
			}
		}
	}
	if err != nil {
		return manifest, fmt.Errorf("load bridge compatibility manifest: %w", err)
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		return manifest, fmt.Errorf("parse bridge compatibility manifest: %w", err)
	}
	if manifest.Schema != 1 {
		return manifest, fmt.Errorf("unsupported bridge manifest schema %d", manifest.Schema)
	}
	if manifest.Repository == "" {
		manifest.Repository = DefaultRepository
	}
	if manifest.Ref == "" {
		manifest.Ref = DefaultRef
	}
	return manifest, nil
}

func IsBundledCompatible(version string, manifest Manifest) bool {
	version = NormalizeVersion(version)
	for _, compatible := range manifest.Base.Compatible {
		if version == compatible {
			return true
		}
	}
	return false
}

func NeedsPreparation(version string) bool {
	version = NormalizeVersion(version)
	if version == "" || version == "1.21.9" || version == BundledVersion {
		return false
	}
	manifest, err := LoadManifest()
	if err != nil {
		return version != BundledVersion
	}
	return !IsBundledCompatible(version, manifest)
}

func PreparedBridge(version string) (string, bool) {
	version = NormalizeVersion(version)
	if version == "" {
		return "", false
	}
	if !NeedsPreparation(version) {
		bridge, err := BundledBridge()
		return bridge, err == nil
	}
	bridge := cachedBridgePath(version)
	return bridge, bridge != ""
}

func ProfileForVersion(version string, manifest Manifest) (*Profile, error) {
	version = NormalizeVersion(version)
	for i := range manifest.Profiles {
		profile := &manifest.Profiles[i]
		rx, err := regexp.Compile(profile.Match)
		if err != nil {
			return nil, fmt.Errorf("invalid match expression for profile %s: %w", profile.ID, err)
		}
		if rx.MatchString(version) {
			return profile, nil
		}
	}
	return nil, fmt.Errorf("Minesport has no compatibility recipe for Minecraft %s yet", version)
}

func BundledBridge() (string, error) {
	manifest := Manifest{Base: BaseSpec{
		Version:        BundledVersion,
		Compatible:     []string{"1.21.9", BundledVersion},
		BundledJarName: "minesport-bridge-0.1.0.jar",
	}}
	if installed := installedManifestPath(); installed != "" {
		if data, err := os.ReadFile(installed); err == nil {
			var parsed Manifest
			if json.Unmarshal(data, &parsed) == nil && parsed.Base.Version != "" {
				manifest = parsed
			}
		}
	}
	return bundledBridgePath(manifest)
}

func Ensure(version string, progress ProgressFunc) (string, error) {
	version = NormalizeVersion(version)
	if version == "" {
		return "", errors.New("could not determine the Minecraft version")
	}

	if version == "1.21.9" || version == BundledVersion {
		report(progress, 2, "Preparing…", "Checking bundled Minecraft "+version+" compatibility")
		bridge, err := BundledBridge()
		if err != nil {
			return "", err
		}
		report(progress, 100, "Ready", "Using bundled Minesport Bridge "+BundledVersion)
		return bridge, nil
	}

	report(progress, 2, "Preparing…", "Checking Minecraft "+version+" compatibility")
	manifest, err := LoadManifest()
	if err != nil {
		return "", err
	}
	if IsBundledCompatible(version, manifest) {
		bridge, err := bundledBridgePath(manifest)
		if err != nil {
			return "", err
		}
		report(progress, 100, "Ready", "Using bundled Minesport Bridge "+manifest.Base.Version)
		return bridge, nil
	}
	if cached := cachedBridgePath(version); cached != "" {
		report(progress, 100, "Ready", "Using cached bridge for Minecraft "+version)
		return cached, nil
	}

	prepared, err := PrepareSource(version, progress)
	if err != nil {
		return "", err
	}
	javaHome, err := ensureJDK(prepared.Profile.Java, progress)
	if err != nil {
		return "", err
	}
	report(progress, 58, "Downloading Gradle…", "Gradle and Fabric dependencies are fetched only when needed")
	report(progress, 68, "Compiling…", "Compiling Minesport Bridge for Minecraft "+version)
	builtJar, err := runGradleBuild(prepared.Workspace, javaHome)
	if err != nil {
		return "", err
	}

	report(progress, 94, "Verifying…", "Checking compiled bridge output")
	destinationRoot := writableCompiledRoot()
	if err := os.MkdirAll(filepath.Join(destinationRoot, safeVersion(version)), 0o755); err != nil {
		return "", fmt.Errorf("create bridge cache: %w", err)
	}
	destination := filepath.Join(destinationRoot, safeVersion(version), "minesport-bridge-"+safeVersion(version)+".jar")
	if err := copyFile(builtJar, destination, 0o644); err != nil {
		return "", fmt.Errorf("cache compiled bridge: %w", err)
	}
	metadata := map[string]any{
		"minecraft": version,
		"profile":   prepared.Profile.ID,
		"built_at":  time.Now().UTC().Format(time.RFC3339),
		"variables": prepared.Variables,
	}
	if data, marshalErr := json.MarshalIndent(metadata, "", "  "); marshalErr == nil {
		_ = os.WriteFile(filepath.Join(filepath.Dir(destination), "build.json"), data, 0o644)
	}
	report(progress, 100, "Ready", "Minecraft "+version+" compatibility prepared")
	return destination, nil
}

func PrepareSource(version string, progress ProgressFunc) (PreparedSource, error) {
	version = NormalizeVersion(version)
	manifest, err := LoadManifest()
	if err != nil {
		return PreparedSource{}, err
	}
	profile, err := ProfileForVersion(version, manifest)
	if err != nil {
		return PreparedSource{}, err
	}
	workspace := filepath.Join(buildWorkspaceRoot(), safeVersion(version))
	if err := os.RemoveAll(workspace); err != nil {
		return PreparedSource{}, fmt.Errorf("reset bridge build workspace: %w", err)
	}
	if err := os.MkdirAll(workspace, 0o755); err != nil {
		return PreparedSource{}, fmt.Errorf("create bridge build workspace: %w", err)
	}

	report(progress, 8, "Downloading source…", "Fetching the 1.21.10 bridge base from GitHub")
	for index, relative := range manifest.Base.Files {
		repoPath := path.Join(manifest.Base.SourceRoot, relative)
		data, err := readRepoResource(manifest.Repository, manifest.Ref, repoPath)
		if err != nil {
			return PreparedSource{}, fmt.Errorf("download %s: %w", repoPath, err)
		}
		mode := os.FileMode(0o644)
		if relative == "gradlew" {
			mode = 0o755
		}
		if err := writeWorkspaceFile(workspace, relative, data, mode); err != nil {
			return PreparedSource{}, err
		}
		if len(manifest.Base.Files) > 0 {
			pct := 8 + (index+1)*12/len(manifest.Base.Files)
			report(progress, pct, "Downloading source…", relative)
		}
	}

	report(progress, 22, "Preparing…", "Resolving Fabric Loader and Fabric API")
	loader := profile.Loader
	if loader == "" || loader == "dynamic" {
		loader, err = resolveFabricLoader(version)
		if err != nil {
			return PreparedSource{}, err
		}
	}
	fabricAPI := profile.FabricAPI
	if fabricAPI == "" || fabricAPI == "dynamic" {
		fabricAPI, err = resolveFabricAPI(version)
		if err != nil {
			return PreparedSource{}, err
		}
	}
	variables := map[string]string{
		"minecraft_version":  version,
		"loader_version":     loader,
		"fabric_api_version": fabricAPI,
		"fabric_version":     fabricAPI,
		"java_version":       fmt.Sprintf("%d", profile.Java),
		"gradle_version":     profile.Gradle,
		"loom_version":       profile.Loom,
	}

	patchBytes, err := readRepoResource(manifest.Repository, manifest.Ref, profile.Patch)
	if err != nil {
		return PreparedSource{}, fmt.Errorf("download patch recipe %s: %w", profile.Patch, err)
	}
	var patchSet PatchSet
	if err := json.Unmarshal(patchBytes, &patchSet); err != nil {
		return PreparedSource{}, fmt.Errorf("parse patch recipe %s: %w", profile.Patch, err)
	}
	if patchSet.Schema != 1 {
		return PreparedSource{}, fmt.Errorf("unsupported patch schema %d in %s", patchSet.Schema, profile.Patch)
	}

	report(progress, 28, "Preparing…", "Applying compatibility recipe "+profile.ID)
	for _, operation := range patchSet.Operations {
		if err := applyOperation(workspace, manifest, operation, variables); err != nil {
			return PreparedSource{}, fmt.Errorf("apply %s operation: %w", operation.Op, err)
		}
	}
	metadata := map[string]any{"minecraft": version, "profile": profile.ID, "variables": variables}
	if data, err := json.MarshalIndent(metadata, "", "  "); err == nil {
		_ = os.WriteFile(filepath.Join(workspace, "minesport-target.json"), data, 0o644)
	}
	return PreparedSource{Version: version, Workspace: workspace, Profile: *profile, Variables: variables}, nil
}

func applyOperation(workspace string, manifest Manifest, operation PatchOperation, variables map[string]string) error {
	expand := func(value string) string { return expandVariables(value, variables) }
	switch operation.Op {
	case "set_property":
		file, err := workspacePath(workspace, expand(operation.File)); if err != nil { return err }
		return setProperty(file, expand(operation.Key), expand(operation.Value))
	case "replace":
		file, err := workspacePath(workspace, expand(operation.File)); if err != nil { return err }
		return replaceInFile(file, expand(operation.From), expand(operation.To), false)
	case "rename_at":
		file, err := workspacePath(workspace, expand(operation.File)); if err != nil { return err }
		return replaceAt(file, operation.Line, operation.Column, expand(operation.From), expand(operation.To))
	case "regex_replace":
		file, err := workspacePath(workspace, expand(operation.File)); if err != nil { return err }
		data, err := os.ReadFile(file); if err != nil { return err }
		pattern := expand(operation.Pattern)
		rx, err := regexp.Compile(pattern); if err != nil { return err }
		if !rx.Match(data) { return fmt.Errorf("expected regex %q was not found in %s", pattern, file) }
		return os.WriteFile(file, rx.ReplaceAll(data, []byte(expand(operation.Replacement))), 0o644)
	case "replace_tree", "rename_package":
		root, err := workspacePath(workspace, expand(operation.Root)); if err != nil { return err }
		extensions := operation.Extensions
		if operation.Op == "rename_package" && len(extensions) == 0 { extensions = []string{".java"} }
		from, to := expand(operation.From), expand(operation.To)
		return filepath.WalkDir(root, func(file string, entry os.DirEntry, walkErr error) error {
			if walkErr != nil { return walkErr }
			if entry.IsDir() || !hasExtension(file, extensions) { return nil }
			return replaceInFile(file, from, to, true)
		})
	case "rename_file":
		from, err := workspacePath(workspace, expand(operation.From)); if err != nil { return err }
		to, err := workspacePath(workspace, expand(operation.To)); if err != nil { return err }
		if err := os.MkdirAll(filepath.Dir(to), 0o755); err != nil { return err }
		return os.Rename(from, to)
	case "overlay":
		source := expand(operation.Source)
		data, err := readRepoResource(manifest.Repository, manifest.Ref, source); if err != nil { return err }
		target, err := workspacePath(workspace, expand(operation.Target)); if err != nil { return err }
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil { return err }
		return os.WriteFile(target, data, 0o644)
	case "module":
		data, err := downloadPinnedModule(expand(operation.URL), expand(operation.SHA256)); if err != nil { return fmt.Errorf("module %s: %w", operation.Name, err) }
		target, err := workspacePath(workspace, projectRelative(expand(operation.Target))); if err != nil { return err }
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil { return err }
		return os.WriteFile(target, data, 0o644)
	case "delete":
		target := operation.Target
		if target == "" { target = operation.File }
		file, err := workspacePath(workspace, expand(target)); if err != nil { return err }
		return os.RemoveAll(file)
	default:
		return fmt.Errorf("unknown compatibility operation %q", operation.Op)
	}
}

func replaceAt(file string, line, column int, from, to string) error {
	if line < 1 || column < 1 || from == "" { return fmt.Errorf("rename_at requires 1-based line/column and non-empty from text") }
	data, err := os.ReadFile(file); if err != nil { return err }
	text := string(data)
	lineStart := 0
	for current := 1; current < line; current++ {
		index := strings.IndexByte(text[lineStart:], '\n')
		if index < 0 { return fmt.Errorf("line %d does not exist in %s", line, file) }
		lineStart += index + 1
	}
	start := lineStart + column - 1
	if start < lineStart || start+len(from) > len(text) { return fmt.Errorf("line %d column %d is outside %s", line, column, file) }
	lineEnd := strings.IndexByte(text[lineStart:], '\n')
	if lineEnd < 0 { lineEnd = len(text) - lineStart } else if strings.HasSuffix(text[lineStart:lineStart+lineEnd], "\r") { lineEnd-- }
	if start+len(from) > lineStart+lineEnd { return fmt.Errorf("rename_at text crosses line boundary in %s", file) }
	if text[start:start+len(from)] != from { return fmt.Errorf("stale rename_at in %s:%d:%d: expected %q, found %q", file, line, column, from, text[start:minInt(len(text), start+len(from))]) }
	return os.WriteFile(file, []byte(text[:start]+to+text[start+len(from):]), 0o644)
}

func projectRelative(value string) string {
	value = strings.TrimSpace(value)
	value = strings.TrimPrefix(value, "&PROJECT&/")
	value = strings.TrimPrefix(value, "&PROJECT&\\")
	return filepath.Clean(filepath.FromSlash(value))
}

func downloadPinnedModule(endpoint, expectedSHA string) ([]byte, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" { return nil, fmt.Errorf("module URL must use HTTPS") }
	if !sha256Pattern.MatchString(expectedSHA) { return nil, fmt.Errorf("module requires a 64-character SHA-256") }
	client := &http.Client{Timeout: 2 * time.Minute}
	req, err := http.NewRequest(http.MethodGet, endpoint, nil); if err != nil { return nil, err }
	req.Header.Set("User-Agent", "Minesport-Bridge-Builder/1")
	resp, err := client.Do(req); if err != nil { return nil, err }
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 { return nil, fmt.Errorf("HTTP %d downloading module", resp.StatusCode) }
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxModuleBytes+1)); if err != nil { return nil, err }
	if len(data) > maxModuleBytes { return nil, fmt.Errorf("module exceeds %d byte limit", maxModuleBytes) }
	actual := sha256.Sum256(data)
	if !strings.EqualFold(hex.EncodeToString(actual[:]), expectedSHA) { return nil, fmt.Errorf("module SHA-256 mismatch") }
	if !utf8.Valid(data) { return nil, fmt.Errorf("module is not valid UTF-8 text") }
	return data, nil
}

func minInt(a, b int) int { if a < b { return a }; return b }

func setProperty(file, key, value string) error {
	data, err := os.ReadFile(file); if err != nil { return err }
	lines := strings.Split(string(data), "\n")
	prefix := key + "="
	updated := false
	for i := range lines { if strings.HasPrefix(strings.TrimSpace(lines[i]), prefix) { lines[i] = prefix + value; updated = true } }
	if !updated { lines = append(lines, prefix+value) }
	return os.WriteFile(file, []byte(strings.Join(lines, "\n")), 0o644)
}

func replaceInFile(file, from, to string, optional bool) error {
	data, err := os.ReadFile(file); if err != nil { return err }
	text := string(data)
	if !strings.Contains(text, from) { if optional { return nil }; return fmt.Errorf("expected text %q was not found in %s", from, file) }
	return os.WriteFile(file, []byte(strings.ReplaceAll(text, from, to)), 0o644)
}

func expandVariables(value string, variables map[string]string) string {
	for key, replacement := range variables { value = strings.ReplaceAll(value, "${"+key+"}", replacement) }
	return value
}

func resolveFabricLoader(version string) (string, error) {
	data, err := httpGet("https://meta.fabricmc.net/v2/versions/loader/" + url.PathEscape(version)); if err != nil { return "", err }
	var response []struct { Loader struct { Version string `json:"version"`; Stable bool `json:"stable"` } `json:"loader"` }
	if err := json.Unmarshal(data, &response); err != nil { return "", err }
	for _, item := range response { if item.Loader.Stable && item.Loader.Version != "" { return item.Loader.Version, nil } }
	if len(response) > 0 && response[0].Loader.Version != "" { return response[0].Loader.Version, nil }
	return "", fmt.Errorf("Fabric Meta does not publish a loader for Minecraft %s", version)
}

type mavenMetadata struct { Versions []string `xml:"versioning>versions>version"` }

func resolveFabricAPI(version string) (string, error) {
	data, err := httpGet("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"); if err != nil { return "", err }
	var metadata mavenMetadata
	if err := xml.Unmarshal(data, &metadata); err != nil { return "", err }
	selected := ""
	for _, candidate := range metadata.Versions { if strings.HasSuffix(candidate, "+"+version) { selected = candidate } }
	if selected == "" { return "", fmt.Errorf("Fabric API does not currently publish a build for Minecraft %s", version) }
	return selected, nil
}

func bundledBridgePath(manifest Manifest) (string, error) {
	version := manifest.Base.Version
	if version == "" { version = BundledVersion }
	jarName := manifest.Base.BundledJarName
	if jarName == "" { jarName = "minesport-bridge-0.1.0.jar" }
	candidates := []string{filepath.Join(supportRoot(), "bundled", version, jarName)}
	if cwd, err := os.Getwd(); err == nil {
		candidates = append(candidates, filepath.Join(cwd, "bridge", "build", "libs", jarName), filepath.Join(cwd, "..", "bridge", "build", "libs", jarName))
	}
	for _, candidate := range candidates { if info, err := os.Stat(candidate); err == nil && !info.IsDir() { return candidate, nil } }
	return "", fmt.Errorf("bundled Minecraft %s bridge is missing; reinstall Minesport", version)
}

func cachedBridgePath(version string) string {
	filename := "minesport-bridge-" + safeVersion(version) + ".jar"
	roots := []string{filepath.Join(supportRoot(), "compiled"), filepath.Join(userDataRoot(), "bridges")}
	for _, root := range roots { candidate := filepath.Join(root, safeVersion(version), filename); if info, err := os.Stat(candidate); err == nil && !info.IsDir() { return candidate } }
	return ""
}

func writableCompiledRoot() string {
	preferred := filepath.Join(supportRoot(), "compiled")
	if canWriteDirectory(preferred) { return preferred }
	fallback := filepath.Join(userDataRoot(), "bridges")
	_ = os.MkdirAll(fallback, 0o755)
	return fallback
}

func supportRoot() string {
	if root := os.Getenv("MINESPORT_BRIDGE_DATA"); root != "" { return root }
	if runtime.GOOS == "windows" {
		if programFiles := os.Getenv("ProgramFiles"); programFiles != "" { return filepath.Join(programFiles, "kastrick's_software", "minesport", "bridge-data") }
	}
	if runtime.GOOS == "linux" {
		system := filepath.Join(string(filepath.Separator), "usr", "share", "kastrick_software", "minesport", "bridge-data")
		if info, err := os.Stat(filepath.Join(system, "manifest.json")); err == nil && !info.IsDir() { return system }
	}
	return filepath.Join(userDataRoot(), "bridge-data")
}

func userDataRoot() string {
	if runtime.GOOS == "windows" { if local := os.Getenv("LOCALAPPDATA"); local != "" { return filepath.Join(local, "kastrick's_software", "minesport") } }
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" { return filepath.Join(home, "Library", "Application Support", "kastrick's_software", "minesport") }
	if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" { return filepath.Join(xdg, "kastrick_software", "minesport") }
	return filepath.Join(home, ".local", "share", "kastrick_software", "minesport")
}

func buildWorkspaceRoot() string {
	if runtime.GOOS == "windows" { if local := os.Getenv("LOCALAPPDATA"); local != "" { return filepath.Join(local, "kastrick's_software", "minesport", "bridge-build") } }
	if cache := os.Getenv("XDG_CACHE_HOME"); cache != "" { return filepath.Join(cache, "kastrick_software", "minesport", "bridge-build") }
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" { return filepath.Join(home, "Library", "Caches", "kastrick's_software", "minesport", "bridge-build") }
	return filepath.Join(home, ".cache", "kastrick_software", "minesport", "bridge-build")
}

func installedManifestPath() string { return filepath.Join(supportRoot(), "manifest.json") }

func readRepoResource(repository, ref, repoPath string) ([]byte, error) {
	if localRoot := os.Getenv("MINESPORT_BRIDGE_REPO_ROOT"); localRoot != "" { file, err := safeJoin(localRoot, filepath.FromSlash(repoPath)); if err != nil { return nil, err }; return os.ReadFile(file) }
	parts := strings.Split(repoPath, "/"); for i := range parts { parts[i] = url.PathEscape(parts[i]) }
	endpoint := fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s", repository, url.PathEscape(ref), strings.Join(parts, "/"))
	return httpGet(endpoint)
}

func httpGet(endpoint string) ([]byte, error) {
	client := &http.Client{Timeout: 5 * time.Minute}
	req, err := http.NewRequest(http.MethodGet, endpoint, nil); if err != nil { return nil, err }
	req.Header.Set("User-Agent", "Minesport-Bridge-Builder/1")
	resp, err := client.Do(req); if err != nil { return nil, err }
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 { return nil, fmt.Errorf("HTTP %d from %s", resp.StatusCode, endpoint) }
	return io.ReadAll(resp.Body)
}

func writeWorkspaceFile(workspace, relative string, data []byte, mode os.FileMode) error { file, err := workspacePath(workspace, relative); if err != nil { return err }; if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil { return err }; return os.WriteFile(file, data, mode) }
func workspacePath(workspace, relative string) (string, error) { return safeJoin(workspace, filepath.FromSlash(relative)) }
func safeJoin(root, relative string) (string, error) { if filepath.IsAbs(relative) { return "", fmt.Errorf("absolute path is not allowed: %s", relative) }; root = filepath.Clean(root); joined := filepath.Clean(filepath.Join(root, relative)); rel, err := filepath.Rel(root, joined); if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) { return "", fmt.Errorf("path escapes root: %s", relative) }; return joined, nil }
func hasExtension(file string, extensions []string) bool { if len(extensions) == 0 { return true }; ext := strings.ToLower(filepath.Ext(file)); for _, candidate := range extensions { if ext == strings.ToLower(candidate) { return true } }; return false }
func safeVersion(version string) string { return regexp.MustCompile(`[^A-Za-z0-9._-]+`).ReplaceAllString(version, "_") }
func canWriteDirectory(directory string) bool { if err := os.MkdirAll(directory, 0o755); err != nil { return false }; test := filepath.Join(directory, ".minesport-write-test"); if err := os.WriteFile(test, []byte("ok"), 0o600); err != nil { return false }; _ = os.Remove(test); return true }
func copyFile(source, destination string, mode os.FileMode) error { input, err := os.Open(source); if err != nil { return err }; defer input.Close(); if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil { return err }; output, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode); if err != nil { return err }; _, copyErr := io.Copy(output, input); closeErr := output.Close(); if copyErr != nil { return copyErr }; return closeErr }
func report(progress ProgressFunc, percent int, stage, detail string) { if progress != nil { progress(Progress{Percent: percent, Stage: stage, Detail: detail}) } }

func buildProjectPath() string { return filepath.Join(userDataRoot(), "bridge-build") }

func runGradleBuild(workspace, javaHome string) (string, error) {
	wrapper := filepath.Join(workspace, "gradlew")
	if runtime.GOOS == "windows" { wrapper = filepath.Join(workspace, "gradlew.bat") }
	if runtime.GOOS != "windows" { _ = os.Chmod(wrapper, 0o755) }
	cmd := exec.Command(wrapper, "--no-daemon", "--stacktrace", "clean", "build")
	processutil.HideWindow(cmd)
	cmd.Dir = workspace
	cmd.Env = append(os.Environ(), "JAVA_HOME="+javaHome)
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil { return "", fmt.Errorf("Gradle bridge compilation failed: %w", err) }
	matches, err := filepath.Glob(filepath.Join(workspace, "build", "libs", "*.jar")); if err != nil { return "", err }
	for _, candidate := range matches { if !strings.Contains(strings.ToLower(filepath.Base(candidate)), "sources") { return candidate, nil } }
	return "", errors.New("Gradle reported success but no bridge JAR was produced")
}
