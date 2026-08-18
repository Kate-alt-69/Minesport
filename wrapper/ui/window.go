package ui

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/ipc"
	"github.com/kastrick/minesport/launcher"
	_ "image/png"
)

// ── App state ──────────────────────────────────────────────────────────────────

type MinesportApp struct {
	window    fyne.Window
	fyneApp   fyne.App
	engine    *ipc.Engine
	mu        sync.Mutex

	settings Settings

	// Debug console — a separate window, only created/shown when
	// settings.DebugMode is on. nil means it's currently closed.
	debugWindow fyne.Window

	// 3D preview viewer — a separate subprocess (see viewer/window.go's
	// doc comment for why), nil when not open.
	viewerSession *ViewerSession

	// Set by a "Joined Blocks" selection from the 3D viewer — an exact
	// block set that isn't representable as a box or ellipsoid. Cleared
	// automatically if the user edits the box/bubble fields by hand
	// afterward (see suppressSelectionClear).
	customSelectionFile    string
	customSelectionCount   int
	suppressSelectionClear bool

	// World state
	worldPath   string
	worldName   string
	mcVersion   string
	loaderType  string
	modsPath    string
	outputPath  string

	// UI components — sidebar
	worldNameLabel   *widget.Label
	worldMetaLabel   *widget.Label
	formatSelect     *widget.Select
	modeSelect       *widget.Select
	minXEntry        *widget.Entry
	maxXEntry        *widget.Entry
	minYEntry        *widget.Entry
	maxYEntry        *widget.Entry
	minZEntry        *widget.Entry
	maxZEntry        *widget.Entry
	outputLabel      *widget.Label
	exportBtn        *widget.Button
	autoDetectBtn    *widget.Button
	optimizeCheck    *widget.Check
	optimizeHint     *widget.Label

	// UI components — selection mode (box vs. bubble)
	selectionModeSelect *widget.Select
	boxCoordGroup       *fyne.Container
	bubbleCoordGroup    *fyne.Container
	centerXEntry        *widget.Entry
	centerYEntry        *widget.Entry
	centerZEntry        *widget.Entry
	radiusXEntry        *widget.Entry
	radiusYEntry        *widget.Entry
	radiusZEntry        *widget.Entry

	// UI components — main area
	worldMap        *WorldMap
	logContent      *widget.Label
	logScroll       *container.Scroll
	progressBar     *widget.ProgressBar
	statusLabel     *widget.Label // dedicated export-state text
	stateIcon       *widget.Icon  // ready/running/done/error indicator
	cursorLabel     *widget.Label // world X/Z under the mouse — separate from export state
	metaHUD         *widget.Label // bottom-right corner: selection size / block+vertex counts
	viewToggle2D    *widget.Button
	viewToggle3D    *widget.Button
	fitBtn          *widget.Button
	settingsBtn     *widget.Button
}

// ── Entry point ───────────────────────────────────────────────────────────────

func Run(jarPath string) {
	a := app.NewWithID("kastrick.dev.minesport")
	w := a.NewWindow("Minesport — by Kastrick")
	w.Resize(fyne.NewSize(1100, 680))
	w.SetMaster()

	ms := &MinesportApp{window: w, fyneApp: a}
	ms.settings = LoadSettings()
	ms.engine = ipc.NewEngine(jarPath)
	w.SetContent(ms.buildUI())

	if ms.settings.DebugMode {
		ms.openDebugConsole()
	}

	ms.engine.OnLog = func(msg string) { ms.appendLog(msg) }
	ms.engine.OnProgress = func(pct int, msg string) {
		ms.progressBar.SetValue(float64(pct) / 100.0)
		ms.statusLabel.SetText(msg)
		ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	}
	ms.engine.OnDone = func(resp ipc.Response) {
		ms.progressBar.SetValue(1.0)
		ms.statusLabel.SetText("Done!")
		ms.stateIcon.SetResource(theme.ConfirmIcon())
		ms.appendLog(fmt.Sprintf("Export complete → %s (%d blocks, %d faces, ≤%d vertices)",
			resp.Output, resp.BlockCount, resp.QuadCount, resp.VertexCount))
		ms.exportBtn.Enable()

		hudText := fmt.Sprintf("%s blocks · %s faces · ≤%s verts",
			formatCount(resp.BlockCount), formatCount(resp.QuadCount), formatCount(resp.VertexCount))
		ms.updateMetaHUD(hudText)

		dialog.ShowInformation("Export complete", fmt.Sprintf(
			"Saved to:\n%s\n\n%s blocks · %s faces · up to %s vertices",
			resp.Output, formatCount(resp.BlockCount), formatCount(resp.QuadCount), formatCount(resp.VertexCount),
		), w)
	}
	ms.engine.OnError = func(msg string) {
		ms.appendLog("Error: " + msg)
		ms.statusLabel.SetText("Failed")
		ms.stateIcon.SetResource(theme.ErrorIcon())
		ms.exportBtn.Enable()
		dialog.ShowError(fmt.Errorf("%s", msg), w)
	}

	if jarPath == "" {
		ms.appendLog("Engine jar not found. Export is disabled.")
		ms.statusLabel.SetText("Engine jar not found")
	} else if err := ms.engine.Start(jarPath); err != nil {
		dialog.ShowError(fmt.Errorf("Engine failed to start: %s", err), w)
	} else {
		ms.appendLog("Engine ready.")
	}

	w.ShowAndRun()
}

