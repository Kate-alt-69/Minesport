package ui

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/bridgecapture"
	"github.com/kastrick/minesport/bridgecompat"
)

const runtimeRegistryPort = 25590

type runtimeCacheCompletion func(error)
type runtimeCacheProgress func(int, string)

type runtimeCacheJobState struct {
	mu         sync.Mutex
	running    bool
	cancelled  bool
	version    string
	modsPath   string
	worker     *bridgecompat.RuntimeWorker
	callbacks  []runtimeCacheCompletion
	listeners  []runtimeCacheProgress
}

var runtimeCacheJobs sync.Map

func runtimeCacheJob(ms *MinesportApp) *runtimeCacheJobState {
	if value, ok := runtimeCacheJobs.Load(ms); ok {
		if state, _ := value.(*runtimeCacheJobState); state != nil {
			return state
		}
	}
	state := &runtimeCacheJobState{}
	actual, _ := runtimeCacheJobs.LoadOrStore(ms, state)
	resolved, _ := actual.(*runtimeCacheJobState)
	return resolved
}

func (ms *MinesportApp) emitRuntimeCacheProgress(percent int, message string) {
	state := runtimeCacheJob(ms)
	state.mu.Lock()
	listeners := append([]runtimeCacheProgress(nil), state.listeners...)
	state.mu.Unlock()
	if len(listeners) == 0 {
		return
	}
	ms.dispatchUI(func() {
		for _, listener := range listeners {
			if listener != nil {
				listener(percent, message)
			}
		}
	})
}

func (ms *MinesportApp) finishRuntimeCacheJob(err error) {
	state := runtimeCacheJob(ms)
	state.mu.Lock()
	callbacks := append([]runtimeCacheCompletion(nil), state.callbacks...)
	state.running = false
	state.cancelled = false
	state.version = ""
	state.modsPath = ""
	state.worker = nil
	state.callbacks = nil
	state.listeners = nil
	state.mu.Unlock()
	if len(callbacks) == 0 {
		return
	}
	ms.dispatchUI(func() {
		for _, callback := range callbacks {
			if callback != nil {
				callback(err)
			}
		}
	})
}

func (ms *MinesportApp) generateRuntimeModelCache(
	force bool,
	progress runtimeCacheProgress,
	done runtimeCacheCompletion,
) (bool, error) {
	version, err := ms.validateBridgeRuntimeCapture()
	if err != nil {
		return false, err
	}
	if !force {
		if _, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
			return false, nil
		}
	}

	state := runtimeCacheJob(ms)
	state.mu.Lock()
	if state.running {
		if state.version != version || !strings.EqualFold(state.modsPath, ms.modsPath) {
			state.mu.Unlock()
			return false, fmt.Errorf("another runtime model-cache worker is already running")
		}
		if progress != nil {
			state.listeners = append(state.listeners, progress)
		}
		if done != nil {
			state.callbacks = append(state.callbacks, done)
		}
		state.mu.Unlock()
		return true, nil
	}
	state.running = true
	state.cancelled = false
	state.version = version
	state.modsPath = ms.modsPath
	state.worker = nil
	state.callbacks = nil
	state.listeners = nil
	if progress != nil {
		state.listeners = append(state.listeners, progress)
	}
	if done != nil {
		state.callbacks = append(state.callbacks, done)
	}
	state.mu.Unlock()

	go ms.runRuntimeModelCacheJob(version, ms.modsPath)
	return true, nil
}

