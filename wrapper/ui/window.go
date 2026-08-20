package ui

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/bridgecompat"
	"github.com/kastrick/minesport/ipc"
	"github.com/kastrick/minesport/launcher"
	_ "image/png"
)

type MinesportApp struct {
	window                                                            fyne.Window
	fyneApp                                                           fyne.App
	engine                                                            *ipc.Engine
	mu                                                                sync.Mutex
	heightmapMu                                                       sync.Mutex
	engineStateMu                                                     sync.Mutex
	engineAvailable                                                   bool
	engineFailureShown                                                bool
	loaderWarningShown                                                bool
	compatibilityInstalling                                           bool
	diagnosticsLogPath                                                string
	settings                                                          Settings
	debugWindow                                                       fyne.Window
	viewerSession                                                     *ViewerSession
	customSelectionFile                                               string
	customSelectionCount                                              int
	suppressSelectionClear                                            bool
	worldPath, worldName, mcVersion, loaderType, modsPath, outputPath string

	worldNameLabel                                       *widget.Label
	worldMetaLabel                                       *widget.Label
	exportNameEntry                                      *widget.Entry
	formatSelect                                         *widget.Select
	modeSelect                                           *widget.Select
	minXRange, minYRange, minZRange                      *AxisRange
	minXEntry, minYEntry, minZEntry                      *StepperEntry
	maxXEntry, maxYEntry, maxZEntry                      *StepperEntry
	centerX, centerY, centerZ, radiusX, radiusY, radiusZ *StepperEntry
	outputLabel                                          *widget.Label
	exportBtn                                            *widget.Button
	autoDetectBtn                                        *widget.Button
	optimizeCheck                                        *widget.Check
	optimizeHint                                         *widget.Label
	selectionModeSelect                                  *widget.Select
	boxCoordGroup, bubbleCoordGroup                      *fyne.Container

	worldMap                           *WorldMapV2
	logContent                         *widget.Label
	logScroll                          *container.Scroll
	progressBar                        *widget.ProgressBar
	statusLabel                        *widget.Label
	stateIcon                          *widget.Icon
	cursorLabel                        *widget.Label
	metaHUD                            *widget.Label
	viewToggle2D, viewToggle3D, fitBtn *widget.Button
	settingsBtn                        *AnimatedSettingsButton
	previewHost                        *fyne.Container
	mapPreparing                       *fyne.Container
	embeddedViewer                     *EmbeddedViewer
	viewHint                           *widget.Label

	exportWindow fyne.Window
	exportTitle  *widget.Label
	exportStage  *widget.Label
	exportDetail *widget.Label
	exportBar    *widget.ProgressBar
	exportStats  *widget.Label
}

func Run(jarPath string) {
	a := app.NewWithID("kastrick.dev.minesport")
	w := a.NewWindow("Minesport — by Kastrick")
	w.Resize(fyne.NewSize(1180, 740))
	w.SetMaster()
	ms := &MinesportApp{window: w, fyneApp: a}
	ms.settings = LoadSettings()
	ms.engine = ipc.NewEngine(jarPath)
	w.SetContent(ms.buildUI())
	ms.installViewportShortcuts()
	w.SetCloseIntercept(func() {
		if ms.embeddedViewer != nil {
			ms.embeddedViewer.Close()
		}
		ms.engine.Stop()
		w.SetCloseIntercept(nil)
		w.Close()
	})
	if ms.settings.DebugMode {
		ms.openDebugConsole()
	}
	ms.engine.OnLog = func(msg string) { ms.appendLog(msg) }
	ms.engine.OnProgress = func(pct int, msg string) {
		ms.progressBar.SetValue(float64(pct) / 100)
		ms.statusLabel.SetText(msg)
		ms.stateIcon.SetResource(theme.ViewRefreshIcon())
		ms.updateExportProgress(pct, msg)
	}
	ms.engine.OnDone = func(resp ipc.Response) { ms.finishExport(resp, true, "") }
	ms.engine.OnError = func(msg string) { ms.finishExport(ipc.Response{}, false, msg) }
	if jarPath == "" {
		ms.statusLabel.SetText("Engine jar not found")
		ms.exportBtn.Disable()
	} else if err := ms.engine.Start(jarPath); err != nil {
		dialog.ShowError(fmt.Errorf("engine failed to start: %s", err), w)
	} else {
		ms.statusLabel.SetText("Ready")
	}
	w.ShowAndRun()
}

func (ms *MinesportApp) buildUI() fyne.CanvasObject {
	side := ms.buildInspector()
	main := ms.buildMainArea()
	ms.updateMetaHUD(ms.selectionSizeText())
	sp := container.NewHSplit(side, main)
	sp.SetOffset(0.27)
	return sp
}