// ── UI builder ─────────────────────────────────────────────────────────────────

func (ms *MinesportApp) buildUI() fyne.CanvasObject {
	sidebar := ms.buildSidebar()
	mainArea := ms.buildMainArea()

	ms.updateMetaHUD(ms.selectionSizeText())

	split := container.NewHSplit(sidebar, mainArea)
	split.SetOffset(0.22)
	return split
}

// ── Sidebar ───────────────────────────────────────────────────────────────────

func (ms *MinesportApp) buildSidebar() fyne.CanvasObject {

	// World card
	ms.worldNameLabel = widget.NewLabel("No world selected")
	ms.worldNameLabel.TextStyle = fyne.TextStyle{Bold: true}
	ms.worldMetaLabel = widget.NewLabel("")
	ms.worldMetaLabel.TextStyle = fyne.TextStyle{Italic: true}

	selectWorldBtn := widget.NewButtonWithIcon("Select World", theme.FolderOpenIcon(), ms.onSelectWorld)
	selectWorldBtn.Alignment = widget.ButtonAlignLeading

	worldCard := widget.NewCard("World", "", container.NewVBox(
		ms.worldNameLabel,
		ms.worldMetaLabel,
		selectWorldBtn,
	))

	// Export settings
	ms.formatSelect = widget.NewSelect([]string{"glTF (recommended)", "OBJ + MTL"}, nil)
	ms.formatSelect.SetSelected("glTF (recommended)")

	ms.modeSelect = widget.NewSelect([]string{"Grouped by type", "Individual blocks", "All merged"}, nil)
	ms.modeSelect.SetSelected("Grouped by type")

	ms.optimizeCheck = widget.NewCheck("Optimize Output", nil)
	ms.optimizeHint = widget.NewLabel("Enable in Settings → Advanced")
	ms.optimizeHint.TextStyle = fyne.TextStyle{Italic: true}
	ms.applyOptimizeGate() // starts disabled unless Settings already has it on

	exportCard := widget.NewCard("Export", "", container.NewVBox(
		widget.NewLabel("Format"),
		ms.formatSelect,
		widget.NewLabel("Mode"),
		ms.modeSelect,
		widget.NewSeparator(),
		ms.optimizeCheck,
		ms.optimizeHint,
	))

	// Region coordinates
	ms.minXEntry = ms.makeEntry("-256")
	ms.maxXEntry = ms.makeEntry("256")
	ms.minYEntry = ms.makeEntry("-64")
	ms.maxYEntry = ms.makeEntry("320")
	ms.minZEntry = ms.makeEntry("-256")
	ms.maxZEntry = ms.makeEntry("256")

	coordGrid := container.NewGridWithColumns(2,
		container.NewVBox(widget.NewLabel("Min X"), ms.minXEntry),
		container.NewVBox(widget.NewLabel("Max X"), ms.maxXEntry),
		container.NewVBox(widget.NewLabel("Min Y"), ms.minYEntry),
		container.NewVBox(widget.NewLabel("Max Y"), ms.maxYEntry),
		container.NewVBox(widget.NewLabel("Min Z"), ms.minZEntry),
		container.NewVBox(widget.NewLabel("Max Z"), ms.maxZEntry),
	)
	ms.boxCoordGroup = container.NewVBox(coordGrid)

	onBoxFieldChanged := func(string) {
		if !ms.suppressSelectionClear {
			ms.customSelectionFile = ""
		}
		ms.updateMetaHUD(ms.selectionSizeText())
	}
	for _, e := range []*widget.Entry{ms.minXEntry, ms.maxXEntry, ms.minYEntry, ms.maxYEntry, ms.minZEntry, ms.maxZEntry} {
		e.OnChanged = onBoxFieldChanged
	}

	// Bubble (center + outward radius) selection — click a point on the map
	// to set the center, then dial in how far outward the selection reaches
	// on each axis. Lets you grab "everything within N blocks of this tree"
	// without hand-computing a bounding box.
	ms.centerXEntry = ms.makeEntry("0")
	ms.centerYEntry = ms.makeEntry("64")
	ms.centerZEntry = ms.makeEntry("0")
	ms.radiusXEntry = ms.makeEntry("32")
	ms.radiusYEntry = ms.makeEntry("32")
	ms.radiusZEntry = ms.makeEntry("32")

	onBubbleFieldChanged := func(string) {
		ms.syncBubblePreview()
		if !ms.suppressSelectionClear {
			ms.customSelectionFile = ""
		}
		ms.updateMetaHUD(ms.selectionSizeText())
	}
	ms.centerXEntry.OnChanged = onBubbleFieldChanged
	ms.centerYEntry.OnChanged = onBubbleFieldChanged
	ms.centerZEntry.OnChanged = onBubbleFieldChanged
	ms.radiusXEntry.OnChanged = onBubbleFieldChanged
	ms.radiusYEntry.OnChanged = onBubbleFieldChanged
	ms.radiusZEntry.OnChanged = onBubbleFieldChanged

	bubbleHint := widget.NewLabel("Click the map to set the center point")
	bubbleHint.TextStyle = fyne.TextStyle{Italic: true}

	bubbleGrid := container.NewGridWithColumns(2,
		container.NewVBox(widget.NewLabel("Center X"), ms.centerXEntry),
		container.NewVBox(widget.NewLabel("Radius X"), ms.radiusXEntry),
		container.NewVBox(widget.NewLabel("Center Y"), ms.centerYEntry),
		container.NewVBox(widget.NewLabel("Radius Y"), ms.radiusYEntry),
		container.NewVBox(widget.NewLabel("Center Z"), ms.centerZEntry),
		container.NewVBox(widget.NewLabel("Radius Z"), ms.radiusZEntry),
	)
	ms.bubbleCoordGroup = container.NewVBox(bubbleHint, bubbleGrid)
	ms.bubbleCoordGroup.Hide()

	ms.selectionModeSelect = widget.NewSelect([]string{"Box (min/max)", "Bubble (center + radius)"}, func(choice string) {
		bubble := choice == "Bubble (center + radius)"
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
		if !ms.suppressSelectionClear {
			ms.customSelectionFile = ""
		}
		ms.updateMetaHUD(ms.selectionSizeText())
	})
	ms.selectionModeSelect.SetSelected("Box (min/max)")

	ms.autoDetectBtn = widget.NewButtonWithIcon("Auto-detect bounds", theme.SearchIcon(), ms.onAutoDetect)
	ms.autoDetectBtn.Disable()

	regionCard := widget.NewCard("Region", "", container.NewVBox(
		ms.selectionModeSelect,
		ms.boxCoordGroup,
		ms.bubbleCoordGroup,
		ms.autoDetectBtn,
	))

	// Output folder
	ms.outputLabel = widget.NewLabel("~/Minesport_Exports")
	ms.outputLabel.Truncation = fyne.TextTruncateEllipsis

	outputBtn := widget.NewButtonWithIcon("Change folder", theme.FolderIcon(), ms.onSelectOutput)
	outputBtn.Alignment = widget.ButtonAlignLeading

	outputCard := widget.NewCard("Output", "", container.NewVBox(ms.outputLabel, outputBtn))

	// Export button
	ms.exportBtn = widget.NewButtonWithIcon("Export", theme.DownloadIcon(), ms.onExport)
	ms.exportBtn.Importance = widget.HighImportance
	ms.exportBtn.Disable()

	// Assemble sidebar — Cards give each section a clear visual boundary
	// instead of stacked labels/separators, and consistent spacing between
	// them instead of the mixed rhythm that grows organically otherwise.
	sidebar := container.NewVBox(
		worldCard,
		exportCard,
		regionCard,
		outputCard,
		container.NewPadded(ms.exportBtn),
	)

	scroll := container.NewVScroll(container.NewPadded(sidebar))
	return scroll
}

