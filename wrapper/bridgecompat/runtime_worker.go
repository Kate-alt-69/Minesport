package bridgecompat

import (
	"archive/zip"
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/kastrick/minesport/processutil"
)

// RuntimeWorker is a disposable Fabric/Loom client used only to let Minecraft
// and installed mods register and bake their actual block models. It never opens
// the selected world and never writes into the user's real Minecraft instance.
type RuntimeWorker struct {
	cmd             *exec.Cmd
	workspace       string
	logPath         string
	version         string
	done            chan struct{}
	mu              sync.Mutex
	result          error
	intentionalStop bool
	stopOnce        sync.Once
}

// StartRuntimeWorker prepares an exact-version Loom workspace, copies the
// selected instance's mod JARs/config into its disposable run directory, and
// launches runClient directly. No launcher/profile/account interaction occurs.
func StartRuntimeWorker(
	version string,
	modsPath string,
	port int,
	progress ProgressFunc,
) (*RuntimeWorker, error) {
	version = NormalizeVersion(version)
	if version == "" {
		return nil, fmt.Errorf("could not determine Minecraft version")
	}
	modsPath = filepath.Clean(strings.TrimSpace(modsPath))
	if modsPath == "" || modsPath == "." {
		return nil, fmt.Errorf("mods folder path is required")
	}
	if info, err := os.Stat(modsPath); err != nil || !info.IsDir() {
		return nil, fmt.Errorf("mods folder is unavailable: %s", modsPath)
	}
	if port <= 0 || port > 65535 {
		return nil, fmt.Errorf("invalid runtime registry port %d", port)
	}

	report(progress, 2, "Preparing worker…", "Building an isolated Minecraft runtime workspace")
	prepared, err := prepareRuntimeWorkerSource(version, progress)
	if err != nil {
		return nil, err
	}
	workspace := prepared.Workspace
	cleanupOnError := true
	defer func() {
		if cleanupOnError {
			_ = os.RemoveAll(workspace)
		}
	}()

	javaHome, err := ensureJDK(prepared.Profile.Java, progress)
	if err != nil {
		return nil, err
	}

	runDir := filepath.Join(workspace, "run")
	if err := os.MkdirAll(filepath.Join(runDir, "mods"), 0o755); err != nil {
		return nil, fmt.Errorf("create worker run directory: %w", err)
	}
	report(progress, 58, "Preparing worker…", "Copying installed mods into the disposable runtime")
	modCount, err := copyWorkerMods(modsPath, filepath.Join(runDir, "mods"))
	if err != nil {
		return nil, err
	}

	// Preserve configuration-dependent registrations without granting the worker
	// write access to the real instance. The copied config is intentionally
	// writable because mods often update config migrations during startup.
	instanceDir := filepath.Dir(modsPath)
	configSource := filepath.Join(instanceDir, "config")
	configTarget := filepath.Join(runDir, "config")
	if info, statErr := os.Stat(configSource); statErr == nil && info.IsDir() {
		if err := copyDirectory(configSource, configTarget); err != nil {
			return nil, fmt.Errorf("copy instance config into worker: %w", err)
		}
	}

	wrapper := filepath.Join(workspace, "gradlew")
	if runtime.GOOS == "windows" {
		wrapper = filepath.Join(workspace, "gradlew.bat")
	} else {
		_ = os.Chmod(wrapper, 0o755)
	}
	if info, err := os.Stat(wrapper); err != nil || info.IsDir() {
		return nil, fmt.Errorf("Gradle wrapper is unavailable in runtime workspace")
	}

	logPath := filepath.Join(workspace, "runtime-worker.log")
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, fmt.Errorf("create runtime worker log: %w", err)
	}

	cmd := exec.Command(wrapper, "--no-daemon", "--console=plain", "runClient")
	processutil.HideWindow(cmd)
	cmd.Dir = workspace
	cmd.Env = append(os.Environ(),
		"JAVA_HOME="+javaHome,
		fmt.Sprintf("MINESPORT_BRIDGE_PORT=%d", port),
		"MINESPORT_BRIDGE_MODE=all",
		"MINESPORT_BRIDGE_WORKER=1",
	)
	cmd.Stdout = logFile
	cmd.Stderr = logFile

	report(progress, 72, "Starting worker…", fmt.Sprintf("Loading Minecraft %s with %d copied mod JAR(s)", version, modCount))
	if err := cmd.Start(); err != nil {
		_ = logFile.Close()
		return nil, fmt.Errorf("start isolated Minecraft runtime worker: %w", err)
	}

	worker := &RuntimeWorker{
		cmd:       cmd,
		workspace: workspace,
		logPath:   logPath,
		version:   version,
		done:      make(chan struct{}),
	}
	cleanupOnError = false
	go worker.waitAndCleanup(logFile)
	return worker, nil
}

