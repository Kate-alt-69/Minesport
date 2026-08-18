package ui

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"
)

// ShowSettingsDialog opens the global Settings dialog. Unlike the sidebar's
// per-export controls (format, mode, region bounds — which only apply to
// the export about to run), everything here applies globally and persists
// to disk immediately on Save.
func ShowSettingsDialog(parent fyne.Window, current Settings, onSave func(Settings)) {
	working := current // local copy, only committed on Save

	// ── Basic tab ──────────────────────────────────────────────────────────
	basicInfo := widget.NewLabel(
		"Per-export options (format, export mode, region bounds) live in the\n" +
			"sidebar next to the world map — they change what THIS export does.\n\n" +
			"Everything on the Advanced tab applies to every export, in every\n" +
			"session, until you change it again here.",
	)
	basicInfo.Wrapping = fyne.TextWrapWord
	basicTab := container.NewPadded(basicInfo)

	// ── Advanced tab ───────────────────────────────────────────────────────

	debugCheck := widget.NewCheck("Debug mode — show engine log in a separate console window", func(v bool) {
		working.DebugMode = v
	})
	debugCheck.SetChecked(working.DebugMode)

	generalCard := widget.NewCard("General", "", debugCheck)

	optimizeCheck := widget.NewCheck("Enable \"Optimize Output\" (experimental)", func(v bool) {
		working.OptimizeOutputEnabled = v
	})
	optimizeCheck.SetChecked(working.OptimizeOutputEnabled)
	optimizeNote := widget.NewLabel(
		"Culls faces the engine can prove are fully hidden between two solid\n" +
			"blocks, and welds duplicate vertices — can meaningfully shrink\n" +
			"Individual/Grouped exports. Turning this on makes the checkbox\n" +
			"available in the sidebar's Export section; it stays off by default\n" +
			"either way. If a face is ever missing that shouldn't be, turn it\n" +
			"back off — the unoptimized path never removes geometry.",
	)
	optimizeNote.Wrapping = fyne.TextWrapWord
	optimizeNote.TextStyle = fyne.TextStyle{Italic: true}
	optimizeCard := widget.NewCard("Optimize Output", "", container.NewVBox(optimizeCheck, optimizeNote))

	selectByModelCheck := widget.NewCheck("Enable \"select by model\" (experimental)", func(v bool) {
		working.SelectByModel = v
	})
	selectByModelCheck.SetChecked(working.SelectByModel)
	selectByModelNote := widget.NewLabel(
		"Click a block to get a best-effort guess of whether it was player-placed\n" +
			"or came from world generation / a mod. This is a heuristic based on\n" +
			"known world-gen and structure block patterns — it is not, and can't\n" +
			"be, 100% accurate for every block in every world.",
	)
	selectByModelNote.Wrapping = fyne.TextWrapWord
	selectByModelNote.TextStyle = fyne.TextStyle{Italic: true}
	selectByModelCard := widget.NewCard("Select by Model", "", container.NewVBox(selectByModelCheck, selectByModelNote))

	resourcePackList := newPathListEditor(parent, "Override block visuals — highest priority first", working.ResourcePackPaths, func(paths []string) {
		working.ResourcePackPaths = paths
	})
	resourcePackCard := widget.NewCard("Resource Packs", "", resourcePackList)

	dataPackList := newPathListEditor(parent, "Block tags only — leave empty to auto-use the world's own datapacks/ folder", working.DataPackPaths, func(paths []string) {
		working.DataPackPaths = paths
	})
	dataPackCard := widget.NewCard("Data Packs", "", dataPackList)

	advancedTab := container.NewVBox(
		generalCard,
		optimizeCard,
		selectByModelCard,
		resourcePackCard,
		dataPackCard,
	)

	tabs := container.NewAppTabs(
		container.NewTabItem("Basic", basicTab),
		container.NewTabItem("Advanced", container.NewVScroll(container.NewPadded(advancedTab))),
	)
	tabs.SetTabLocation(container.TabLocationTop)

	content := container.NewGridWrap(fyne.NewSize(600, 480), tabs)

	d := dialog.NewCustomConfirm("Settings", "Save", "Cancel", content, func(save bool) {
		if save && onSave != nil {
			onSave(working)
		}
	}, parent)
	d.Resize(fyne.NewSize(640, 560))
	d.Show()
}

// ── Path list editor ─────────────────────────────────────────────────────────
// Small reusable widget: a label, a list of paths, and Add/Remove controls.
// Used for both the resource pack list and the data pack list.

func newPathListEditor(parent fyne.Window, title string, initial []string, onChange func([]string)) fyne.CanvasObject {
	paths := append([]string{}, initial...) // local copy

	label := widget.NewLabel(title)
	label.TextStyle = fyne.TextStyle{Italic: true}

	list := widget.NewList(
		func() int { return len(paths) },
		func() fyne.CanvasObject { return widget.NewLabel("") },
		func(i widget.ListItemID, obj fyne.CanvasObject) {
			obj.(*widget.Label).SetText(paths[i])
			obj.(*widget.Label).Truncation = fyne.TextTruncateEllipsis
		},
	)
	list.Resize(fyne.NewSize(0, 90))

	var selected = -1
	list.OnSelected = func(id widget.ListItemID) { selected = id }
	list.OnUnselected = func(widget.ListItemID) { selected = -1 }

	addFolderBtn := widget.NewButton("Add folder...", func() {
		go func() {
			folder := nativeOpenFolder("Select " + title)
			if folder == "" {
				return
			}
			paths = append(paths, folder)
			list.Refresh()
			onChange(append([]string{}, paths...))
		}()
	})

	manualEntry := widget.NewEntry()
	manualEntry.SetPlaceHolder("...or paste a folder / .zip path and press Enter")
	manualEntry.OnSubmitted = func(text string) {
		if text == "" {
			return
		}
		paths = append(paths, text)
		manualEntry.SetText("")
		list.Refresh()
		onChange(append([]string{}, paths...))
	}

	removeBtn := widget.NewButton("Remove selected", func() {
		if selected < 0 || selected >= len(paths) {
			return
		}
		paths = append(paths[:selected], paths[selected+1:]...)
		selected = -1
		list.UnselectAll()
		list.Refresh()
		onChange(append([]string{}, paths...))
	})

	listBox := container.NewBorder(nil, nil, nil, nil, list)
	listBox = container.NewGridWrap(fyne.NewSize(480, 90), listBox)

	return container.NewVBox(
		label,
		listBox,
		container.NewHBox(addFolderBtn, removeBtn),
		manualEntry,
	)
}