func (ms *MinesportApp) buildInspector() fyne.CanvasObject {
	ms.worldNameLabel = widget.NewLabel("No world selected")
	ms.worldNameLabel.TextStyle = fyne.TextStyle{Bold: true}
	ms.worldMetaLabel = widget.NewLabel("Select a Minecraft save to begin")
	ms.exportNameEntry = widget.NewEntry()
	ms.exportNameEntry.SetText("Minesport_Export")
	ms.exportNameEntry.SetPlaceHolder("Export name")
	selectBtn := widget.NewButtonWithIcon("Select world", theme.FolderOpenIcon(), ms.onSelectWorld)
	selectBtn.Importance = widget.HighImportance
	ms.settingsBtn = NewAnimatedSettingsButton(ms.onOpenSettings)
	worldHeader := container.NewBorder(nil, nil, widget.NewLabelWithStyle("WORLD INSPECTOR", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}), ms.settingsBtn)
	worldCard := widget.NewCard("", "", container.NewVBox(worldHeader, ms.worldNameLabel, ms.worldMetaLabel, compactEntryRow("Export name", ms.exportNameEntry), widget.NewLabel("Used for the exported filename and imported 3D object name."), container.NewPadded(selectBtn)))

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
	for _, e := range []*StepperEntry{ms.centerX, ms.centerY, ms.centerZ, ms.radiusX, ms.radiusY, ms.radiusZ} {
		e.SetBounds(-30000000, 30000000)
		e.OnChanged = func(string) { ms.syncBubblePreview(); ms.updateMetaHUD(ms.selectionSizeText()) }
	}
	ms.bubbleCoordGroup = container.NewVBox(compactNumberRow("Center X", ms.centerX), compactNumberRow("Center Y", ms.centerY), compactNumberRow("Center Z", ms.centerZ), widget.NewSeparator(), compactNumberRow("Radius X", ms.radiusX), compactNumberRow("Radius Y", ms.radiusY), compactNumberRow("Radius Z", ms.radiusZ))
	ms.bubbleCoordGroup.Hide()
	ms.selectionModeSelect.SetSelected("Box selection")
	ms.autoDetectBtn = widget.NewButtonWithIcon("Auto-detect world bounds", theme.SearchIcon(), ms.onAutoDetect)
	ms.autoDetectBtn.Disable()
	selectionCard := widget.NewCard("SELECTION", "", container.NewVBox(ms.selectionModeSelect, ms.boxCoordGroup, ms.bubbleCoordGroup, container.NewPadded(ms.autoDetectBtn)))

	ms.formatSelect = widget.NewSelect([]string{"glTF 2.0", "OBJ + MTL"}, nil)
	ms.formatSelect.SetSelected("glTF 2.0")
	ms.modeSelect = widget.NewSelect([]string{"Grouped", "Individual blocks", "Merged"}, nil)
	ms.modeSelect.SetSelected("Grouped")
	ms.optimizeCheck = widget.NewCheck("Optimize mesh output", nil)
	ms.optimizeHint = widget.NewLabel("Vertex welding + safe covered-face removal. Individual blocks remain separate and can be heavy.")
	ms.optimizeHint.TextStyle = fyne.TextStyle{Italic: true}
	ms.applyOptimizeGate()
	exportCard := widget.NewCard("EXPORT", "", container.NewVBox(compactSelectRow("Format", ms.formatSelect), compactSelectRow("Objects", ms.modeSelect), ms.optimizeCheck, ms.optimizeHint))

	ms.outputLabel = widget.NewLabel("~/Minesport_Exports")
	ms.outputLabel.Truncation = fyne.TextTruncateEllipsis
	outputCard := widget.NewCard("OUTPUT", "", container.NewVBox(ms.outputLabel, widget.NewButtonWithIcon("Change folder", theme.FolderIcon(), ms.onSelectOutput)))
	ms.exportBtn = widget.NewButtonWithIcon("Export world", theme.DownloadIcon(), ms.onExport)
	ms.exportBtn.Importance = widget.HighImportance
	ms.exportBtn.Disable()
	side := container.NewVBox(worldCard, selectionCard, exportCard, outputCard, container.NewPadded(ms.exportBtn))
	return container.NewVScroll(container.NewPadded(side))
}

func compactEntryRow(label string, e *widget.Entry) fyne.CanvasObject {
	return container.NewBorder(nil, nil, widget.NewLabel(label), nil, e)
}
func compactNumberRow(label string, e *StepperEntry) fyne.CanvasObject {
	return container.NewBorder(nil, nil, widget.NewLabel(label), nil, e)
}
func compactSelectRow(label string, s *widget.Select) fyne.CanvasObject {
	return container.NewBorder(nil, nil, widget.NewLabel(label), nil, s)
}
func (ms *MinesportApp) applyOptimizeGate() {
	if ms.optimizeCheck == nil {
		return
	}
	ms.optimizeCheck.Enable()
	ms.optimizeHint.Show()
}