// applyOptimizeGate enables/disables the sidebar's Optimize Output checkbox
// (and swaps the hint text) to match the global Settings toggle. Called at
// sidebar build time and again whenever Settings are saved.
func (ms *MinesportApp) applyOptimizeGate() {
	if ms.optimizeCheck == nil {
		return
	}
	if ms.settings.OptimizeOutputEnabled {
		ms.optimizeCheck.Enable()
		ms.optimizeHint.Hide()
	} else {
		ms.optimizeCheck.SetChecked(false)
		ms.optimizeCheck.Disable()
		ms.optimizeHint.Show()
	}
}

// ── Main area ─────────────────────────────────────────────────────────────────

func (ms *MinesportApp) buildMainArea() fyne.CanvasObject {

	// View toggle toolbar
	ms.viewToggle2D = widget.NewButtonWithIcon("2D", theme.GridIcon(), func() {
		ms.worldMap.SetMode2D()
	})
	ms.viewToggle2D.Importance = widget.HighImportance

	// "3D" opens the real 3D preview viewer (a separate process — see
	// viewer/window.go's doc comment for why) rather than toggling a mode
	// on the map widget the way "2D" does. It used to switch the map into
	// a flat pseudo-isometric render; that's retired now that there's an
	// actual navigable 3D view.
	ms.viewToggle3D = widget.NewButtonWithIcon("3D", theme.ViewFullScreenIcon(), ms.onExplore3D)
	ms.viewToggle3D.Disable() // enabled once a world is selected

	ms.fitBtn = widget.NewButtonWithIcon("Fit world", theme.ZoomFitIcon(), func() {
		ms.worldMap.FitToWindow()
	})

	ms.settingsBtn = widget.NewButtonWithIcon("Settings", theme.SettingsIcon(), ms.onOpenSettings)

	hintDrag := widget.NewLabel("drag: select")
	hintPan := widget.NewLabel("RMB: pan")
	hintZoom := widget.NewLabel("scroll: zoom")
	for _, l := range []*widget.Label{hintDrag, hintPan, hintZoom} {
		l.TextStyle = fyne.TextStyle{Italic: true}
	}

	toolbar := container.NewBorder(nil, nil,
		container.NewHBox(ms.viewToggle2D, ms.viewToggle3D),
		container.NewHBox(ms.fitBtn, ms.settingsBtn),
		container.NewHBox(hintDrag, hintPan, hintZoom),
	)

	// World map widget
	ms.worldMap = NewWorldMap()
	ms.worldMap.OnSelectionChanged = func(minX, minZ, maxX, maxZ int) {
		ms.minXEntry.SetText(fmt.Sprintf("%d", minX))
		ms.minZEntry.SetText(fmt.Sprintf("%d", minZ))
		ms.maxXEntry.SetText(fmt.Sprintf("%d", maxX))
		ms.maxZEntry.SetText(fmt.Sprintf("%d", maxZ))
		ms.appendLog(fmt.Sprintf("Selection: X[%d→%d] Z[%d→%d]", minX, maxX, minZ, maxZ))
	}
	ms.worldMap.OnCursorMoved = func(worldX, worldZ int) {
		ms.cursorLabel.SetText(fmt.Sprintf("X: %d  Z: %d", worldX, worldZ))
	}
	ms.worldMap.OnCenterPicked = func(worldX, worldZ int) {
		ms.centerXEntry.SetText(fmt.Sprintf("%d", worldX))
		ms.centerZEntry.SetText(fmt.Sprintf("%d", worldZ))
		ms.appendLog(fmt.Sprintf("Bubble center set: X=%d Z=%d", worldX, worldZ))
	}

	mapArea := container.NewStack(
		ms.worldMap,
		container.NewVBox(
			layout.NewSpacer(),
			container.NewHBox(layout.NewSpacer(), ms.buildMetaHUD()),
		),
	)

	// Log content is captured continuously (appendLog always writes here)
	// but is only ever SHOWN inside the separate debug console window —
	// see openDebugConsole(). The main window stays clean by default.
	ms.logContent = widget.NewLabel("")
	ms.logContent.TextStyle = fyne.TextStyle{Monospace: true}
	ms.logContent.Wrapping = fyne.TextWrapWord
	ms.logScroll = container.NewVScroll(ms.logContent)
	ms.logScroll.SetMinSize(fyne.NewSize(0, 90))

	// Export-state row: an icon + text that always shows what's actually
	// happening right now (ready / running phase / done with stats / failed)
	// — previously this label doubled as the cursor-position readout too,
	// so hovering the map would silently blow away whatever export status
	// was showing. Cursor position now lives in its own label.
	ms.progressBar = widget.NewProgressBar()
	ms.stateIcon = widget.NewIcon(theme.InfoIcon())
	ms.statusLabel = widget.NewLabel("Ready")
	ms.statusLabel.TextStyle = fyne.TextStyle{Italic: true}
	ms.cursorLabel = widget.NewLabel("")
	ms.cursorLabel.TextStyle = fyne.TextStyle{Italic: true}

	stateRow := container.NewBorder(nil, nil,
		container.NewHBox(ms.stateIcon, ms.statusLabel),
		ms.cursorLabel,
		ms.progressBar,
	)

	// Assemble main area — no console/log panel here anymore; just the
	// map and the export-state row. Enable Debug mode in Settings to see
	// engine output, in its own window.
	return container.NewBorder(
		toolbar,
		stateRow,
		nil, nil,
		mapArea,
	)
}

