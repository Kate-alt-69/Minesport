package ui

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	fynedriver "fyne.io/fyne/v2/driver"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
	"github.com/kastrick/minesport/ipc"
	"github.com/kastrick/minesport/processutil"
)

type ViewerEvent struct {
	Type    string   `json:"type"`
	X, Y, Z int      `json:"x"`
	Blocks  [][3]int `json:"blocks"`
	Min     [3]int   `json:"min"`
	Max     [3]int   `json:"max"`
	Count   int      `json:"count"`
	Message string   `json:"message"`
}

type ViewerSession struct {
	cmd   *exec.Cmd
	stdin io.WriteCloser
	mu    sync.Mutex

	stateMu    sync.Mutex
	readySeen  bool
	readyCount int
	closed     bool

	OnReady       func(int)
	OnError       func(string)
	OnPick        func(int, int, int)
	OnSelection   func([][3]int, int)
	OnBoxSelected func([3]int, [3]int, int)
	OnScreenshot  func(string)
	OnClosed      func()
}

func LaunchViewer(exePath, blocksPath string) (*ViewerSession, error) {
	cmd := exec.Command(exePath, "--viewer-embed", blocksPath)
	processutil.HideWindow(cmd)
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		_ = in.Close()
		return nil, err
	}
	if err = cmd.Start(); err != nil {
		_ = in.Close()
		return nil, err
	}
	vs := &ViewerSession{cmd: cmd, stdin: in}
	go vs.readLoop(out)
	go func() {
		_ = cmd.Wait()
		vs.stateMu.Lock()
		vs.closed = true
		cb := vs.OnClosed
		vs.stateMu.Unlock()
		if cb != nil {
			cb()
		}
	}()
	return vs, nil
}

func (vs *ViewerSession) readLoop(stdout io.Reader) {
	s := bufio.NewScanner(stdout)
	s.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for s.Scan() {
		var ev ViewerEvent
		if json.Unmarshal([]byte(s.Text()), &ev) != nil {
			continue
		}
		switch ev.Type {
		case "ready":
			vs.stateMu.Lock()
			vs.readySeen = true
			vs.readyCount = ev.Count
			cb := vs.OnReady
			vs.stateMu.Unlock()
			if cb != nil {
				cb(ev.Count)
			}
		case "error":
			if vs.OnError != nil {
				vs.OnError(ev.Message)
			}
		case "pick":
			if vs.OnPick != nil {
				vs.OnPick(ev.X, ev.Y, ev.Z)
			}
		case "point1Set":
			// Axum-style direct box selection uses point A/B inside the viewer;
			// keep this informational rather than opening the legacy popup.
		case "selection":
			if vs.OnSelection != nil {
				vs.OnSelection(ev.Blocks, ev.Count)
			}
		case "boxSelected":
			if vs.OnBoxSelected != nil {
				vs.OnBoxSelected(ev.Min, ev.Max, ev.Count)
			}
		case "screenshot":
			if vs.OnScreenshot != nil {
				vs.OnScreenshot(ev.Message)
			}
		}
	}
}

func (vs *ViewerSession) replayLifecycle() {
	vs.stateMu.Lock()
	readySeen, readyCount, readyCB := vs.readySeen, vs.readyCount, vs.OnReady
	closed, closedCB := vs.closed, vs.OnClosed
	vs.stateMu.Unlock()
	if readySeen && readyCB != nil {
		readyCB(readyCount)
	}
	if closed && closedCB != nil {
		closedCB()
	}
}

func (vs *ViewerSession) send(cmd map[string]interface{}) {
	vs.mu.Lock()
	defer vs.mu.Unlock()
	if vs.stdin == nil {
		return
	}
	b, err := json.Marshal(cmd)
	if err != nil {
		return
	}
	_, _ = fmt.Fprintln(vs.stdin, string(b))
}

func (vs *ViewerSession) FloodFill(x, y, z, power int) {
	vs.send(map[string]interface{}{"command": "floodFill", "x": x, "y": y, "z": z, "power": power})
}
func (vs *ViewerSession) HighlightBox(min, max [3]int) {
	vs.send(map[string]interface{}{"command": "highlightBox", "min": min, "max": max})
}
func (vs *ViewerSession) ClearHighlight() {
	vs.send(map[string]interface{}{"command": "clearHighlight"})
}
func (vs *ViewerSession) Embed(parent uintptr, x, y, width, height int) {
	vs.send(map[string]interface{}{
		"command": "embed", "parent": uint64(parent),
		"x": x, "y": y, "width": width, "height": height,
	})
}
func (vs *ViewerSession) SetRect(x, y, width, height int) {
	vs.send(map[string]interface{}{"command": "embedRect", "x": x, "y": y, "width": width, "height": height})
}
func (vs *ViewerSession) SetVisible(visible bool) {
	vs.send(map[string]interface{}{"command": "visible", "visible": visible})
}
func (vs *ViewerSession) Fit()   { vs.send(map[string]interface{}{"command": "fit"}) }
func (vs *ViewerSession) Close() { vs.send(map[string]interface{}{"command": "quit"}) }