func (ms *MinesportApp) buildMainArea() fyne.CanvasObject {
	ms.viewToggle2D = widget.NewButtonWithIcon("2D", theme.GridIcon(), ms.show2DPreview)
	ms.viewToggle2D.Importance = widget.HighImportance
	ms.viewToggle3D = widget.NewButtonWithIcon("3D preview", theme.ViewFullScreenIcon(), ms.onExplore3D)
	ms.viewToggle3D.Disable()
	ms.fitBtn = widget.NewButtonWithIcon("Fit", theme.ZoomFitIcon(), func() {
		if ms.embeddedViewer != nil && ms.embeddedViewer.Visible() {
			ms.embeddedViewer.Fit()
		} else {
			ms.worldMap.FitToWindow()
		}
	})
	ms.viewHint = widget.NewLabel("LMB select · MMB pan · scroll zoom")
	ms.worldMap = NewWorldMapV2()
	ms.worldMap.OnSelectionChanged = func(minX, minZ, maxX, maxZ int) {
		ms.minXRange.Front.SetText(fmt.Sprintf("%d", minX))
		ms.minXRange.Back.SetText(fmt.Sprintf("%d", maxX))
		ms.minZRange.Front.SetText(fmt.Sprintf("%d", minZ))
		ms.minZRange.Back.SetText(fmt.Sprintf("%d", maxZ))
		ms.updateMetaHUD(ms.selectionSizeText())
	}
	ms.worldMap.OnCursorMoved = func(x, z int) { ms.cursorLabel.SetText(fmt.Sprintf("X %d  ·  Z %d", x, z)) }
	ms.worldMap.OnCenterPicked = func(x, z int) { ms.centerX.SetText(fmt.Sprintf("%d", x)); ms.centerZ.SetText(fmt.Sprintf("%d", z)) }
	preparingText := widget.NewLabelWithStyle("Preparing 2D map…", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	preparingProgress := widget.NewProgressBarInfinite()
	preparingCard := widget.NewCard("", "", container.NewVBox(preparingText, preparingProgress))
	ms.mapPreparing = container.NewCenter(preparingCard)
	ms.mapPreparing.Hide()
	mapArea := container.NewStack(ms.worldMap, container.NewVBox(layout.NewSpacer(), container.NewHBox(layout.NewSpacer(), ms.buildMetaHUD())), ms.mapPreparing)
	ms.previewHost = container.NewMax(mapArea)
	ms.logContent = widget.NewLabel("")
	ms.logContent.TextStyle = fyne.TextStyle{Monospace: true}
	ms.logContent.Wrapping = fyne.TextWrapWord
	ms.logScroll = container.NewVScroll(ms.logContent)
	ms.progressBar = widget.NewProgressBar()
	ms.statusLabel = widget.NewLabel("Ready")
	ms.stateIcon = widget.NewIcon(theme.InfoIcon())
	ms.cursorLabel = widget.NewLabel("")
	state := container.NewBorder(nil, nil, container.NewHBox(ms.stateIcon, ms.statusLabel), ms.cursorLabel, ms.progressBar)
	viewControls := container.NewHBox(layout.NewSpacer(), ms.viewHint, ms.viewToggle2D, ms.viewToggle3D, ms.fitBtn)
	viewportOverlay := container.NewVBox(container.NewPadded(viewControls), layout.NewSpacer())
	return container.NewBorder(nil, state, nil, nil, container.NewStack(ms.previewHost, viewportOverlay))
}

func (ms *MinesportApp) installViewportShortcuts() {
	ms.window.Canvas().SetOnTypedKey(func(event *fyne.KeyEvent) {
		if event.Name != fyne.KeyF6 {
			return
		}
		if ms.embeddedViewer != nil && ms.embeddedViewer.Visible() {
			ms.embeddedViewer.Fit()
			ms.statusLabel.SetText("3D camera centered")
			return
		}
		if ms.worldMap != nil {
			ms.worldMap.FitToWindow()
			ms.statusLabel.SetText("2D map fitted")
		}
	})
}

func (ms *MinesportApp) buildMetaHUD() fyne.CanvasObject {
	bg := canvas.NewRectangle(color.NRGBA{12, 16, 20, 220})
	bg.CornerRadius = 6
	ms.metaHUD = widget.NewLabel("")
	ms.metaHUD.TextStyle = fyne.TextStyle{Monospace: true, Bold: true}
	return container.NewStack(bg, container.NewPadded(ms.metaHUD))
}
func (ms *MinesportApp) updateMetaHUD(text string) {
	if ms.metaHUD != nil {
		ms.metaHUD.SetText(text)
	}
}
func (ms *MinesportApp) selectionSizeText() string {
	var w, h, d int
	if ms.selectionModeSelect != nil && ms.selectionModeSelect.Selected == "Bubble selection" {
		w = 2*ms.radiusX.Int(32) + 1
		h = 2*ms.radiusY.Int(32) + 1
		d = 2*ms.radiusZ.Int(32) + 1
	} else {
		a, b := ms.minXRange.Bounds()
		c, e := ms.minYRange.Bounds()
		f, g := ms.minZRange.Bounds()
		w = b - a + 1
		h = e - c + 1
		d = g - f + 1
	}
	if w < 0 {
		w = -w
	}
	if h < 0 {
		h = -h
	}
	if d < 0 {
		d = -d
	}
	return fmt.Sprintf("%s blocks  ·  %s × %s × %s", formatCount(w*h*d), formatCount(w), formatCount(h), formatCount(d))
}
func formatCount(n int) string {
	if n < 0 {
		n = 0
	}
	s := fmt.Sprintf("%d", n)
	out := ""
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			out += ","
		}
		out += string(c)
	}
	return out
}

