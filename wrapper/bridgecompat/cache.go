package bridgecompat

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/kastrick/minesport/appdirs"
)

// ClearGeneratedCache removes only Bridge artifacts that Minesport can recreate.
// The bundled bridge under bridge-data/bundled is an installation asset and is
// intentionally preserved; deleting it would require reinstalling Minesport.
func ClearGeneratedCache() ([]string, error) {
	ensureMu.Lock()
	defer ensureMu.Unlock()

	candidates := []string{
		filepath.Join(supportRoot(), "compiled"),
		filepath.Join(appdirs.CacheRoot(), "bridges"),
		buildWorkspaceRoot(),
	}

	seen := map[string]bool{}
	removed := make([]string, 0, len(candidates))
	var failures []error
	for _, candidate := range candidates {
		candidate = filepath.Clean(candidate)
		if candidate == "" || candidate == "." || seen[candidate] {
			continue
		}
		seen[candidate] = true
		if info, err := os.Stat(candidate); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			failures = append(failures, fmt.Errorf("inspect Bridge cache %s: %w", candidate, err))
			continue
		} else if !info.IsDir() {
			failures = append(failures, fmt.Errorf("refusing to remove non-directory Bridge cache path %s", candidate))
			continue
		}
		if err := os.RemoveAll(candidate); err != nil {
			failures = append(failures, fmt.Errorf("remove Bridge cache %s: %w", candidate, err))
			continue
		}
		removed = append(removed, candidate)
	}
	return removed, errors.Join(failures...)
}
