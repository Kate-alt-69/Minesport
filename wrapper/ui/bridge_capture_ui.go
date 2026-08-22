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

func (ms *MinesportApp) buildBridgeRuntimeCaptureCard() fyne.CanvasObject {
	status := widget.NewLabel(ms.bridgeRuntimeCaptureStatus())
	status.Wrapping = fyne.TextWrapWord

	capture := widget.NewButtonWithIcon("Generate runtime model cache", theme.MediaPlayIcon(), nil)
	var workerMu sync.Mutex
	var activeWorker *bridgecompat.RuntimeWorker
	activeVersion := ""
	cancelled := false

	cancel := widget.NewButton("Cancel", nil)
	cancel.Disable()
	cancel.OnTapped = func() {
		workerMu.Lock()
		cancelled = true
		worker := activeWorker
		version := activeVersion
		activeWorker = nil
		activeVersion = ""
		workerMu.Unlock()

		if version != "" {
			bridgecapture.CancelSession(version)
		}
		if worker != nil {
			go func() { _ = worker.Stop() }()
		}
		status.SetText("Runtime model-cache generation cancelled. Disposable worker cleanup requested.")
		capture.Enable()
		cancel.Disable()
	}

	capture.OnTapped = func() {
		version, err := ms.validateBridgeRuntimeCapture()
		if err != nil {
			status.SetText(err.Error())
			return
		}

		workerMu.Lock()
		cancelled = false
		activeVersion = version
		workerMu.Unlock()
		capture.Disable()
		cancel.Enable()
		status.SetText("Preparing isolated Minecraft " + version + " registry worker…")

		go func() {
			fingerprint, err := bridgecapture.BeginSession(version, ms.modsPath)
			if err != nil {
				status.SetText("Could not begin runtime-cache session: " + err.Error())
				capture.Enable()
				cancel.Disable()
				return
			}
			ms.appendLog("Runtime model cache fingerprint: " + fingerprint)

			worker, err := bridgecompat.StartRuntimeWorker(
				version,
				ms.modsPath,
				runtimeRegistryPort,
				func(update bridgecompat.Progress) {
					detail := strings.TrimSpace(update.Detail)
					if detail == "" {
						detail = update.Stage
					} else {
						detail = update.Stage + " · " + detail
					}
					status.SetText(fmt.Sprintf("Runtime worker · %d%% · %s", update.Percent, detail))
				},
			)
			if err != nil {
				bridgecapture.CancelSession(version)
				status.SetText("Runtime worker preparation failed: " + err.Error())
				capture.Enable()
				cancel.Disable()
				return
			}

			workerMu.Lock()
			if cancelled {
				workerMu.Unlock()
				bridgecapture.CancelSession(version)
				_ = worker.Stop()
				return
			}
			activeWorker = worker
			workerMu.Unlock()

			status.SetText(
				"Isolated Minecraft worker is loading registries and baked models. " +
				"No world is opened and the normal launcher is not used.",
			)
			ms.appendLog("Runtime registry worker started for Minecraft " + version)

			ticker := time.NewTicker(250 * time.Millisecond)
			defer ticker.Stop()
			timeout := time.NewTimer(10 * time.Minute)
			defer timeout.Stop()

			for {
				if _, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
					// The registry receiver only publishes after the Bridge sent DONE, so
					// geometry/light data is complete. Minecraft normally exits ~500ms later.
					<-worker.Done()
					workerMu.Lock()
					if activeWorker == worker {
						activeWorker = nil
						activeVersion = ""
					}
					workerMu.Unlock()
					status.SetText(
						"READY · Full runtime block registry cached for Minecraft " + version +
						" and this mod set. Baked geometry/state/light data will be reused; textures stay referenced from resource packs/mod JARs/vanilla assets.",
					)
					capture.Enable()
					cancel.Disable()
					return
				}

				select {
				case <-worker.Done():
					err := worker.Wait()
					bridgecapture.CancelSession(version)
					workerMu.Lock()
					if activeWorker == worker {
						activeWorker = nil
						activeVersion = ""
					}
					workerMu.Unlock()
					if err != nil {
						status.SetText("Runtime worker exited before a complete registry was cached: " + err.Error())
					} else {
						status.SetText("Runtime worker exited before Minesport received a complete registry. Check the debug log and try again.")
					}
					capture.Enable()
					cancel.Disable()
					return
				case <-ticker.C:
					workerMu.Lock()
					wasCancelled := cancelled
					workerMu.Unlock()
					if wasCancelled {
						bridgecapture.CancelSession(version)
						_ = worker.Stop()
						return
					}
				case <-timeout.C:
					bridgecapture.CancelSession(version)
					_ = worker.Stop()
					workerMu.Lock()
					if activeWorker == worker {
						activeWorker = nil
						activeVersion = ""
					}
					workerMu.Unlock()
					status.SetText("Runtime model-cache generation timed out after 10 minutes. Disposable worker was stopped and cleaned up.")
					capture.Enable()
					cancel.Disable()
					return
				}
			}
		}()
	}

	if _, err := ms.validateBridgeRuntimeCapture(); err != nil {
		capture.Disable()
	}

	hint := workbenchHelp(
		"Fabric runtime worker: Minesport starts an isolated exact-version Minecraft/Fabric client through Loom, copies the selected mod JARs and config into a disposable run directory, hides the game window, reads the full registered block/state model data, then deletes the worker directory. It never opens the selected world or writes to the real instance. Texture image bytes are not duplicated in this cache.",
	)
	return widget.NewCard(
		"RUNTIME MODEL CACHE",
		"Use Minecraft's own registry + baked models for custom blocks",
		container.NewVBox(status, hint, container.NewHBox(capture, cancel)),
	)
}

func (ms *MinesportApp) bridgeRuntimeCaptureStatus() string {
	version := bridgecompat.NormalizeVersion(ms.mcVersion)
	if strings.TrimSpace(ms.worldPath) == "" {
		return "NOT CACHED · Select a Minecraft world first."
	}
	if normalizedLoader(ms.loaderType) != "fabric" {
		return "UNAVAILABLE · Runtime model-cache generation currently supports Fabric instances."
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
	if _, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
		return "READY · Runtime models match Minecraft " + version + " and the current mod set."
	}
	if _, ok := bridgecapture.SnapshotPath(version); ok {
		return "STALE · A runtime model registry exists for Minecraft " + version + ", but the mod set changed. It will not be used until regenerated."
	}
	return "NOT CACHED · Static asset resolution still works. Generate once to cache Minecraft's actual registered block states and baked models for this mod set."
}

func (ms *MinesportApp) validateBridgeRuntimeCapture() (string, error) {
	if strings.TrimSpace(ms.worldPath) == "" {
		return "", fmt.Errorf("select a Minecraft world before generating the runtime model cache")
	}
	if normalizedLoader(ms.loaderType) != "fabric" {
		return "", fmt.Errorf("runtime model-cache generation currently supports Fabric instances only")
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
