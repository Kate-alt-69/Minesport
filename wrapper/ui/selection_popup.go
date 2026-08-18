package ui

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"
)

// joinedResult holds the most recent flood-fill preview from the viewer
// subprocess. Guarded by a mutex because it's written from the viewer
// session's stdout-reading goroutine and read from the dialog's confirm
// callback (main/UI goroutine) — two different goroutines, no implicit
// synchronization otherwise.
type joinedResult struct {
	mu     sync.Mutex
	blocks [][3]int
	count  int
	ready  bool
}

func (jr *joinedResult) set(blocks [][3]int, count int) {
	jr.mu.Lock()
	defer jr.mu.Unlock()
	jr.blocks, jr.count, jr.ready = blocks, count, true
}

func (jr *joinedResult) get() ([][3]int, int, bool) {
	jr.mu.Lock()
	defer jr.mu.Unlock()
	return jr.blocks, jr.count, jr.ready
}

// showSelectionPopup opens the "how do you want this selection to be"
// dialog after a block is picked in the 3D viewer — Joined Blocks
// (flood-fill, capped by Power) or Area Selection (a box centered on the
// picked block).
func (ms *MinesportApp) showSelectionPopup(x, y, z int) {
	if ms.viewerSession == nil {
		return
	}

	modeSelect := widget.NewSelect([]string{"Joined Blocks", "Area Selection"}, nil)

	// ── Joined Blocks ──────────────────────────────────────────────────────
	result := &joinedResult{}
	powerEntry := ms.makeEntry("64")
	joinedStatus := widget.NewLabel("Click Preview to compute the selection.")
	joinedStatus.TextStyle = fyne.TextStyle{Italic: true}

	previewBtn := widget.NewButton("Preview", func() {
		joinedStatus.SetText("Computing...")
		ms.viewerSession.FloodFill(x, y, z, ms.intEntry(powerEntry, 64))
	})

	joinedGroup := container.NewVBox(
		widget.NewLabel(fmt.Sprintf("Starting from block (%d, %d, %d)", x, y, z)),
		widget.NewLabel("Power — max blocks the fill can reach. Never crosses air; any\ntouching block type can join (grass next to stone next to wood, etc)."),
		powerEntry,
		previewBtn,
		joinedStatus,
	)

	// ── Area Selection ─────────────────────────────────────────────────────
	areaXEntry := ms.makeEntry("16")
	areaYEntry := ms.makeEntry("16")
	areaZEntry := ms.makeEntry("16")

	updateAreaPreview := func(string) {
		rx, ry, rz := ms.intEntry(areaXEntry, 16), ms.intEntry(areaYEntry, 16), ms.intEntry(areaZEntry, 16)
		ms.viewerSession.HighlightBox([3]int{x - rx, y - ry, z - rz}, [3]int{x + rx, y + ry, z + rz})
	}
	areaXEntry.OnChanged = updateAreaPreview
	areaYEntry.OnChanged = updateAreaPreview
	areaZEntry.OnChanged = updateAreaPreview

	areaGroup := container.NewVBox(
		widget.NewLabel(fmt.Sprintf("Centered on block (%d, %d, %d)", x, y, z)),
		widget.NewLabel("Cube — how far outward on each axis. More shapes coming later."),
		container.NewGridWithColumns(3,
			container.NewVBox(widget.NewLabel("X reach"), areaXEntry),
			container.NewVBox(widget.NewLabel("Y reach"), areaYEntry),
			container.NewVBox(widget.NewLabel("Z reach"), areaZEntry),
		),
		widget.NewLabel("Preview updates live in the 3D view as you type."),
	)
	areaGroup.Hide()

	modeSelect.OnChanged = func(choice string) {
		if choice == "Area Selection" {
			joinedGroup.Hide()
			areaGroup.Show()
			updateAreaPreview("")
		} else {
			areaGroup.Hide()
			joinedGroup.Show()
			ms.viewerSession.ClearHighlight()
		}
	}
	modeSelect.SetSelected("Joined Blocks")

	content := container.NewVBox(modeSelect, joinedGroup, areaGroup)

	ms.viewerSession.OnSelection = func(blocks [][3]int, count int) {
		result.set(blocks, count)
		joinedStatus.SetText(fmt.Sprintf("%s blocks selected", formatCount(count)))
	}

	d := dialog.NewCustomConfirm("Selection", "Use This Selection", "Cancel", content, func(confirmed bool) {
		if !confirmed {
			ms.viewerSession.ClearHighlight()
			return
		}
		if modeSelect.Selected == "Area Selection" {
			rx, ry, rz := ms.intEntry(areaXEntry, 16), ms.intEntry(areaYEntry, 16), ms.intEntry(areaZEntry, 16)
			ms.applyAreaSelection(x, y, z, rx, ry, rz)
			return
		}
		blocks, count, ready := result.get()
		if !ready {
			dialog.ShowInformation("No preview yet", "Click Preview first so there's something to use.", ms.window)
			return
		}
		ms.applyJoinedSelection(blocks, count)
	}, ms.window)
	d.Resize(fyne.NewSize(480, 380))
	d.Show()
}

