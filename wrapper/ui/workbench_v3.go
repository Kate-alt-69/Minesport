package ui

import (
	"fmt"
	"image/color"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

// Workbench V3 keeps the Phase-2 information architecture while making the
// viewport and task feedback behave like a real production workbench: engine
// chatter stays in diagnostics, coordinates belong to the viewer, and active
// work rises from the bottom of the window as a compact non-modal drawer.
func (ms *MinesportApp) buildWorkbenchUIV3() fyne.CanvasObject {
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

	// Kept for compatibility with existing code paths and the debug console,
	// but no longer rendered in the production status bar.
	ms.statusLabel = widget.NewLabel("")
	ms.stateIcon = widget.NewIcon(theme.InfoIcon())
	ms.cursorLabel = widget.NewLabel("X —  ·  Z —")
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

	viewport := ms.buildWorkbenchViewportV3()
	split := container.NewHSplit(sidebar, viewport)
	split.SetOffset(0.255)

	activityRail := ms.buildWorkbenchActivityRailV2()
	topBar := ms.buildWorkbenchTopBar()
	ms.buildWorkbenchTaskDrawerV3()
	statusAccent := ms.buildWorkbenchStatusAccentV3()
	body := container.NewBorder(topBar, statusAccent, activityRail, nil, split)

	ms.setWorkbenchPaneV2(workbenchPaneWorld)
	ms.updateWorkbenchWorldContext()
	ms.updateMetaHUD(ms.selectionSizeText())
	return container.NewStack(newMinesportWorkbenchBackground(), body)
}

func (ms *MinesportApp) buildWorkbenchViewportV3() fyne.CanvasObject {
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
	preparingHint := widget.NewLabel("The workbench stays interactive while the map is prepared.")
	preparingHint.Alignment = fyne.TextAlignCenter
	preparingProgress := widget.NewProgressBarInfinite()
	preparingCard := widget.NewCard("", "", container.NewVBox(preparingText, preparingProgress, preparingHint))
	ms.mapPreparing = container.NewCenter(container.NewPadded(preparingCard))
	ms.mapPreparing.Hide()

	mapArea := container.NewStack(ms.worldMap, ms.mapPreparing)
	ms.previewHost = container.NewMax(mapArea)

	viewportSurface := canvas.NewRectangle(color.NRGBA{R: 13, G: 15, B: 16, A: 235})
	viewControls := container.NewHBox(ms.viewHint, layout.NewSpacer(), ms.viewToggle2D, ms.viewToggle3D, ms.fitBtn)
	controlSurface := canvas.NewRectangle(color.NRGBA{R: 20, G: 23, B: 24, A: 232})
	controlBar := container.NewStack(controlSurface, container.NewPadded(viewControls))

	selectionHUD := ms.buildMetaHUD()
	coordBackground := canvas.NewRectangle(color.NRGBA{R: 10, G: 13, B: 14, A: 225})
	coordBackground.CornerRadius = 5
	ms.cursorLabel.TextStyle = fyne.TextStyle{Monospace: true, Bold: true}
	coordHUD := container.NewStack(coordBackground, container.NewPadded(ms.cursorLabel))
	footerSurface := canvas.NewRectangle(color.NRGBA{R: 17, G: 20, B: 20, A: 238})
	footer := container.NewStack(
		footerSurface,
		container.NewPadded(container.NewBorder(nil, nil, selectionHUD, coordHUD, nil)),
	)

	return container.NewStack(
		viewportSurface,
		container.NewBorder(controlBar, footer, nil, nil, ms.previewHost),
	)
}

func (ms *MinesportApp) buildWorkbenchStatusAccentV3() fyne.CanvasObject {
	line := canvas.NewRectangle(color.NRGBA{R: 58, G: 121, B: 61, A: 255})
	line.SetMinSize(fyne.NewSize(1, 3))
	return line
}

type workbenchTaskRuntimeV3 struct {
	mu           sync.Mutex
	drawer       *fyne.Container
	title        *widget.Label
	percent      *widget.Label
	activity     *widget.Activity
	overlayAdded bool
	visible      bool
	animation    *fyne.Animation
	hideTimer    *time.Timer
	kind         string
	detail       string
}

var workbenchTaskRuntimesV3 sync.Map

func (ms *MinesportApp) taskRuntimeV3() *workbenchTaskRuntimeV3 {
	value, ok := workbenchTaskRuntimesV3.Load(ms)
	if !ok {
		return nil
	}
	state, _ := value.(*workbenchTaskRuntimeV3)
	return state
}

func (ms *MinesportApp) buildWorkbenchTaskDrawerV3() fyne.CanvasObject {
	if state := ms.taskRuntimeV3(); state != nil {
		return state.drawer
	}

	title := widget.NewLabelWithStyle("", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	title.Truncation = fyne.TextTruncateEllipsis
	percent := widget.NewLabel("")
	percent.TextStyle = fyne.TextStyle{Monospace: true, Bold: true}
	activity := widget.NewActivity()
	loader := container.NewGridWrap(fyne.NewSize(24, 24), activity)

	background := canvas.NewRectangle(color.NRGBA{R: 24, G: 30, B: 25, A: 252})
	background.CornerRadius = 8
	content := container.NewBorder(nil, nil, title, container.NewHBox(loader, percent), nil)
	drawer := container.NewStack(background, container.NewPadded(content))
	drawer.Hide()

	state := &workbenchTaskRuntimeV3{
		drawer:   drawer,
		title:    title,
		percent:  percent,
		activity: activity,
	}
	workbenchTaskRuntimesV3.Store(ms, state)
	return drawer
}

func (ms *MinesportApp) beginWorkbenchTaskV3(kind, message string, determinate bool) {
	state := ms.taskRuntimeV3()
	if state == nil {
		ms.buildWorkbenchTaskDrawerV3()
		state = ms.taskRuntimeV3()
	}
	if state == nil {
		return
	}

	state.mu.Lock()
	if state.hideTimer != nil {
		state.hideTimer.Stop()
		state.hideTimer = nil
	}
	if state.animation != nil {
		state.animation.Stop()
		state.animation = nil
	}
	state.kind = strings.ToUpper(strings.TrimSpace(kind))
	if state.kind == "" {
		state.kind = "TASK"
	}
	state.detail = ""
	state.title.SetText(state.kind + " · " + message)
	if determinate {
		state.percent.SetText("0%")
	} else {
		state.percent.SetText("…")
	}
	state.activity.Start()
	state.mu.Unlock()

	ms.showWorkbenchTaskDrawerV3(true)
}

func (ms *MinesportApp) updateWorkbenchTaskV3(pct int, message, detail string) {
	state := ms.taskRuntimeV3()
	if state == nil {
		return
	}
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}

	state.mu.Lock()
	if strings.TrimSpace(message) != "" {
		state.title.SetText(state.kind + " · " + message)
	}
	state.percent.SetText(fmt.Sprintf("%d%%", pct))
	state.detail = detail
	state.activity.Start()
	state.mu.Unlock()
	ms.showWorkbenchTaskDrawerV3(false)
}

func (ms *MinesportApp) finishWorkbenchTaskV3(ok bool, message, detail string) {
	state := ms.taskRuntimeV3()
	if state == nil {
		return
	}

	state.mu.Lock()
	state.activity.Stop()
	state.detail = detail
	if ok {
		state.title.SetText("DONE · " + message)
		state.percent.SetText("100%")
	} else {
		state.title.SetText("FAILED · " + message)
		state.percent.SetText("!")
	}
	if state.hideTimer != nil {
		state.hideTimer.Stop()
	}
	delay := 950 * time.Millisecond
	if !ok {
		delay = 3 * time.Second
	}
	state.hideTimer = time.AfterFunc(delay, func() { ms.hideWorkbenchTaskDrawerV3() })
	state.mu.Unlock()

	ms.showWorkbenchTaskDrawerV3(false)
	if strings.TrimSpace(detail) != "" {
		ms.appendLog(message + ": " + detail)
	}
}

func (ms *MinesportApp) showWorkbenchTaskDrawerV3(animateIn bool) {
	state := ms.taskRuntimeV3()
	if state == nil || ms.window == nil {
		return
	}
	windowCanvas := ms.window.Canvas()
	canvasSize := windowCanvas.Size()
	if canvasSize.Width < 2 || canvasSize.Height < 2 {
		return
	}

	state.mu.Lock()
	width := canvasSize.Width - 32
	if width < 320 {
		width = canvasSize.Width
	}
	height := state.drawer.MinSize().Height
	if height < 48 {
		height = 48
	}
	state.drawer.Resize(fyne.NewSize(width, height))
	endX := float32(16)
	if width >= canvasSize.Width {
		endX = 0
	}
	end := fyne.NewPos(endX, canvasSize.Height-height-12)
	if end.Y < 0 {
		end.Y = 0
	}

	if !state.overlayAdded {
		windowCanvas.Overlays().Add(state.drawer)
		state.overlayAdded = true
	}
	wasVisible := state.visible
	state.drawer.Show()
	state.visible = true
	if state.animation != nil {
		state.animation.Stop()
	}

	if animateIn && !wasVisible {
		start := fyne.NewPos(end.X, canvasSize.Height+4)
		state.drawer.Move(start)
		animation := canvas.NewPositionAnimation(start, end, 180*time.Millisecond, state.drawer.Move)
		animation.Curve = fyne.AnimationEaseOut
		state.animation = animation
		state.mu.Unlock()
		animation.Start()
		return
	}
	state.drawer.Move(end)
	state.mu.Unlock()
}

func (ms *MinesportApp) hideWorkbenchTaskDrawerV3() {
	state := ms.taskRuntimeV3()
	if state == nil || ms.window == nil {
		return
	}
	windowCanvas := ms.window.Canvas()
	canvasSize := windowCanvas.Size()

	state.mu.Lock()
	if !state.visible {
		state.mu.Unlock()
		return
	}
	if state.animation != nil {
		state.animation.Stop()
	}
	start := state.drawer.Position()
	end := fyne.NewPos(start.X, canvasSize.Height+4)
	animation := canvas.NewPositionAnimation(start, end, 170*time.Millisecond, state.drawer.Move)
	animation.Curve = fyne.AnimationEaseIn
	state.animation = animation
	state.mu.Unlock()
	animation.Start()

	time.AfterFunc(190*time.Millisecond, func() {
		state.mu.Lock()
		state.drawer.Hide()
		state.visible = false
		state.animation = nil
		state.mu.Unlock()
	})
}

func cleanupWorkbenchRuntimeV3(ms *MinesportApp) {
	value, ok := workbenchTaskRuntimesV3.LoadAndDelete(ms)
	if !ok {
		return
	}
	state, _ := value.(*workbenchTaskRuntimeV3)
	if state == nil {
		return
	}
	state.mu.Lock()
	if state.animation != nil {
		state.animation.Stop()
	}
	if state.hideTimer != nil {
		state.hideTimer.Stop()
	}
	state.activity.Stop()
	state.mu.Unlock()
}
