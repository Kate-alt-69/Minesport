package ui

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/ipc"
)

type exportPreflightState struct {
	result       *widget.Label
	run          *widget.Button
	preset       *widget.Select
	lastPreset   string
	optimizer    *widget.Label
	lastAnalysis *previewBlockAnalysis
}

var exportPreflightStates sync.Map

var builtInExportPresets = []string{
	"Custom",
	"Blender Animation",
	"Blender Lightweight",
	"Raw Minecraft",
	"Modded Accurate",
}

// installWorkbenchEnhancements decorates the existing Export activity instead
// of creating another copy of the workbench shell. Future workflow tools can
// use the same pattern to extend an activity holder in place.
func (ms *MinesportApp) installWorkbenchEnhancements() {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	holderObject := wb.panes[workbenchPaneExport]
	holder, ok := holderObject.(*fyne.Container)
	if !ok || len(holder.Objects) == 0 {
		return
	}
	if _, loaded := exportPreflightStates.Load(ms); loaded {
		return
	}

	base := holder.Objects[0]
	state := &exportPreflightState{}

	state.preset = widget.NewSelect(builtInExportPresets, func(choice string) {
		if strings.TrimSpace(choice) == "" || choice == "Custom" {
			state.lastPreset = "Custom"
			return
		}
		ms.applyBuiltInExportPreset(choice)
		state.lastPreset = choice
		if state.lastAnalysis != nil && state.optimizer != nil {
			state.optimizer.SetText(ms.optimizationAnalysisText(*state.lastAnalysis))
		}
	})
	state.preset.SetSelected("Custom")

	state.run = widget.NewButtonWithIcon("Run quick preflight", theme.SearchIcon(), func() {
		ms.runQuickPreflight()
	})
	state.result = widget.NewLabel("Preflight has not been run for this selection.")
	state.result.Wrapping = fyne.TextWrapWord
	state.optimizer = widget.NewLabel("Run Quick Preflight to populate the optimization analyzer.")
	state.optimizer.Wrapping = fyne.TextWrapWord

	presetHint := widget.NewLabel("Presets configure the current export controls; Advanced settings remain available underneath.")
	presetHint.Wrapping = fyne.TextWrapWord
	presetCard := widget.NewCard("PRESET", "", container.NewVBox(state.preset, presetHint))
	preflightCard := widget.NewCard("QUICK PREFLIGHT", "", container.NewVBox(state.run, state.result))

	optimizeMore := widget.NewButton("Apply safe optimization", func() {
		ms.settings.OptimizeOutputEnabled = true
		ms.settings.FlatterOptimizationEnabled = true
		ms.settings.HiddenBlockCullingEnabled = false
		if ms.optimizeCheck != nil {
			ms.optimizeCheck.SetChecked(true)
		}
		ms.applySettings(ms.settings)
		ms.refreshWorkbenchSettingsActivity()
		if state.lastAnalysis != nil {
			state.optimizer.SetText(ms.optimizationAnalysisText(*state.lastAnalysis))
		}
		ms.appendLog("Optimization analyzer applied safe optimization: face culling + FLATTER")
	})
	optimizerCard := widget.NewCard(
		"OPTIMIZATION ANALYZER",
		"Logical pressure before final geometry compilation",
		container.NewVBox(state.optimizer, optimizeMore),
	)

	holder.RemoveAll()
	holder.Add(container.NewBorder(
		presetCard,
		container.NewVBox(preflightCard, optimizerCard),
		nil,
		nil,
		base,
	))
	exportPreflightStates.Store(ms, state)
}

func cleanupWorkbenchEnhancements(ms *MinesportApp) {
	exportPreflightStates.Delete(ms)
}