func nativeWindowHandle(window fyne.Window) uintptr {
	native, ok := window.(fynedriver.NativeWindow)
	if !ok {
		return 0
	}
	result := make(chan uintptr, 1)
	native.RunNative(func(context any) {
		var handle uintptr
		switch value := context.(type) {
		case fynedriver.WindowsWindowContext:
			handle = value.HWND
		case fynedriver.X11WindowContext:
			handle = value.WindowHandle
		case fynedriver.WaylandWindowContext:
			handle = value.WaylandSurface
		case fynedriver.MacWindowContext:
			handle = value.NSWindow
		}
		result <- handle
	})
	select {
	case handle := <-result:
		return handle
	case <-time.After(2 * time.Second):
		return 0
	}
}

const maxPaddedPreviewVolume int64 = 2_500_000

func addPreviewContext(p ipc.ListBlocksParams) ipc.ListBlocksParams {
	width := int64(p.MaxX - p.MinX + 1)
	height := int64(p.MaxY - p.MinY + 1)
	depth := int64(p.MaxZ - p.MinZ + 1)
	if width < 1 || height < 1 || depth < 1 {
		return p
	}
	paddedWidth, paddedHeight, paddedDepth := width+64, height+32, depth+64
	if paddedWidth*paddedHeight*paddedDepth > maxPaddedPreviewVolume {
		return p
	}
	p.MinX -= 32
	p.MaxX += 32
	p.MinY -= 16
	p.MaxY += 16
	p.MinZ -= 32
	p.MaxZ += 32
	// The live viewer needs the rectangular surroundings as context. Selection
	// semantics remain in the UI/export request and are highlighted separately.
	p.CenterX, p.CenterY, p.CenterZ = nil, nil, nil
	p.RadiusX, p.RadiusY, p.RadiusZ = nil, nil, nil
	return p
}

func (ms *MinesportApp) onExplore3D() {
	if ms.worldPath == "" {
		dialog.ShowError(fmt.Errorf("select a world first"), ms.window)
		return
	}
	if !ms.isEngineAvailable() {
		ms.handleCoreEngineFailure("3D preview was requested while the core engine was unavailable.")
		return
	}
	if ms.embeddedViewer != nil {
		ms.showEmbedded3D()
		return
	}
	ms.statusLabel.SetText("Loading 3D preview…")
	ms.appendLog("Live 3D renderer: requesting blocks from engine")
	ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	ms.viewToggle3D.Disable()
	go func() {
		p := ipc.ListBlocksParams{WorldPath: ms.worldPath, ModsPath: ms.modsPath, ModLoader: ms.loaderType}
		var selectedMin, selectedMax [3]int
		if ms.selectionModeSelect.Selected == "Bubble selection" {
			cx, cy, cz := ms.centerX.Int(0), ms.centerY.Int(64), ms.centerZ.Int(0)
			rx, ry, rz := ms.radiusX.Int(32), ms.radiusY.Int(32), ms.radiusZ.Int(32)
			p.MinX, p.MaxX = cx-rx, cx+rx
			p.MinY, p.MaxY = cy-ry, cy+ry
			p.MinZ, p.MaxZ = cz-rz, cz+rz
		} else {
			p.MinX, p.MaxX = ms.minXRange.Bounds()
			p.MinY, p.MaxY = ms.minYRange.Bounds()
			p.MinZ, p.MaxZ = ms.minZRange.Bounds()
		}
		selectedMin = [3]int{p.MinX, p.MinY, p.MinZ}
		selectedMax = [3]int{p.MaxX, p.MaxY, p.MaxZ}
		previewParams := addPreviewContext(p)
		if previewParams.MinX != p.MinX || previewParams.MinY != p.MinY || previewParams.MinZ != p.MinZ {
			ms.appendLog(fmt.Sprintf(
				"3D context bounds: X %d..%d, Y %d..%d, Z %d..%d (selection remains highlighted)",
				previewParams.MinX, previewParams.MaxX, previewParams.MinY, previewParams.MaxY, previewParams.MinZ, previewParams.MaxZ,
			))
		}
		path, count, err := ms.engine.ListBlocks(previewParams)
		if err != nil {
			ms.appendLog("3D block request failed: " + err.Error())
			ms.explore3DFailed(err.Error())
			return
		}
		ms.appendLog(fmt.Sprintf("3D block response: %d blocks in %s", count, path))
		if count == 0 {
			ms.explore3DFailed("No solid blocks were found in the current selection.")
			return
		}
		executable, err := os.Executable()
		if err != nil {
			_ = os.Remove(path)
			ms.explore3DFailed("Could not locate the Minesport executable: " + err.Error())
			return
		}
		parentHandle := nativeWindowHandle(ms.window)
		if parentHandle == 0 {
			_ = os.Remove(path)
			ms.explore3DFailed("Could not obtain the Minesport window handle needed to host the live 3D renderer.")
			return
		}
		session, err := LaunchViewer(executable, path)
		if err != nil {
			_ = os.Remove(path)
			ms.explore3DFailed("Could not start the original 3D renderer: " + err.Error())
			return
		}
		preview := NewEmbeddedViewer(session, parentHandle)
		session.OnReady = func(readyCount int) {
			ms.viewerSession = session
			ms.embeddedViewer = preview
			ms.showEmbedded3D()
			ms.statusLabel.SetText(fmt.Sprintf("3D ready · %s blocks", formatCount(readyCount)))
			ms.stateIcon.SetResource(theme.ConfirmIcon())
			ms.viewToggle3D.Enable()
			ms.appendLog(fmt.Sprintf("Original OpenGL 3D renderer embedded: %d blocks", readyCount))
			session.HighlightBox(selectedMin, selectedMax)
		}
		session.OnError = func(message string) {
			ms.appendLog("Live 3D renderer error: " + message)
			ms.explore3DFailed(message)
		}
		session.OnPick = func(x, y, z int) { ms.showSelectionPopup(x, y, z) }
		session.OnBoxSelected = func(min, max [3]int, selectedCount int) {
			ms.applyBoxSelectionFromViewer(min, max, selectedCount)
		}
		session.OnScreenshot = func(path string) {
			ms.statusLabel.SetText("3D screenshot saved")
			ms.appendLog("3D screenshot saved: " + path)
		}
		session.OnClosed = func() {
			_ = os.Remove(path)
			if ms.viewerSession != session {
				return
			}
			ms.viewerSession = nil
			ms.embeddedViewer = nil
			ms.show2DPreview()
			ms.statusLabel.SetText("3D preview closed")
			ms.viewToggle3D.Enable()
			ms.appendLog("Live 3D renderer closed")
		}
		session.replayLifecycle()
	}()
}

