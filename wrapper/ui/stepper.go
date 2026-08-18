package ui

import (
	"fmt"
	"strconv"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/widget"
)

// StepperEntry is a numeric text field with mouse-wheel nudging and +/- buttons.
// The inner Entry remains a normal editable text control.
type StepperEntry struct {
	widget.BaseWidget
	entry   *widget.Entry
	step    int
	min     *int
	max     *int
	OnChanged func(string)
}

func NewStepperEntry(value string) *StepperEntry {
	s := &StepperEntry{entry: widget.NewEntry(), step: 1}
	s.entry.SetText(value)
	s.entry.OnChanged = func(v string) {
		if s.OnChanged != nil { s.OnChanged(v) }
	}
	s.ExtendBaseWidget(s)
	return s
}

func (s *StepperEntry) SetBounds(min, max int) {
	s.min = &min
	s.max = &max
}

func (s *StepperEntry) SetText(v string) { s.entry.SetText(v) }
func (s *StepperEntry) Text() string { return s.entry.Text }
func (s *StepperEntry) Int(fallback int) int {
	v, err := strconv.Atoi(strings.TrimSpace(s.Text()))
	if err != nil { return fallback }
	return v
}

func (s *StepperEntry) setInt(v int) {
	if s.min != nil && v < *s.min { v = *s.min }
	if s.max != nil && v > *s.max { v = *s.max }
	s.SetText(fmt.Sprintf("%d", v))
}

func (s *StepperEntry) nudge(delta int) { s.setInt(s.Int(0) + delta*s.step) }

func (s *StepperEntry) Scrolled(ev *fyne.ScrollEvent) {
	if ev.Scrolled.DY > 0 { s.nudge(1) }
	if ev.Scrolled.DY < 0 { s.nudge(-1) }
}

func (s *StepperEntry) CreateRenderer() fyne.WidgetRenderer {
	dec := widget.NewButton("−", func() { s.nudge(-1) })
	inc := widget.NewButton("+", func() { s.nudge(1) })
	dec.Importance = widget.LowImportance
	inc.Importance = widget.LowImportance
	content := container.NewBorder(nil, nil, dec, inc, s.entry)
	return &stepperRenderer{objects: []fyne.CanvasObject{content}, size: fyne.NewSize(120, 32)}
}

func (s *StepperEntry) MinSize() fyne.Size { return fyne.NewSize(120, 32) }

type stepperRenderer struct { objects []fyne.CanvasObject; size fyne.Size }
func (r *stepperRenderer) Layout(size fyne.Size) { r.size = size; r.objects[0].Resize(size) }
func (r *stepperRenderer) MinSize() fyne.Size { return fyne.NewSize(120, 32) }
func (r *stepperRenderer) Refresh() {}
func (r *stepperRenderer) Objects() []fyne.CanvasObject { return r.objects }
func (r *stepperRenderer) Destroy() {}

// AxisRange is a compact Front|link|Back selector. It starts collapsed and
// expands only when the small arrow is clicked.
type AxisRange struct {
	Label string
	Front *StepperEntry
	Back  *StepperEntry
	Link  *widget.Check
	Expanded bool
	Container *fyne.Container
	Arrow *widget.Button
}

func NewAxisRange(label string, front, back int, onChange func()) *AxisRange {
	r := &AxisRange{Label: label, Front: NewStepperEntry(fmt.Sprintf("%d", front)), Back: NewStepperEntry(fmt.Sprintf("%d", back))}
	r.Front.SetBounds(-30_000_000, 30_000_000)
	r.Back.SetBounds(-30_000_000, 30_000_000)
	r.Link = widget.NewCheck("", nil)
	r.Link.SetChecked(true)
	r.Link.OnChanged = func(v bool) {
		if v { r.Back.SetText(r.Front.Text()) }
		if onChange != nil { onChange() }
	}
	r.Front.OnChanged = func(v string) {
		if r.Link.Checked { r.Back.SetText(v) }
		if onChange != nil { onChange() }
	}
	r.Back.OnChanged = func(string) { if onChange != nil { onChange() } }

	r.Arrow = widget.NewButton("›", func() { r.Toggle() })
	r.Arrow.Importance = widget.LowImportance
	collapsed := container.NewBorder(nil, nil, nil, r.Arrow, r.Front)
	expandedRow := container.NewGridWithColumns(3,
		r.Front,
		container.NewCenter(container.NewHBox(widget.NewLabel("🔗"), r.Link)),
		r.Back,
	)
	expandedRow.Hide()
	r.Container = container.NewVBox(collapsed, expandedRow)
	return r
}

func (r *AxisRange) Toggle() {
	r.Expanded = !r.Expanded
	if r.Expanded {
		r.Arrow.SetText("⌄")
		if len(r.Container.Objects) > 1 { r.Container.Objects[1].Show() }
	} else {
		r.Arrow.SetText("›")
		if len(r.Container.Objects) > 1 { r.Container.Objects[1].Hide() }
	}
	r.Container.Refresh()
}

func (r *AxisRange) Bounds() (int, int) { return r.Front.Int(0), r.Back.Int(0) }

// Small helper used by compact field cards.
func stackSection(title string, body fyne.CanvasObject) fyne.CanvasObject {
	label := widget.NewLabel(title)
	label.TextStyle = fyne.TextStyle{Bold: true}
	return container.NewVBox(label, body, layout.NewSpacer())
}
