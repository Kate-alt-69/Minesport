package ui

import (
	"fmt"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/launcher"
)

// relativeTime formats a timestamp the way most launchers show "last
// played" — relative for anything recent, a plain date once it's old
// enough that "N days ago" stops being useful at a glance.
func relativeTime(t time.Time) string {
	if t.IsZero() {
		return "unknown"
	}
	d := time.Since(t)
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		m := int(d.Minutes())
		if m == 1 {
			return "1 minute ago"
		}
		return fmt.Sprintf("%d minutes ago", m)
	case d < 24*time.Hour:
		h := int(d.Hours())
		if h == 1 {
			return "1 hour ago"
		}
		return fmt.Sprintf("%d hours ago", h)
	case d < 30*24*time.Hour:
		days := int(d.Hours() / 24)
		if days == 1 {
			return "yesterday"
		}
		return fmt.Sprintf("%d days ago", days)
	default:
		return t.Format("Jan 2, 2006")
	}
}

// launcherIcon picks a themed icon per launcher type. Fyne doesn't let us
// ship real launcher logos here (brand icons aren't ours to redistribute),
// so this leans on Fyne's own icon set for a bit of visual variety instead
// of every row looking identical.
func launcherIcon(t launcher.LauncherType) fyne.Resource {
	switch t {
	case launcher.LauncherOfficial:
		return theme.HomeIcon()
	case launcher.LauncherPrism, launcher.LauncherMultiMC:
		return theme.ViewRestoreIcon()
	case launcher.LauncherCurseForge, launcher.LauncherATLauncher:
		return theme.StorageIcon()
	default:
		return theme.ComputerIcon() // FreesmLauncher and anything else
	}
}

func loaderIcon(l launcher.ModLoader) fyne.Resource {
	if l == launcher.LoaderVanilla {
		return theme.HomeIcon()
	}
	return theme.SettingsIcon() // any mod loader — Fabric/Forge/NeoForge/Quilt
}

// iconRow builds a consistent icon + title (bold) + subtitle (dim) row,
// used for all three list steps below so launcher/instance/world entries
// share one visual language instead of three different plain-label lists.
func iconRow() fyne.CanvasObject {
	icon := widget.NewIcon(nil)
	title := widget.NewLabel("")
	title.TextStyle = fyne.TextStyle{Bold: true}
	subtitle := widget.NewLabel("")
	subtitle.TextStyle = fyne.TextStyle{Italic: true}

	text := container.NewVBox(title, subtitle)
	row := container.NewBorder(nil, nil, container.NewCenter(icon), nil, text)
	return container.NewPadded(row)
}

func setIconRow(obj fyne.CanvasObject, res fyne.Resource, title, subtitle string) {
	row := obj.(*fyne.Container).Objects[0].(*fyne.Container) // padded → border row
	iconWrap := row.Objects[1].(*fyne.Container)               // NewCenter wrapper
	icon := iconWrap.Objects[0].(*widget.Icon)
	text := row.Objects[0].(*fyne.Container) // VBox(title, subtitle)

	icon.SetResource(res)
	text.Objects[0].(*widget.Label).SetText(title)
	text.Objects[1].(*widget.Label).SetText(subtitle)
}

