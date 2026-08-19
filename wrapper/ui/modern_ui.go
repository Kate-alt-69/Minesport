package ui

import (
    "fmt"
    "path/filepath"
    "strings"

    "fyne.io/fyne/v2"
    "fyne.io/fyne/v2/app"
    "fyne.io/fyne/v2/container"
    "fyne.io/fyne/v2/dialog"
    "fyne.io/fyne/v2/theme"
    "fyne.io/fyne/v2/widget"

    "github.com/kastrick/minesport/ipc"
)

// RunModern is the production UI entrypoint. It reuses the stable Minesport
// inspector/map UI, but replaces the legacy export window with the newer
// minimal progress and completion flow.
func RunModern(jarPath string) {
    a := app.NewWithID("kastrick.dev.minesport")
    w := a.NewWindow("Minesport — by Kastrick")
    w.Resize(fyne.NewSize(1180, 740))
    w.SetMaster()

    ms := &MinesportApp{window: w, fyneApp: a}
    ms.settings = LoadSettings()
    ms.engine = ipc.NewEngine(jarPath)
    w.SetContent(ms.buildUI())

    ms.exportBtn.OnTapped = ms.onExportModern

    if ms.settings.DebugMode {
        ms.openDebugConsole()
    }

    ms.engine.OnLog = func(msg string) {
        ms.appendLog(msg)
    }
    ms.engine.OnProgress = func(pct int, msg string) {
        if ms.progressBar != nil {
            ms.progressBar.SetValue(float64(pct) / 100)
        }
        if ms.statusLabel != nil {
            ms.statusLabel.SetText(msg)
        }
        if ms.stateIcon != nil {
            ms.stateIcon.SetResource(theme.ViewRefreshIcon())
        }
        ms.updateModernExportProgress(pct, msg)
    }
    ms.engine.OnDone = func(resp ipc.Response) {
        ms.finishModernExport(resp, true, "")
    }
    ms.engine.OnError = func(msg string) {
        ms.finishModernExport(ipc.Response{}, false, msg)
    }

    if jarPath == "" {
        ms.statusLabel.SetText("Engine unavailable")
        ms.exportBtn.Disable()
    } else if err := ms.engine.Start(jarPath); err != nil {
        dialog.ShowError(fmt.Errorf("engine failed to start: %s", err), w)
    } else {
        ms.statusLabel.SetText("Ready")
    }

    w.ShowAndRun()
}