// buildMetaHUD creates the bottom-right corner overlay: selection size
// while you're setting one up, then real block/face/vertex counts once a
// listBlocks or export actually runs — the "for 3D artists" readout.
func (ms *MinesportApp) buildMetaHUD() fyne.CanvasObject {
	backdrop := canvas.NewRectangle(color.NRGBA{R: 20, G: 20, B: 26, A: 190})
	backdrop.CornerRadius = 4

	ms.metaHUD = widget.NewLabel("")
	ms.metaHUD.TextStyle = fyne.TextStyle{Monospace: true}
	ms.metaHUD.Alignment = fyne.TextAlignTrailing

	return container.NewStack(backdrop, container.NewPadded(ms.metaHUD))
}

// updateMetaHUD refreshes the corner readout. Called whenever the
// selection changes (live estimate) and whenever real counts come back
// from the engine (listBlocks / export done).
func (ms *MinesportApp) updateMetaHUD(text string) {
	if ms.metaHUD == nil {
		return
	}
	ms.metaHUD.SetText(text)
}

// selectionSizeText computes the current selection's block-space
// dimensions from whichever mode (box/bubble) is active — cheap, instant,
// no engine round-trip, shown before any real data has been fetched.
func (ms *MinesportApp) selectionSizeText() string {
	var w, h, d int
	if ms.selectionModeSelect != nil && ms.selectionModeSelect.Selected == "Bubble (center + radius)" {
		w = 2 * ms.intEntry(ms.radiusXEntry, 32)
		h = 2 * ms.intEntry(ms.radiusYEntry, 32)
		d = 2 * ms.intEntry(ms.radiusZEntry, 32)
	} else {
		w = ms.intEntry(ms.maxXEntry, 256) - ms.intEntry(ms.minXEntry, -256)
		h = ms.intEntry(ms.maxYEntry, 320) - ms.intEntry(ms.minYEntry, -64)
		d = ms.intEntry(ms.maxZEntry, 256) - ms.intEntry(ms.minZEntry, -256)
	}
	return fmt.Sprintf("selection: %d × %d × %d blocks (up to %s)", w, h, d, formatCount(w*h*d))
}

