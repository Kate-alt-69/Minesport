package ui

import (
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

type infoPopoverButton struct {
	widget.BaseWidget
	title string
	body  string
}

func newInfoPopoverButton(title, body string) *infoPopoverButton {
	b := &infoPopoverButton{title: title, body: body}
	b.ExtendBaseWidget(b)
	return b
}

func (b *infoPopoverButton) Tapped(*fyne.PointEvent) {
	driver := fyne.CurrentApp().Driver()
	parentCanvas := driver.CanvasForObject(b)
	if parentCanvas == nil {
		return
	}
	title := widget.NewLabel(b.title)
	title.TextStyle = fyne.TextStyle{Bold: true}
	body := widget.NewLabel(b.body)
	body.Wrapping = fyne.TextWrapWord
	content := container.NewVBox(title, widget.NewSeparator(), body)
	content.Resize(fyne.NewSize(340, 150))
	popup := widget.NewPopUp(container.NewGridWrap(fyne.NewSize(340, 150), container.NewPadded(content)), parentCanvas)
	popup.Resize(fyne.NewSize(356, 166))

	buttonPos := driver.AbsolutePositionForObject(b)
	canvasSize := parentCanvas.Size()
	x := buttonPos.X + b.Size().Width - 356
	if x < 8 {
		x = 8
	}
	if x+356 > canvasSize.Width-8 {
		x = canvasSize.Width - 364
	}
	y := buttonPos.Y + b.Size().Height + 6
	if y+166 > canvasSize.Height-8 {
		y = buttonPos.Y - 172
	}
	if y < 8 {
		y = 8
	}
	popup.ShowAtPosition(fyne.NewPos(x, y))
}

func (b *infoPopoverButton) CreateRenderer() fyne.WidgetRenderer {
	accent := theme.Color(theme.ColorNamePrimary)
	circle := canvas.NewCircle(color.Transparent)
	circle.StrokeColor = accent
	circle.StrokeWidth = 1.5
	label := canvas.NewText("i", accent)
	label.TextStyle = fyne.TextStyle{Bold: true}
	label.Alignment = fyne.TextAlignCenter
	return &infoPopoverRenderer{button: b, circle: circle, label: label, objects: []fyne.CanvasObject{circle, label}}
}

type infoPopoverRenderer struct {
	button  *infoPopoverButton
	circle  *canvas.Circle
	label   *canvas.Text
	objects []fyne.CanvasObject
}

func (r *infoPopoverRenderer) Layout(size fyne.Size) {
	diameter := minFloat32(size.Width, size.Height) - 4
	r.circle.Resize(fyne.NewSquareSize(diameter))
	r.circle.Move(fyne.NewPos((size.Width-diameter)/2, (size.Height-diameter)/2))
	r.label.Resize(size)
	r.label.Move(fyne.NewPos(0, 1))
}

func (r *infoPopoverRenderer) MinSize() fyne.Size           { return fyne.NewSquareSize(25) }
func (r *infoPopoverRenderer) Objects() []fyne.CanvasObject { return r.objects }
func (r *infoPopoverRenderer) Destroy()                     {}
func (r *infoPopoverRenderer) Refresh() {
	accent := theme.Color(theme.ColorNamePrimary)
	r.circle.StrokeColor = accent
	r.label.Color = accent
	r.circle.Refresh()
	r.label.Refresh()
}

func minFloat32(a, b float32) float32 {
	if a < b {
		return a
	}
	return b
}

var _ fyne.Tappable = (*infoPopoverButton)(nil)
