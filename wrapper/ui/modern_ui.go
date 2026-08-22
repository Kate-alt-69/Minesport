package ui

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/ipc"
)

// RunModern is the production UI entrypoint. Engine lifecycle chatter is kept
// in diagnostics; only user-visible work such as export appears in the compact
// bottom task drawer.
func RunModern(jarPath, diagnosticsLogPath string) {
	a := app.NewWithID("kastrick.dev.minesport")
	w := a.NewWindow("Minesport — by Kastrick")
	w.Resize(fyne.NewSize(1280, 800))
	w.SetMaster()

	ms := &MinesportApp{window: w, fyneApp: a, diagnosticsLogPath: diagnosticsLogPath}
	ms.settings = LoadSettings()
	ms.engine = ipc.NewEngine(jarPath)
	w.SetContent(ms.buildModernUI())
	ms.installWorkbenchEnhancements()
	ms.installViewportShortcuts()
	w.SetCloseIntercept(func() {
		cleanupWorkbenchEnhancements(ms)
		cleanupWorkbenchRuntimeV3(ms)
		workbenchStates.Delete(ms)
		if ms.embeddedViewer != nil {
			ms.embeddedViewer.Close()
		}
		ms.cancelRuntimeModelCacheGeneration()
		ms.engine.Stop()
		w.SetCloseIntercept(nil)
		w.Close()
	})

	ms.exportBtn.OnTapped = ms.onExportModern

	if ms.settings.DebugMode {
		ms.openDebugConsole()
	}

	// Engine resolvers can emit hundreds of lines in a burst. Repainting the
	// entire debug label for every line can starve Fyne's UI/layout locks and
	// make the workbench appear completely frozen even while the engine is idle.
	// Preserve every line, but coalesce visual updates to ~8 Hz.
	engineLogQueue := make(chan string, 4096)
	go func() {
		ticker := time.NewTicker(125 * time.Millisecond)
		defer ticker.Stop()
		pending := make([]string, 0, 128)
		flush := func() {
			if len(pending) == 0 {
				return
			}
			ms.appendLog(strings.Join(pending, "\n"))
			pending = pending[:0]
		}
		for {
			select {
			case msg := <-engineLogQueue:
				pending = append(pending, msg)
				if len(pending) >= 128 {
					flush()
				}
			case <-ticker.C:
				flush()
			}
		}
	}()

	ms.engine.OnLog = func(msg string) {
		engineLogQueue <- msg
	}
	ms.engine.OnProgress = func(pct int, msg string) {
		ms.updateModernExportProgress(pct, msg)
	}
	ms.engine.OnDone = func(resp ipc.Response) {
		ms.finishModernExport(resp, true, "")
	}
	ms.engine.OnError = func(msg string) {
		ms.appendLog("Engine operation failed: " + msg)
		if ms.workbenchExportActive() {
			ms.finishModernExport(ipc.Response{}, false, msg)
			return
		}
		ms.showOperationFailure("Core engine operation failed", msg)
	}
	ms.engine.OnExit = func(msg string) {
		ms.appendLog("Core engine stopped: " + msg)
		ms.handleCoreEngineFailure(msg)
	}
	if diagnosticsLogPath != "" {
		ms.appendLog("Persistent diagnostics log: " + diagnosticsLogPath)
	}

	w.Show()
	if jarPath == "" {
		ms.handleCoreEngineFailure("The bundled engine could not be found.")
	} else if err := ms.engine.Start(jarPath); err != nil {
		ms.handleCoreEngineFailure("The Java engine could not be started: " + err.Error())
	} else {
		go func() {
			if err := ms.engine.Ping(15 * time.Second); err != nil {
				ms.handleCoreEngineFailure("The Java process started but IPC did not become ready: " + err.Error())
				return
			}
			ms.setEngineAvailable(true)
			ms.appendLog("Core engine readiness check passed")
			maybePromptBlenderTranslator(ms)
		}()
	}

	a.Run()
}

func (ms *MinesportApp) buildModernUI() fyne.CanvasObject {
	return ms.buildWorkbenchUIV3()
}