func formatCount(n int) string {
	if n < 0 {
		n = 0
	}
	s := fmt.Sprintf("%d", n)
	var out []byte
	for i, c := range []byte(s) {
		if i > 0 && (len(s)-i)%3 == 0 {
			out = append(out, ',')
		}
		out = append(out, c)
	}
	return string(out)
}

// ── Actions ───────────────────────────────────────────────────────────────────

func (ms *MinesportApp) onSelectWorld() {
	ShowWorldPicker(ms.window, func(worldPath, modsPath string) {
		ms.worldPath = worldPath
		ms.modsPath = modsPath

		name := filepath.Base(worldPath)
		ms.worldName = name
		ms.worldNameLabel.SetText(name)

		// Detect version + loader from instance info
		meta := ms.detectWorldMeta(worldPath)
		ms.worldMetaLabel.SetText(meta)

		if ms.outputPath == "" {
			home, _ := os.UserHomeDir()
			ms.outputPath = filepath.Join(home, "Minesport_Exports")
			ms.outputLabel.SetText(ms.outputPath)
		}

		ms.exportBtn.Enable()
		ms.autoDetectBtn.Enable()
		ms.viewToggle3D.Enable()
		ms.appendLog("World selected: " + name)
		ms.appendLog("Output: " + ms.outputPath)

		go ms.generateHeightmap(worldPath)
	})
}