// applyJoinedSelection writes the exact flood-filled block set to a temp
// file (the engine's exportWithGeometry/handleExport reads this via the
// "customSelectionFile" option — see IpcMode.loadCustomSelection) and
// points the sidebar's box bounds at its bounding box, since the engine
// still needs a box to scan region files by before narrowing to the exact
// set.
func (ms *MinesportApp) applyJoinedSelection(blocks [][3]int, count int) {
	if len(blocks) == 0 {
		dialog.ShowInformation("Empty selection", "No blocks in this selection.", ms.window)
		return
	}

	minX, minY, minZ := blocks[0][0], blocks[0][1], blocks[0][2]
	maxX, maxY, maxZ := minX, minY, minZ
	for _, b := range blocks[1:] {
		if b[0] < minX { minX = b[0] }
		if b[1] < minY { minY = b[1] }
		if b[2] < minZ { minZ = b[2] }
		if b[0] > maxX { maxX = b[0] }
		if b[1] > maxY { maxY = b[1] }
		if b[2] > maxZ { maxZ = b[2] }
	}

	tmpFile, err := os.CreateTemp("", "minesport_selection_*.json")
	if err != nil {
		dialog.ShowError(err, ms.window)
		return
	}
	defer tmpFile.Close()

	type coord struct {
		X int `json:"x"`
		Y int `json:"y"`
		Z int `json:"z"`
	}
	coords := make([]coord, len(blocks))
	for i, b := range blocks {
		coords[i] = coord{b[0], b[1], b[2]}
	}
	if err := json.NewEncoder(tmpFile).Encode(coords); err != nil {
		dialog.ShowError(err, ms.window)
		return
	}

	ms.customSelectionFile = tmpFile.Name()
	ms.customSelectionCount = count

	ms.suppressSelectionClear = true
	ms.selectionModeSelect.SetSelected("Box (min/max)")
	ms.minXEntry.SetText(fmt.Sprintf("%d", minX))
	ms.minYEntry.SetText(fmt.Sprintf("%d", minY))
	ms.minZEntry.SetText(fmt.Sprintf("%d", minZ))
	ms.maxXEntry.SetText(fmt.Sprintf("%d", maxX))
	ms.maxYEntry.SetText(fmt.Sprintf("%d", maxY))
	ms.maxZEntry.SetText(fmt.Sprintf("%d", maxZ))
	ms.suppressSelectionClear = false

	ms.updateMetaHUD(fmt.Sprintf("%s blocks selected (joined, 3D)", formatCount(count)))
	ms.appendLog(fmt.Sprintf("3D selection applied: %s blocks (joined)", formatCount(count)))
}

// applyAreaSelection is just a box — no exact-selection file needed, the
// existing Box mode bounds already represent it exactly.
func (ms *MinesportApp) applyAreaSelection(cx, cy, cz, rx, ry, rz int) {
	ms.customSelectionFile = ""
	ms.customSelectionCount = 0

	ms.suppressSelectionClear = true
	ms.selectionModeSelect.SetSelected("Box (min/max)")
	ms.minXEntry.SetText(fmt.Sprintf("%d", cx-rx))
	ms.minYEntry.SetText(fmt.Sprintf("%d", cy-ry))
	ms.minZEntry.SetText(fmt.Sprintf("%d", cz-rz))
	ms.maxXEntry.SetText(fmt.Sprintf("%d", cx+rx))
	ms.maxYEntry.SetText(fmt.Sprintf("%d", cy+ry))
	ms.maxZEntry.SetText(fmt.Sprintf("%d", cz+rz))
	ms.suppressSelectionClear = false

	ms.updateMetaHUD(ms.selectionSizeText())
	ms.appendLog(fmt.Sprintf("3D area selection applied: (%d,%d,%d) ± (%d,%d,%d)", cx, cy, cz, rx, ry, rz))
}
