package ui

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

type workbenchAssetCenterState struct {
	holder *fyne.Container
	home   fyne.CanvasObject
	stack  *fyne.Container
	status *widget.Label
}

var workbenchAssetCenterStates sync.Map

func installWorkbenchAssetCenter(ms *MinesportApp) {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	holderObject := wb.panes[workbenchPaneSettings]
	holder, ok := holderObject.(*fyne.Container)
	if !ok || len(holder.Objects) == 0 {
		return
	}
	if _, loaded := workbenchAssetCenterStates.Load(ms); loaded {
		return
	}

	base := holder.Objects[0]
	state := &workbenchAssetCenterState{holder: holder}
	openAssets := widget.NewButtonWithIcon("Open Asset Resolution Center", theme.SearchIcon(), func() {
		showWorkbenchAssetCenter(ms)
	})
	openAssets.Importance = widget.HighImportance
	intro := container.NewVBox(
		widget.NewLabelWithStyle("ASSETS", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		workbenchHelp("Manage the resource-pack stack and inspect the resolver policy used by the Java engine."),
		openAssets,
	)
	state.home = container.NewBorder(container.NewPadded(intro), nil, nil, nil, base)
	holder.RemoveAll()
	holder.Add(state.home)
	workbenchAssetCenterStates.Store(ms, state)
}

func cleanupWorkbenchAssetCenter(ms *MinesportApp) {
	workbenchAssetCenterStates.Delete(ms)
}

func showWorkbenchAssetCenter(ms *MinesportApp) {
	value, ok := workbenchAssetCenterStates.Load(ms)
	if !ok {
		return
	}
	state, _ := value.(*workbenchAssetCenterState)
	if state == nil || state.holder == nil {
		return
	}
	state.holder.RemoveAll()
	state.holder.Add(ms.buildWorkbenchAssetCenter(state))
}

func (ms *MinesportApp) closeWorkbenchAssetCenter(state *workbenchAssetCenterState) {
	if state == nil || state.holder == nil || state.home == nil {
		return
	}
	state.holder.RemoveAll()
	state.holder.Add(state.home)
}

func (ms *MinesportApp) buildWorkbenchAssetCenter(state *workbenchAssetCenterState) fyne.CanvasObject {
	back := widget.NewButtonWithIcon("Settings", theme.NavigateBackIcon(), func() {
		ms.closeWorkbenchAssetCenter(state)
	})
	title := widget.NewLabelWithStyle("ASSET RESOLUTION", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	header := container.NewBorder(nil, nil, back, nil, title)

	state.status = widget.NewLabel("Resource-pack changes save immediately and apply to the next Engine operation.")
	state.status.Wrapping = fyne.TextWrapWord
	state.stack = container.NewVBox()
	ms.rebuildResourcePackStack(state)

	addFolder := widget.NewButton("Add folder…", func() {
		go func() {
			path := nativeOpenFolder("Add Minecraft Resource Pack")
			if path != "" {
				ms.addResourcePackPath(state, path)
			}
		}()
	})
	addZip := widget.NewButton("Add ZIP…", func() {
		go func() {
			path := nativeOpenFile(
				"Add Minecraft Resource Pack",
				"Minecraft Resource Pack|*.zip",
			)
			if path != "" {
				ms.addResourcePackPath(state, path)
			}
		}()
	})

	chain := widget.NewLabel(
		"Current Engine policy\n" +
			"1 · Resource-pack stack (highest priority first)\n" +
			"2 · Local vanilla client assets\n" +
			"3 · Official Mojang Piston recovery on vanilla texture misses\n" +
			"4 · Fabric / Polymer / Quilt / Forge mod resolvers for matching namespaces\n" +
			"5 · Classic missing-texture fallback",
	)
	chain.Wrapping = fyne.TextWrapWord
	policyWarning := workbenchHelp(
		"This is the Engine's current real order. Provenance tracking now records which resolver actually wins so Preflight/Inspector can expose it next.",
	)

	piston := widget.NewLabel(
		"Piston recovery is lazy: Minesport only downloads the official client after a local vanilla texture miss. Downloads are retried and the client jar is SHA-1 verified before use.",
	)
	piston.Wrapping = fyne.TextWrapWord

	advanced := widget.NewButton("Data packs / advanced settings…", ms.openWorkbenchAdvancedSettings)

	content := container.NewVBox(
		header,
		state.status,
		workbenchSection("RESOURCE PACK STACK"),
		workbenchHelp("Top entry wins. Reorder packs here instead of deleting and re-adding them."),
		state.stack,
		container.NewHBox(addFolder, addZip),
		workbenchSection("RESOLUTION CHAIN"),
		chain,
		policyWarning,
		workbenchSection("RECOVERY"),
		piston,
		advanced,
	)
	return container.NewVScroll(container.NewPadded(content))
}

func resourcePackPathState(path string) (string, bool) {
	info, err := os.Stat(path)
	if err != nil {
		return "MISSING", false
	}
	if info.IsDir() {
		return "FOLDER", true
	}
	if strings.EqualFold(filepath.Ext(path), ".zip") {
		return "ZIP", true
	}
	return "FILE", true
}

func (ms *MinesportApp) rebuildResourcePackStack(state *workbenchAssetCenterState) {
	if state == nil || state.stack == nil {
		return
	}
	state.stack.RemoveAll()
	paths := append([]string(nil), ms.settings.ResourcePackPaths...)
	if len(paths) == 0 {
		state.stack.Add(workbenchHelp("No custom resource packs. Vanilla/mod assets resolve directly."))
		return
	}

	for index, path := range paths {
		i := index
		p := path
		kind, healthy := resourcePackPathState(p)
		name := filepath.Base(filepath.Clean(p))
		if name == "." || name == string(filepath.Separator) || name == "" {
			name = p
		}
		statusText := fmt.Sprintf("%d · %s · %s", i+1, name, kind)
		if !healthy {
			statusText += " · NOT FOUND"
		}
		label := widget.NewLabel(statusText)
		label.Truncation = fyne.TextTruncateEllipsis
		pathLabel := widget.NewLabel(p)
		pathLabel.Truncation = fyne.TextTruncateEllipsis

		up := widget.NewButton("↑", func() { ms.moveResourcePackPath(state, i, -1) })
		down := widget.NewButton("↓", func() { ms.moveResourcePackPath(state, i, 1) })
		remove := widget.NewButtonWithIcon("", theme.DeleteIcon(), func() {
			ms.removeResourcePackPath(state, i)
		})
		if i == 0 {
			up.Disable()
		}
		if i == len(paths)-1 {
			down.Disable()
		}
		row := container.NewBorder(
			nil,
			nil,
			container.NewVBox(label, pathLabel),
			container.NewHBox(up, down, remove),
			nil,
		)
		state.stack.Add(row)
		state.stack.Add(widget.NewSeparator())
	}
}

func (ms *MinesportApp) saveResourcePackPaths(state *workbenchAssetCenterState, paths []string, message string) {
	next := ms.settings
	next.ResourcePackPaths = append([]string(nil), paths...)
	ms.applySettings(next)
	if state != nil && state.status != nil {
		state.status.SetText(message)
	}
	ms.rebuildResourcePackStack(state)
	ms.appendLog(message)
}

func (ms *MinesportApp) addResourcePackPath(state *workbenchAssetCenterState, path string) {
	path = filepath.Clean(strings.TrimSpace(path))
	if path == "" || path == "." {
		return
	}
	paths := append([]string(nil), ms.settings.ResourcePackPaths...)
	for _, existing := range paths {
		if strings.EqualFold(filepath.Clean(existing), path) {
			if state != nil && state.status != nil {
				state.status.SetText("That resource pack is already in the stack.")
			}
			return
		}
	}
	paths = append(paths, path)
	ms.saveResourcePackPaths(state, paths, "Resource pack added · saved")
}

func (ms *MinesportApp) removeResourcePackPath(state *workbenchAssetCenterState, index int) {
	paths := append([]string(nil), ms.settings.ResourcePackPaths...)
	if index < 0 || index >= len(paths) {
		return
	}
	paths = append(paths[:index], paths[index+1:]...)
	ms.saveResourcePackPaths(state, paths, "Resource pack removed · saved")
}

func (ms *MinesportApp) moveResourcePackPath(state *workbenchAssetCenterState, index, delta int) {
	paths := movePathEntry(ms.settings.ResourcePackPaths, index, delta)
	if paths == nil {
		return
	}
	ms.saveResourcePackPaths(state, paths, "Resource-pack priority updated · saved")
}

func movePathEntry(paths []string, index, delta int) []string {
	target := index + delta
	if index < 0 || index >= len(paths) || target < 0 || target >= len(paths) {
		return nil
	}
	result := append([]string(nil), paths...)
	result[index], result[target] = result[target], result[index]
	return result
}