func (ms *MinesportApp) onSelectOutput() {
	go func() {
		folder := nativeOpenFolder("Select Output Folder")
		if folder == "" {
			return
		}
		ms.outputPath = folder
		ms.outputLabel.SetText(folder)
		ms.appendLog("Output folder: " + folder)
	}()
}

func (ms *MinesportApp) onAutoDetect() {
	if ms.worldPath == "" {
		return
	}
	ms.appendLog("Auto-detecting world bounds...")
	go func() {
		resp, err := ms.engine.SendCommand(map[string]interface{}{
			"command":   "heightmap",
			"worldPath": ms.worldPath,
			"scale":     1,
		})
		if err != nil || resp == nil || resp.Type != "heightmap" {
			return
		}
		// Set bounds from actual world data
		ms.minXEntry.SetText(fmt.Sprintf("%d", resp.MinX))
		ms.maxXEntry.SetText(fmt.Sprintf("%d", resp.MaxX))
		ms.minZEntry.SetText(fmt.Sprintf("%d", resp.MinZ))
		ms.maxZEntry.SetText(fmt.Sprintf("%d", resp.MaxZ))
		ms.appendLog(fmt.Sprintf("Auto-detected: X[%d→%d] Z[%d→%d]",
			resp.MinX, resp.MaxX, resp.MinZ, resp.MaxZ))
	}()
}

