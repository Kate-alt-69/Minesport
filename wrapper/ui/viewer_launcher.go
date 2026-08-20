package ui

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"

	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
	"github.com/kastrick/minesport/ipc"
	"github.com/kastrick/minesport/processutil"
	"github.com/kastrick/minesport/viewer"
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
	OnClosed      func()
}

func LaunchViewer(exePath, blocksPath string) (*ViewerSession, error) {
	cmd := exec.Command(exePath, "--viewer", blocksPath)
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
func (vs *ViewerSession) Close() { vs.send(map[string]interface{}{"command": "quit"}) }

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
	ms.appendLog("Embedded 3D preview: requesting blocks from engine")
	ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	go func() {
		p := ipc.ListBlocksParams{WorldPath: ms.worldPath}
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
		path, count, err := ms.engine.ListBlocks(p)
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
		blocks, err := viewer.LoadBlocks(path)
		_ = os.Remove(path)
		if err != nil {
			ms.explore3DFailed("Could not load 3D block data: " + err.Error())
			return
		}
		preview, err := NewEmbeddedViewer(blocks)
		if err != nil {
			ms.explore3DFailed(err.Error())
			return
		}
		preview.OnBoxSelected = func(min, max [3]int, count int) { ms.applyBoxSelectionFromViewer(min, max, count) }
		preview.OnHint = func(message string) { ms.statusLabel.SetText(message); ms.updateMetaHUD(message) }
		ms.embeddedViewer = preview
		ms.showEmbedded3D()
		ms.statusLabel.SetText(fmt.Sprintf("3D ready · %s blocks", formatCount(count)))
		ms.stateIcon.SetResource(theme.ConfirmIcon())
		ms.appendLog(fmt.Sprintf("Embedded 3D preview ready: %d blocks", count))
	}()
}

func (ms *MinesportApp) explore3DFailed(msg string) {
	ms.statusLabel.SetText("3D preview failed — see log")
	ms.stateIcon.SetResource(theme.ErrorIcon())
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
	ms.viewHint.SetText("drag orbit · scroll zoom · LMB point A/B · RMB clear")
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