func (ms *MinesportApp) onSelectWorld() {
	if ms.embeddedViewer != nil {
		ms.embeddedViewer.Close()
		ms.viewerSession = nil
		ms.embeddedViewer = nil
		ms.show2DPreview()
	}
	ShowWorldPicker(ms.window, func(worldPath, modsPath string) {
		ms.show2DPreview()
		ms.worldPath = worldPath
		ms.modsPath = modsPath
		ms.worldName = filepath.Base(worldPath)
		ms.worldNameLabel.SetText(ms.worldName)
		ms.worldMetaLabel.SetText(ms.detectWorldMeta(worldPath))
		ms.appendLog(fmt.Sprintf("Selected world: %s (Minecraft %s, %s)", worldPath, ms.mcVersion, ms.loaderType))
		if strings.TrimSpace(ms.exportNameEntry.Text) == "" || ms.exportNameEntry.Text == "Minesport_Export" {
			ms.exportNameEntry.SetText(sanitizeExportName(ms.worldName) + "_export")
		}
		if ms.outputPath == "" {
			home, _ := os.UserHomeDir()
			ms.outputPath = filepath.Join(home, "Minesport_Exports")
			ms.outputLabel.SetText(ms.outputPath)
		}
		ms.showLoaderWarning()
		if ms.isEngineAvailable() {
			ms.exportBtn.Enable()
			ms.autoDetectBtn.Enable()
			ms.viewToggle3D.Enable()
			ms.requireBridgeCompatibility(nil)
			go ms.generateHeightmap(worldPath)
		} else {
			ms.appendLog("World selected while core engine is unavailable; preview and export remain disabled")
		}
	})
}
func (ms *MinesportApp) onSelectOutput() {
	go func() {
		f := nativeOpenFolder("Select Output Folder")
		if f != "" {
			ms.outputPath = f
			ms.outputLabel.SetText(f)
		}
	}()
}
func (ms *MinesportApp) onAutoDetect() {
	if ms.worldPath == "" {
		return
	}
	if !ms.isEngineAvailable() {
		ms.handleCoreEngineFailure("World bounds were requested while the core engine was unavailable.")
		return
	}
	go func() {
		r, e := ms.engine.SendCommand(map[string]interface{}{"command": "heightmap", "worldPath": ms.worldPath, "scale": 1})
		if e != nil {
			ms.heightmapFailed("Auto-detect IPC failed: " + e.Error())
			return
		}
		if r == nil {
			ms.heightmapFailed("Auto-detect returned no response")
			return
		}
		if r.Type == "error" {
			ms.heightmapFailed(r.Message)
			return
		}
		if r.Type != "heightmap" {
			ms.heightmapFailed("Unexpected auto-detect response: " + r.Type)
			return
		}
		ms.minXRange.Front.SetText(fmt.Sprintf("%d", r.MinX))
		ms.minXRange.Back.SetText(fmt.Sprintf("%d", r.MaxX))
		ms.minZRange.Front.SetText(fmt.Sprintf("%d", r.MinZ))
		ms.minZRange.Back.SetText(fmt.Sprintf("%d", r.MaxZ))
	}()
}

func (ms *MinesportApp) onExport() {
	if ms.worldPath == "" {
		dialog.ShowError(fmt.Errorf("no world selected"), ms.window)
		return
	}
	if ms.requireBridgeCompatibility(ms.onExport) {
		return
	}
	name := sanitizeExportName(ms.exportNameEntry.Text)
	if name == "" {
		name = sanitizeExportName(ms.worldName)
		if name == "" {
			name = "Minesport_Export"
		}
	}
	ext := ".gltf"
	if strings.Contains(ms.formatSelect.Selected, "OBJ") {
		ext = ".obj"
	}
	if ms.outputPath == "" {
		home, _ := os.UserHomeDir()
		ms.outputPath = filepath.Join(home, "Minesport_Exports")
		ms.outputLabel.SetText(ms.outputPath)
	}
	_ = os.MkdirAll(ms.outputPath, 0755)
	desired := filepath.Join(ms.outputPath, name+ext)
	if exportFilesExist(desired) {
		existing := existingExportFile(desired)
		dir := filepath.Dir(existing)
		msg := fmt.Sprintf("%s already exists in %s.\n\nDo you want to replace it?", filepath.Base(existing), dir)
		ms.exportBtn.Disable()
		content := container.NewVBox(widget.NewLabel(msg))
		d := dialog.NewCustomConfirm("File already exists", "YES", "NO", content, func(replace bool) {
			if replace {
				if err := removeExportFiles(desired); err != nil {
					dialog.ShowError(fmt.Errorf("could not replace export: %w", err), ms.window)
					ms.exportBtn.Enable()
					return
				}
				ms.startExport(desired)
				return
			}
			ms.startExport(nextExportPath(desired))
		}, ms.window)
		d.Show()
		return
	}
	ms.startExport(desired)
}

