//go:build !windows

package viewer

import (
	"os"
	"path/filepath"
)

func picturesDirectory() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Pictures"), nil
}