func (ms *MinesportApp) onExportModern() {
	if ms.worldPath == "" {
		dialog.ShowError(fmt.Errorf("no world selected"), ms.window)
		return
	}
	if !ms.isEngineAvailable() {
		ms.handleCoreEngineFailure("Export was requested while the core engine was unavailable.")
		return
	}

	// Fabric exports automatically populate the runtime registry when the exact
	// Minecraft-version/mod-set cache is missing or stale. Manual generation is
	// still available under Settings → Advanced, but it is never a prerequisite
	// the user has to remember before pressing Export.
	if ms.ensureRuntimeModelCacheForExport(ms.onExportModernAfterRuntimeCache) {
		return
	}
	ms.onExportModernAfterRuntimeCache()
}

func (ms *MinesportApp) onExportModernAfterRuntimeCache() {
	if ms.requireBridgeCompatibility(ms.onExportModernAfterRuntimeCache) {
		return
	}

	name := sanitizeExportName(ms.exportNameEntry.Text)
	if name == "" {
		name = sanitizeExportName(ms.worldName)
		if name == "" {
			name = "Minesport_Export"
		}
	}

	extension := ".gltf"
	if strings.Contains(ms.formatSelect.Selected, "OBJ") {
		extension = ".obj"
	}

	if ms.outputPath == "" {
		home, _ := os.UserHomeDir()
		ms.outputPath = filepath.Join(home, "Minesport_Exports")
		ms.outputLabel.SetText(ms.outputPath)
	}
	if err := os.MkdirAll(ms.outputPath, 0o755); err != nil {
		dialog.ShowError(err, ms.window)
		return
	}

	desired := filepath.Join(ms.outputPath, name+extension)
	if !exportFilesExist(desired) {
		ms.startModernExport(desired)
		return
	}

	existing := existingExportFile(desired)
	message := widget.NewLabel(fmt.Sprintf(
		"%s already exists in %s.\n\nDo you want to replace it?",
		filepath.Base(existing),
		filepath.Dir(existing),
	))
	message.Wrapping = fyne.TextWrapWord
	ms.exportBtn.Disable()

	d := dialog.NewCustomConfirm(
		"File already exists",
		"YES",
		"NO",
		container.NewPadded(message),
		func(replace bool) {
			if replace {
				if err := removeExportFiles(desired); err != nil {
					dialog.ShowError(fmt.Errorf("could not replace export: %w", err), ms.window)
					ms.exportBtn.Enable()
					return
				}
				ms.startModernExport(desired)
				return
			}
			ms.startModernExport(nextExportPath(desired))
		},
		ms.window,
	)
	d.Show()
}

