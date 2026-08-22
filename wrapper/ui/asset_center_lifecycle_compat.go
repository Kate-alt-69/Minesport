package ui

// The old Asset Center navigation layer was removed in 0.2.0. These lifecycle
// hooks remain temporarily so older workbench startup code can compile while
// the real asset controls live directly inside Settings.
func installWorkbenchAssetCenter(_ *MinesportApp) {}

func cleanupWorkbenchAssetCenter(_ *MinesportApp) {}
