package ui

import (
	"fmt"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/blendertranslator"
)

type blenderInspectorState struct {
	card       *widget.Card
	mode       *widget.Select
	status     *widget.Label
	statusIcon *widget.Icon
}

var blenderInspectorStates = struct {
	sync.Mutex
	values map[fyne.Window]*blenderInspectorState
}{values: make(map[fyne.Window]*blenderInspectorState)}

func buildBlenderInspectorCard(parent fyne.Window, settings Settings) fyne.CanvasObject {
	mode := widget.NewSelect([]string{"Animate export", "Animate static"}, nil)
	mode.SetSelected("Animate export")

	info := newInfoPopoverButton(
		"Blender animation export",
		"Animate export prepares every dynamic animation/state descriptor available from Minesport metadata (for example movable parts plus animated textures).\n\nAnimate static keeps interactable state changes at the exported world state and prepares only continuously animated/static-world visuals such as animated textures.\n\nFor OBJ, use Minesport's Open with → Blender action or Blender's File → Import → Minesport OBJ. Blender's standard Wavefront OBJ importer bypasses the translator.\n\nThe final Continuous Animation toggle is created inside Blender's Properties panel, not in Minesport.",
	)

	status := widget.NewLabel(blendertranslator.StatusText())
	status.Truncation = fyne.TextTruncateEllipsis
	statusIcon := widget.NewIcon(theme.ErrorIcon())
	if blendertranslator.CurrentStatus().Complete() {
		statusIcon.SetResource(theme.ConfirmIcon())
	}

	content := container.NewVBox(
		container.NewBorder(nil, nil, widget.NewLabel("Animation"), info, mode),
		container.NewBorder(nil, nil, statusIcon, nil, status),
	)
	card := widget.NewCard("WORLD INSPECTOR · BLENDER", "", content)
	if !settings.BlenderExportEnabled {
		card.Hide()
	}

	blenderInspectorStates.Lock()
	blenderInspectorStates.values[parent] = &blenderInspectorState{
		card: card, mode: mode, status: status, statusIcon: statusIcon,
	}
	blenderInspectorStates.Unlock()

	return card
}

func blenderAnimationMode(parent fyne.Window) string {
	blenderInspectorStates.Lock()
	state := blenderInspectorStates.values[parent]
	blenderInspectorStates.Unlock()
	if state == nil || state.mode == nil {
		return "animate_export"
	}
	if strings.EqualFold(state.mode.Selected, "Animate static") {
		return "animate_static"
	}
	return "animate_export"
}

func refreshBlenderInspectorVisibility(parent fyne.Window, enabled bool) {
	blenderInspectorStates.Lock()
	state := blenderInspectorStates.values[parent]
	blenderInspectorStates.Unlock()
	if state == nil || state.card == nil {
		return
	}
	if enabled {
		refreshBlenderInspectorStatus(parent)
		state.card.Show()
	} else {
		state.card.Hide()
	}
}

func refreshBlenderInspectorStatus(parent fyne.Window) {
	blenderInspectorStates.Lock()
	state := blenderInspectorStates.values[parent]
	blenderInspectorStates.Unlock()
	if state == nil || state.status == nil || state.statusIcon == nil {
		return
	}
	state.status.SetText(blendertranslator.StatusText())
	if blendertranslator.CurrentStatus().Complete() {
		state.statusIcon.SetResource(theme.ConfirmIcon())
	} else {
		state.statusIcon.SetResource(theme.ErrorIcon())
	}
}

func maybePromptBlenderTranslator(ms *MinesportApp) {
	if ms == nil || ms.window == nil || ms.settings.BlenderTranslatorPrompted {
		return
	}
	targets := blendertranslator.DiscoverTargets()
	if len(targets) == 0 {
		// Do not consume the first-launch prompt until Blender has actually been
		// detected. If the user installs/launches Blender later, Minesport can
		// still offer the translator on a future start.
		return
	}

	message := widget.NewLabel(fmt.Sprintf(
		"Minesport found %d Blender 4.3+ profile(s).\n\nInstall the Minesport Dynamic Translator? It only translates Minesport metadata during import; it does not run a per-frame animation runtime.",
		len(targets),
	))
	message.Wrapping = fyne.TextWrapWord

	d := dialog.NewCustomConfirm(
		"Blender integration",
		"Install",
		"Not now",
		container.NewPadded(message),
		func(install bool) {
			ms.settings.BlenderTranslatorPrompted = true
			if !install {
				_ = ms.settings.Save()
				return
			}

			paths, err := blendertranslator.Install()
			if err != nil {
				_ = ms.settings.Save()
				dialog.ShowError(err, ms.window)
				return
			}

			ms.settings.BlenderExportEnabled = true
			_ = ms.settings.Save()
			refreshBlenderInspectorVisibility(ms.window, true)

			dialog.ShowInformation(
				"Blender translator installed",
				fmt.Sprintf("Installed into %d Blender profile(s). If Blender does not enable it automatically, enable 'Minesport Dynamic Translator' once under Blender Preferences → Add-ons.", len(paths)),
				ms.window,
			)
		},
		ms.window,
	)
	d.Resize(fyne.NewSize(520, 230))
	d.Show()
}
