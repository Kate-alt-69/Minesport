package ui

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

// buildWorkbenchAssetSettingsSection renders asset controls directly inside the
// Settings activity. There is deliberately no separate "Asset Resolution
// Center" navigation state anymore.
func (ms *MinesportApp) buildWorkbenchAssetSettingsSection(status *widget.Label) fyne.CanvasObject {
	stack := container.NewVBox()

	var rebuild func()
	save := func(paths []string, message string) {
		next := ms.settings
		next.ResourcePackPaths = append([]string(nil), paths...)
		ms.applySettings(next)
		if status != nil {
			status.SetText(message)
		}
		ms.appendLog(message)
		if rebuild != nil {
			rebuild()
		}
	}

	rebuild = func() {
		stack.RemoveAll()
		paths := append([]string(nil), ms.settings.ResourcePackPaths...)
		if len(paths) == 0 {
			stack.Add(workbenchHelp("No custom resource packs. Vanilla and mod assets resolve directly."))
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

			up := widget.NewButton("↑", func() {
				if moved := movePathEntry(ms.settings.ResourcePackPaths, i, -1); moved != nil {
					save(moved, "Resource-pack priority updated · saved")
				}
			})
			down := widget.NewButton("↓", func() {
				if moved := movePathEntry(ms.settings.ResourcePackPaths, i, 1); moved != nil {
					save(moved, "Resource-pack priority updated · saved")
				}
			})
			remove := widget.NewButtonWithIcon("", theme.DeleteIcon(), func() {
				paths := append([]string(nil), ms.settings.ResourcePackPaths...)
				if i < 0 || i >= len(paths) {
					return
				}
				paths = append(paths[:i], paths[i+1:]...)
				save(paths, "Resource pack removed · saved")
			})
			if i == 0 {
				up.Disable()
			}
			if i == len(paths)-1 {
				down.Disable()
			}
			stack.Add(container.NewBorder(
				nil,
				nil,
				container.NewVBox(label, pathLabel),
				container.NewHBox(up, down, remove),
				nil,
			))
			stack.Add(widget.NewSeparator())
		}
	}
	rebuild()

	addFolder := widget.NewButton("Add folder…", func() {
		go func() {
			path := nativeOpenFolder("Add Minecraft Resource Pack")
			if path == "" {
				return
			}
			ms.addInlineResourcePack(path, save, status)
		}()
	})
	addZip := widget.NewButton("Add ZIP…", func() {
		go func() {
			path := nativeOpenFile("Add Minecraft Resource Pack", "Minecraft Resource Pack|*.zip")
			if path == "" {
				return
			}
			ms.addInlineResourcePack(path, save, status)
		}()
	})

	chain := widget.NewLabel(
		"1 · Resource-pack stack (highest priority first)\n" +
			"2 · Local vanilla client assets\n" +
			"3 · Official Mojang Piston recovery on vanilla misses\n" +
			"4 · Matching mod/runtime registry resolver\n" +
			"5 · Missing-texture fallback",
	)
	chain.Wrapping = fyne.TextWrapWord

	return container.NewVBox(
		workbenchSection("ASSETS & RESOLVER"),
		workbenchHelp("Resource packs and resolver policy live directly in Settings; there is no separate Asset Center."),
		stack,
		container.NewHBox(addFolder, addZip),
		widget.NewLabelWithStyle("RESOLUTION ORDER", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		chain,
		workbenchHelp("Piston recovery stays lazy and SHA-1 verified. Runtime model geometry stores texture IDs only; image bytes continue to resolve from packs, mod JARs, vanilla assets or Piston."),
		ms.buildDocumentationSettingsSection(),
	)
}

func (ms *MinesportApp) addInlineResourcePack(path string, save func([]string, string), status *widget.Label) {
	path = filepath.Clean(strings.TrimSpace(path))
	if path == "" || path == "." {
		return
	}
	paths := append([]string(nil), ms.settings.ResourcePackPaths...)
	for _, existing := range paths {
		if strings.EqualFold(filepath.Clean(existing), path) {
			if status != nil {
				status.SetText("That resource pack is already in the stack.")
			}
			return
		}
	}
	paths = append(paths, path)
	save(paths, "Resource pack added · saved")
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

func movePathEntry(paths []string, index, delta int) []string {
	target := index + delta
	if index < 0 || index >= len(paths) || target < 0 || target >= len(paths) {
		return nil
	}
	result := append([]string(nil), paths...)
	result[index], result[target] = result[target], result[index]
	return result
}