func (worker *RuntimeWorker) waitAndCleanup(logFile *os.File) {
	err := worker.cmd.Wait()
	_ = logFile.Close()

	worker.mu.Lock()
	intentionalStop := worker.intentionalStop
	worker.mu.Unlock()

	if err != nil && !intentionalStop {
		detail := tailFile(worker.logPath, 50)
		diagnostics := preserveRuntimeWorkerDiagnostics(worker.workspace, worker.version)
		if diagnostics != "" {
			if detail != "" {
				err = fmt.Errorf("runtime worker exited: %w\nDiagnostics preserved at: %s\n%s", err, diagnostics, detail)
			} else {
				err = fmt.Errorf("runtime worker exited: %w\nDiagnostics preserved at: %s", err, diagnostics)
			}
		} else if detail != "" {
			err = fmt.Errorf("runtime worker exited: %w\n%s", err, detail)
		} else {
			err = fmt.Errorf("runtime worker exited: %w", err)
		}
	} else if intentionalStop {
		// Cancel/timeout is a controlled shutdown, not a Minecraft crash.
		err = nil
	}

	worker.mu.Lock()
	worker.result = err
	worker.mu.Unlock()
	_ = os.RemoveAll(worker.workspace)
	close(worker.done)
}

func (worker *RuntimeWorker) Done() <-chan struct{} {
	if worker == nil || worker.done == nil {
		closed := make(chan struct{})
		close(closed)
		return closed
	}
	return worker.done
}

func (worker *RuntimeWorker) Wait() error {
	if worker == nil {
		return nil
	}
	<-worker.done
	worker.mu.Lock()
	defer worker.mu.Unlock()
	return worker.result
}

func (worker *RuntimeWorker) Stop() error {
	if worker == nil {
		return nil
	}
	var killErr error
	worker.stopOnce.Do(func() {
		worker.mu.Lock()
		worker.intentionalStop = true
		worker.mu.Unlock()
		if worker.cmd != nil && worker.cmd.Process != nil {
			killErr = worker.cmd.Process.Kill()
		}
	})
	<-worker.done
	return killErr
}

// prepareRuntimeWorkerSource always produces an exact-version source workspace,
// including versions whose normal Bridge path can use the bundled binary.
func prepareRuntimeWorkerSource(version string, progress ProgressFunc) (PreparedSource, error) {
	manifest, err := LoadManifest()
	if err != nil {
		return PreparedSource{}, err
	}
	version = NormalizeVersion(version)

	root := filepath.Join(buildWorkspaceRoot(), "runtime-workers")
	if err := os.MkdirAll(root, 0o755); err != nil {
		return PreparedSource{}, err
	}
	workspace, err := os.MkdirTemp(root, safeVersion(version)+"-")
	if err != nil {
		return PreparedSource{}, fmt.Errorf("create runtime worker workspace: %w", err)
	}
	fail := true
	defer func() {
		if fail {
			_ = os.RemoveAll(workspace)
		}
	}()

	report(progress, 8, "Preparing worker…", "Fetching Minesport Bridge source")
	for index, relative := range manifest.Base.Files {
		repoPath := filepath.ToSlash(filepath.Join(manifest.Base.SourceRoot, relative))
		data, readErr := readRepoResource(manifest.Repository, manifest.Ref, repoPath)
		if readErr != nil {
			return PreparedSource{}, fmt.Errorf("download %s: %w", repoPath, readErr)
		}
		mode := os.FileMode(0o644)
		if relative == "gradlew" {
			mode = 0o755
		}
		if err := writeWorkspaceFile(workspace, relative, data, mode); err != nil {
			return PreparedSource{}, err
		}
		if len(manifest.Base.Files) > 0 {
			report(progress, 8+(index+1)*12/len(manifest.Base.Files), "Preparing worker…", relative)
		}
	}

	profile, profileErr := ProfileForVersion(version, manifest)
	baseCompatible := IsBundledCompatible(version, manifest)
	if profileErr != nil && !baseCompatible {
		return PreparedSource{}, profileErr
	}
	if baseCompatible {
		profile = &Profile{
			ID:        "runtime-base-" + safeVersion(version),
			Java:      manifest.Base.Java,
			Loader:    "dynamic",
			FabricAPI: "dynamic",
		}
	}

	report(progress, 24, "Preparing worker…", "Resolving exact Fabric Loader/API")
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

	if baseCompatible {
		if err := setProperty(filepath.Join(workspace, "gradle.properties"), "minecraft_version", version); err != nil {
			return PreparedSource{}, err
		}
		if err := setProperty(filepath.Join(workspace, "gradle.properties"), "loader_version", loader); err != nil {
			return PreparedSource{}, err
		}
		if err := setProperty(filepath.Join(workspace, "gradle.properties"), "fabric_version", fabricAPI); err != nil {
			return PreparedSource{}, err
		}
	} else {
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
		report(progress, 30, "Preparing worker…", "Applying compatibility recipe "+profile.ID)
		for _, operation := range patchSet.Operations {
			if err := applyOperation(workspace, manifest, operation, variables); err != nil {
				return PreparedSource{}, fmt.Errorf("apply %s operation: %w", operation.Op, err)
			}
		}
	}

	metadata := map[string]any{
		"minecraft": version,
		"profile":   profile.ID,
		"variables": variables,
		"purpose":   "runtime-registry-worker",
	}
	if data, marshalErr := json.MarshalIndent(metadata, "", "  "); marshalErr == nil {
		_ = os.WriteFile(filepath.Join(workspace, "minesport-runtime-worker.json"), data, 0o600)
	}

	fail = false
	return PreparedSource{Version: version, Workspace: workspace, Profile: *profile, Variables: variables}, nil
}

