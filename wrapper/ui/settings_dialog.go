package ui

import (
	"fmt"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/blendertranslator"
)

func infoButton(parent fyne.Window, title, body string) *widget.Button {
	return widget.NewButton("ⓘ", func() {
		dialog.ShowInformation(title, body, parent)
	})
}

func basicSettingRow(parent fyne.Window, label string, value bool, onChange func(bool), info string) fyne.CanvasObject {
	check := widget.NewCheck(label, onChange)
	check.SetChecked(value)
	return container.NewBorder(nil, nil, nil, infoButton(parent, label, info), check)
}

func ShowSettingsDialog(parent fyne.Window, current Settings, onSave func(Settings)) {
	working := current

	face := basicSettingRow(
		parent,
		"Face culling",
		working.OptimizeOutputEnabled,
		func(v bool) { working.OptimizeOutputEnabled = v },
		"Removes a face only when neighboring geometry fully covers that face.",
	)
	hidden := basicSettingRow(
		parent,
		"Hidden block culling (Experimental)",
		working.HiddenBlockCullingEnabled,
		func(v bool) { working.HiddenBlockCullingEnabled = v },
		"Removes a whole block only when all six sides are proven fully covered. Uncertain/custom geometry is kept.",
	)
	basic := container.NewVBox(
		widget.NewCard("VISIBILITY", "", container.NewVBox(face, hidden)),
		widget.NewLabel("Simple by default. Use ⓘ for details."),
	)

	debug := widget.NewCheck("Debug engine console", func(v bool) { working.DebugMode = v })
	debug.SetChecked(working.DebugMode)
	model := widget.NewCheck("Select by model (experimental)", func(v bool) { working.SelectByModel = v })
	model.SetChecked(working.SelectByModel)
	general := widget.NewCard("GENERAL", "", container.NewVBox(debug, model))

	blenderEnabled := widget.NewCheck("Enable Blender export", func(v bool) {
		working.BlenderExportEnabled = v
	})
	blenderEnabled.SetChecked(working.BlenderExportEnabled)

	blenderInfo := widget.NewLabel("Blender 4.3+ translation metadata and one-shot translator integration. No per-frame Minesport runtime is installed.")
	blenderInfo.Wrapping = fyne.TextWrapWord

	translatorStatus := blendertranslator.CurrentStatus()
	installStatus := widget.NewLabel(blendertranslator.StatusText())
	installStatus.Wrapping = fyne.TextWrapWord
	installIcon := widget.NewIcon(theme.ErrorIcon())
	if translatorStatus.Complete() {
		installIcon.SetResource(theme.ConfirmIcon())
	}
	refreshInstallStatus := func() {
		status := blendertranslator.CurrentStatus()
		installStatus.SetText(blendertranslator.StatusText())
		if status.Complete() {
			installIcon.SetResource(theme.ConfirmIcon())
		} else {
			installIcon.SetResource(theme.ErrorIcon())
		}
	}

	installButton := widget.NewButton("Install / repair Blender translator", func() {
		installed, err := blendertranslator.Install()
		if err != nil {
			dialog.ShowError(err, parent)
			refreshInstallStatus()
			return
		}
		working.BlenderTranslatorPrompted = true
		if len(installed) == 0 {
			dialog.ShowInformation(
				"Blender translator",
				"No Blender 4.3+ user profile was detected yet. Launch Blender once, then use this button again.",
				parent,
			)
		} else {
			refreshInstallStatus()
			dialog.ShowInformation(
				"Blender translator installed",
				fmt.Sprintf("Installed the Minesport translator into %d Blender profile(s). Enable 'Minesport Dynamic Translator' once in Blender's Add-ons if Blender has not enabled it already.", len(installed)),
				parent,
			)
		}
	})

	blenderCard := widget.NewCard(
		"MODEL → BLENDER EXPORT",
		"",
		container.NewVBox(
			container.NewBorder(nil, nil, nil, infoButton(
				parent,
				"Blender Export",
				"When enabled, the World Inspector shows Animate export / Animate static. Minesport writes translation metadata; Blender's translator converts it once into native collections, actions, bones and material nodes. Continuous animation controls stay inside Blender.",
			), blenderEnabled),
			blenderInfo,
			container.NewBorder(nil, nil, installIcon, nil, installStatus),
			widget.NewLabel("This checks whether the translator files are installed. Blender controls whether the add-on is enabled."),
			installButton,
		),
	)

	rp := newPathListEditor(
		parent,
		"Resource packs — highest priority first",
		working.ResourcePackPaths,
		func(p []string) { working.ResourcePackPaths = p },
	)
	dp := newPathListEditor(
		parent,
		"Data packs — block tags",
		working.DataPackPaths,
		func(p []string) { working.DataPackPaths = p },
	)

	advanced := container.NewVBox(
		general,
		blenderCard,
		widget.NewCard("RESOURCE PACKS", "", rp),
		widget.NewCard("DATA PACKS", "", dp),
	)

	tabs := container.NewAppTabs(
		container.NewTabItem("Basic", container.NewVScroll(container.NewPadded(basic))),
		container.NewTabItem("Advanced", container.NewVScroll(container.NewPadded(advanced))),
	)

	d := dialog.NewCustomConfirm(
		"Settings",
		"Save",
		"Cancel",
		container.NewPadded(tabs),
		func(save bool) {
			if !save {
				return
			}
			if onSave != nil {
				onSave(working)
			}
			refreshBlenderInspectorVisibility(parent, working.BlenderExportEnabled)
		},
		parent,
	)
	d.Resize(fyne.NewSize(680, 590))
	d.Show()
}

func newPathListEditor(parent fyne.Window, title string, initial []string, onChange func([]string)) fyne.CanvasObject {
	paths := append([]string{}, initial...)
	label := widget.NewLabel(title)
	label.TextStyle = fyne.TextStyle{Italic: true}

	list := widget.NewList(
		func() int { return len(paths) },
		func() fyne.CanvasObject { return widget.NewLabel("") },
		func(i widget.ListItemID, o fyne.CanvasObject) {
			o.(*widget.Label).SetText(paths[i])
			o.(*widget.Label).Truncation = fyne.TextTruncateEllipsis
		},
	)

	selected := -1
	list.OnSelected = func(i widget.ListItemID) { selected = i }
	list.OnUnselected = func(widget.ListItemID) { selected = -1 }

	add := widget.NewButton("Add folder…", func() {
		go func() {
			p := nativeOpenFolder("Select " + title)
			if p != "" {
				paths = append(paths, p)
				list.Refresh()
				onChange(append([]string{}, paths...))
			}
		}()
	})

	entry := widget.NewEntry()
	entry.SetPlaceHolder("Paste folder or .zip path and press Enter")
	entry.OnSubmitted = func(t string) {
		if t == "" {
			return
		}
		paths = append(paths, t)
		entry.SetText("")
		list.Refresh()
		onChange(append([]string{}, paths...))
	}

	remove := widget.NewButton("Remove", func() {
		if selected < 0 || selected >= len(paths) {
			return
		}
		paths = append(paths[:selected], paths[selected+1:]...)
		selected = -1
		list.UnselectAll()
		list.Refresh()
		onChange(append([]string{}, paths...))
	})

	return container.NewVBox(
		label,
		container.NewGridWrap(fyne.NewSize(500, 100), list),
		container.NewHBox(add, remove),
		entry,
	)
}
