package ui

import (
    "fmt"
    "strings"
    "time"

    "fyne.io/fyne/v2"
    "fyne.io/fyne/v2/container"
    "fyne.io/fyne/v2/dialog"
    "fyne.io/fyne/v2/theme"
    "fyne.io/fyne/v2/widget"

    "github.com/kastrick/minesport/launcher"
)

func relativeTime(t time.Time) string {
    if t.IsZero() { return "unknown" }
    d := time.Since(t)
    switch {
    case d < time.Minute: return "just now"
    case d < time.Hour: return fmt.Sprintf("%dm ago", int(d.Minutes()))
    case d < 24*time.Hour: return fmt.Sprintf("%dh ago", int(d.Hours()))
    case d < 30*24*time.Hour: return fmt.Sprintf("%dd ago", int(d.Hours()/24))
    default: return t.Format("Jan 2, 2006")
    }
}

func pickerRow(icon fyne.Resource) fyne.CanvasObject {
    ico := widget.NewIcon(icon)
    title := widget.NewLabel(""); title.TextStyle = fyne.TextStyle{Bold:true}
    sub := widget.NewLabel(""); sub.TextStyle = fyne.TextStyle{Italic:true}
    return container.NewBorder(nil,nil,container.NewPadded(ico),nil,container.NewPadded(container.NewVBox(title,sub)))
}
func setPickerRow(obj fyne.CanvasObject, icon fyne.Resource, title, sub string) {
    row := obj.(*fyne.Container)
    body := row.Objects[0].(*fyne.Container)
    iconWrap := body.Objects[1].(*fyne.Container)
    iconWrap.Objects[0].(*widget.Icon).SetResource(icon)
    text := body.Objects[0].(*fyne.Container)
    text.Objects[0].(*widget.Label).SetText(title)
    text.Objects[1].(*widget.Label).SetText(sub)
}

func ShowWorldPicker(parent fyne.Window, onSelect func(string,string)) {
    launchers := launcher.DiscoverAll()
    if len(launchers) == 0 { dialog.ShowError(fmt.Errorf("no Minecraft launchers found on this system"), parent); return }

    var instances []launcher.Instance
    var worlds []launcher.World
    step, selL, selI, selW := 0, -1, -1, -1

    breadcrumb := widget.NewLabel("Launcher"); breadcrumb.TextStyle = fyne.TextStyle{Bold:true}
    search := widget.NewEntry(); search.SetPlaceHolder("Search launcher, instance or world…")
    list := widget.NewList(func() int { return 0 }, pickerRow, func(int, fyne.CanvasObject){})
    back := widget.NewButtonWithIcon("Back", theme.NavigateBackIcon(), nil)
    selectBtn := widget.NewButtonWithIcon("Use world", theme.ConfirmIcon(), nil)
    selectBtn.Importance = widget.HighImportance; selectBtn.Disable()

    var visibleLaunchers []launcher.Launcher
    var visibleInstances []launcher.Instance
    var visibleWorlds []launcher.World

    matches := func(text string, query string) bool {
        q := strings.ToLower(strings.TrimSpace(query))
        if q == "" { return true }
        return strings.Contains(strings.ToLower(text), q)
    }

    refresh := func() {
        query := search.Text
        switch step {
        case 0:
            breadcrumb.SetText("Launcher"); back.Disable(); selectBtn.Disable()
            visibleLaunchers = visibleLaunchers[:0]
            for _, l := range launchers { if matches(l.Name,query) || matches(l.RootPath,query) { visibleLaunchers = append(visibleLaunchers,l) } }
            list.Length = func() int { return len(visibleLaunchers) }
            list.UpdateItem = func(i widget.ListItemID, o fyne.CanvasObject) { l:=visibleLaunchers[i]; setPickerRow(o,theme.ComputerIcon(),l.Name,l.RootPath) }
        case 1:
            breadcrumb.SetText(fmt.Sprintf("%s  ›  Instance", launchers[selL].Name)); back.Enable(); selectBtn.Disable()
            visibleInstances = visibleInstances[:0]
            for _, x := range instances { if matches(x.Name,query) || matches(x.Version,query) || matches(string(x.Loader),query) { visibleInstances=append(visibleInstances,x) } }
            list.Length = func() int { return len(visibleInstances) }
            list.UpdateItem = func(i widget.ListItemID,o fyne.CanvasObject){x:=visibleInstances[i];poly:="";if x.HasPolymer(){poly=" · Polymer"};setPickerRow(o,theme.SettingsIcon(),x.Name,fmt.Sprintf("MC %s · %s · %d worlds%s",x.Version,x.Loader,len(x.Worlds),poly))}
        case 2:
            breadcrumb.SetText(fmt.Sprintf("%s  ›  %s  ›  World",launchers[selL].Name,instances[selI].Name)); back.Enable()
            visibleWorlds = visibleWorlds[:0]
            for _, x := range worlds { if matches(x.Name,query) || matches(x.Path,query) { visibleWorlds=append(visibleWorlds,x) } }
            list.Length = func() int { return len(visibleWorlds) }
            list.UpdateItem = func(i widget.ListItemID,o fyne.CanvasObject){x:=visibleWorlds[i];setPickerRow(o,theme.FileIcon(),x.Name,fmt.Sprintf("%s · %s",relativeTime(x.LastPlayed),x.Path))}
            if selW >= 0 { selectBtn.Enable() } else { selectBtn.Disable() }
        }
        list.UnselectAll(); list.Refresh()
    }

    list.OnSelected = func(i widget.ListItemID) {
        switch step {
        case 0:
            if i < 0 || i >= len(visibleLaunchers) { return }
            chosen := visibleLaunchers[i]
            for idx, l := range launchers { if l.RootPath == chosen.RootPath && l.Name == chosen.Name { selL = idx; break } }
            instances = launcher.DiscoverInstances(chosen)
            if len(instances) == 0 { refresh(); return }
            selI, selW = -1, -1; step = 1
        case 1:
            if i < 0 || i >= len(visibleInstances) { return }
            chosen := visibleInstances[i]
            for idx, x := range instances { if x.RootPath == chosen.RootPath && x.Name == chosen.Name { selI = idx; break } }
            if selI < 0 { return }
            worlds = instances[selI].Worlds
            if len(worlds) == 0 { refresh(); return }
            selW = -1; step = 2
        case 2:
            if i < 0 || i >= len(visibleWorlds) { return }
            chosen := visibleWorlds[i]
            for idx, x := range worlds { if x.Path == chosen.Path { selW = idx; break } }
        }
        search.SetText("")
        refresh()
    }

    back.OnTapped = func() {
        if step == 0 { return }
        step--
        if step == 0 { selL, selI, selW = -1,-1,-1 }
        if step == 1 { selI, selW = -1,-1 }
        refresh()
    }
    selectBtn.OnTapped = func() {
        if step != 2 || selW < 0 || selI < 0 { return }
        onSelect(worlds[selW].Path, instances[selI].ModsPath)
    }
    search.OnChanged = func(string) { refresh() }

    refresh()
    content := container.NewBorder(
        container.NewVBox(container.NewPadded(breadcrumb),container.NewPadded(search)),
        container.NewPadded(container.NewBorder(nil,nil,back,nil,selectBtn)), nil,nil,
        container.NewPadded(list),
    )
    d := dialog.NewCustom("Select Minecraft World","Cancel",content,parent)
    d.Resize(fyne.NewSize(760,560)); d.Show()
}
