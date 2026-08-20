package ui

import (
	"image/color"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"
)

// EmbeddedViewer is a Fyne layout host for the original OpenGL viewer process.
// On Windows the renderer's live native window is parented into this rectangle,
// so its existing input callbacks and GPU rendering remain intact.
type EmbeddedViewer struct {
	widget.BaseWidget

	session      *ViewerSession
	parentHandle uintptr
	mu           sync.Mutex
	timer        *time.Timer
	visible      bool
	attached     bool
}

func NewEmbeddedViewer(session *ViewerSession, parentHandle uintptr) *EmbeddedViewer {
	v := &EmbeddedViewer{session: session, parentHandle: parentHandle, visible: true}
	v.ExtendBaseWidget(v)
	return v
}

func (v *EmbeddedViewer) CreateRenderer() fyne.WidgetRenderer {
	background := canvas.NewRectangle(color.NRGBA{R: 8, G: 12, B: 15, A: 255})
	message := widget.NewLabel("Starting the live 3D renderer…")
	message.Alignment = fyne.TextAlignCenter
	return &nativeViewerHostRenderer{
		host:    v,
		objects: []fyne.CanvasObject{container.NewStack(background, container.NewCenter(message))},
	}
}

func (v *EmbeddedViewer) Fit() {
	if v.session != nil {
		v.session.Fit()
	}
}

func (v *EmbeddedViewer) Show() {
	v.BaseWidget.Show()
	v.mu.Lock()
	v.visible = true
	v.mu.Unlock()
	if v.session != nil {
		v.session.SetVisible(true)
	}
	v.scheduleSync()
}

func (v *EmbeddedViewer) Hide() {
	v.mu.Lock()
	v.visible = false
	v.mu.Unlock()
	if v.session != nil {
		v.session.SetVisible(false)
	}
	v.BaseWidget.Hide()
}

func (v *EmbeddedViewer) Close() {
	v.mu.Lock()
	if v.timer != nil {
		v.timer.Stop()
		v.timer = nil
	}
	v.mu.Unlock()
	if v.session != nil {
		v.session.Close()
	}
}

func (v *EmbeddedViewer) scheduleSync() {
	v.mu.Lock()
	if !v.visible {
		v.mu.Unlock()
		return
	}
	if v.timer != nil {
		v.timer.Stop()
	}
	v.timer = time.AfterFunc(20*time.Millisecond, v.syncRect)
	v.mu.Unlock()
}

func (v *EmbeddedViewer) syncRect() {
	if v.session == nil || v.parentHandle == 0 {
		return
	}
	driver := fyne.CurrentApp().Driver()
	canvasForHost := driver.CanvasForObject(v)
	if canvasForHost == nil {
		return
	}
	abs := driver.AbsolutePositionForObject(v)
	x, y := canvasForHost.PixelCoordinateForPosition(abs)
	scale := canvasForHost.Scale()
	width := int(v.Size().Width * scale)
	height := int(v.Size().Height * scale)
	// Keep the Fyne viewport controls clickable above the native child window.
	controlInset := int(48 * scale)
	y += controlInset
	height -= controlInset
	if width < 2 || height < 2 {
		return
	}
	v.mu.Lock()
	attached := v.attached
	if !attached {
		v.attached = true
	}
	v.mu.Unlock()
	if attached {
		v.session.SetRect(x, y, width, height)
	} else {
		v.session.Embed(v.parentHandle, x, y, width, height)
	}
}

type nativeViewerHostRenderer struct {
	host    *EmbeddedViewer
	objects []fyne.CanvasObject
}

func (r *nativeViewerHostRenderer) Layout(size fyne.Size) {
	for _, object := range r.objects {
		object.Resize(size)
	}
	r.host.scheduleSync()
}

func (r *nativeViewerHostRenderer) MinSize() fyne.Size           { return fyne.NewSize(320, 240) }
func (r *nativeViewerHostRenderer) Refresh()                     { r.host.scheduleSync() }
func (r *nativeViewerHostRenderer) Objects() []fyne.CanvasObject { return r.objects }
func (r *nativeViewerHostRenderer) Destroy()                     {}
