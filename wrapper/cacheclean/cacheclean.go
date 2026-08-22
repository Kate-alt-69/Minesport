package cacheclean

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/kastrick/minesport/appdirs"
	"github.com/kastrick/minesport/bridgecapture"
	"github.com/kastrick/minesport/bridgecompat"
)

type Result struct {
	RemovedPaths []string
}

// RemoveAll deletes every Minesport-owned cache that can be regenerated.
// Durable settings, diagnostics, projects, worlds, exports, resource packs and
// the installer-bundled Bridge seed are intentionally outside this operation.
func RemoveAll() (Result, error) {
	result := Result{}
	var failures []error

	// Remove any currently staged temporary capture bridge first so a cleanup
	// requested during a session does not leave a transient JAR behind.
	bridgecapture.CleanupStaged()

	bridgePaths, bridgeErr := bridgecompat.ClearGeneratedCache()
	result.RemovedPaths = append(result.RemovedPaths, bridgePaths...)
	if bridgeErr != nil {
		failures = append(failures, bridgeErr)
	}

	root := filepath.Clean(appdirs.CacheRoot())
	if err := validateCacheRoot(root); err != nil {
		failures = append(failures, err)
	} else if info, err := os.Stat(root); err != nil {
		if !os.IsNotExist(err) {
			failures = append(failures, fmt.Errorf("inspect Minesport cache %s: %w", root, err))
		}
	} else if !info.IsDir() {
		failures = append(failures, fmt.Errorf("refusing to remove non-directory Minesport cache path %s", root))
	} else if err := os.RemoveAll(root); err != nil {
		failures = append(failures, fmt.Errorf("remove Minesport cache %s: %w", root, err))
	} else {
		result.RemovedPaths = append(result.RemovedPaths, root)
	}

	return result, errors.Join(failures...)
}

func validateCacheRoot(root string) error {
	root = filepath.Clean(strings.TrimSpace(root))
	if root == "" || root == "." {
		return fmt.Errorf("refusing to remove an empty Minesport cache path")
	}
	volume := filepath.VolumeName(root)
	volumeRoot := filepath.Clean(volume + string(filepath.Separator))
	if root == volumeRoot || root == string(filepath.Separator) {
		return fmt.Errorf("refusing to remove filesystem root as Minesport cache: %s", root)
	}
	if home, err := os.UserHomeDir(); err == nil && samePath(root, home) {
		return fmt.Errorf("refusing to remove the user home directory as Minesport cache: %s", root)
	}
	if samePath(root, appdirs.DataRoot()) {
		return fmt.Errorf("refusing to remove Minesport durable data as cache: %s", root)
	}
	return nil
}

func samePath(a, b string) bool {
	a = filepath.Clean(strings.TrimSpace(a))
	b = filepath.Clean(strings.TrimSpace(b))
	if a == "" || b == "" {
		return false
	}
	if filepath.Separator == '\\' {
		return strings.EqualFold(a, b)
	}
	return a == b
}