func (ms *MinesportApp) runRuntimeModelCacheJob(version, modsPath string) {
	fingerprint, err := bridgecapture.BeginSession(version, modsPath)
	if err != nil {
		ms.finishRuntimeCacheJob(err)
		return
	}
	ms.appendLogAsync("Runtime model cache fingerprint: " + fingerprint)
	ms.emitRuntimeCacheProgress(2, "Fingerprint ready · preparing isolated Minecraft worker")

	worker, err := bridgecompat.StartRuntimeWorker(
		version,
		modsPath,
		runtimeRegistryPort,
		func(update bridgecompat.Progress) {
			detail := strings.TrimSpace(update.Detail)
			if detail == "" {
				detail = update.Stage
			} else {
				detail = update.Stage + " · " + detail
			}
			ms.emitRuntimeCacheProgress(update.Percent, detail)
		},
	)
	if err != nil {
		bridgecapture.CancelSession(version)
		ms.finishRuntimeCacheJob(err)
		return
	}

	state := runtimeCacheJob(ms)
	state.mu.Lock()
	if state.cancelled {
		state.mu.Unlock()
		bridgecapture.CancelSession(version)
		_ = worker.Stop()
		ms.finishRuntimeCacheJob(fmt.Errorf("runtime model-cache generation cancelled"))
		return
	}
	state.worker = worker
	state.mu.Unlock()

	ms.appendLogAsync("Runtime registry worker started for Minecraft " + version)
	ms.emitRuntimeCacheProgress(75, "Minecraft registry worker loading all registered blocks, states and baked models")

	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	timeout := time.NewTimer(10 * time.Minute)
	defer timeout.Stop()

	for {
		if path, ok := bridgecapture.SnapshotPathForMods(version, modsPath); ok {
			// The Bridge publishes a reusable instance-wide registry only after its
			// complete all-registry packet has been received. Selection bounds are
			// never involved in this capture.
			ms.emitRuntimeCacheProgress(98, "Full registry received · shutting down disposable worker")
			_ = worker.Stop()
			bridgecapture.CancelSession(version)
			ms.appendLogAsync("Full runtime model registry ready: " + path)
			ms.emitRuntimeCacheProgress(100, "Full runtime model registry ready")
			ms.finishRuntimeCacheJob(nil)
			return
		}

		select {
		case <-worker.Done():
			waitErr := worker.Wait()
			bridgecapture.CancelSession(version)
			if _, ok := bridgecapture.SnapshotPathForMods(version, modsPath); ok && waitErr == nil {
				ms.emitRuntimeCacheProgress(100, "Full runtime model registry ready")
				ms.finishRuntimeCacheJob(nil)
				return
			}
			if waitErr == nil {
				waitErr = fmt.Errorf("runtime worker exited before Minesport received a complete registry")
			}
			ms.finishRuntimeCacheJob(waitErr)
			return
		case <-ticker.C:
			state.mu.Lock()
			cancelled := state.cancelled
			state.mu.Unlock()
			if cancelled {
				bridgecapture.CancelSession(version)
				_ = worker.Stop()
				ms.finishRuntimeCacheJob(fmt.Errorf("runtime model-cache generation cancelled"))
				return
			}
		case <-timeout.C:
			bridgecapture.CancelSession(version)
			_ = worker.Stop()
			ms.finishRuntimeCacheJob(fmt.Errorf("runtime model-cache generation timed out after 10 minutes"))
			return
		}
	}
}

func (ms *MinesportApp) cancelRuntimeModelCacheGeneration() {
	state := runtimeCacheJob(ms)
	state.mu.Lock()
	if !state.running {
		state.mu.Unlock()
		return
	}
	state.cancelled = true
	version := state.version
	worker := state.worker
	state.mu.Unlock()

	if version != "" {
		bridgecapture.CancelSession(version)
	}
	if worker != nil {
		go func() { _ = worker.Stop() }()
	}
}

// ensureRuntimeModelCacheForExport returns true when Export has been deferred
// while the instance-wide registry worker runs. Failure is non-fatal: Minesport
// reports it clearly and continues with the static resolver chain.
func (ms *MinesportApp) ensureRuntimeModelCacheForExport(continueExport func()) bool {
	if normalizedLoader(ms.loaderType) != "fabric" {
		return false
	}
	version, err := ms.validateBridgeRuntimeCapture()
	if err != nil {
		ms.appendLog("Runtime model cache skipped: " + err.Error())
		return false
	}
	if _, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
		return false
	}

	ms.exportBtn.Disable()
	ms.beginWorkbenchTaskV3("RUNTIME CACHE", "Waiting for full Minecraft registry…", true)
	ms.showRuntimeCacheWindow(version)
	started, startErr := ms.generateRuntimeModelCache(
		false,
		func(percent int, message string) {
			ms.updateRuntimeCacheWindow(percent, message)
			ms.updateWorkbenchTaskV3(percent, message, "Minecraft "+version+" · full current-instance registry")
		},
		func(cacheErr error) {
			ms.closeRuntimeCacheWindow()
			ms.refreshWorkbenchSettingsActivity()
			if cacheErr != nil {
				ms.appendLog("[WARN] Runtime model cache unavailable; continuing with static resolver fallback: " + cacheErr.Error())
				ms.finishWorkbenchTaskV3(false, "Runtime cache unavailable", "Continuing export with static asset resolution · "+cacheErr.Error())
			} else {
				ms.finishWorkbenchTaskV3(true, "Full runtime registry ready", "Minecraft "+version+" · current mod set")
			}
			ms.exportBtn.Enable()
			if continueExport != nil {
				continueExport()
			}
		},
	)
	if startErr != nil {
		ms.closeRuntimeCacheWindow()
		ms.appendLog("[WARN] Runtime model cache could not start; continuing with static resolver fallback: " + startErr.Error())
		ms.finishWorkbenchTaskV3(false, "Runtime cache unavailable", "Continuing export with static asset resolution")
		ms.exportBtn.Enable()
		return false
	}
	if !started {
		ms.closeRuntimeCacheWindow()
	}
	return started
}