func (ms *MinesportApp) onExportModern() {
    if ms.worldPath == "" {
        dialog.ShowError(fmt.Errorf("no world selected"), ms.window)
        return
    }

    ms.exportBtn.Disable()
    ms.progressBar.SetValue(0)
    ms.statusLabel.SetText("Preparing export…")
    ms.showModernExportProgress()

    format := "gltf"
    if strings.Contains(ms.formatSelect.Selected, "OBJ") {
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
        OutputPath: filepath.Join(ms.outputPath, ms.worldName+"_export."+format),
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

    options := map[string]string{"faceCulling": "false"}
    if ms.optimizeCheck.Checked {
        options["optimize"] = "true"
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
    p.Options = options

    if err := ms.engine.Export(p); err != nil {
        ms.finishModernExport(ipc.Response{}, false, err.Error())
    }
}

func (ms *MinesportApp) showModernExportProgress() {
    if ms.exportWindow != nil {
        return
    }

    w := ms.fyneApp.NewWindow("Minesport — Exporting")
    w.Resize(fyne.NewSize(520, 230))
    w.SetFixedSize(true)
    w.CenterOnScreen()

    ms.exportWindow = w
    ms.exportTitle = widget.NewLabel("Exporting…")
    ms.exportTitle.TextStyle = fyne.TextStyle{Bold: true}
    ms.exportStage = widget.NewLabel("Preparing export")
    ms.exportDetail = widget.NewLabel("")
    ms.exportBar = widget.NewProgressBar()
    ms.exportStats = widget.NewLabel("")

    card := container.NewVBox(
        ms.exportTitle,
        ms.exportStage,
        ms.exportBar,
        ms.exportDetail,
        ms.exportStats,
    )

    w.SetContent(container.NewPadded(card))
    ms.window.Hide()
    w.Show()
    w.CenterOnScreen()
}

func (ms *MinesportApp) updateModernExportProgress(pct int, msg string) {
    if ms.exportWindow == nil || ms.exportBar == nil {
        return
    }

    ms.exportBar.SetValue(float64(pct) / 100)
    ms.exportStage.SetText(msg)

    blocks := ms.estimateBlocks()
    estimatedBlocks := int(float64(blocks) * float64(pct) / 100)
    verts := estimatedBlocks * 24
    data := estimatedBlocks * 80

    ms.exportDetail.SetText(fmt.Sprintf("Blocks %s / ~%s", formatCount(estimatedBlocks), formatCount(blocks)))
    ms.exportStats.SetText(fmt.Sprintf("Estimated vertices  ~%s\nEstimated geometry  ~%s KB", formatCount(verts), formatCount(data/1024)))
}

func (ms *MinesportApp) finishModernExport(resp ipc.Response, ok bool, msg string) {
    if !ok {
        ms.statusLabel.SetText("Failed")
        ms.stateIcon.SetResource(theme.ErrorIcon())
        if ms.exportWindow != nil {
            ms.exportWindow.Close()
            ms.exportWindow = nil
        }
        ms.window.Show()
        ms.exportBtn.Enable()
        dialog.ShowError(fmt.Errorf("export failed: %s", msg), ms.window)
        return
    }

    ms.progressBar.SetValue(1)
    ms.statusLabel.SetText("Done")
    ms.stateIcon.SetResource(theme.ConfirmIcon())
    ms.updateMetaHUD(fmt.Sprintf("%s blocks · %s faces · ≤%s verts", formatCount(resp.BlockCount), formatCount(resp.QuadCount), formatCount(resp.VertexCount)))

    if ms.exportWindow != nil {
        ms.exportWindow.Close()
        ms.exportWindow = nil
    }

    ms.window.Show()
    ms.window.RequestFocus()
    ms.exportBtn.Enable()

    format := "gltf"
    if strings.Contains(ms.formatSelect.Selected, "OBJ") {
        format = "obj"
    }
    exportedPath := filepath.Join(ms.outputPath, ms.worldName+"_export."+format)
    ms.showModernExportComplete(exportedPath, resp)
}

func (ms *MinesportApp) showModernExportComplete(exportedPath string, resp ipc.Response) {
    pathLabel := widget.NewLabel(exportedPath)
    pathLabel.Truncation = fyne.TextTruncateEllipsis

    summary := widget.NewLabel(fmt.Sprintf(
        "%s blocks · %s faces · ≤%s vertices",
        formatCount(resp.BlockCount),
        formatCount(resp.QuadCount),
        formatCount(resp.VertexCount),
    ))

    openFolder := widget.NewButton("Open folder ▼", nil)
    openFolder.Importance = widget.HighImportance
    openFolder.OnTapped = func() {
        menu := fyne.NewMenu("",
            fyne.NewMenuItem("Open exported folder", func() {
                _ = openPath(filepath.Dir(exportedPath))
            }),
            fyne.NewMenuItem("Open exported file", func() {
                _ = openPath(exportedPath)
            }),
        )
        widget.ShowPopUpMenuAtRelativePosition(menu, ms.window.Canvas(), fyne.NewPos(0, openFolder.Size().Height), openFolder)
    }

    buttons := []fyne.CanvasObject{openFolder}
    addAppButton := func(label, appName string) {
        b := widget.NewButton(label, func() {
            if err := openWithApp(appName, exportedPath); err != nil {
                dialog.ShowError(err, ms.window)
            }
        })
        if !appAvailable(appName) {
            b.Disable()
        }
        buttons = append(buttons, b)
    }

    addAppButton("Open with Blender", "Blender")
    addAppButton("Open with Blockbench", "Blockbench")
    addAppButton("Open with MeshLab", "MeshLab")

    content := container.NewVBox(
        widget.NewLabel("Export complete"),
        summary,
        widget.NewSeparator(),
        widget.NewLabel("Created file"),
        pathLabel,
        widget.NewSeparator(),
        container.NewGridWithColumns(2, buttons...),
    )

    d := dialog.NewCustom("Export complete", "Done", content, ms.window)
    d.Resize(fyne.NewSize(540, 300))
    d.Show()
}
