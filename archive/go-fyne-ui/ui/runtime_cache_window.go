package ui

import (
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

type runtimeCacheWindowState struct {
	window  fyne.Window
	stage   *widget.Label
	detail  *widget.Label
	bar     *widget.ProgressBar
	cancel  *widget.Button
	version string
}

var runtimeCacheWindows sync.Map

func runtimeCacheWindowFor(ms *MinesportApp) *runtimeCacheWindowState {
	if value, ok := runtimeCacheWindows.Load(ms); ok {
		state, _ := value.(*runtimeCacheWindowState)
		return state
	}
	return nil
}

// showRuntimeCacheWindow intentionally hides the heavy workbench while the
// disposable Minecraft worker starts. This keeps the export flow focused and
// avoids continuously repainting the 2D/3D workbench while Gradle + Minecraft
// are consuming CPU/GPU on older machines.
func (ms *MinesportApp) showRuntimeCacheWindow(version string) {
	if ms == nil || ms.fyneApp == nil {
		return
	}
	if existing := runtimeCacheWindowFor(ms); existing != nil {
		existing.version = version
		existing.stage.SetText("Preparing Minecraft runtime models…")
		existing.detail.SetText("Minecraft " + version + " · exact current mod set")
		existing.bar.SetValue(0)
		return
	}

	w := ms.fyneApp.NewWindow("Minesport — Preparing runtime models")
	w.SetFixedSize(true)

	spinner := widget.NewActivity()
	spinner.Start()
	stage := widget.NewLabelWithStyle(
		"Preparing Minecraft runtime models…",
		fyne.TextAlignLeading,
		fyne.TextStyle{Bold: true},
	)
	stage.Wrapping = fyne.TextWrapWord
	detail := widget.NewLabel("Minecraft " + version + " · exact current mod set")
	detail.Wrapping = fyne.TextWrapWord
	bar := widget.NewProgressBar()
	bar.SetValue(0)

	cancel := widget.NewButtonWithIcon("Cancel", theme.CancelIcon(), nil)
	state := &runtimeCacheWindowState{
		window:  w,
		stage:   stage,
		detail:  detail,
		bar:     bar,
		cancel:  cancel,
		version: version,
	}
	cancel.OnTapped = func() {
		cancel.Disable()
		stage.SetText("Cancelling runtime-model cache…")
		detail.SetText("Stopping the disposable Minecraft worker safely.")
		ms.cancelRuntimeModelCacheGeneration()
	}

	content := container.NewVBox(
		container.NewBorder(nil, nil, spinner, nil, stage),
		detail,
		bar,
		widget.NewSeparator(),
		widget.NewLabel("Minesport is caching Minecraft's registered baked block models. Your world is not opened or modified."),
		container.NewHBox(cancel),
	)
	w.SetContent(container.NewPadded(content))
	w.Resize(fyne.NewSize(520, 190))
	w.CenterOnScreen()
	w.SetCloseIntercept(func() {
		cancel.OnTapped()
	})

	runtimeCacheWindows.Store(ms, state)
	if ms.window != nil {
		ms.window.Hide()
	}
	w.Show()
}

func (ms *MinesportApp) updateRuntimeCacheWindow(percent int, message string) {
	state := runtimeCacheWindowFor(ms)
	if state == nil {
		return
	}
	if percent < 0 {
		percent = 0
	} else if percent > 100 {
		percent = 100
	}
	state.bar.SetValue(float64(percent) / 100.0)
	if message != "" {
		state.stage.SetText(message)
	}
	state.detail.SetText("Minecraft " + state.version + " · exact current mod set")
}

func (ms *MinesportApp) closeRuntimeCacheWindow() {
	value, ok := runtimeCacheWindows.LoadAndDelete(ms)
	if ok {
		state, _ := value.(*runtimeCacheWindowState)
		if state != nil && state.window != nil {
			state.window.SetCloseIntercept(nil)
			state.window.Close()
		}
	}
	if ms != nil && ms.window != nil {
		ms.window.Show()
		ms.window.RequestFocus()
	}
}
