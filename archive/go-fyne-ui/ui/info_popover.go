package ui

import (
	"image/color"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

const (
	infoPopoverPreferredWidth float32 = 356
	infoPopoverMinWidth       float32 = 220
	infoPopoverMinHeight      float32 = 128
	infoPopoverMaxHeight      float32 = 360
	infoPopoverMargin         float32 = 8
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

	// Labels do not clip their painted text to a fixed GridWrap cell. The old
	// 150px body therefore escaped the popup for longer help text. A scroll
	// viewport provides real clipping while retaining a compact popup for short
	// explanations.
	canvasSize := parentCanvas.Size()
	popupWidth := infoPopoverWidth(canvasSize.Width)
	popupHeight := infoPopoverHeight(b.body, canvasSize.Height)
	bodyHeight := popupHeight - 68
	bodyScroll := container.NewVScroll(body)
	bodyScroll.SetMinSize(fyne.NewSize(popupWidth-32, bodyHeight))
	content := container.NewBorder(container.NewVBox(title, widget.NewSeparator()), nil, nil, nil, bodyScroll)
	popup := widget.NewPopUp(container.NewPadded(content), parentCanvas)
	popup.Resize(fyne.NewSize(popupWidth, popupHeight))

	buttonPos := driver.AbsolutePositionForObject(b)
	x := buttonPos.X + b.Size().Width - popupWidth
	if x < infoPopoverMargin {
		x = infoPopoverMargin
	}
	if x+popupWidth > canvasSize.Width-infoPopoverMargin {
		x = canvasSize.Width - popupWidth - infoPopoverMargin
	}
	if x < infoPopoverMargin {
		x = infoPopoverMargin
	}
	y := buttonPos.Y + b.Size().Height + 6
	if y+popupHeight > canvasSize.Height-infoPopoverMargin {
		y = buttonPos.Y - popupHeight - 6
	}
	if y < infoPopoverMargin {
		y = infoPopoverMargin
	}
	popup.ShowAtPosition(fyne.NewPos(x, y))
}

func infoPopoverWidth(canvasWidth float32) float32 {
	available := canvasWidth - 2*infoPopoverMargin
	if available >= infoPopoverPreferredWidth {
		return infoPopoverPreferredWidth
	}
	if available >= infoPopoverMinWidth {
		return available
	}
	return infoPopoverMinWidth
}

func infoPopoverHeight(body string, canvasHeight float32) float32 {
	// At the current theme's normal text size, the usable width fits roughly
	// 43 characters per line. Count explicit paragraphs as well so the popup is
	// tall enough before the first frame is painted.
	lines := 0
	for _, paragraph := range strings.Split(body, "\n") {
		if paragraph == "" {
			lines++
			continue
		}
		lines += (len([]rune(paragraph)) + 42) / 43
	}
	height := float32(lines*20 + 68)
	if height < infoPopoverMinHeight {
		height = infoPopoverMinHeight
	}
	if height > infoPopoverMaxHeight {
		height = infoPopoverMaxHeight
	}
	available := canvasHeight - 2*infoPopoverMargin
	if available > 0 && height > available {
		height = available
	}
	return height
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