func (ms *MinesportApp) onExport() {
	if ms.worldPath == "" {
		dialog.ShowError(fmt.Errorf("No world selected"), ms.window)
		return
	}

	ms.exportBtn.Disable()
	ms.progressBar.SetValue(0)
	ms.statusLabel.SetText("Exporting...")
	ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	ms.appendLog("─── Starting export ───")

	format := "gltf"
	if strings.Contains(ms.formatSelect.Selected, "OBJ") {
		format = "obj"
	}
	exportMode := "grouped"
	switch ms.modeSelect.Selected {
	case "Individual blocks":
		exportMode = "individual"
	case "All merged":
		exportMode = "merged"
	}

	outFile := filepath.Join(ms.outputPath, ms.worldName+"_export")
	if format == "gltf" {
		outFile += ".gltf"
	} else {
		outFile += ".obj"
	}

	params := ipc.ExportParams{
		WorldPath:  ms.worldPath,
		OutputPath: outFile,
		Format:     format,
		ExportMode: exportMode,
	}

	if ms.selectionModeSelect.Selected == "Bubble (center + radius)" {
		cx := ms.intEntry(ms.centerXEntry, 0)
		cy := ms.intEntry(ms.centerYEntry, 64)
		cz := ms.intEntry(ms.centerZEntry, 0)
		rx := ms.intEntry(ms.radiusXEntry, 32)
		ry := ms.intEntry(ms.radiusYEntry, 32)
		rz := ms.intEntry(ms.radiusZEntry, 32)

		// The engine still needs a bounding box to scan region files —
		// the ellipsoid's own bounding box covers it, and the engine
		// narrows down to the true ellipsoid from there.
		params.MinX, params.MaxX = cx-rx, cx+rx
		params.MinY, params.MaxY = cy-ry, cy+ry
		params.MinZ, params.MaxZ = cz-rz, cz+rz
		params.CenterX, params.CenterY, params.CenterZ = &cx, &cy, &cz
		params.RadiusX, params.RadiusY, params.RadiusZ = &rx, &ry, &rz

		ms.appendLog(fmt.Sprintf("Bubble selection: center(%d,%d,%d) radius(%d,%d,%d)", cx, cy, cz, rx, ry, rz))
	} else {
		params.MinX = ms.intEntry(ms.minXEntry, -256)
		params.MinY = ms.intEntry(ms.minYEntry, -64)
		params.MinZ = ms.intEntry(ms.minZEntry, -256)
		params.MaxX = ms.intEntry(ms.maxXEntry, 256)
		params.MaxY = ms.intEntry(ms.maxYEntry, 320)
		params.MaxZ = ms.intEntry(ms.maxZEntry, 256)
	}

	options := map[string]string{}
	if ms.optimizeCheck != nil && ms.optimizeCheck.Checked {
		options["optimize"] = "true"
		ms.appendLog("Optimize Output: culling hidden faces, welding vertices (experimental)")
	}
	if ms.customSelectionFile != "" {
		options["customSelectionFile"] = ms.customSelectionFile
		ms.appendLog(fmt.Sprintf("Using exact 3D selection: %s blocks (narrowed from the box above)", formatCount(ms.customSelectionCount)))
	}
	if len(ms.settings.ResourcePackPaths) > 0 {
		options["resourcePacks"] = PathListString(ms.settings.ResourcePackPaths)
	}
	if len(ms.settings.DataPackPaths) > 0 {
		options["dataPacks"] = PathListString(ms.settings.DataPackPaths)
	}
	if len(options) > 0 {
		params.Options = options
	}

	err := ms.engine.Export(params)
	if err != nil {
		ms.appendLog("Export send failed: " + err.Error())
		ms.exportBtn.Enable()
	}
}

// ── Heightmap ─────────────────────────────────────────────────────────────────

