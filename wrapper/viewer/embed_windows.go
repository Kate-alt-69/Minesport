//go:build windows

package viewer

import (
	"fmt"
	"syscall"
	"unsafe"

	"github.com/go-gl/glfw/v3.3/glfw"
)

const (
	gwlStyle       = ^uintptr(15) // -16
	wsChild        = uintptr(0x40000000)
	wsVisible      = uintptr(0x10000000)
	wsPopup        = uintptr(0x80000000)
	wsCaption      = uintptr(0x00C00000)
	wsThickFrame   = uintptr(0x00040000)
	wsMinimizeBox  = uintptr(0x00020000)
	wsMaximizeBox  = uintptr(0x00010000)
	wsSysMenu      = uintptr(0x00080000)
	wsClipChildren = uintptr(0x02000000)
	wsClipSiblings = uintptr(0x04000000)

	swpNoActivate = uintptr(0x0010)
	swpNoZOrder   = uintptr(0x0004)
	swpShowWindow = uintptr(0x0040)
	swHide        = uintptr(0)
	swShow        = uintptr(5)
)

var (
	viewerUser32      = syscall.NewLazyDLL("user32.dll")
	procSetParent     = viewerUser32.NewProc("SetParent")
	procGetParent     = viewerUser32.NewProc("GetParent")
	procGetWindowLong = viewerUser32.NewProc("GetWindowLongPtrW")
	procSetWindowLong = viewerUser32.NewProc("SetWindowLongPtrW")
	procSetWindowPos  = viewerUser32.NewProc("SetWindowPos")
	procShowWindow    = viewerUser32.NewProc("ShowWindow")
)

type nativeEmbed struct {
	window *glfw.Window
	child  uintptr
	parent uintptr
}

func newNativeEmbed(window *glfw.Window) *nativeEmbed {
	return &nativeEmbed{window: window, child: uintptr(unsafe.Pointer(window.GetWin32Window()))}
}

func (e *nativeEmbed) Attach(parent uintptr) error {
	if e.child == 0 || parent == 0 {
		return fmt.Errorf("native window handle was unavailable")
	}
	procSetParent.Call(e.child, parent)
	actual, _, _ := procGetParent.Call(e.child)
	if actual != parent {
		return fmt.Errorf("Windows refused to attach the renderer window")
	}
	style, _, _ := procGetWindowLong.Call(e.child, gwlStyle)
	style &^= wsPopup | wsCaption | wsThickFrame | wsMinimizeBox | wsMaximizeBox | wsSysMenu
	style |= wsChild | wsVisible | wsClipChildren | wsClipSiblings
	procSetWindowLong.Call(e.child, gwlStyle, style)
	e.parent = parent
	return nil
}

func (e *nativeEmbed) SetRect(x, y, width, height int) {
	if e.child == 0 || width < 1 || height < 1 {
		return
	}
	procSetWindowPos.Call(e.child, 0, uintptr(x), uintptr(y), uintptr(width), uintptr(height), swpNoActivate|swpNoZOrder|swpShowWindow)
}

func (e *nativeEmbed) Show(show bool) {
	if e.child == 0 {
		return
	}
	command := swHide
	if show {
		command = swShow
	}
	procShowWindow.Call(e.child, command)
}
