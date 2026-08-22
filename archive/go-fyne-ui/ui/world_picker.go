package ui

import (
	"fmt"
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
	"github.com/kastrick/minesport/launcher"
	"strings"
	"time"
)

func relativeTime(t time.Time) string {
	if t.IsZero() {
		return "unknown"
	}
	d := time.Since(t)
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	case d < 30*24*time.Hour:
		return fmt.Sprintf("%dd ago", int(d.Hours()/24))
	default:
		return t.Format("Jan 2, 2006")
	}
}
func pickerRow() fyne.CanvasObject {
	ico := widget.NewIcon(nil)
	title := widget.NewLabel("")
	title.TextStyle = fyne.TextStyle{Bold: true}
	sub := widget.NewLabel("")
	sub.TextStyle = fyne.TextStyle{Italic: true}
	return container.NewHBox(container.NewPadded(ico), container.NewPadded(container.NewVBox(title, sub)))
}
func setPickerRow(obj fyne.CanvasObject, icon fyne.Resource, title, sub string) {
	row, ok := obj.(*fyne.Container)
	if !ok || len(row.Objects) < 2 {
		return
	}
	iconWrap, ok := row.Objects[0].(*fyne.Container)
	if !ok || len(iconWrap.Objects) < 1 {
		return
	}
	if ico, ok := iconWrap.Objects[0].(*widget.Icon); ok {
		ico.SetResource(icon)
	}
	textWrap, ok := row.Objects[1].(*fyne.Container)
	if !ok || len(textWrap.Objects) < 1 {
		return
	}
	text, ok := textWrap.Objects[0].(*fyne.Container)
	if !ok || len(text.Objects) < 2 {
		return
	}
	if l, ok := text.Objects[0].(*widget.Label); ok {
		l.SetText(title)
	}
	if l, ok := text.Objects[1].(*widget.Label); ok {
		l.SetText(sub)
	}
}
func ShowWorldPicker(parent fyne.Window, onSelect func(string, string)) {
	launchers := launcher.DiscoverAll()
	if len(launchers) == 0 {
		dialog.ShowError(fmt.Errorf("no Minecraft launchers found on this system"), parent)
		return
	}
	var instances []launcher.Instance
	var worlds []launcher.World
	step, selL, selI, selW := 0, -1, -1, -1
	breadcrumb := widget.NewLabel("Launcher")
	breadcrumb.TextStyle = fyne.TextStyle{Bold: true}
	search := widget.NewEntry()
	search.SetPlaceHolder("Search launcher, instance or world…")
	list := widget.NewList(func() int { return 0 }, pickerRow, func(i widget.ListItemID, o fyne.CanvasObject) {})
	back := widget.NewButtonWithIcon("Back", theme.NavigateBackIcon(), nil)
	selectBtn := widget.NewButtonWithIcon("Use world", theme.ConfirmIcon(), nil)
	selectBtn.Importance = widget.HighImportance
	selectBtn.Disable()
	var visibleLaunchers []launcher.Launcher
	var visibleInstances []launcher.Instance
	var visibleWorlds []launcher.World
	matches := func(text, query string) bool {
		q := strings.ToLower(strings.TrimSpace(query))
		return q == "" || strings.Contains(strings.ToLower(text), q)
	}
	refresh := func() {
		query := search.Text
		switch step {
		case 0:
			breadcrumb.SetText("Launcher")
			back.Disable()
			selectBtn.Disable()
			visibleLaunchers = visibleLaunchers[:0]
			for _, l := range launchers {
				if matches(l.Name, query) || matches(l.RootPath, query) {
					visibleLaunchers = append(visibleLaunchers, l)
				}
			}
			list.Length = func() int { return len(visibleLaunchers) }
			list.UpdateItem = func(i widget.ListItemID, o fyne.CanvasObject) {
				if i < 0 || int(i) >= len(visibleLaunchers) {
					return
				}
				l := visibleLaunchers[i]
				setPickerRow(o, theme.ComputerIcon(), l.Name, l.RootPath)
			}
		case 1:
			if selL < 0 || selL >= len(launchers) {
				return
			}
			breadcrumb.SetText(fmt.Sprintf("%s  ›  Instance", launchers[selL].Name))
			back.Enable()
			selectBtn.Disable()
			visibleInstances = visibleInstances[:0]
			for _, x := range instances {
				if matches(x.Name, query) || matches(x.Version, query) || matches(string(x.Loader), query) || matches(x.MinecraftDir, query) {
					visibleInstances = append(visibleInstances, x)
				}
			}
			list.Length = func() int { return len(visibleInstances) }
			list.UpdateItem = func(i widget.ListItemID, o fyne.CanvasObject) {
				if i < 0 || int(i) >= len(visibleInstances) {
					return
				}
				x := visibleInstances[i]
				poly := ""
				if x.HasPolymer() {
					poly = " · Polymer"
				}
				setPickerRow(o, theme.SettingsIcon(), x.Name, fmt.Sprintf("MC %s · %s · %d worlds%s", x.Version, x.Loader, len(x.Worlds), poly))
			}
		case 2:
			if selL < 0 || selL >= len(launchers) || selI < 0 || selI >= len(instances) {
				return
			}
			breadcrumb.SetText(fmt.Sprintf("%s  ›  %s  ›  World", launchers[selL].Name, instances[selI].Name))
			back.Enable()
			visibleWorlds = visibleWorlds[:0]
			for _, x := range worlds {
				if matches(x.Name, query) || matches(x.Path, query) || matches(x.LevelName, query) {
					visibleWorlds = append(visibleWorlds, x)
				}
			}
			list.Length = func() int { return len(visibleWorlds) }
			list.UpdateItem = func(i widget.ListItemID, o fyne.CanvasObject) {
				if i < 0 || int(i) >= len(visibleWorlds) {
					return
				}
				x := visibleWorlds[i]
				setPickerRow(o, theme.FileIcon(), x.Name, fmt.Sprintf("%s · %s", relativeTime(x.LastPlayed), x.Path))
			}
			if selW >= 0 {
				selectBtn.Enable()
			} else {
				selectBtn.Disable()
			}
		}
		list.UnselectAll()
		list.Refresh()
	}
	list.OnSelected = func(i widget.ListItemID) {
		switch step {
		case 0:
			if i < 0 || int(i) >= len(visibleLaunchers) {
				return
			}
			chosen := visibleLaunchers[i]
			for idx, l := range launchers {
				if l.RootPath == chosen.RootPath && l.Name == chosen.Name {
					selL = idx
					break
				}
			}
			instances = launcher.DiscoverInstances(chosen)
			if len(instances) == 0 {
				refresh()
				return
			}
			selI, selW = -1, -1
			step = 1
		case 1:
			if i < 0 || int(i) >= len(visibleInstances) {
				return
			}
			chosen := visibleInstances[i]
			for idx, x := range instances {
				if x.MinecraftDir == chosen.MinecraftDir && x.Name == chosen.Name && x.Version == chosen.Version {
					selI = idx
					break
				}
			}
			if selI < 0 {
				return
			}
			worlds = instances[selI].Worlds
			if len(worlds) == 0 {
				refresh()
				return
			}
			selW = -1
			step = 2
		case 2:
			if i < 0 || int(i) >= len(visibleWorlds) {
				return
			}
			chosen := visibleWorlds[i]
			for idx, x := range worlds {
				if x.Path == chosen.Path {
					selW = idx
					break
				}
			}
		}
		search.SetText("")
		refresh()
	}
	var closePicker func()
	selected := false
	back.OnTapped = func() {
		if step == 0 {
			return
		}
		step--
		if step == 0 {
			selL, selI, selW = -1, -1, -1
		}
		if step == 1 {
			selI, selW = -1, -1
		}
		refresh()
	}
	selectBtn.OnTapped = func() {
		if step != 2 || selW < 0 || selI < 0 || selected {
			return
		}
		selected = true
		selectBtn.Disable()
		worldPath, modsPath := worlds[selW].Path, instances[selI].ModsPath
		if closePicker != nil {
			closePicker()
		}
		onSelect(worldPath, modsPath)
	}
	search.OnChanged = func(string) { refresh() }
	refresh()
	content := container.NewBorder(container.NewVBox(container.NewPadded(breadcrumb), container.NewPadded(search)), container.NewPadded(container.NewBorder(nil, nil, back, nil, selectBtn)), nil, nil, container.NewPadded(list))
	d := dialog.NewCustom("Select Minecraft World", "Cancel", content, parent)
	closePicker = d.Hide
	d.Resize(fyne.NewSize(760, 560))
	d.Show()
}