func (ms *MinesportApp) explore3DFailed(msg string) {
	ms.statusLabel.SetText("3D preview failed — see log")
	ms.stateIcon.SetResource(theme.ErrorIcon())
	if ms.worldPath != "" && ms.isEngineAvailable() {
		ms.viewToggle3D.Enable()
	}
	ms.showOperationFailure("3D preview failed", msg)
}

func (ms *MinesportApp) showEmbedded3D() {
	if ms.previewHost == nil || ms.embeddedViewer == nil {
		return
	}
	ms.previewHost.RemoveAll()
	ms.previewHost.Add(ms.embeddedViewer)
	ms.previewHost.Refresh()
	ms.embeddedViewer.Show()
	ms.viewToggle3D.Importance = widget.HighImportance
	ms.viewToggle2D.Importance = widget.MediumImportance
	ms.viewHint.SetText("WASD fly · Space/Shift vertical · Ctrl sprint · MMB look · ESC controls")
}

func (ms *MinesportApp) show2DPreview() {
	if ms.previewHost == nil || ms.worldMap == nil {
		return
	}
	if ms.embeddedViewer != nil {
		ms.embeddedViewer.Hide()
	}
	mapArea := container.NewStack(ms.worldMap, container.NewVBox(layout.NewSpacer(), container.NewHBox(layout.NewSpacer(), ms.buildMetaHUD())))
	ms.previewHost.RemoveAll()
	ms.previewHost.Add(mapArea)
	ms.previewHost.Refresh()
	ms.viewToggle2D.Importance = widget.HighImportance
	ms.viewToggle3D.Importance = widget.MediumImportance
	if ms.viewHint != nil {
		ms.viewHint.SetText("LMB select · MMB pan · scroll zoom")
	}
	ms.worldMap.SetMode2D()
}

func (ms *MinesportApp) applyBoxSelectionFromViewer(min, max [3]int, count int) {
	if ms.customSelectionFile != "" {
		_ = os.Remove(ms.customSelectionFile)
	}
	ms.customSelectionFile = ""
	ms.customSelectionCount = 0
	ms.suppressSelectionClear = true
	ms.selectionModeSelect.SetSelected("Box selection")
	ms.minXRange.Front.SetText(fmt.Sprintf("%d", min[0]))
	ms.minXRange.Back.SetText(fmt.Sprintf("%d", max[0]))
	ms.minYRange.Front.SetText(fmt.Sprintf("%d", min[1]))
	ms.minYRange.Back.SetText(fmt.Sprintf("%d", max[1]))
	ms.minZRange.Front.SetText(fmt.Sprintf("%d", min[2]))
	ms.minZRange.Back.SetText(fmt.Sprintf("%d", max[2]))
	ms.suppressSelectionClear = false
	ms.updateMetaHUD(fmt.Sprintf("%s blocks selected · 3D", formatCount(count)))
}