func (ms *MinesportApp) applyBuiltInExportPreset(name string) {
	if ms.formatSelect == nil || ms.modeSelect == nil || ms.optimizeCheck == nil {
		return
	}

	switch name {
	case "Blender Animation":
		ms.formatSelect.SetSelected("glTF 2.0")
		ms.modeSelect.SetSelected("Individual blocks")
		ms.optimizeCheck.SetChecked(true)
		ms.settings.OptimizeOutputEnabled = true
		ms.settings.HiddenBlockCullingEnabled = false
		ms.settings.FlatterOptimizationEnabled = true
		ms.settings.BlenderExportEnabled = true
	case "Blender Lightweight":
		ms.formatSelect.SetSelected("glTF 2.0")
		ms.modeSelect.SetSelected("Grouped")
		ms.optimizeCheck.SetChecked(true)
		ms.settings.OptimizeOutputEnabled = true
		ms.settings.HiddenBlockCullingEnabled = true
		ms.settings.FlatterOptimizationEnabled = true
		ms.settings.BlenderExportEnabled = true
	case "Raw Minecraft":
		ms.formatSelect.SetSelected("glTF 2.0")
		ms.modeSelect.SetSelected("Individual blocks")
		ms.optimizeCheck.SetChecked(false)
		ms.settings.OptimizeOutputEnabled = false
		ms.settings.HiddenBlockCullingEnabled = false
		ms.settings.FlatterOptimizationEnabled = false
		ms.settings.BlenderExportEnabled = false
	case "Modded Accurate":
		ms.formatSelect.SetSelected("glTF 2.0")
		ms.modeSelect.SetSelected("Grouped")
		ms.optimizeCheck.SetChecked(false)
		ms.settings.OptimizeOutputEnabled = true
		ms.settings.HiddenBlockCullingEnabled = false
		ms.settings.FlatterOptimizationEnabled = false
		ms.settings.BlenderExportEnabled = true
	default:
		return
	}

	ms.applySettings(ms.settings)
	ms.refreshWorkbenchSettingsActivity()
	ms.appendLog("Applied export preset: " + name)
}

func (ms *MinesportApp) refreshWorkbenchSettingsActivity() {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	holderObject := wb.panes[workbenchPaneSettings]
	holder, ok := holderObject.(*fyne.Container)
	if !ok {
		return
	}
	holder.RemoveAll()
	holder.Add(ms.buildWorkbenchSettingsPane())
	// Reattach workbench decorators that live above the base Settings pane.
	workbenchAssetCenterStates.Delete(ms)
	installWorkbenchAssetCenter(ms)
}

func (ms *MinesportApp) setPreflightBusy(state *exportPreflightState, busy bool) {
	if state != nil && state.run != nil {
		if busy {
			state.run.Disable()
		} else {
			state.run.Enable()
		}
	}
	if ms.exportBtn != nil {
		if busy {
			ms.exportBtn.Disable()
		} else if ms.worldPath != "" && ms.isEngineAvailable() {
			ms.exportBtn.Enable()
		}
	}
}

func (ms *MinesportApp) runQuickPreflight() {
	stateValue, ok := exportPreflightStates.Load(ms)
	if !ok {
		return
	}
	state, _ := stateValue.(*exportPreflightState)
	if state == nil || state.run == nil || state.result == nil {
		return
	}
	if ms.worldPath == "" {
		state.result.SetText("Select a Minecraft world before running preflight.")
		return
	}
	if !ms.isEngineAvailable() {
		state.result.SetText("The core engine is unavailable. See the debug console for startup details.")
		return
	}

	params := ipc.ListBlocksParams{WorldPath: ms.worldPath, ModsPath: ms.modsPath, ModLoader: normalizedLoader(ms.loaderType)}
	if ms.selectionModeSelect.Selected == "Bubble selection" {
		cx, cy, cz := ms.centerX.Int(0), ms.centerY.Int(64), ms.centerZ.Int(0)
		rx, ry, rz := ms.radiusX.Int(32), ms.radiusY.Int(32), ms.radiusZ.Int(32)
		params.MinX, params.MaxX = cx-rx, cx+rx
		params.MinY, params.MaxY = cy-ry, cy+ry
		params.MinZ, params.MaxZ = cz-rz, cz+rz
		params.CenterX, params.CenterY, params.CenterZ = &cx, &cy, &cz
		params.RadiusX, params.RadiusY, params.RadiusZ = &rx, &ry, &rz
	} else {
		params.MinX, params.MaxX = ms.minXRange.Bounds()
		params.MinY, params.MaxY = ms.minYRange.Bounds()
		params.MinZ, params.MaxZ = ms.minZRange.Bounds()
	}

	ms.setPreflightBusy(state, true)
	state.result.SetText("Scanning the selected world region…")
	ms.beginWorkbenchTaskV3("PREFLIGHT", "Scanning selected blocks…", false)

	go func() {
		file, count, err := ms.engine.ListBlocks(params)
		if err != nil {
			ms.setPreflightBusy(state, false)
			state.result.SetText("Preflight failed. See the debug console for details.")
			ms.finishWorkbenchTaskV3(false, "Preflight failed", err.Error())
			return
		}

		analysis, err := analyzePreviewBlockFile(file)
		if err != nil {
			ms.setPreflightBusy(state, false)
			state.result.SetText(fmt.Sprintf("%s solid blocks found, but diagnostics could not read the preview list.", formatCount(count)))
			ms.finishWorkbenchTaskV3(false, "Preflight diagnostics failed", err.Error())
			return
		}
		if analysis.Blocks == 0 && count > 0 {
			analysis.Blocks = count
		}

		state.lastAnalysis = &analysis
		ms.setPreflightBusy(state, false)
		state.result.SetText(ms.quickPreflightText(analysis))
		if state.optimizer != nil {
			state.optimizer.SetText(ms.optimizationAnalysisText(analysis))
		}
		ms.finishWorkbenchTaskV3(true, "Preflight ready", fmt.Sprintf("%s solid blocks · %s block types", formatCount(analysis.Blocks), formatCount(analysis.UniqueTypes)))
	}()
}