// ShowWorldPicker opens a multi-step dialog:
//
//	Launcher → Instance → World
//
// Calls onSelect(worldPath, modsPath) when user confirms.
func ShowWorldPicker(parent fyne.Window, onSelect func(worldPath, modsPath string)) {
	launchers := launcher.DiscoverAll()

	if len(launchers) == 0 {
		dialog.ShowError(fmt.Errorf("no Minecraft launchers found on this system"), parent)
		return
	}

	// ── Breadcrumb ─────────────────────────────────────────────────────────
	breadcrumb := widget.NewRichText()
	breadcrumb.Wrapping = fyne.TextWrapOff

	// ── Step 1: Launcher picker ───────────────────────────────────────────────
	launcherList := widget.NewList(
		func() int { return len(launchers) },
		iconRow,
		func(i widget.ListItemID, obj fyne.CanvasObject) {
			l := launchers[i]
			setIconRow(obj, launcherIcon(l.Type), l.Name, l.RootPath)
		},
	)

	selectedLauncher := -1
	launcherList.OnSelected = func(i widget.ListItemID) {
		selectedLauncher = i
	}

	launcherStep := widget.NewCard("Select a Launcher", "Where should Minesport look for worlds?",
		container.NewStack(launcherList))

	// ── Step 2: Instance picker ───────────────────────────────────────────────
	var instances []launcher.Instance

	instanceList := widget.NewList(
		func() int { return len(instances) },
		iconRow,
		func(i widget.ListItemID, obj fyne.CanvasObject) {
			inst := instances[i]
			polymer := ""
			if inst.HasPolymer() {
				polymer = " · Polymer ✓"
			}
			subtitle := fmt.Sprintf("MC %s · %s · %d world(s)%s", inst.Version, inst.Loader, len(inst.Worlds), polymer)
			setIconRow(obj, loaderIcon(inst.Loader), inst.Name, subtitle)
		},
	)

	selectedInstance := -1
	instanceList.OnSelected = func(i widget.ListItemID) {
		selectedInstance = i
	}

	instanceStep := widget.NewCard("Select an Instance", "Which game version/profile?",
		container.NewStack(instanceList))

	// ── Step 3: World picker ──────────────────────────────────────────────────
	var worlds []launcher.World

	worldList := widget.NewList(
		func() int { return len(worlds) },
		iconRow,
		func(i widget.ListItemID, obj fyne.CanvasObject) {
			w := worlds[i]
			subtitle := fmt.Sprintf("played %s · %s", relativeTime(w.LastPlayed), w.Path)
			setIconRow(obj, theme.FileIcon(), w.Name, subtitle)
		},
	)

	selectedWorld := -1
	worldList.OnSelected = func(i widget.ListItemID) {
		selectedWorld = i
	}

	worldStep := widget.NewCard("Select a World", "Which save do you want to export?",
		container.NewStack(worldList))

	// ── Navigation ────────────────────────────────────────────────────────────
	pages := container.NewStack(launcherStep, instanceStep, worldStep)
	instanceStep.Hide()
	worldStep.Hide()

	backBtn := widget.NewButtonWithIcon("Back", theme.NavigateBackIcon(), nil)
	selectBtn := widget.NewButtonWithIcon("Select", theme.ConfirmIcon(), nil)
	selectBtn.Importance = widget.HighImportance
	selectBtn.Hide()
	backBtn.Hide()

	currentStep := 0

	// rebuildBreadcrumb renders "Launcher › Instance › World" with the
	// current step bold and completed steps showing what was actually
	// chosen instead of the generic step name.
	rebuildBreadcrumb := func() {
		seg := func(text string, active bool) *widget.TextSegment {
			return &widget.TextSegment{
				Text:  text,
				Style: widget.RichTextStyle{TextStyle: fyne.TextStyle{Bold: active}},
			}
		}
		sep := &widget.TextSegment{Text: "   ›   "}

		launcherText := "1. Launcher"
		if selectedLauncher >= 0 {
			launcherText = launchers[selectedLauncher].Name
		}
		instanceText := "2. Instance"
		if selectedInstance >= 0 {
			instanceText = instances[selectedInstance].Name
		}
		worldText := "3. World"
		if selectedWorld >= 0 {
			worldText = worlds[selectedWorld].Name
		}

		breadcrumb.Segments = []widget.RichTextSegment{
			seg(launcherText, currentStep == 0),
			sep,
			seg(instanceText, currentStep == 1),
			sep,
			seg(worldText, currentStep == 2),
		}
		breadcrumb.Refresh()
	}

	updateNav := func() {
		switch currentStep {
		case 0:
			backBtn.Hide()
			selectBtn.Hide()
			launcherStep.Show()
			instanceStep.Hide()
			worldStep.Hide()

		case 1:
			backBtn.Show()
			selectBtn.Hide()
			launcherStep.Hide()
			instanceStep.Show()
			worldStep.Hide()

		case 2:
			backBtn.Show()
			selectBtn.Show()
			launcherStep.Hide()
			instanceStep.Hide()
			worldStep.Show()
		}
		rebuildBreadcrumb()
	}

	// Selecting a launcher or instance advances immediately — no separate
	// Next click needed. World stays an explicit Select since that's the
	// action that actually closes the dialog and hands off a result.
	launcherList.OnSelected = func(i widget.ListItemID) {
		selectedLauncher = i

		instances = launcher.DiscoverInstances(launchers[i])
		instanceList.Refresh()
		if len(instances) == 0 {
			dialog.ShowError(fmt.Errorf("no instances found for this launcher"), parent)
			selectedLauncher = -1
			return
		}
		currentStep = 1
		selectedInstance = -1
		updateNav()
	}

	instanceList.OnSelected = func(i widget.ListItemID) {
		selectedInstance = i

		worlds = instances[i].Worlds
		worldList.Refresh()
		if len(worlds) == 0 {
			dialog.ShowError(fmt.Errorf("no worlds found in this instance"), parent)
			selectedInstance = -1
			return
		}
		currentStep = 2
		selectedWorld = -1
		updateNav()
	}

	worldList.OnSelected = func(i widget.ListItemID) {
		selectedWorld = i
		rebuildBreadcrumb()
	}

	backBtn.OnTapped = func() {
		currentStep--
		selectedWorld = -1
		updateNav()
	}

	selectBtn.OnTapped = func() {
		if selectedWorld < 0 {
			dialog.ShowError(fmt.Errorf("please select a world first"), parent)
			return
		}
		world := worlds[selectedWorld]
		modsPath := ""
		if selectedInstance >= 0 {
			modsPath = instances[selectedInstance].ModsPath
		}
		onSelect(world.Path, modsPath)
	}

	rebuildBreadcrumb()

	navBar := container.NewBorder(nil, nil, backBtn, selectBtn, nil)
	header := container.NewPadded(breadcrumb)

	content := container.NewBorder(header, navBar, nil, nil, pages)

	d := dialog.NewCustom("Select Minecraft World", "Cancel", content, parent)
	d.Resize(fyne.NewSize(680, 480))
	d.Show()
}
