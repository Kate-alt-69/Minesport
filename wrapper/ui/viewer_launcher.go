package ui

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"

	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"

	"github.com/kastrick/minesport/ipc"
)

// ViewerEvent mirrors viewer.outEvent — kept as a separate type rather than
// a shared import since the viewer package is a standalone subprocess, not
// a library the main app links against (see viewer/window.go's doc comment
// on why they're separate processes at all).
type ViewerEvent struct {
	Type    string   `json:"type"`
	X       int      `json:"x"`
	Y       int      `json:"y"`
	Z       int      `json:"z"`
	Blocks  [][3]int `json:"blocks"`
	Min     [3]int   `json:"min"`
	Max     [3]int   `json:"max"`
	Count   int      `json:"count"`
	Message string   `json:"message"`
}

// ViewerSession is one running 3D preview subprocess.
type ViewerSession struct {
	cmd   *exec.Cmd
	stdin io.WriteCloser
	mu    sync.Mutex

	OnReady      func(count int)
	OnError      func(message string)
	OnPick       func(x, y, z int)
	OnSelection  func(blocks [][3]int, count int)
	OnBoxSelected func(min, max [3]int, count int)
	OnClosed     func()
}

// LaunchViewer starts the 3D preview as its own process (exePath re-invoked
// with --viewer) and begins reading its stdout events in the background.
// See viewer/window.go's doc comment for why this has to be a separate
// process rather than an in-process call.
func LaunchViewer(exePath, blocksPath string) (*ViewerSession, error) {
	cmd := exec.Command(exePath, "--viewer", blocksPath)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("stdin pipe: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("stdout pipe: %w", err)
	}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start viewer: %w", err)
	}

	vs := &ViewerSession{cmd: cmd, stdin: stdin}

	go vs.readLoop(stdout)
	go func() {
		cmd.Wait()
		if vs.OnClosed != nil {
			vs.OnClosed()
		}
	}()

	return vs, nil
}

