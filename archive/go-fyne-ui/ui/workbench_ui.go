package ui

import (
	"fmt"
	"image"
	"image/color"
	"path/filepath"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

const (
	workbenchPaneWorld   = "world"
	workbenchPaneBlender = "blender"
	workbenchPaneTasks   = "tasks"
)

var workbenchStates sync.Map

type workbenchState struct {
	sidebarTitle *widget.Label
	sidebarStack *fyne.Container
	panes        map[string]fyne.CanvasObject
	activity     map[string]*widget.Button
	worldContext *widget.Label
	taskShelf    *fyne.Container
	taskTitle    *widget.Label
	taskDetail   *widget.Label
	taskProgress *widget.ProgressBar
	taskInfinite *widget.ProgressBarInfinite
	exportActive bool
	taskKind     string
}

func (ms *MinesportApp) workbenchState() *workbenchState {
	value, ok := workbenchStates.Load(ms)
	if !ok {
		return nil
	}
	wb, _ := value.(*workbenchState)
	return wb
}

func (ms *MinesportApp) buildWorkbenchUI() fyne.CanvasObject {
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

	worldPane := ms.buildInspector()
	blenderPane := container.NewVScroll(container.NewPadded(buildBlenderInspectorCard(ms.window, ms.settings)))
	tasksPane := ms.buildWorkbenchTasksPane()
	worldHolder := container.NewMax(worldPane)
	blenderHolder := container.NewMax(blenderPane)
	tasksHolder := container.NewMax(tasksPane)
	wb.panes[workbenchPaneWorld] = worldHolder
	wb.panes[workbenchPaneBlender] = blenderHolder
	wb.panes[workbenchPaneTasks] = tasksHolder
	blenderHolder.Hide()
	tasksHolder.Hide()
	wb.sidebarStack = container.NewStack(worldHolder, blenderHolder, tasksHolder)

	wb.sidebarTitle = widget.NewLabelWithStyle("WORLD / EXPORT", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	sidebarHeader := container.NewBorder(
		nil,
		nil,
		wb.sidebarTitle,
		widget.NewButtonWithIcon("", theme.MoreHorizontalIcon(), ms.onOpenSettings),
		nil,
	)
	sidebarSurface := canvas.NewRectangle(color.NRGBA{R: 24, G: 26, B: 27, A: 244})
	sidebar := container.NewStack(
		sidebarSurface,
		container.NewBorder(container.NewPadded(sidebarHeader), nil, nil, nil, wb.sidebarStack),
	)

	viewport := ms.buildWorkbenchViewport()
	split := container.NewHSplit(sidebar, viewport)
	split.SetOffset(0.255)

	activityRail := ms.buildWorkbenchActivityRail()
	topBar := ms.buildWorkbenchTopBar()
	taskShelf := ms.buildWorkbenchTaskShelf()
	statusBar := ms.buildWorkbenchStatusBar()
	body := container.NewBorder(topBar, container.NewVBox(taskShelf, statusBar), activityRail, nil, split)

	background := newMinesportWorkbenchBackground()
	ms.setWorkbenchPane(workbenchPaneWorld)
	ms.updateWorkbenchWorldContext()
	ms.updateMetaHUD(ms.selectionSizeText())
	return container.NewStack(background, body)
}

func (ms *MinesportApp) buildWorkbenchViewport() fyne.CanvasObject {
	ms.viewToggle2D = widget.NewButtonWithIcon("2D", theme.GridIcon(), ms.show2DPreview)
	ms.viewToggle2D.Importance = widget.HighImportance
	ms.viewToggle3D = widget.NewButtonWithIcon("3D", theme.ViewFullScreenIcon(), ms.onExplore3D)
	ms.viewToggle3D.Disable()
	ms.fitBtn = widget.NewButtonWithIcon("Fit", theme.ZoomFitIcon(), func() {
		if ms.embeddedViewer != nil && ms.embeddedViewer.Visible() {
			ms.embeddedViewer.Fit()
			return
		}
		if ms.worldMap != nil {
			ms.worldMap.FitToWindow()
		}
	})
	ms.viewHint = widget.NewLabel("LMB select · MMB pan · scroll zoom · F6 fit")

	ms.worldMap = NewWorldMapV2()
	ms.worldMap.OnSelectionChanged = func(minX, minZ, maxX, maxZ int) {
		ms.minXRange.Front.SetText(fmt.Sprintf("%d", minX))
		ms.minXRange.Back.SetText(fmt.Sprintf("%d", maxX))
		ms.minZRange.Front.SetText(fmt.Sprintf("%d", minZ))
		ms.minZRange.Back.SetText(fmt.Sprintf("%d", maxZ))
		ms.updateMetaHUD(ms.selectionSizeText())
	}
	ms.worldMap.OnCursorMoved = func(x, z int) {
		ms.cursorLabel.SetText(fmt.Sprintf("X %d  ·  Z %d", x, z))
	}
	ms.worldMap.OnCenterPicked = func(x, z int) {
		ms.centerX.SetText(fmt.Sprintf("%d", x))
		ms.centerZ.SetText(fmt.Sprintf("%d", z))
	}

	preparingText := widget.NewLabelWithStyle("Loading chunks…", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	preparingHint := widget.NewLabel("Minesport is still responsive — you can change settings or inspect tasks.")
	preparingHint.Alignment = fyne.TextAlignCenter
	preparingProgress := widget.NewProgressBarInfinite()
	preparingCard := widget.NewCard("", "", container.NewVBox(preparingText, preparingProgress, preparingHint))
	ms.mapPreparing = container.NewCenter(container.NewPadded(preparingCard))
	ms.mapPreparing.Hide()

	mapArea := container.NewStack(
		ms.worldMap,
		container.NewVBox(layout.NewSpacer(), container.NewHBox(layout.NewSpacer(), ms.buildMetaHUD())),
		ms.mapPreparing,
	)
	ms.previewHost = container.NewMax(mapArea)

	viewportSurface := canvas.NewRectangle(color.NRGBA{R: 13, G: 15, B: 16, A: 235})
	viewControls := container.NewHBox(ms.viewHint, layout.NewSpacer(), ms.viewToggle2D, ms.viewToggle3D, ms.fitBtn)
	controlSurface := canvas.NewRectangle(color.NRGBA{R: 20, G: 23, B: 24, A: 232})
	controlBar := container.NewStack(controlSurface, container.NewPadded(viewControls))
	return container.NewStack(viewportSurface, container.NewBorder(controlBar, nil, nil, nil, ms.previewHost))
}

func (ms *MinesportApp) buildWorkbenchActivityRail() fyne.CanvasObject {
	wb := ms.workbenchState()
	railSurface := canvas.NewRectangle(color.NRGBA{R: 16, G: 18, B: 18, A: 250})
	makeButton := func(key string, icon fyne.Resource) *widget.Button {
		button := widget.NewButtonWithIcon("", icon, func() { ms.setWorkbenchPane(key) })
		button.Alignment = widget.ButtonAlignCenter
		wb.activity[key] = button
		return button
	}
	world := makeButton(workbenchPaneWorld, theme.FolderOpenIcon())
	blender := makeButton(workbenchPaneBlender, theme.MediaVideoIcon())
	tasks := makeButton(workbenchPaneTasks, theme.HistoryIcon())
	settings := widget.NewButtonWithIcon("", theme.SettingsIcon(), ms.onOpenSettings)
	wrap := func(obj fyne.CanvasObject) fyne.CanvasObject {
		return container.NewGridWrap(fyne.NewSize(48, 48), obj)
	}
	return container.NewStack(
		railSurface,
		container.NewVBox(
			container.NewPadded(widget.NewLabelWithStyle("M", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})),
			wrap(world), wrap(blender), wrap(tasks), layout.NewSpacer(), wrap(settings),
		),
	)
}

func (ms *MinesportApp) buildWorkbenchTopBar() fyne.CanvasObject {
	wb := ms.workbenchState()
	surface := canvas.NewRectangle(color.NRGBA{R: 30, G: 31, B: 29, A: 248})
	grassLine := canvas.NewRectangle(color.NRGBA{R: 78, G: 138, B: 58, A: 255})
	grassLine.SetMinSize(fyne.NewSize(1, 3))
	title := widget.NewLabelWithStyle("MINESPORT", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	subtitle := widget.NewLabel("Minecraft world → production workbench")
	wb.worldContext = widget.NewLabel("No world loaded")
	wb.worldContext.Truncation = fyne.TextTruncateEllipsis
	bar := container.NewBorder(nil, nil, container.NewHBox(title, widget.NewSeparator(), subtitle), wb.worldContext, nil)
	return container.NewVBox(grassLine, container.NewStack(surface, container.NewPadded(bar)))
}

func (ms *MinesportApp) buildWorkbenchTaskShelf() fyne.CanvasObject {
	wb := ms.workbenchState()
	wb.taskTitle = widget.NewLabelWithStyle("READY", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	wb.taskDetail = widget.NewLabel("No background work is running.")
	wb.taskDetail.Truncation = fyne.TextTruncateEllipsis
	wb.taskProgress = ms.progressBar
	wb.taskInfinite = widget.NewProgressBarInfinite()
	wb.taskInfinite.Hide()
	dismiss := widget.NewButtonWithIcon("", theme.CancelIcon(), func() {
		if wb.taskShelf != nil && !wb.exportActive {
			wb.taskShelf.Hide()
		}
	})
	header := container.NewBorder(nil, nil, wb.taskTitle, dismiss, wb.taskDetail)
	progressStack := container.NewStack(wb.taskProgress, wb.taskInfinite)
	surface := canvas.NewRectangle(color.NRGBA{R: 27, G: 32, B: 27, A: 248})
	wb.taskShelf = container.NewVBox(container.NewStack(surface, container.NewPadded(container.NewVBox(header, progressStack))))
	wb.taskShelf.Hide()
	return wb.taskShelf
}

func (ms *MinesportApp) buildWorkbenchStatusBar() fyne.CanvasObject {
	surface := canvas.NewRectangle(color.NRGBA{R: 34, G: 78, B: 41, A: 255})
	engine := container.NewHBox(ms.stateIcon, ms.statusLabel)
	right := container.NewHBox(ms.cursorLabel)
	return container.NewStack(surface, container.NewPadded(container.NewBorder(nil, nil, engine, right, nil)))
}

func (ms *MinesportApp) buildWorkbenchTasksPane() fyne.CanvasObject {
	title := widget.NewLabelWithStyle("TASKS & DIAGNOSTICS", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	description := widget.NewLabel("Long engine, download, map and export work stays in the background. The bottom task shelf remains visible while Minesport is busy.")
	description.Wrapping = fyne.TextWrapWord
	openDebug := widget.NewButtonWithIcon("Open debug console", theme.InfoIcon(), ms.openDebugConsole)
	openLogs := widget.NewButtonWithIcon("Open diagnostics folder", theme.FolderOpenIcon(), func() {
		if ms.diagnosticsLogPath != "" {
			_ = openPath(filepath.Dir(ms.diagnosticsLogPath))
		}
	})
	if ms.diagnosticsLogPath == "" {
		openLogs.Disable()
	}
	hint := widget.NewLabel("Tip: the main viewport never needs to disappear during export anymore.")
	hint.Wrapping = fyne.TextWrapWord
	return container.NewVScroll(container.NewPadded(container.NewVBox(title, widget.NewSeparator(), description, container.NewHBox(openDebug, openLogs), widget.NewSeparator(), hint)))
}

func (ms *MinesportApp) setWorkbenchPane(key string) {
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
	case workbenchPaneBlender:
		wb.sidebarTitle.SetText("BLENDER")
	case workbenchPaneTasks:
		wb.sidebarTitle.SetText("TASKS")
	default:
		wb.sidebarTitle.SetText("WORLD / EXPORT")
	}
}

func (ms *MinesportApp) updateWorkbenchWorldContext() {
	wb := ms.workbenchState()
	if wb == nil || wb.worldContext == nil {
		return
	}
	if strings.TrimSpace(ms.worldName) == "" {
		wb.worldContext.SetText("World workbench")
		return
	}
	meta := "Minecraft world"
	if ms.worldMetaLabel != nil && strings.TrimSpace(ms.worldMetaLabel.Text) != "" {
		meta = strings.TrimSpace(ms.worldMetaLabel.Text)
	}
	wb.worldContext.SetText(ms.worldName + "  ·  " + meta)
}

func (ms *MinesportApp) beginWorkbenchTask(kind, message string, determinate bool) {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	wb.taskKind = strings.ToUpper(strings.TrimSpace(kind))
	if wb.taskKind == "" {
		wb.taskKind = "TASK"
	}
	wb.taskTitle.SetText(wb.taskKind + " · " + message)
	wb.taskDetail.SetText("Working in the background — Minesport is still responsive.")
	wb.taskShelf.Show()
	if determinate {
		wb.taskInfinite.Stop()
		wb.taskInfinite.Hide()
		wb.taskProgress.Show()
		wb.taskProgress.SetValue(0)
	} else {
		wb.taskProgress.Hide()
		wb.taskInfinite.Show()
		wb.taskInfinite.Start()
	}
	if ms.statusLabel != nil {
		ms.statusLabel.SetText(message)
	}
	if ms.stateIcon != nil {
		ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	}
}

func (ms *MinesportApp) updateWorkbenchTask(pct int, message, detail string) {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	if wb.taskShelf != nil && !wb.taskShelf.Visible() {
		wb.taskShelf.Show()
	}
	wb.taskInfinite.Stop()
	wb.taskInfinite.Hide()
	wb.taskProgress.Show()
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	wb.taskProgress.SetValue(float64(pct) / 100.0)
	if strings.TrimSpace(message) != "" {
		wb.taskTitle.SetText(wb.taskKind + " · " + message)
	}
	if strings.TrimSpace(detail) != "" {
		wb.taskDetail.SetText(detail)
	}
}

func (ms *MinesportApp) finishWorkbenchTask(ok bool, message, detail string) {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	wb.taskInfinite.Stop()
	wb.taskInfinite.Hide()
	wb.taskProgress.Show()
	if ok {
		wb.taskProgress.SetValue(1)
		wb.taskTitle.SetText("DONE · " + message)
		if ms.stateIcon != nil {
			ms.stateIcon.SetResource(theme.ConfirmIcon())
		}
	} else {
		wb.taskProgress.SetValue(0)
		wb.taskTitle.SetText("FAILED · " + message)
		if ms.stateIcon != nil {
			ms.stateIcon.SetResource(theme.ErrorIcon())
		}
	}
	if strings.TrimSpace(detail) == "" {
		detail = "Ready for the next action."
	}
	wb.taskDetail.SetText(detail)
	if ms.statusLabel != nil {
		ms.statusLabel.SetText(message)
	}
	wb.taskShelf.Show()
}

func (ms *MinesportApp) setWorkbenchExportActive(active bool) {
	if wb := ms.workbenchState(); wb != nil {
		wb.exportActive = active
	}
}

func (ms *MinesportApp) workbenchExportActive() bool {
	wb := ms.workbenchState()
	return wb != nil && wb.exportActive
}

func newMinesportWorkbenchBackground() fyne.CanvasObject {
	raster := canvas.NewRaster(func(w, h int) image.Image {
		if w < 1 {
			w = 1
		}
		if h < 1 {
			h = 1
		}
		img := image.NewNRGBA(image.Rect(0, 0, w, h))
		palette := [...]color.NRGBA{
			{R: 76, G: 58, B: 43, A: 255},
			{R: 84, G: 63, B: 46, A: 255},
			{R: 68, G: 53, B: 41, A: 255},
			{R: 92, G: 69, B: 49, A: 255},
		}
		const pixel = 10
		for y := 0; y < h; y++ {
			gy := y / pixel
			for x := 0; x < w; x++ {
				gx := x / pixel
				hash := (gx*31 + gy*17 + (gx*gy)%11) & 3
				img.SetNRGBA(x, y, palette[hash])
			}
		}
		return img
	})
	raster.Translucency = 0.52
	raster.ScaleMode = canvas.ImageScalePixels
	return raster
}
