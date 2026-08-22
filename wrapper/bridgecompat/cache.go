package bridgecompat

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/kastrick/minesport/appdirs"
)

// generatedCacheUseMu protects long-lived users of the generated Bridge cache
// (especially disposable Minecraft workers) from destructive diagnostics cleanup.
// Workers hold a read lock for their lifetime; cleanup only proceeds when it can
// acquire the write lock immediately.
var generatedCacheUseMu sync.RWMutex

// ClearGeneratedCache removes only Bridge artifacts that Minesport can recreate.
// The bundled bridge under bridge-data/bundled is an installation asset and is
// intentionally preserved; deleting it would require reinstalling Minesport.
func ClearGeneratedCache() ([]string, error) {
	if !generatedCacheUseMu.TryLock() {
		return nil, fmt.Errorf("Bridge/runtime cache is currently in use; wait for the active runtime-model job to finish or cancel it first")
	}
	defer generatedCacheUseMu.Unlock()

	ensureMu.Lock()
	defer ensureMu.Unlock()

	rawCandidates := []string{
		filepath.Join(supportRoot(), "compiled"),
		filepath.Join(appdirs.CacheRoot(), "bridges"),
		buildWorkspaceRoot(),
	}

	// Build and validate the entire destructive plan first. If any configured
	// path is suspicious, nothing is removed.
	seen := map[string]bool{}
	candidates := make([]string, 0, len(rawCandidates))
	for _, candidate := range rawCandidates {
		candidate = filepath.Clean(candidate)
		if candidate == "" || candidate == "." || seen[candidate] {
			continue
		}
		seen[candidate] = true
		if err := validateGeneratedCachePath(candidate); err != nil {
			return nil, err
		}
		candidates = append(candidates, candidate)
	}

	removed := make([]string, 0, len(candidates))
	var failures []error
	for _, candidate := range candidates {
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

func validateGeneratedCachePath(candidate string) error {
	candidate = filepath.Clean(strings.TrimSpace(candidate))
	if candidate == "" || candidate == "." {
		return fmt.Errorf("refusing to remove an empty Bridge cache path")
	}
	volume := filepath.VolumeName(candidate)
	root := filepath.Clean(volume + string(filepath.Separator))
	if candidate == root || candidate == string(filepath.Separator) {
		return fmt.Errorf("refusing to remove filesystem root as Bridge cache: %s", candidate)
	}

	// The generated targets themselves must be dedicated subdirectories. This
	// also makes a dangerous MINESPORT_BRIDGE_DATA override such as C:\ or /
	// fail closed instead of turning <override>/compiled into an arbitrary path.
	trimmed := strings.TrimPrefix(candidate, volume)
	trimmed = strings.Trim(trimmed, string(filepath.Separator))
	depth := 0
	if trimmed != "" {
		for _, part := range strings.Split(trimmed, string(filepath.Separator)) {
			if strings.TrimSpace(part) != "" {
				depth++
			}
		}
	}
	if depth < 2 {
		return fmt.Errorf("refusing to remove an overly broad Bridge cache path: %s", candidate)
	}

	base := strings.ToLower(filepath.Base(candidate))
	if base != "compiled" && base != "bridges" && base != "bridge-build" {
		return fmt.Errorf("refusing to remove unexpected Bridge cache path: %s", candidate)
	}
	return nil
}
