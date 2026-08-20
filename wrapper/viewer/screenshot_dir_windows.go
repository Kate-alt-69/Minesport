//go:build windows

package viewer

import "golang.org/x/sys/windows"

func picturesDirectory() (string, error) {
	return windows.KnownFolderPath(windows.FOLDERID_Pictures, windows.KF_FLAG_DEFAULT)
}