// buildBridgeRuntimeAdvancedCard is intentionally compact. Full-registry cache
// generation starts automatically after selecting a Fabric world; this UI is
// only for manual rebuilds and diagnostics under Settings → Advanced.
func (ms *MinesportApp) buildBridgeRuntimeAdvancedCard() fyne.CanvasObject {
	status := widget.NewLabel(ms.bridgeRuntimeCaptureStatus())
	status.Truncation = fyne.TextTruncateEllipsis

	rebuild := widget.NewButtonWithIcon("Generate / rebuild full runtime registry", theme.MediaPlayIcon(), nil)
	cancel := widget.NewButton("Cancel", func() {
		ms.cancelRuntimeModelCacheGeneration()
		status.SetText("Runtime model cache: cancellation requested…")
	})
	cancel.Disable()

	rebuild.OnTapped = func() {
		version, err := ms.validateBridgeRuntimeCapture()
		if err != nil {
			status.SetText("Runtime model cache: " + err.Error())
			return
		}
		rebuild.Disable()
		cancel.Enable()
		ms.beginWorkbenchTaskV3("RUNTIME CACHE", "Manual full-registry rebuild…", true)
		_, err = ms.generateRuntimeModelCache(
			true,
			func(percent int, message string) {
				status.SetText(fmt.Sprintf("Runtime model cache: %d%% · %s", percent, message))
				ms.updateWorkbenchTaskV3(percent, message, "Minecraft "+version+" · full registry manual rebuild")
			},
			func(jobErr error) {
				rebuild.Enable()
				cancel.Disable()
				status.SetText(ms.bridgeRuntimeCaptureStatus())
				if jobErr != nil {
					ms.finishWorkbenchTaskV3(false, "Runtime registry rebuild failed", jobErr.Error())
					return
				}
				ms.finishWorkbenchTaskV3(true, "Full runtime registry ready", "Minecraft "+version+" · current mod set")
			},
		)
		if err != nil {
			rebuild.Enable()
			cancel.Disable()
			status.SetText("Runtime model cache: " + err.Error())
			ms.finishWorkbenchTaskV3(false, "Runtime registry rebuild failed", err.Error())
		}
	}

	if ms.runtimeCacheIsPreparingForCurrentWorld() {
		cancel.Enable()
		rebuild.Disable()
	} else if _, err := ms.validateBridgeRuntimeCapture(); err != nil {
		rebuild.Disable()
	}

	return widget.NewCard(
		"RUNTIME MODEL CACHE",
		"Automatic after selecting a Fabric world · caches the full registered instance registry",
		container.NewVBox(status, container.NewHBox(rebuild, cancel)),
	)
}

// Compatibility name for older callers while the Settings layout is migrated.
func (ms *MinesportApp) buildBridgeRuntimeCaptureCard() fyne.CanvasObject {
	return ms.buildBridgeRuntimeAdvancedCard()
}

func (ms *MinesportApp) bridgeRuntimeCaptureStatus() string {
	version := bridgecompat.NormalizeVersion(ms.mcVersion)
	if strings.TrimSpace(ms.worldPath) == "" {
		return "NOT CACHED · Select a Minecraft world first."
	}
	if normalizedLoader(ms.loaderType) != "fabric" {
		return "UNAVAILABLE · Runtime model cache currently applies to Fabric instances."
	}
	if version == "" {
		return "UNAVAILABLE · Minesport could not determine this world's Minecraft version."
	}
	if strings.TrimSpace(ms.modsPath) == "" {
		return "UNAVAILABLE · The selected Fabric instance has no detected mods folder."
	}
	if info, err := os.Stat(ms.modsPath); err != nil || !info.IsDir() {
		return "UNAVAILABLE · The selected instance mods folder cannot be read."
	}
	if ms.runtimeCacheIsPreparingForCurrentWorld() {
		return "PREPARING · Caching every registered block state and baked model for Minecraft " + version + "."
	}
	if path, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
		return "READY · Full Minecraft " + version + " runtime registry matches this mod set · " + path
	}
	if _, ok := bridgecapture.SnapshotPath(version); ok {
		return "STALE · Mod set changed; Minesport will rebuild the full runtime registry automatically."
	}
	return "NOT CACHED · Minesport will prepare the full registered block/state/model registry automatically."
}

func (ms *MinesportApp) validateBridgeRuntimeCapture() (string, error) {
	if strings.TrimSpace(ms.worldPath) == "" {
		return "", fmt.Errorf("select a Minecraft world first")
	}
	if normalizedLoader(ms.loaderType) != "fabric" {
		return "", fmt.Errorf("runtime model cache currently supports Fabric instances only")
	}
	version := bridgecompat.NormalizeVersion(ms.mcVersion)
	if version == "" {
		return "", fmt.Errorf("could not determine the selected world's Minecraft version")
	}
	if strings.TrimSpace(ms.modsPath) == "" {
		return "", fmt.Errorf("could not determine the selected Fabric instance mods folder")
	}
	if info, err := os.Stat(ms.modsPath); err != nil || !info.IsDir() {
		return "", fmt.Errorf("selected Fabric mods folder is unavailable: %s", ms.modsPath)
	}
	return version, nil
}