func (ms *MinesportApp) generateHeightmap(worldFolder string) {
	ms.appendLog("Generating world map (1:1)...")
	ms.progressBar.SetValue(0.1)
	ms.statusLabel.SetText("Loading map...")

	resp, err := ms.engine.SendCommand(map[string]interface{}{
		"command":   "heightmap",
		"worldPath": worldFolder,
		"scale":     1, // 1:1 — every pixel = 1 block
	})
	if err != nil || resp == nil || resp.Type != "heightmap" || resp.Image == "" {
		ms.appendLog("[WARN] Heightmap not available")
		ms.progressBar.SetValue(0)
		ms.statusLabel.SetText("Ready")
		return
	}

	imgBytes, err := base64.StdEncoding.DecodeString(resp.Image)
	if err != nil {
		ms.appendLog("[WARN] Heightmap decode failed: " + err.Error())
		return
	}

	img, _, err := image.Decode(bytes.NewReader(imgBytes))
	if err != nil {
		ms.appendLog("[WARN] Heightmap parse failed: " + err.Error())
		return
	}

	rgba := image.NewRGBA(img.Bounds())
	for y := img.Bounds().Min.Y; y < img.Bounds().Max.Y; y++ {
		for x := img.Bounds().Min.X; x < img.Bounds().Max.X; x++ {
			rgba.Set(x, y, img.At(x, y))
		}
	}

	ms.worldMap.LoadHeightmap(rgba, resp.MinX, resp.MinZ, resp.MaxX, resp.MaxZ)
	ms.worldMap.FitToWindow()

	ms.progressBar.SetValue(0)
	ms.statusLabel.SetText("Ready")
	ms.appendLog(fmt.Sprintf("Map ready: X[%d→%d] Z[%d→%d] — %dx%d px",
		resp.MinX, resp.MaxX, resp.MinZ, resp.MaxZ,
		img.Bounds().Dx(), img.Bounds().Dy()))

	// Set auto-bounds from map
	ms.minXEntry.SetText(fmt.Sprintf("%d", resp.MinX))
	ms.maxXEntry.SetText(fmt.Sprintf("%d", resp.MaxX))
	ms.minZEntry.SetText(fmt.Sprintf("%d", resp.MinZ))
	ms.maxZEntry.SetText(fmt.Sprintf("%d", resp.MaxZ))
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func (ms *MinesportApp) appendLog(msg string) {
	ms.mu.Lock()
	current := ms.logContent.Text
	ms.mu.Unlock()

	lines := strings.Split(current, "\n")
	if len(lines) > 200 {
		lines = lines[len(lines)-200:]
	}
	lines = append(lines, msg)
	next := strings.Join(lines, "\n")

	ms.logContent.SetText(next)
	ms.logScroll.ScrollToBottom()
}

// ── Settings ──────────────────────────────────────────────────────────────────

func (ms *MinesportApp) onOpenSettings() {
	ShowSettingsDialog(ms.window, ms.settings, ms.applySettings)
}

func (ms *MinesportApp) applySettings(s Settings) {
	wasDebug := ms.settings.DebugMode
	ms.settings = s

	if err := s.Save(); err != nil {
		ms.appendLog("[WARN] Failed to save settings: " + err.Error())
	}

	if s.DebugMode && !wasDebug {
		ms.openDebugConsole()
	} else if !s.DebugMode && wasDebug {
		ms.closeDebugConsole()
	}

	ms.applyOptimizeGate()
	ms.appendLog("Settings updated.")
}

// openDebugConsole shows the engine log in its own window. The log content
// widget already exists and has been accumulating messages the whole time
// (appendLog never stops writing to it) — this just makes it visible.
func (ms *MinesportApp) openDebugConsole() {
	if ms.debugWindow != nil {
		ms.debugWindow.RequestFocus()
		return
	}
	dw := ms.fyneApp.NewWindow("Minesport — Debug Console")
	dw.SetContent(container.NewPadded(ms.logScroll))
	dw.Resize(fyne.NewSize(640, 360))
	dw.SetOnClosed(func() {
		ms.debugWindow = nil
		// Closing the console window doesn't silently flip Debug mode off
		// behind the user's back — that's still controlled from Settings.
	})
	ms.debugWindow = dw
	dw.Show()
}

func (ms *MinesportApp) closeDebugConsole() {
	if ms.debugWindow != nil {
		ms.debugWindow.Close()
		ms.debugWindow = nil
	}
}

// syncBubblePreview pushes the current center/radius sidebar fields into
// the map's preview rectangle. Cheap no-op if a field is invalid/empty —
// entries mid-edit are common while typing.
func (ms *MinesportApp) syncBubblePreview() {
	if ms.worldMap == nil {
		return
	}
	cx := ms.intEntry(ms.centerXEntry, 0)
	cz := ms.intEntry(ms.centerZEntry, 0)
	rx := ms.intEntry(ms.radiusXEntry, 32)
	rz := ms.intEntry(ms.radiusZEntry, 32)
	ms.worldMap.SetBubbleCenter(cx, cz)
	ms.worldMap.SetBubbleRadius(rx, rz)
}

func (ms *MinesportApp) makeEntry(defaultVal string) *widget.Entry {
	e := widget.NewEntry()
	e.SetText(defaultVal)
	return e
}

func (ms *MinesportApp) intEntry(e *widget.Entry, fallback int) int {
	v, err := strconv.Atoi(strings.TrimSpace(e.Text))
	if err != nil {
		return fallback
	}
	return v
}

func (ms *MinesportApp) detectWorldMeta(worldPath string) string {
	// Find which instance/launcher this world belongs to
	launchers := launcher.DiscoverAll()
	for _, l := range launchers {
		instances := launcher.DiscoverInstances(l)
		for _, inst := range instances {
			if strings.HasPrefix(worldPath, inst.MinecraftDir) {
				ms.mcVersion = inst.Version
				ms.loaderType = string(inst.Loader)
				polymerNote := ""
				if inst.HasPolymer() {
					polymerNote = " · Polymer"
				}
				return fmt.Sprintf("MC %s · %s%s", inst.Version, inst.Loader, polymerNote)
			}
		}
	}
	return ""
}

func (ms *MinesportApp) setProgress(pct int) {
	ms.progressBar.SetValue(float64(pct) / 100.0)
}