func (vs *ViewerSession) readLoop(stdout io.Reader) {
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)

	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		var ev ViewerEvent
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			continue
		}

		switch ev.Type {
		case "ready":
			if vs.OnReady != nil {
				vs.OnReady(ev.Count)
			}
		case "error":
			if vs.OnError != nil {
				vs.OnError(ev.Message)
			}
		case "pick":
			if vs.OnPick != nil {
				vs.OnPick(ev.X, ev.Y, ev.Z)
			}
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

func (vs *ViewerSession) send(cmd map[string]interface{}) {
	vs.mu.Lock()
	defer vs.mu.Unlock()
	if vs.stdin == nil {
		return
	}
	data, err := json.Marshal(cmd)
	if err != nil {
		return
	}
	fmt.Fprintln(vs.stdin, string(data))
}

// FloodFill asks the viewer to compute and highlight a "Joined Blocks"
// selection starting from the given block, capped at `power` blocks.
// The result comes back asynchronously via OnSelection.
func (vs *ViewerSession) FloodFill(x, y, z, power int) {
	vs.send(map[string]interface{}{
		"command": "floodFill",
		"x": x, "y": y, "z": z,
		"power": power,
	})
}

// HighlightBox previews an area selection in the 3D view without needing
// a round trip — the main app already has enough info (the picked point +
// extents) to compute the box itself.
func (vs *ViewerSession) HighlightBox(min, max [3]int) {
	vs.send(map[string]interface{}{
		"command": "highlightBox",
		"min": min, "max": max,
	})
}

func (vs *ViewerSession) ClearHighlight() {
	vs.send(map[string]interface{}{"command": "clearHighlight"})
}

func (vs *ViewerSession) Close() {
	vs.send(map[string]interface{}{"command": "quit"})
}

// onExplore3D loads the currently selected region's blocks (same bounds
// the sidebar would export) and opens the 3D preview subprocess on them.
func (ms *MinesportApp) onExplore3D() {
	if ms.worldPath == "" {
		dialog.ShowError(fmt.Errorf("select a world first"), ms.window)
		return
	}
	if ms.viewerSession != nil {
		ms.appendLog("3D preview is already open.")
		return
	}

	ms.statusLabel.SetText("Loading 3D preview...")
	ms.stateIcon.SetResource(theme.ViewRefreshIcon())
	ms.appendLog("Loading blocks for 3D preview...")

	go func() {
		params := ipc.ListBlocksParams{WorldPath: ms.worldPath}

		if ms.selectionModeSelect.Selected == "Bubble (center + radius)" {
			cx := ms.intEntry(ms.centerXEntry, 0)
			cy := ms.intEntry(ms.centerYEntry, 64)
			cz := ms.intEntry(ms.centerZEntry, 0)
			rx := ms.intEntry(ms.radiusXEntry, 32)
			ry := ms.intEntry(ms.radiusYEntry, 32)
			rz := ms.intEntry(ms.radiusZEntry, 32)
			params.MinX, params.MaxX = cx-rx, cx+rx
			params.MinY, params.MaxY = cy-ry, cy+ry
			params.MinZ, params.MaxZ = cz-rz, cz+rz
			params.CenterX, params.CenterY, params.CenterZ = &cx, &cy, &cz
			params.RadiusX, params.RadiusY, params.RadiusZ = &rx, &ry, &rz
		} else {
			params.MinX = ms.intEntry(ms.minXEntry, -256)
			params.MinY = ms.intEntry(ms.minYEntry, -64)
			params.MinZ = ms.intEntry(ms.minZEntry, -256)
			params.MaxX = ms.intEntry(ms.maxXEntry, 256)
			params.MaxY = ms.intEntry(ms.maxYEntry, 320)
			params.MaxZ = ms.intEntry(ms.maxZEntry, 256)
		}

		path, count, err := ms.engine.ListBlocks(params)
		if err != nil {
			ms.explore3DFailed("3D preview failed: " + err.Error())
			return
		}
		if count == 0 {
			ms.statusLabel.SetText("Ready")
			ms.stateIcon.SetResource(theme.InfoIcon())
			dialog.ShowInformation("No blocks", "No solid blocks found in the current selection.", ms.window)
			return
		}

		exePath, err := os.Executable()
		if err != nil {
			ms.explore3DFailed("3D preview failed: " + err.Error())
			return
		}

		session, err := LaunchViewer(exePath, path)
		if err != nil {
			ms.explore3DFailed("3D preview failed: " + err.Error())
			return
		}
		ms.viewerSession = session

		session.OnReady = func(n int) {
			ms.statusLabel.SetText("Ready")
			ms.stateIcon.SetResource(theme.ConfirmIcon())
			ms.appendLog(fmt.Sprintf("3D preview open: %s blocks. Hold RMB + WASD/Shift/Space to fly, left-click to pick a block.", formatCount(n)))
		}
		session.OnError = func(msg string) {
			ms.explore3DFailed("3D viewer error: " + msg)
		}
		session.OnPick = func(x, y, z int) {
			ms.showSelectionPopup(x, y, z)
		}
		session.OnBoxSelected = func(min, max [3]int, count int) {
			ms.applyBoxSelectionFromViewer(min, max, count)
		}
		session.OnClosed = func() {
			ms.viewerSession = nil
			ms.appendLog("3D preview closed.")
		}
	}()
}

func (ms *MinesportApp) explore3DFailed(msg string) {
	ms.appendLog(msg)
	ms.statusLabel.SetText("Ready")
	ms.stateIcon.SetResource(theme.InfoIcon())
	dialog.ShowError(fmt.Errorf("%s", msg), ms.window)
}

// applyBoxSelectionFromViewer handles the E/Q quick-select result — applies
// straight to the sidebar bounds with no confirmation dialog, since the
// whole point of E/Q over the pick-and-popup flow is speed. Clears any
// exact "Joined Blocks" selection, same as the popup's Area Selection does.
func (ms *MinesportApp) applyBoxSelectionFromViewer(min, max [3]int, count int) {
	ms.customSelectionFile = ""
	ms.customSelectionCount = 0

	ms.suppressSelectionClear = true
	ms.selectionModeSelect.SetSelected("Box (min/max)")
	ms.minXEntry.SetText(fmt.Sprintf("%d", min[0]))
	ms.minYEntry.SetText(fmt.Sprintf("%d", min[1]))
	ms.minZEntry.SetText(fmt.Sprintf("%d", min[2]))
	ms.maxXEntry.SetText(fmt.Sprintf("%d", max[0]))
	ms.maxYEntry.SetText(fmt.Sprintf("%d", max[1]))
	ms.maxZEntry.SetText(fmt.Sprintf("%d", max[2]))
	ms.suppressSelectionClear = false

	ms.updateMetaHUD(fmt.Sprintf("%s blocks selected (E/Q box, 3D)", formatCount(count)))
	ms.appendLog(fmt.Sprintf("3D box selection (E/Q): %d,%d,%d → %d,%d,%d (%s blocks)",
		min[0], min[1], min[2], max[0], max[1], max[2], formatCount(count)))
}
