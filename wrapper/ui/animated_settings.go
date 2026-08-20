package ui

import (
	"image/color"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

type AnimatedSettingsButton struct {
	widget.BaseWidget
	mu        sync.Mutex
	pulse     float64
	animation *fyne.Animation
	onTapped  func()
}

func NewAnimatedSettingsButton(onTapped func()) *AnimatedSettingsButton {
	b := &AnimatedSettingsButton{onTapped: onTapped}
	b.ExtendBaseWidget(b)
	return b
}

func (b *AnimatedSettingsButton) Tapped(*fyne.PointEvent) {
	if b.onTapped != nil {
		b.onTapped()
	}
}

func (b *AnimatedSettingsButton) StartPulse() {
	b.StopPulse()
	animation := fyne.NewAnimation(900*time.Millisecond, func(value float32) {
		b.mu.Lock()
		b.pulse = float64(value)
		b.mu.Unlock()
		canvas.Refresh(b)
	})
	animation.AutoReverse = true
	animation.RepeatCount = fyne.AnimationRepeatForever
	b.mu.Lock()
	b.animation = animation
	b.mu.Unlock()
	animation.Start()
}

func (b *AnimatedSettingsButton) StopPulse() {
	b.mu.Lock()
	animation := b.animation
	b.animation = nil
	b.pulse = 0
	b.mu.Unlock()
	if animation != nil {
		animation.Stop()
	}
	canvas.Refresh(b)
}

func (b *AnimatedSettingsButton) CreateRenderer() fyne.WidgetRenderer {
	background := canvas.NewCircle(theme.Color(theme.ColorNameButton))
	background.StrokeColor = theme.Color(theme.ColorNamePrimary)
	background.StrokeWidth = 1
	icon := canvas.NewImageFromResource(theme.SettingsIcon())
	icon.FillMode = canvas.ImageFillContain
	return &animatedSettingsRenderer{button: b, background: background, icon: icon, objects: []fyne.CanvasObject{background, icon}}
}

type animatedSettingsRenderer struct {
	button     *AnimatedSettingsButton
	background *canvas.Circle
	icon       *canvas.Image
	objects    []fyne.CanvasObject
}

func (r *animatedSettingsRenderer) Layout(size fyne.Size) {
	diameter := minFloat32(size.Width, size.Height) - 2
	r.background.Resize(fyne.NewSquareSize(diameter))
	r.background.Move(fyne.NewPos((size.Width-diameter)/2, (size.Height-diameter)/2))
	iconSize := diameter * .56
	r.icon.Resize(fyne.NewSquareSize(iconSize))
	r.icon.Move(fyne.NewPos((size.Width-iconSize)/2, (size.Height-iconSize)/2))
}

func (r *animatedSettingsRenderer) MinSize() fyne.Size           { return fyne.NewSquareSize(34) }
func (r *animatedSettingsRenderer) Objects() []fyne.CanvasObject { return r.objects }
func (r *animatedSettingsRenderer) Destroy() {
	r.button.mu.Lock()
	animation := r.button.animation
	r.button.animation = nil
	r.button.mu.Unlock()
	if animation != nil {
		animation.Stop()
	}
}
func (r *animatedSettingsRenderer) Refresh() {
	r.button.mu.Lock()
	pulse := r.button.pulse
	r.button.mu.Unlock()
	r.background.FillColor = theme.Color(theme.ColorNameButton)
	r.background.StrokeColor = color.NRGBA{R: 68, G: uint8(120 + pulse*100), B: 245, A: 255}
	r.background.StrokeWidth = float32(1 + pulse*2)
	r.icon.Translucency = .18 * (1 - pulse)
	r.background.Refresh()
	r.icon.Refresh()
}

var _ fyne.Tappable = (*AnimatedSettingsButton)(nil)