func (ms *MinesportApp) startModernExport(outputPath string) {
	ms.exportBtn.Disable()
	ms.showModernExportProgress()
	ms.updateWorkbenchWorldContext()

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

	p := ipc.ExportParams{
		WorldPath:  ms.worldPath,
		ModsPath:   ms.modsPath,
		ModLoader:  normalizedLoader(ms.loaderType),
		OutputPath: outputPath,
		Format:     format,
		ExportMode: mode,
	}

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

	options := map[string]string{
		"faceCulling":      "false",
		"minecraftVersion": normalizedMinecraftVersion(ms.mcVersion),
		"modLoader":        normalizedLoader(ms.loaderType),
	}
	if ms.optimizeCheck.Checked {
		options["optimize"] = "true"
		options["faceCulling"] = "true"
	}
	if ms.settings.OptimizeOutputEnabled {
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
	if ms.settings.BlenderExportEnabled {
		options["blenderExport"] = "true"
		options["blenderAnimationMode"] = blenderAnimationMode(ms.window)
	}
	p.Options = options

	if err := ms.engine.Export(p); err != nil {
		ms.finishModernExport(ipc.Response{}, false, err.Error())
	}
}

func (ms *MinesportApp) showModernExportProgress() {
	ms.setWorkbenchExportActive(true)
	ms.beginWorkbenchTaskV3("EXPORT", "Preparing export…", true)
}

func (ms *MinesportApp) updateModernExportProgress(pct int, msg string) {
	if !ms.workbenchExportActive() {
		return
	}
	blocks := ms.estimateBlocks()
	estimatedBlocks := int(float64(blocks) * float64(pct) / 100)
	verts := estimatedBlocks * 24
	data := estimatedBlocks * 80
	detail := fmt.Sprintf(
		"Blocks %s / ~%s · estimated vertices ~%s · geometry ~%s KB",
		formatCount(estimatedBlocks),
		formatCount(blocks),
		formatCount(verts),
		formatCount(data/1024),
	)
	ms.updateWorkbenchTaskV3(pct, msg, detail)
}

func (ms *MinesportApp) finishModernExport(resp ipc.Response, ok bool, msg string) {
	ms.setWorkbenchExportActive(false)
	ms.exportBtn.Enable()

	if !ok {
		ms.finishWorkbenchTaskV3(false, "Export failed", msg)
		ms.showOperationFailure("Export failed", msg)
		return
	}

	ms.updateMetaHUD(fmt.Sprintf(
		"%s blocks · %s faces · %s verts",
		formatCount(resp.BlockCount),
		formatCount(resp.QuadCount),
		formatCount(resp.VertexCount),
	))
	ms.finishWorkbenchTaskV3(
		true,
		"Export complete",
		fmt.Sprintf("%s blocks · %s faces · %s vertices", formatCount(resp.BlockCount), formatCount(resp.QuadCount), formatCount(resp.VertexCount)),
	)

	exportedPath := resp.Output
	if exportedPath == "" {
		name := sanitizeExportName(ms.exportNameEntry.Text)
		if name == "" {
			name = "Minesport_Export"
		}
		ext := ".gltf"
		if strings.Contains(ms.formatSelect.Selected, "OBJ") {
			ext = ".obj"
		}
		exportedPath = filepath.Join(ms.outputPath, name+ext)
	}

	ms.showModernExportComplete(exportedPath, resp)
}

func (ms *MinesportApp) showModernExportComplete(exportedPath string, resp ipc.Response) {
	pathLabel := widget.NewLabel(exportedPath)
	pathLabel.Truncation = fyne.TextTruncateEllipsis

	summary := widget.NewLabel(fmt.Sprintf(
		"%s blocks · %s faces · %s vertices",
		formatCount(resp.BlockCount),
		formatCount(resp.QuadCount),
		formatCount(resp.VertexCount),
	))

	openFile := widget.NewButtonWithIcon("Open", theme.FolderOpenIcon(), func() {
		if err := revealPath(exportedPath); err != nil {
			dialog.ShowError(err, ms.window)
		}
	})
	openFile.Importance = widget.HighImportance

	openWith := widget.NewButton("Open with… ▼", nil)
	openWith.OnTapped = func() {
		items := make([]*fyne.MenuItem, 0, 3)
		for _, appName := range []string{"Blender", "Blockbench", "MeshLab"} {
			name := appName
			label := name
			if name == "Blender" {
				label = "Blender (Minesport importer)"
			}
			item := fyne.NewMenuItem(label, func() {
				if err := openWithApp(name, exportedPath); err != nil {
					dialog.ShowError(err, ms.window)
				}
			})
			item.Disabled = !appAvailable(name)
			items = append(items, item)
		}
		widget.ShowPopUpMenuAtRelativePosition(
			fyne.NewMenu("Open with", items...),
			ms.window.Canvas(),
			fyne.NewPos(0, openWith.Size().Height),
			openWith,
		)
	}

	content := container.NewVBox(
		summary,
		widget.NewSeparator(),
		widget.NewLabel("Created file"),
		container.NewBorder(nil, nil, nil, openFile, pathLabel),
		widget.NewSeparator(),
		container.NewHBox(openWith),
	)

	d := dialog.NewCustom("Export complete", "Done", content, ms.window)
	d.Resize(fyne.NewSize(560, 230))
	d.Show()
}
