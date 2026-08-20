//go:build windows

package processutil

import (
	"os/exec"
	"testing"
)

func TestHideWindow(t *testing.T) {
	cmd := exec.Command("cmd.exe", "/d", "/c", "exit 0")
	HideWindow(cmd)

	if cmd.SysProcAttr == nil {
		t.Fatal("HideWindow did not configure SysProcAttr")
	}
	if !cmd.SysProcAttr.HideWindow {
		t.Fatal("HideWindow did not set HideWindow")
	}
	if cmd.SysProcAttr.CreationFlags&createNoWindow == 0 {
		t.Fatal("HideWindow did not set CREATE_NO_WINDOW")
	}
}