func copyWorkerMods(sourceDir, targetDir string) (int, error) {
	entries, err := os.ReadDir(sourceDir)
	if err != nil {
		return 0, fmt.Errorf("read selected mods folder: %w", err)
	}
	count := 0
	for _, entry := range entries {
		if entry.IsDir() || !strings.EqualFold(filepath.Ext(entry.Name()), ".jar") {
			continue
		}
		lower := strings.ToLower(entry.Name())
		if strings.HasPrefix(lower, "minesport-capture-bridge-") {
			continue
		}
		source := filepath.Join(sourceDir, entry.Name())
		if shouldSkipRuntimeWorkerMod(source, entry.Name()) {
			continue
		}
		target := filepath.Join(targetDir, entry.Name())
		if err := copyFile(source, target, 0o444); err != nil {
			return count, fmt.Errorf("copy mod %s into runtime worker: %w", entry.Name(), err)
		}
		_ = os.Chmod(target, 0o444)
		count++
	}
	return count, nil
}

// Crash Assistant treats the intentionally short-lived registry worker like a
// normal player session and spawns a separate crash UI when the worker exits
// before TitleScreen. It contributes no block/model registration, so it must not
// be loaded in this headless disposable worker. Avoid broad client-mod filtering:
// content mods and their client dependencies still need to initialize normally.
func shouldSkipRuntimeWorkerMod(jarPath, filename string) bool {
	if strings.EqualFold(runtimeWorkerFabricModID(jarPath), "crash_assistant") {
		return true
	}
	lower := strings.ToLower(filename)
	return strings.HasPrefix(lower, "crashassistant-") ||
		strings.HasPrefix(lower, "crash-assistant-")
}

func runtimeWorkerFabricModID(jarPath string) string {
	archive, err := zip.OpenReader(jarPath)
	if err != nil {
		return ""
	}
	defer archive.Close()

	for _, item := range archive.File {
		if item.Name != "fabric.mod.json" {
			continue
		}
		reader, err := item.Open()
		if err != nil {
			return ""
		}
		var metadata struct {
			ID string `json:"id"`
		}
		err = json.NewDecoder(io.LimitReader(reader, 1<<20)).Decode(&metadata)
		_ = reader.Close()
		if err != nil {
			return ""
		}
		return strings.TrimSpace(metadata.ID)
	}
	return ""
}

func preserveRuntimeWorkerDiagnostics(workspace, version string) string {
	base, err := os.UserCacheDir()
	if err != nil || strings.TrimSpace(base) == "" {
		base = os.TempDir()
	}
	stamp := time.Now().UTC().Format("20060102T150405.000Z")
	destination := filepath.Join(
		base,
		"kastrick_software",
		"minesport",
		"diagnostics",
		"runtime-workers",
		safeVersion(version)+"-"+stamp,
	)
	if err := os.MkdirAll(destination, 0o700); err != nil {
		return ""
	}

	copied := false
	files := []struct {
		source string
		name   string
	}{
		{filepath.Join(workspace, "runtime-worker.log"), "runtime-worker.log"},
		{filepath.Join(workspace, "run", "logs", "latest.log"), "minecraft-latest.log"},
	}
	for _, file := range files {
		if info, statErr := os.Stat(file.source); statErr != nil || info.IsDir() {
			continue
		}
		if copyFile(file.source, filepath.Join(destination, file.name), 0o600) == nil {
			copied = true
		}
	}
	if !copied {
		_ = os.RemoveAll(destination)
		return ""
	}
	return destination
}

func copyDirectory(source, target string) error {
	return filepath.WalkDir(source, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		relative, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		destination := filepath.Join(target, relative)
		if entry.Type()&os.ModeSymlink != 0 {
			return nil
		}
		if entry.IsDir() {
			return os.MkdirAll(destination, 0o755)
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return nil
		}
		return copyFile(path, destination, 0o644)
	})
}

func tailFile(path string, limit int) string {
	file, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer file.Close()
	var lines []string
	scanner := bufio.NewScanner(io.LimitReader(file, 8<<20))
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
		if limit > 0 && len(lines) > limit*2 {
			lines = append([]string(nil), lines[len(lines)-limit:]...)
		}
	}
	if limit > 0 && len(lines) > limit {
		lines = lines[len(lines)-limit:]
	}
	return strings.Join(lines, "\n")
}
