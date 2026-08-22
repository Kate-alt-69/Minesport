package ui

import (
	"fmt"
	"image/color"
	"os"
	"path/filepath"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

const (
	workbenchPaneExport   = "export"
	workbenchPaneSettings = "settings"
)

// buildWorkbenchUIV2 is the production Minesport shell. It keeps the VS Code
// idea of an activity rail/context sidebar/workbench/status area, but uses a
// restrained Minecraft visual language and Minesport-specific workflows.
func (ms *MinesportApp) buildWorkbenchUIV2() fyne.CanvasObject {
	ms.fyneApp.Settings().SetTheme(newMinesportTheme())

	wb := &workbenchState{
		panes:    map[string]fyne.CanvasObject{},
		activity: map[string]*widget.Button{},
	}
	workbenchStates.Store(ms, wb)

	ms.logContent = widget.NewLabel("")
	ms.logContent.TextStyle = fyne.TextStyle{Monospace: true}
	ms.logContent.Wrapping = fyne.TextWrapWord
	ms.logScroll = container.NewVScroll(ms.logContent)
	ms.statusLabel = widget.NewLabel("Starting…")
	ms.stateIcon = widget.NewIcon(theme.ViewRefreshIcon())
	ms.cursorLabel = widget.NewLabel("")
	ms.progressBar = widget.NewProgressBar()
	ms.progressBar.SetValue(0)

	worldPane, exportPane := ms.buildWorkbenchWorldExportPanes()
	blenderPane := container.NewVScroll(container.NewPadded(buildBlenderInspectorCard(ms.window, ms.settings)))
	tasksPane := ms.buildWorkbenchTasksPane()
	settingsPane := ms.buildWorkbenchSettingsPane()

	panes := []struct {
		key    string
		object fyne.CanvasObject
	}{
		{workbenchPaneWorld, worldPane},
		{workbenchPaneExport, exportPane},
		{workbenchPaneBlender, blenderPane},
		{workbenchPaneTasks, tasksPane},
		{workbenchPaneSettings, settingsPane},
	}
	stackObjects := make([]fyne.CanvasObject, 0, len(panes))
	for _, item := range panes {
		holder := container.NewMax(item.object)
		wb.panes[item.key] = holder
		stackObjects = append(stackObjects, holder)
		if item.key != workbenchPaneWorld {
			holder.Hide()
		}
	}
	wb.sidebarStack = container.NewStack(stackObjects...)

	wb.sidebarTitle = widget.NewLabelWithStyle("WORLD", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	more := widget.NewButtonWithIcon("", theme.MoreHorizontalIcon(), ms.openWorkbenchAdvancedSettings)
	sidebarHeader := container.NewBorder(nil, nil, wb.sidebarTitle, more, nil)
	sidebarSurface := canvas.NewRectangle(color.NRGBA{R: 22, G: 24, B: 24, A: 246})
	sidebar := container.NewStack(
		sidebarSurface,
		container.NewBorder(container.NewPadded(sidebarHeader), nil, nil, nil, wb.sidebarStack),
	)

	viewport := ms.buildWorkbenchViewport()
	split := container.NewHSplit(sidebar, viewport)
	split.SetOffset(0.255)

	activityRail := ms.buildWorkbenchActivityRailV2()
	topBar := ms.buildWorkbenchTopBar()
	taskShelf := ms.buildWorkbenchTaskShelf()
	statusBar := ms.buildWorkbenchStatusBar()
	body := container.NewBorder(topBar, container.NewVBox(taskShelf, statusBar), activityRail, nil, split)

	ms.setWorkbenchPaneV2(workbenchPaneWorld)
	ms.updateWorkbenchWorldContext()
	ms.updateMetaHUD(ms.selectionSizeText())
	return container.NewStack(newMinesportWorkbenchBackground(), body)
}

func workbenchSection(title string) fyne.CanvasObject {
	label := widget.NewLabelWithStyle(title, fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	return container.NewVBox(widget.NewSeparator(), label)
}

func workbenchHelp(text string) *widget.Label {
	label := widget.NewLabel(text)
	label.Wrapping = fyne.TextWrapWord
	label.TextStyle = fyne.TextStyle{Italic: true}
	return label
}

func (ms *MinesportApp) buildWorkbenchWorldExportPanes() (fyne.CanvasObject, fyne.CanvasObject) {
	// WORLD activity ----------------------------------------------------------
	ms.worldNameLabel = widget.NewLabel("No world selected")
	ms.worldNameLabel.TextStyle = fyne.TextStyle{Bold: true}
	ms.worldMetaLabel = widget.NewLabel("Choose a Minecraft save to begin")
	ms.worldMetaLabel.Wrapping = fyne.TextWrapWord
	selectWorld := widget.NewButtonWithIcon("Open Minecraft world…", theme.FolderOpenIcon(), ms.onSelectWorld)
	selectWorld.Importance = widget.HighImportance

	ms.selectionModeSelect = widget.NewSelect([]string{"Box selection", "Bubble selection"}, func(choice string) {
		if ms.boxCoordGroup == nil || ms.bubbleCoordGroup == nil {
			return
		}
		bubble := choice == "Bubble selection"
		if bubble {
			ms.boxCoordGroup.Hide()
			ms.bubbleCoordGroup.Show()
		} else {
			ms.bubbleCoordGroup.Hide()
			ms.boxCoordGroup.Show()
		}
		if ms.worldMap != nil {
			ms.worldMap.SetBubbleMode(bubble)
		}
		ms.updateMetaHUD(ms.selectionSizeText())
	})
	ms.minXRange = NewAxisRange("X", -256, 256, func() { ms.updateMetaHUD(ms.selectionSizeText()) })
	ms.minYRange = NewAxisRange("Y", -64, 320, func() { ms.updateMetaHUD(ms.selectionSizeText()) })
	ms.minZRange = NewAxisRange("Z", -256, 256, func() { ms.updateMetaHUD(ms.selectionSizeText()) })
	ms.minXEntry, ms.maxXEntry = ms.minXRange.Front, ms.minXRange.Back
	ms.minYEntry, ms.maxYEntry = ms.minYRange.Front, ms.minYRange.Back
	ms.minZEntry, ms.maxZEntry = ms.minZRange.Front, ms.minZRange.Back
	ms.boxCoordGroup = container.NewVBox(ms.minXRange.Container, ms.minYRange.Container, ms.minZRange.Container)

	ms.centerX = NewStepperEntry("0")
	ms.centerY = NewStepperEntry("64")
	ms.centerZ = NewStepperEntry("0")
	ms.radiusX = NewStepperEntry("32")
	ms.radiusY = NewStepperEntry("32")
	ms.radiusZ = NewStepperEntry("32")
	for _, entry := range []*StepperEntry{ms.centerX, ms.centerY, ms.centerZ, ms.radiusX, ms.radiusY, ms.radiusZ} {
		entry.SetBounds(-30000000, 30000000)
		entry.OnChanged = func(string) {
			ms.syncBubblePreview()
			ms.updateMetaHUD(ms.selectionSizeText())
		}
	}
	ms.bubbleCoordGroup = container.NewVBox(
		compactNumberRow("Center X", ms.centerX),
		compactNumberRow("Center Y", ms.centerY),
		compactNumberRow("Center Z", ms.centerZ),
		widget.NewSeparator(),
		compactNumberRow("Radius X", ms.radiusX),
		compactNumberRow("Radius Y", ms.radiusY),
		compactNumberRow("Radius Z", ms.radiusZ),
	)
	ms.bubbleCoordGroup.Hide()
	ms.selectionModeSelect.SetSelected("Box selection")
	ms.autoDetectBtn = widget.NewButtonWithIcon("Use world bounds", theme.SearchIcon(), ms.onAutoDetect)
	ms.autoDetectBtn.Disable()

	worldPane := container.NewVScroll(container.NewPadded(container.NewVBox(
		widget.NewLabelWithStyle("WORLD", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		ms.worldNameLabel,
		ms.worldMetaLabel,
		selectWorld,
		workbenchSection("SELECTION"),
		ms.selectionModeSelect,
		ms.boxCoordGroup,
		ms.bubbleCoordGroup,
		ms.autoDetectBtn,
		workbenchHelp("Selection stays editable while maps and exports run in the background."),
	)))

	// EXPORT activity ---------------------------------------------------------
	ms.exportNameEntry = widget.NewEntry()
	ms.exportNameEntry.SetText("Minesport_Export")
	ms.exportNameEntry.SetPlaceHolder("Export name")
	ms.formatSelect = widget.NewSelect([]string{"glTF 2.0", "OBJ + MTL"}, nil)
	ms.formatSelect.SetSelected("glTF 2.0")
	ms.modeSelect = widget.NewSelect([]string{"Grouped", "Individual blocks", "Merged"}, nil)
	ms.modeSelect.SetSelected("Grouped")
	ms.optimizeCheck = widget.NewCheck("Optimize mesh output", nil)
	ms.optimizeHint = workbenchHelp("Object mode controls logical organization. FLATTER, when enabled, optimizes eligible geometry before Grouped / Individual / Merged organization.")
	ms.applyOptimizeGate()

	ms.outputLabel = widget.NewLabel("~/Minesport_Exports")
	ms.outputLabel.Truncation = fyne.TextTruncateEllipsis
	changeOutput := widget.NewButtonWithIcon("Choose output folder…", theme.FolderIcon(), ms.onSelectOutput)
	optimizerSettings := widget.NewButton("Optimizer settings", func() { ms.setWorkbenchPaneV2(workbenchPaneSettings) })
	ms.exportBtn = widget.NewButtonWithIcon("Export world", theme.DownloadIcon(), nil)
	ms.exportBtn.Importance = widget.HighImportance
	ms.exportBtn.Disable()

	exportPane := container.NewVScroll(container.NewPadded(container.NewVBox(
		widget.NewLabelWithStyle("EXPORT", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		compactEntryRow("Name", ms.exportNameEntry),
		compactSelectRow("Format", ms.formatSelect),
		compactSelectRow("Objects", ms.modeSelect),
		workbenchSection("OPTIMIZATION"),
		ms.optimizeCheck,
		ms.optimizeHint,
		optimizerSettings,
		workbenchSection("OUTPUT"),
		ms.outputLabel,
		changeOutput,
		widget.NewSeparator(),
		container.NewPadded(ms.exportBtn),
		workbenchHelp("Export automatically prepares a matching Fabric runtime-model cache when needed. Progress appears in the bottom task shelf."),
	)))

	return worldPane, exportPane
}

func (ms *MinesportApp) buildWorkbenchSettingsPane() fyne.CanvasObject {
	status := widget.NewLabel("Changes save immediately.")
	status.Wrapping = fyne.TextWrapWord

	newToggle := func(label, description string, initial bool, change func(*Settings, bool)) fyne.CanvasObject {
		check := widget.NewCheck(label, nil)
		check.SetChecked(initial)
		check.OnChanged = func(value bool) {
			next := ms.settings
			change(&next, value)
			ms.applySettings(next)
			status.SetText(label + " updated · saved")
			if ms.stateIcon != nil {
				ms.stateIcon.SetResource(theme.ConfirmIcon())
			}
			if ms.statusLabel != nil {
				ms.statusLabel.SetText("Settings saved")
			}
		}
		return container.NewVBox(check, workbenchHelp(description))
	}

	face := newToggle(
		"Face culling",
		"Remove a face only when neighboring geometry and its resolved texture prove that the face is fully hidden.",
		ms.settings.OptimizeOutputEnabled,
		func(s *Settings, value bool) { s.OptimizeOutputEnabled = value },
	)
	flatter := newToggle(
		"FLATTER geometry · Experimental",
		"Virtualize reconstructable Minecraft blocks into greedy 3D surfaces while preserving logical voxels for Blender editing.",
		ms.settings.FlatterOptimizationEnabled,
		func(s *Settings, value bool) { s.FlatterOptimizationEnabled = value },
	)
	flatterSize := widget.NewSelect([]string{"8 × 8 × 8", "16 × 16 × 16", "32 × 32 × 32", "64 × 64 × 64"}, func(value string) {
		next := ms.settings
		switch value {
		case "8 × 8 × 8":
			next.FlatterCellSize = 8
		case "32 × 32 × 32":
			next.FlatterCellSize = 32
		case "64 × 64 × 64":
			next.FlatterCellSize = 64
		default:
			next.FlatterCellSize = 16
		}
		ms.applySettings(next)
		status.SetText(fmt.Sprintf("FLATTER object size updated to %d³ · saved", next.FlatterCellSize))
	})
	cell := normalizeFlatterCellSize(ms.settings.FlatterCellSize)
	flatterSize.SetSelected(fmt.Sprintf("%d × %d × %d", cell, cell, cell))
	flatterSizeRow := container.NewVBox(
		container.NewBorder(nil, nil, widget.NewLabel("FLATTER object size"), nil, flatterSize),
		workbenchHelp("Controls the spatial FLATTER cell. Smaller cells rebuild more locally; larger cells create fewer objects and broader greedy surfaces."),
	)
	hidden := newToggle(
		"Hidden block culling · Experimental",
		"Remove complete blocks only when all six sides are proven fully occluded. Transparent/cutout neighbors never count as opaque cover.",
		ms.settings.HiddenBlockCullingEnabled,
		func(s *Settings, value bool) { s.HiddenBlockCullingEnabled = value },
	)
	blender := newToggle(
		"Blender integration",
		"Write Minesport translation metadata and enable Blender-focused export controls.",
		ms.settings.BlenderExportEnabled,
		func(s *Settings, value bool) {
			s.BlenderExportEnabled = value
			refreshBlenderInspectorVisibility(ms.window, value)
		},
	)
	debug := newToggle(
		"Debug engine console",
		"Keep detailed engine/IPC diagnostics available for compatibility debugging.",
		ms.settings.DebugMode,
		func(s *Settings, value bool) { s.DebugMode = value },
	)
	selectByModel := newToggle(
		"Select by model · Experimental",
		"Use resolved model information for compatible selection workflows.",
		ms.settings.SelectByModel,
		func(s *Settings, value bool) { s.SelectByModel = value },
	)

	assets := ms.buildWorkbenchAssetSettingsSection(status)
	advancedTools := ms.buildAdvancedPipelineTools()
	dataPackSummary := widget.NewLabel(fmt.Sprintf("Data packs configured: %d", len(ms.settings.DataPackPaths)))
	legacyAdvanced := widget.NewButtonWithIcon("Translator / data packs / additional settings…", theme.SettingsIcon(), ms.openWorkbenchAdvancedSettings)

	return container.NewVScroll(container.NewPadded(container.NewVBox(
		widget.NewLabelWithStyle("SETTINGS", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		status,
		workbenchSection("GEOMETRY"),
		face,
		flatter,
		flatterSizeRow,
		hidden,
		workbenchSection("PIPELINE"),
		blender,
		selectByModel,
		assets,
		workbenchSection("ADVANCED"),
		debug,
		advancedTools,
		dataPackSummary,
		legacyAdvanced,
	)))
}

func (ms *MinesportApp) openWorkbenchAdvancedSettings() {
	ShowSettingsDialog(ms.window, ms.settings, ms.applySettings, nil)
}

func (ms *MinesportApp) buildWorkbenchActivityRailV2() fyne.CanvasObject {
	wb := ms.workbenchState()
	railSurface := canvas.NewRectangle(color.NRGBA{R: 14, G: 16, B: 16, A: 252})
	makeButton := func(key string, icon fyne.Resource) *widget.Button {
		button := widget.NewButtonWithIcon("", icon, func() { ms.setWorkbenchPaneV2(key) })
		button.Alignment = widget.ButtonAlignCenter
		wb.activity[key] = button
		return button
	}
	world := makeButton(workbenchPaneWorld, theme.FolderOpenIcon())
	export := makeButton(workbenchPaneExport, theme.DownloadIcon())
	blender := makeButton(workbenchPaneBlender, theme.MediaVideoIcon())
	tasks := makeButton(workbenchPaneTasks, theme.HistoryIcon())
	settings := makeButton(workbenchPaneSettings, theme.SettingsIcon())
	wrap := func(object fyne.CanvasObject) fyne.CanvasObject {
		return container.NewGridWrap(fyne.NewSize(48, 48), object)
	}
	logo := widget.NewLabelWithStyle("M", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	return container.NewStack(
		railSurface,
		container.NewVBox(
			container.NewPadded(logo),
			wrap(world), wrap(export), wrap(blender), wrap(tasks),
			layout.NewSpacer(), wrap(settings),
		),
	)
}

func (ms *MinesportApp) setWorkbenchPaneV2(key string) {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	for paneKey, pane := range wb.panes {
		if paneKey == key {
			pane.Show()
		} else {
			pane.Hide()
		}
	}
	for buttonKey, button := range wb.activity {
		if buttonKey == key {
			button.Importance = widget.HighImportance
		} else {
			button.Importance = widget.MediumImportance
		}
		button.Refresh()
	}
	switch key {
	case workbenchPaneExport:
		wb.sidebarTitle.SetText("EXPORT")
	case workbenchPaneBlender:
		wb.sidebarTitle.SetText("BLENDER")
	case workbenchPaneTasks:
		wb.sidebarTitle.SetText("TASKS")
	case workbenchPaneSettings:
		wb.sidebarTitle.SetText("SETTINGS")
	default:
		wb.sidebarTitle.SetText("WORLD")
	}
}

func defaultWorkbenchOutputPath() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return "Minesport_Exports"
	}
	return filepath.Join(home, "Minesport_Exports")
}