type previewBlockAnalysis struct {
	Blocks             int
	UniqueTypes        int
	UnresolvedTextures int
	TopTypes           []string
	TypeCounts         map[string]int
}

type previewBlockRecord struct {
	ID            string `json:"id"`
	TextureTop    string `json:"textureTop"`
	TextureSide   string `json:"textureSide"`
	TextureBottom string `json:"textureBottom"`
}

func analyzePreviewBlockFile(path string) (previewBlockAnalysis, error) {
	file, err := os.Open(path)
	if err != nil {
		return previewBlockAnalysis{}, err
	}
	defer file.Close()

	decoder := json.NewDecoder(bufio.NewReaderSize(file, 256*1024))
	token, err := decoder.Token()
	if err != nil {
		return previewBlockAnalysis{}, err
	}
	if delimiter, ok := token.(json.Delim); !ok || delimiter != '[' {
		return previewBlockAnalysis{}, fmt.Errorf("preview block list is not a JSON array")
	}

	counts := map[string]int{}
	result := previewBlockAnalysis{TypeCounts: counts}
	for decoder.More() {
		var record previewBlockRecord
		if err := decoder.Decode(&record); err != nil {
			return previewBlockAnalysis{}, err
		}
		result.Blocks++
		id := strings.TrimSpace(record.ID)
		if id == "" {
			id = "minecraft:unknown"
		}
		counts[id]++
		if record.TextureTop == "" && record.TextureSide == "" && record.TextureBottom == "" {
			result.UnresolvedTextures++
		}
	}
	if _, err := decoder.Token(); err != nil {
		return previewBlockAnalysis{}, err
	}

	result.UniqueTypes = len(counts)
	type frequency struct {
		ID    string
		Count int
	}
	frequencies := make([]frequency, 0, len(counts))
	for id, count := range counts {
		frequencies = append(frequencies, frequency{ID: id, Count: count})
	}
	sort.Slice(frequencies, func(i, j int) bool {
		if frequencies[i].Count == frequencies[j].Count {
			return frequencies[i].ID < frequencies[j].ID
		}
		return frequencies[i].Count > frequencies[j].Count
	})
	for i := 0; i < len(frequencies) && i < 5; i++ {
		result.TopTypes = append(result.TopTypes, fmt.Sprintf("%s × %s", frequencies[i].ID, formatCount(frequencies[i].Count)))
	}
	return result, nil
}

