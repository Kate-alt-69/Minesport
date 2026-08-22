package ui

import (
	"fmt"
	"os"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/bridgecapture"
	"github.com/kastrick/minesport/bridgecompat"
	"github.com/kastrick/minesport/launcher"
)

func (ms *MinesportApp) buildBridgeRuntimeCaptureCard() fyne.CanvasObject {
	status := widget.NewLabel(ms.bridgeRuntimeCaptureStatus())
	status.Wrapping = fyne.TextWrapWord

	capture := widget.NewButtonWithIcon("Capture runtime block data", theme.MediaPlayIcon(), nil)
	cancel := widget.NewButton("Cancel capture", func() {
		bridgecapture.CleanupStaged()
		status.SetText("Runtime capture cancelled. Temporary Minesport bridge removed.")
		capture.Enable()
	})
	cancel.Disable()

	capture.OnTapped = func() {
		version, err := ms.validateBridgeRuntimeCapture()
		if err != nil {
			status.SetText(err.Error())
			return
		}
		capture.Disable()
		cancel.Enable()
		status.SetText("Preparing the Minesport Bridge for Minecraft " + version + "…")

		go func() {
			bridgeJar, err := bridgecompat.Ensure(version, func(update bridgecompat.Progress) {
				detail := strings.TrimSpace(update.Detail)
				if detail == "" {
					detail = update.Stage
				} else {
					detail = update.Stage + " · " + detail
				}
				status.SetText(fmt.Sprintf("Preparing Bridge · %d%% · %s", update.Percent, detail))
			})
			if err != nil {
				status.SetText("Bridge preparation failed: " + err.Error())
				capture.Enable()
				cancel.Disable()
				return
			}

			staged, err := bridgecapture.StageBridge(bridgeJar, ms.modsPath, version)
			if err != nil {
				status.SetText("Could not stage runtime capture: " + err.Error())
				capture.Enable()
				cancel.Disable()
				return
			}
			ms.appendLog("Runtime Bridge staged temporarily: " + staged)

			foundLauncher, instance, found := launcher.FindInstanceForWorld(ms.worldPath)
			if !found {
				bridgecapture.CleanupStaged()
				status.SetText("Could not map this world back to a detected Minecraft instance. Temporary bridge removed.")
				capture.Enable()
				cancel.Disable()
				return
			}
			if err := launcher.LaunchInstance(foundLauncher, instance); err != nil {
				bridgecapture.CleanupStaged()
				status.SetText("Could not launch the selected instance for capture: " + err.Error() + ". Temporary bridge removed.")
				capture.Enable()
				cancel.Disable()
				return
			}

			status.SetText(
				"Runtime capture launched through " + foundLauncher.Name +
				". Minecraft will load the instance, send its runtime block registry to Minesport, then close automatically.",
			)
			ms.appendLog("Runtime Bridge capture launched: " + instance.Summary())

			deadline := time.Now().Add(10 * time.Minute)
			for time.Now().Before(deadline) {
				if _, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
					status.SetText("READY · Runtime registry captured for the current Minecraft version and mod set. Modded light emission will be used on export.")
					capture.Enable()
					cancel.Disable()
					return
				}
				time.Sleep(500 * time.Millisecond)
			}

			bridgecapture.CleanupStaged()
			status.SetText("Runtime capture timed out after 10 minutes. Temporary Minesport bridge cleanup was requested; try capture again and check the debug log if Minecraft did not start.")
			capture.Enable()
			cancel.Disable()
		}()
	}

	if _, err := ms.validateBridgeRuntimeCapture(); err != nil {
		capture.Disable()
	}

	hint := workbenchHelp(
		"Fabric only for now. Minesport temporarily stages its version-matched Bridge in the selected instance, launches that existing instance once, captures state-dependent runtime data such as modded light emission, then removes the temporary Bridge. Cached data is rejected automatically after the mod JAR set changes.",
	)
	return widget.NewCard(
		"RUNTIME BRIDGE",
		"Capture data that static mod JAR parsing cannot reliably know",
		container.NewVBox(status, hint, container.NewHBox(capture, cancel)),
	)
}

func (ms *MinesportApp) bridgeRuntimeCaptureStatus() string {
	version := bridgecompat.NormalizeVersion(ms.mcVersion)
	if strings.TrimSpace(ms.worldPath) == "" {
		return "NOT CAPTURED · Select a Minecraft world first."
	}
	if normalizedLoader(ms.loaderType) != "fabric" {
		return "UNAVAILABLE · Runtime Bridge capture currently supports Fabric instances."
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
		return "READY · Runtime registry matches Minecraft " + version + " and the current mod set."
	}
	if _, ok := bridgecapture.SnapshotPath(version); ok {
		return "STALE · A runtime registry exists for Minecraft " + version + ", but the mod set changed. Recapture before using runtime metadata."
	}
	return "NOT CAPTURED · Static asset resolution still works. Capture once to teach Minesport state-dependent runtime metadata for this mod set."
}

func (ms *MinesportApp) validateBridgeRuntimeCapture() (string, error) {
	if strings.TrimSpace(ms.worldPath) == "" {
		return "", fmt.Errorf("select a Minecraft world before runtime capture")
	}
	if normalizedLoader(ms.loaderType) != "fabric" {
		return "", fmt.Errorf("runtime Bridge capture currently supports Fabric instances only")
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
