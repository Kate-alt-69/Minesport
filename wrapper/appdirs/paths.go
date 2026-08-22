package appdirs

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const (
	VendorDir = "kastrick's_software"
	AppDir    = "minesport"
)

// DataRoot stores durable per-user Minesport state. On Windows this is kept
// under LocalAppData so Minesport does not scatter folders across Local/Roaming.
func DataRoot() string {
	if override := strings.TrimSpace(os.Getenv("MINESPORT_DATA_DIR")); override != "" {
		return filepath.Clean(override)
	}

	home, _ := os.UserHomeDir()
	switch runtime.GOOS {
	case "windows":
		if local := strings.TrimSpace(os.Getenv("LOCALAPPDATA")); local != "" {
			return filepath.Join(local, VendorDir, AppDir)
		}
	case "darwin":
		if home != "" {
			return filepath.Join(home, "Library", "Application Support", VendorDir, AppDir)
		}
	default:
		if data := strings.TrimSpace(os.Getenv("XDG_DATA_HOME")); data != "" {
			return filepath.Join(data, VendorDir, AppDir)
		}
		if home != "" {
			return filepath.Join(home, ".local", "share", VendorDir, AppDir)
		}
	}

	if base, err := os.UserConfigDir(); err == nil && strings.TrimSpace(base) != "" {
		return filepath.Join(base, VendorDir, AppDir)
	}
	return filepath.Join(os.TempDir(), VendorDir, AppDir)
}

// CacheRoot stores data that can be regenerated. Minesport intentionally uses
// ~/.cache on every desktop OS, including Windows, so generated model/heightmap
// caches live in one predictable user-owned location.
func CacheRoot() string {
	if override := strings.TrimSpace(os.Getenv("MINESPORT_CACHE_DIR")); override != "" {
		return filepath.Clean(override)
	}
	if home, err := os.UserHomeDir(); err == nil && strings.TrimSpace(home) != "" {
		return filepath.Join(home, ".cache", VendorDir, AppDir)
	}
	if base, err := os.UserCacheDir(); err == nil && strings.TrimSpace(base) != "" {
		return filepath.Join(base, VendorDir, AppDir)
	}
	return filepath.Join(os.TempDir(), VendorDir, AppDir, ".cache")
}

func SettingsPath() string {
	return filepath.Join(DataRoot(), "settings.json")
}

func DiagnosticsRoot() string {
	return filepath.Join(DataRoot(), "diagnostics")
}