func (ms *MinesportApp) startExport(outputPath string) {
	ms.exportBtn.Disable()
	ms.progressBar.SetValue(0)
	ms.statusLabel.SetText("Preparing export…")
	ms.showExportProgress()
	format := "gltf"
	if strings.HasSuffix(strings.ToLower(outputPath), ".obj") {
		format = "obj"
	}
	mode := "grouped"
	switch ms.modeSelect.Selected {
	case "Individual blocks":
		mode = "individual"
	case "Merged":
		mode = "merged"
	}
	p := ipc.ExportParams{WorldPath: ms.worldPath, ModsPath: ms.modsPath, ModLoader: normalizedLoader(ms.loaderType), OutputPath: outputPath, Format: format, ExportMode: mode}
	if ms.selectionModeSelect.Selected == "Bubble selection" {
		cx, cy, cz := ms.centerX.Int(0), ms.centerY.Int(64), ms.centerZ.Int(0)
		rx, ry, rz := ms.radiusX.Int(32), ms.radiusY.Int(32), ms.radiusZ.Int(32)
		p.MinX, p.MaxX = cx-rx, cx+rx
		p.MinY, p.MaxY = cy-ry, cy+ry
		p.MinZ, p.MaxZ = cz-rz, cz+rz
		p.CenterX, p.CenterY, p.CenterZ = &cx, &cy, &cz
		p.RadiusX, p.RadiusY, p.RadiusZ = &rx, &ry, &rz
	} else {
		p.MinX, p.MaxX = ms.minXRange.Bounds()
		p.MinY, p.MaxY = ms.minYRange.Bounds()
		p.MinZ, p.MaxZ = ms.minZRange.Bounds()
	}
	options := map[string]string{"faceCulling": strconv.FormatBool(ms.settings.OptimizeOutputEnabled), "minecraftVersion": bridgecompat.NormalizeVersion(ms.mcVersion)}
	if ms.optimizeCheck.Checked {
		options["optimize"] = "true"
		options["faceCulling"] = "true"
	}
	if ms.settings.HiddenBlockCullingEnabled {
		options["hiddenBlockCulling"] = "true"
	}
	if ms.customSelectionFile != "" {
		options["customSelectionFile"] = ms.customSelectionFile
	}
	if len(ms.settings.ResourcePackPaths) > 0 {
		options["resourcePacks"] = PathListString(ms.settings.ResourcePackPaths)
	}
	if len(ms.settings.DataPackPaths) > 0 {
		options["dataPacks"] = PathListString(ms.settings.DataPackPaths)
	}
	p.Options = options
	if err := ms.engine.Export(p); err != nil {
		ms.finishExport(ipc.Response{}, false, err.Error())
	}
}

func (ms *MinesportApp) requireBridgeCompatibility(onReady func()) bool {
	version := bridgecompat.NormalizeVersion(ms.mcVersion)
	if normalizedLoader(ms.loaderType) != "fabric" {
		return false
	}
	ms.engineStateMu.Lock()
	installing := ms.compatibilityInstalling
	ms.engineStateMu.Unlock()
	if installing {
		ms.statusLabel.SetText("Compatibility installation already running…")
		ms.appendLog("Ignored duplicate compatibility request for Minecraft " + version)
		return true
	}
	if version == "" || !bridgecompat.NeedsPreparation(version) {
		return false
	}
	if bridge, ok := bridgecompat.PreparedBridge(version); ok {
		ms.appendLog("Compatibility bridge ready: " + bridge)
		return false
	}
	message := fmt.Sprintf("Minesport compatibility for Minecraft %s is not installed.\n\nInstall it now? This one-time setup downloads the matching Java/Gradle/Fabric dependencies and caches the compiled bridge.", version)
	dialog.NewConfirm("Install Minecraft compatibility", message, func(install bool) {
		if !install {
			ms.appendLog("Compatibility installation declined for Minecraft " + version)
			ms.statusLabel.SetText("Compatibility not installed")
			return
		}
		ms.engineStateMu.Lock()
		ms.compatibilityInstalling = true
		ms.engineStateMu.Unlock()
		ms.exportBtn.Disable()
		ms.statusLabel.SetText("Installing Minecraft " + version + " compatibility…")
		ms.stateIcon.SetResource(theme.ViewRefreshIcon())
		go func() {
			bridge, err := bridgecompat.Ensure(version, func(update bridgecompat.Progress) {
				ms.progressBar.SetValue(float64(update.Percent) / 100)
				ms.statusLabel.SetText(update.Stage)
				ms.appendLog(fmt.Sprintf("Compatibility %d%%: %s %s", update.Percent, update.Stage, update.Detail))
			})
			if err != nil {
				ms.engineStateMu.Lock()
				ms.compatibilityInstalling = false
				ms.engineStateMu.Unlock()
				ms.appendLog("Compatibility installation failed: " + err.Error())
				ms.statusLabel.SetText("Compatibility installation failed")
				ms.stateIcon.SetResource(theme.ErrorIcon())
				ms.exportBtn.Enable()
				ms.showOperationFailure("Minecraft compatibility installation failed", fmt.Sprintf("Minecraft %s: %v", version, err))
				return
			}
			ms.engineStateMu.Lock()
			ms.compatibilityInstalling = false
			ms.engineStateMu.Unlock()
			ms.appendLog("Compatibility bridge installed: " + bridge)
			ms.statusLabel.SetText("Compatibility ready")
			ms.stateIcon.SetResource(theme.ConfirmIcon())
			ms.exportBtn.Enable()
			if onReady != nil {
				onReady()
			}
		}()
	}, ms.window).Show()
	return true
}

