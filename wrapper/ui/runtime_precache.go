package ui

import (
	"strings"

	"github.com/kastrick/minesport/bridgecapture"
	"github.com/kastrick/minesport/bridgecompat"
)

// precacheRuntimeModelsForSelectedWorld prepares the complete Minecraft baked
// block/state registry for the selected Fabric instance. The selected world is
// used only to identify the exact Minecraft version, loader, mods and config;
// capture itself is registry-wide and completely independent of the current
// world selection bounds.
func (ms *MinesportApp) precacheRuntimeModelsForSelectedWorld() {
	if ms == nil || normalizedLoader(ms.loaderType) != "fabric" {
		return
	}
	version, err := ms.validateBridgeRuntimeCapture()
	if err != nil {
		ms.appendLog("Runtime registry precache skipped: " + err.Error())
		ms.refreshWorkbenchSettingsActivity()
		return
	}
	if _, ok := bridgecapture.SnapshotPathForMods(version, ms.modsPath); ok {
		ms.appendLog("Full runtime model registry already cached for Minecraft " + version + " and the current mod set")
		ms.refreshWorkbenchSettingsActivity()
		return
	}

	ms.beginWorkbenchTaskV3("RUNTIME CACHE", "Preparing full Minecraft registry…", true)
	started, startErr := ms.generateRuntimeModelCache(
		false,
		func(percent int, message string) {
			detail := "Minecraft " + version + " · full registered block/state/model registry"
			ms.updateWorkbenchTaskV3(percent, message, detail)
		},
		func(jobErr error) {
			ms.refreshWorkbenchSettingsActivity()
			if jobErr != nil {
				ms.appendLog("[WARN] Background runtime registry precache failed: " + jobErr.Error())
				ms.finishWorkbenchTaskV3(false, "Runtime registry precache failed", jobErr.Error())
				return
			}
			ms.finishWorkbenchTaskV3(true, "Full runtime registry ready", "Minecraft "+version+" · current mod set")
			ms.appendLog("Full Minecraft runtime registry precache complete for " + version)
		},
	)
	if startErr != nil {
		ms.appendLog("[WARN] Runtime registry precache could not start: " + startErr.Error())
		ms.finishWorkbenchTaskV3(false, "Runtime registry precache unavailable", startErr.Error())
		ms.refreshWorkbenchSettingsActivity()
		return
	}
	if !started {
		// A matching snapshot appeared between the initial check and job setup.
		ms.finishWorkbenchTaskV3(true, "Full runtime registry already ready", "Minecraft "+version+" · current mod set")
		ms.refreshWorkbenchSettingsActivity()
		return
	}

	// generateRuntimeModelCache flips the shared job state synchronously before
	// launching its worker goroutine. Rebuild Settings now so an already-open
	// Advanced pane immediately changes from NOT CACHED to PREPARING.
	ms.refreshWorkbenchSettingsActivity()
	ms.appendLog("Preparing full registered Minecraft block/model registry for " + version + " · selection bounds are not used")
}

func (ms *MinesportApp) runtimeCacheIsPreparingForCurrentWorld() bool {
	if ms == nil {
		return false
	}
	state := runtimeCacheJob(ms)
	state.mu.Lock()
	defer state.mu.Unlock()
	if !state.running {
		return false
	}
	return state.version == bridgecompat.NormalizeVersion(ms.mcVersion) && strings.EqualFold(state.modsPath, ms.modsPath)
}
