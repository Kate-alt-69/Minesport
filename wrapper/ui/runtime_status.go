package ui

import (
	"path/filepath"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/bridgecompat"
)

func (ms *MinesportApp) setEngineAvailable(available bool) {
	ms.engineStateMu.Lock()
	ms.engineAvailable = available
	if available {
		ms.engineFailureShown = false
	}
	ms.engineStateMu.Unlock()

	if !available {
		ms.exportBtn.Disable()
		ms.autoDetectBtn.Disable()
		ms.viewToggle3D.Disable()
		return
	}
	if ms.worldPath != "" {
		ms.exportBtn.Enable()
		ms.autoDetectBtn.Enable()
		ms.viewToggle3D.Enable()
		go ms.generateHeightmap(ms.worldPath)
	}
}

func (ms *MinesportApp) isEngineAvailable() bool {
	ms.engineStateMu.Lock()
	defer ms.engineStateMu.Unlock()
	return ms.engineAvailable
}

func (ms *MinesportApp) handleCoreEngineFailure(reason string) {
	ms.setEngineAvailable(false)
	ms.appendLog("CORE ENGINE UNAVAILABLE: " + reason)
	ms.statusLabel.SetText("Core engine unavailable — see log")
	ms.stateIcon.SetResource(theme.ErrorIcon())

	ms.engineStateMu.Lock()
	if ms.engineFailureShown {
		ms.engineStateMu.Unlock()
		return
	}
	ms.engineFailureShown = true
	ms.engineStateMu.Unlock()

	message := reason
	if ms.diagnosticsLogPath != "" {
		message += "\n\nFull diagnostics were written to:\n" + ms.diagnosticsLogPath
	}
	label := widget.NewLabel(message)
	label.Wrapping = fyne.TextWrapWord
	buttons := container.NewHBox()
	if ms.diagnosticsLogPath != "" {
		buttons.Add(widget.NewButtonWithIcon("Open log folder", theme.FolderOpenIcon(), func() {
			if err := openPath(filepath.Dir(ms.diagnosticsLogPath)); err != nil {
				ms.appendLog("Could not open diagnostics folder: " + err.Error())
			}
		}))
	}
	content := container.NewVBox(label, buttons)
	d := dialog.NewCustom("Minesport core engine did not start", "Close", content, ms.window)
	d.Resize(fyne.NewSize(580, 260))
	d.Show()
}

func (ms *MinesportApp) showOperationFailure(title, reason string) {
	ms.appendLog(strings.ToUpper(title) + ": " + reason)
	message := reason
	if ms.diagnosticsLogPath != "" {
		message += "\n\nFull log: " + ms.diagnosticsLogPath
	}
	label := widget.NewLabel(message)
	label.Wrapping = fyne.TextWrapWord
	buttons := container.NewHBox()
	if ms.diagnosticsLogPath != "" {
		buttons.Add(widget.NewButtonWithIcon("Open log folder", theme.FolderOpenIcon(), func() {
			if err := openPath(filepath.Dir(ms.diagnosticsLogPath)); err != nil {
				ms.appendLog("Could not open diagnostics folder: " + err.Error())
			}
		}))
	}
	d := dialog.NewCustom(title, "Close", container.NewVBox(label, buttons), ms.window)
	d.Resize(fyne.NewSize(580, 260))
	d.Show()
}

func normalizedLoader(loader string) string {
	value := strings.ToLower(strings.TrimSpace(loader))
	switch {
	case strings.Contains(value, "neoforge"):
		return "neoforge"
	case strings.Contains(value, "forge"):
		return "forge"
	case strings.Contains(value, "fabric"):
		return "fabric"
	case strings.Contains(value, "quilt"):
		return "quilt"
	case strings.Contains(value, "vanilla"):
		return "vanilla"
	default:
		return value
	}
}

func normalizedMinecraftVersion(version string) string {
	return bridgecompat.NormalizeVersion(version)
}

func (ms *MinesportApp) showLoaderWarning() {
	loader := normalizedLoader(ms.loaderType)
	if loader != "forge" && loader != "neoforge" {
		return
	}
	ms.appendLog("Forge/NeoForge export selected: static block models and textures are supported; runtime-generated rendering may be limited")
	if ms.loaderWarningShown {
		return
	}
	ms.loaderWarningShown = true
	dialog.ShowInformation(
		"Forge export compatibility",
		"Some features may not be available for Forge exports. Ordinary block models and textures are supported, but runtime-generated geometry, custom renderers, animated block entities, and shader-driven content may use fallback geometry or be omitted.",
		ms.window,
	)
}