func exportFilesExist(path string) bool {
	for _, p := range relatedExportFiles(path) {
		if _, err := os.Stat(p); err == nil {
			return true
		}
	}
	return false
}
func existingExportFile(path string) string {
	for _, p := range relatedExportFiles(path) {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return path
}
func relatedExportFiles(path string) []string {
	ext := strings.ToLower(filepath.Ext(path))
	base := strings.TrimSuffix(path, filepath.Ext(path))
	switch ext {
	case ".obj":
		return []string{path, base + ".mtl"}
	case ".gltf":
		return []string{path, base + ".bin"}
	default:
		return []string{path}
	}
}
func removeExportFiles(path string) error {
	for _, p := range relatedExportFiles(path) {
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}
func nextExportPath(path string) string {
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	for i := 1; ; i++ {
		candidate := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if !exportFilesExist(candidate) {
			return candidate
		}
	}
}
func sanitizeExportName(name string) string {
	name = strings.TrimSpace(name)
	name = strings.NewReplacer("<", "_", ">", "_", ":", "_", "\"", "_", "/", "_", "\\", "_", "|", "_", "?", "_", "*", "_").Replace(name)
	name = strings.Trim(name, " .")
	if name == "." || name == ".." {
		return ""
	}
	return name
}

func (ms *MinesportApp) showExportProgress() {
	if ms.exportWindow != nil {
		return
	}
	w := ms.fyneApp.NewWindow("Minesport — Exporting")
	w.Resize(fyne.NewSize(620, 330))
	w.SetFixedSize(true)
	ms.exportTitle = widget.NewLabel("Exporting world…")
	ms.exportTitle.TextStyle = fyne.TextStyle{Bold: true}
	ms.exportStage = widget.NewLabel("Preparing…")
	ms.exportDetail = widget.NewLabel("")
	ms.exportBar = widget.NewProgressBar()
	ms.exportStats = widget.NewLabel("")
	hideBtn := widget.NewButton("Hide", func() { w.Hide() })
	card := widget.NewCard("LIVE EXPORT", "", container.NewVBox(ms.exportStage, ms.exportBar, ms.exportDetail, widget.NewSeparator(), ms.exportStats))
	w.SetContent(container.NewBorder(container.NewPadded(ms.exportTitle), container.NewPadded(hideBtn), nil, nil, container.NewPadded(card)))
	ms.exportWindow = w
	ms.window.Hide()
	w.CenterOnScreen()
	w.Show()
}
func (ms *MinesportApp) updateExportProgress(pct int, msg string) {
	if ms.exportWindow == nil {
		return
	}
	ms.exportBar.SetValue(float64(pct) / 100)
	ms.exportStage.SetText(msg)
	blocks := ms.estimateBlocks()
	estimatedBlocks := int(float64(blocks) * float64(pct) / 100)
	verts := estimatedBlocks * 24
	data := estimatedBlocks * 80
	ms.exportDetail.SetText(fmt.Sprintf("Blocks %s / ~%s", formatCount(estimatedBlocks), formatCount(blocks)))
	ms.exportStats.SetText(fmt.Sprintf("Estimated vertices  ~%s\nEstimated geometry data  ~%s KB", formatCount(verts), formatCount(data/1024)))
}
func (ms *MinesportApp) estimateBlocks() int {
	var w, h, d int
	if ms.selectionModeSelect.Selected == "Bubble selection" {
		w = 2*ms.radiusX.Int(32) + 1
		h = 2*ms.radiusY.Int(32) + 1
		d = 2*ms.radiusZ.Int(32) + 1
	} else {
		a, b := ms.minXRange.Bounds()
		c, e := ms.minYRange.Bounds()
		f, g := ms.minZRange.Bounds()
		w = b - a + 1
		h = e - c + 1
		d = g - f + 1
	}
	if w < 1 {
		w = 1
	}
	if h < 1 {
		h = 1
	}
	if d < 1 {
		d = 1
	}
	return w * h * d
}
func (ms *MinesportApp) finishExport(resp ipc.Response, ok bool, msg string) {
	if ok {
		ms.progressBar.SetValue(1)
		ms.statusLabel.SetText("Done")
		ms.stateIcon.SetResource(theme.ConfirmIcon())
		ms.updateExportProgress(100, "Export complete")
		ms.updateMetaHUD(fmt.Sprintf("%s blocks · %s faces · %s verts", formatCount(resp.BlockCount), formatCount(resp.QuadCount), formatCount(resp.VertexCount)))
		if ms.exportWindow != nil {
			ms.exportWindow.Close()
			ms.exportWindow = nil
		}
		ms.window.Show()
		ms.exportBtn.Enable()
	} else {
		ms.statusLabel.SetText("Failed")
		ms.stateIcon.SetResource(theme.ErrorIcon())
		if ms.exportWindow != nil {
			ms.exportStage.SetText("Export failed")
			ms.exportDetail.SetText(msg)
			ms.exportWindow.Show()
		} else {
			ms.window.Show()
		}
		ms.exportBtn.Enable()
	}
}
func (ms *MinesportApp) generateHeightmap(worldFolder string) {
	ms.heightmapMu.Lock()
	defer ms.heightmapMu.Unlock()
	if ms.worldPath != worldFolder {
		return
	}
	ms.setMapPreparing(true, "Preparing 2D map…")
	if cached, pngBytes, ok := loadCachedHeightmap(worldFolder); ok {
		ms.appendLog("2D map cache hit: using saved heightmap")
		if err := ms.applyHeightmapBytes(worldFolder, pngBytes, cached.MinX, cached.MinZ, cached.MaxX, cached.MaxZ, cached.Scale); err == nil {
			ms.statusLabel.SetText("Heightmap ready · cached")
			ms.stateIcon.SetResource(theme.ConfirmIcon())
			ms.setMapPreparing(false, "")
			return
		}
		ms.appendLog("Cached 2D map was unreadable; rebuilding it")
	}
	ms.appendLog("Heightmap request: " + worldFolder)
	resp, err := ms.engine.SendCommand(map[string]interface{}{"command": "heightmap", "worldPath": worldFolder, "scale": 1})
	if err != nil {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Heightmap IPC failed: " + err.Error())
		return
	}
	if resp == nil {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Engine returned no heightmap response")
		return
	}
	if resp.Type == "error" {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Engine error: " + resp.Message)
		return
	}
	if resp.Type != "heightmap" {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Unexpected response type " + resp.Type)
		return
	}
	if resp.Image == "" {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Engine response contained no image")
		return
	}
	ms.appendLog(fmt.Sprintf("Heightmap response: %d base64 bytes, bounds X %d..%d Z %d..%d, scale %d", len(resp.Image), resp.MinX, resp.MaxX, resp.MinZ, resp.MaxZ, resp.Scale))
	b, err := base64.StdEncoding.DecodeString(resp.Image)
	if err != nil {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Heightmap base64 decode failed: " + err.Error())
		return
	}
	if err := ms.applyHeightmapBytes(worldFolder, b, resp.MinX, resp.MinZ, resp.MaxX, resp.MaxZ, resp.Scale); err != nil {
		ms.setMapPreparing(false, "")
		ms.heightmapFailed("Heightmap PNG decode failed: " + err.Error())
		return
	}
	if err := saveCachedHeightmap(worldFolder, b, resp.MinX, resp.MinZ, resp.MaxX, resp.MaxZ, resp.Scale); err != nil {
		ms.appendLog("Could not cache 2D map: " + err.Error())
	} else {
		ms.appendLog("2D map cached for the next launch")
	}
	ms.setMapPreparing(false, "")
	ms.statusLabel.SetText("Heightmap ready")
	ms.stateIcon.SetResource(theme.ConfirmIcon())
}

func (ms *MinesportApp) applyHeightmapBytes(worldFolder string, pngBytes []byte, minX, minZ, maxX, maxZ, scale int) error {
	img, _, err := image.Decode(bytes.NewReader(pngBytes))
	if err != nil {
		return err
	}
	if ms.worldPath != worldFolder {
		return fmt.Errorf("world selection changed while the map was preparing")
	}
	rgba := image.NewRGBA(img.Bounds())
	for y := img.Bounds().Min.Y; y < img.Bounds().Max.Y; y++ {
		for x := img.Bounds().Min.X; x < img.Bounds().Max.X; x++ {
			rgba.Set(x, y, img.At(x, y))
		}
	}
	ms.worldMap.LoadHeightmap(rgba, minX, minZ, maxX, maxZ)
	ms.worldMap.FitToWindow()
	ms.minXRange.Front.SetText(fmt.Sprintf("%d", minX))
	ms.minXRange.Back.SetText(fmt.Sprintf("%d", maxX))
	ms.minZRange.Front.SetText(fmt.Sprintf("%d", minZ))
	ms.minZRange.Back.SetText(fmt.Sprintf("%d", maxZ))
	_ = scale
	return nil
}

func (ms *MinesportApp) setMapPreparing(preparing bool, message string) {
	if ms.mapPreparing == nil {
		return
	}
	if preparing {
		ms.mapPreparing.Show()
		ms.statusLabel.SetText(message)
		ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	} else {
		ms.mapPreparing.Hide()
	}
}
func (ms *MinesportApp) heightmapFailed(reason string) {
	ms.setMapPreparing(false, "")
	ms.statusLabel.SetText("Heightmap failed — see log")
	ms.stateIcon.SetResource(theme.ErrorIcon())
	ms.showOperationFailure("2D preview failed", reason)
}
func (ms *MinesportApp) appendLog(msg string) {
	log.Printf("[runtime] %s", msg)
	ms.mu.Lock()
	defer ms.mu.Unlock()
	if ms.logContent == nil {
		return
	}
	lines := strings.Split(ms.logContent.Text, "\n")
	if len(lines) > 2000 {
		lines = lines[len(lines)-2000:]
	}
	for _, line := range strings.Split(msg, "\n") {
		lines = append(lines, time.Now().Format("15:04:05.000")+"  "+line)
	}
	ms.logContent.SetText(strings.Join(lines, "\n"))
	if ms.logScroll != nil {
		ms.logScroll.ScrollToBottom()
	}
}
func (ms *MinesportApp) onOpenSettings() {
	previewVisible := ms.embeddedViewer != nil && ms.embeddedViewer.Visible()
	if previewVisible {
		ms.embeddedViewer.Hide()
	}
	ms.settingsBtn.StartPulse()
	ShowSettingsDialog(ms.window, ms.settings, ms.applySettings, func() {
		ms.settingsBtn.StopPulse()
		if previewVisible && ms.embeddedViewer != nil {
			ms.embeddedViewer.Show()
		}
	})
}
func (ms *MinesportApp) applySettings(s Settings) {
	was := ms.settings.DebugMode
	ms.settings = s
	if err := s.Save(); err != nil {
		ms.appendLog(err.Error())
	}
	if s.DebugMode && !was {
		ms.openDebugConsole()
	} else if !s.DebugMode && was {
		ms.closeDebugConsole()
	}
	ms.applyOptimizeGate()
}
func (ms *MinesportApp) openDebugConsole() {
	if ms.debugWindow != nil {
		return
	}
	w := ms.fyneApp.NewWindow("Minesport — Debug Console")
	copyBtn := widget.NewButtonWithIcon("Copy all", theme.ContentCopyIcon(), func() { w.Clipboard().SetContent(ms.logContent.Text); ms.statusLabel.SetText("Debug log copied") })
	clearBtn := widget.NewButtonWithIcon("Clear", theme.DeleteIcon(), func() { ms.mu.Lock(); ms.logContent.SetText(""); ms.mu.Unlock() })
	openLogsBtn := widget.NewButtonWithIcon("Open log folder", theme.FolderOpenIcon(), func() {
		if ms.diagnosticsLogPath != "" {
			_ = openPath(filepath.Dir(ms.diagnosticsLogPath))
		}
	})
	if ms.diagnosticsLogPath == "" {
		openLogsBtn.Disable()
	}
	pathLabel := widget.NewLabel(ms.diagnosticsLogPath)
	pathLabel.Truncation = fyne.TextTruncateEllipsis
	toolbar := container.NewBorder(nil, nil, container.NewHBox(copyBtn, clearBtn, openLogsBtn), nil, pathLabel)
	w.SetContent(container.NewBorder(container.NewPadded(toolbar), nil, nil, nil, container.NewPadded(ms.logScroll)))
	w.Resize(fyne.NewSize(760, 440))
	ms.debugWindow = w
	w.Show()
}
func (ms *MinesportApp) closeDebugConsole() {
	if ms.debugWindow != nil {
		ms.debugWindow.Close()
		ms.debugWindow = nil
	}
}
func (ms *MinesportApp) syncBubblePreview() {
	if ms.worldMap == nil {
		return
	}
	ms.worldMap.SetBubbleCenter(ms.centerX.Int(0), ms.centerZ.Int(0))
	ms.worldMap.SetBubbleRadius(ms.radiusX.Int(32), ms.radiusZ.Int(32))
}
func (ms *MinesportApp) detectWorldMeta(path string) string {
	for _, l := range launcher.DiscoverAll() {
		for _, i := range launcher.DiscoverInstances(l) {
			if strings.HasPrefix(path, i.MinecraftDir) {
				ms.mcVersion = i.Version
				ms.loaderType = string(i.Loader)
				poly := ""
				if i.HasPolymer() {
					poly = " · Polymer"
				}
				return fmt.Sprintf("MC %s · %s%s", i.Version, i.Loader, poly)
			}
		}
	}
	return "Minecraft world"
}

func (ms *MinesportApp) makeEntry(value string) *StepperEntry { return NewStepperEntry(value) }
func (ms *MinesportApp) intEntry(e *StepperEntry, fallback int) int {
	if e == nil {
		return fallback
	}
	return e.Int(fallback)
}
