//go:build windows

package processutil

import (
	"os/exec"
	"syscall"
)

const createNoWindow uint32 = 0x08000000

// HideWindow prevents console applications launched by the GUI from opening a
// visible terminal while leaving their standard streams available to callers.
func HideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: createNoWindow,
	}
}