func (ms *MinesportApp) quickPreflightText(result previewBlockAnalysis) string {
	upperFaces := result.Blocks * 6
	upperVertices := result.Blocks * 24
	lines := []string{
		fmt.Sprintf("Solid blocks: %s", formatCount(result.Blocks)),
		fmt.Sprintf("Unique block states/types: %s", formatCount(result.UniqueTypes)),
		fmt.Sprintf("Preview textures unresolved: %s", formatCount(result.UnresolvedTextures)),
		fmt.Sprintf("Geometry upper bound before culling/FLATTER: ~%s faces · ~%s vertices", formatCount(upperFaces), formatCount(upperVertices)),
	}
	if len(result.TopTypes) > 0 {
		lines = append(lines, "Most common: "+strings.Join(result.TopTypes, " · "))
	}
	if ms.settings.FlatterOptimizationEnabled {
		lines = append(lines, "FLATTER: enabled · exact eligibility/compression is evaluated during geometry compilation.")
	} else {
		lines = append(lines, "FLATTER: disabled.")
	}
	if ms.customSelectionFile != "" {
		lines = append(lines, "Note: exact custom-selection filtering happens during export; this quick scan reports the enclosing selected region.")
	}
	if len(ms.settings.ResourcePackPaths) > 0 {
		lines = append(lines, fmt.Sprintf("Warning: %d resource pack(s) are configured, but the current preview resolver does not yet apply them. Export does.", len(ms.settings.ResourcePackPaths)))
	}
	return strings.Join(lines, "\n")
}

type optimizationBuckets struct {
	TerrainLike     int
	TransparentLike int
	ShapeHeavy      int
	Other           int
}

func bucketOptimizationPressure(counts map[string]int) optimizationBuckets {
	var result optimizationBuckets
	for id, count := range counts {
		name := id
		if colon := strings.IndexByte(name, ':'); colon >= 0 {
			name = name[colon+1:]
		}
		switch {
		case containsAny(name, "leaves", "glass", "water", "lava", "ice", "vine", "flower", "sapling", "grass", "mushroom"):
			result.TransparentLike += count
		case containsAny(name, "stairs", "slab", "fence", "wall", "door", "trapdoor", "rail", "chest", "piston", "torch", "lantern", "sign", "pane"):
			result.ShapeHeavy += count
		case containsAny(name, "stone", "dirt", "sand", "gravel", "deepslate", "netherrack", "ore", "planks", "log", "terracotta", "concrete", "wool", "brick"):
			result.TerrainLike += count
		default:
			result.Other += count
		}
	}
	return result
}

func containsAny(value string, parts ...string) bool {
	for _, part := range parts {
		if strings.Contains(value, part) {
			return true
		}
	}
	return false
}

func percentage(part, total int) float64 {
	if total <= 0 {
		return 0
	}
	return (float64(part) / float64(total)) * 100.0
}

func (ms *MinesportApp) optimizationAnalysisText(result previewBlockAnalysis) string {
	buckets := bucketOptimizationPressure(result.TypeCounts)
	upperFaces := result.Blocks * 6
	lines := []string{
		fmt.Sprintf("Pre-geometry pressure: ~%s block faces maximum", formatCount(upperFaces)),
		fmt.Sprintf("Terrain/cube-like IDs: %s (%.1f%%)", formatCount(buckets.TerrainLike), percentage(buckets.TerrainLike, result.Blocks)),
		fmt.Sprintf("Transparent/cutout-like IDs: %s (%.1f%%)", formatCount(buckets.TransparentLike), percentage(buckets.TransparentLike, result.Blocks)),
		fmt.Sprintf("Shape-heavy IDs: %s (%.1f%%)", formatCount(buckets.ShapeHeavy), percentage(buckets.ShapeHeavy, result.Blocks)),
		fmt.Sprintf("Other IDs: %s (%.1f%%)", formatCount(buckets.Other), percentage(buckets.Other, result.Blocks)),
		"",
		fmt.Sprintf("Face culling: %s", enabledText(ms.settings.OptimizeOutputEnabled || (ms.optimizeCheck != nil && ms.optimizeCheck.Checked))),
		fmt.Sprintf("FLATTER: %s", enabledText(ms.settings.FlatterOptimizationEnabled)),
		fmt.Sprintf("Hidden-block culling: %s", enabledText(ms.settings.HiddenBlockCullingEnabled)),
	}
	if buckets.TransparentLike > 0 {
		lines = append(lines, "Transparent/cutout blocks use conservative occlusion rules, so they intentionally trade some geometry savings for visual correctness.")
	}
	lines = append(lines, "Exact faces saved are reported only after Java geometry compilation; these categories are workload indicators, not fabricated savings estimates.")
	return strings.Join(lines, "\n")
}

func enabledText(value bool) string {
	if value {
		return "ON"
	}
	return "OFF"
}
