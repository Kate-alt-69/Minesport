//go:build !windows

package viewer

import (
	"fmt"

	"github.com/go-gl/glfw/v3.3/glfw"
)

type nativeEmbed struct {
	window *glfw.Window
}

func newNativeEmbed(window *glfw.Window) *nativeEmbed { return &nativeEmbed{window: window} }

func (e *nativeEmbed) Attach(uintptr) error {
	return fmt.Errorf("native in-window hosting is currently available on Windows; opened the original renderer window instead")
}

func (e *nativeEmbed) SetRect(_, _, width, height int) {
	if width > 0 && height > 0 {
		e.window.SetSize(width, height)
	}
}

func (e *nativeEmbed) Show(show bool) {
	if show {
		e.window.Show()
	} else {
		e.window.Hide()
	}
}
