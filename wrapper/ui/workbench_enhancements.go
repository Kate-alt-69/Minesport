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
	preset       *widget.Select
	lastPreset   string
	summary      *widget.Label
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

// installWorkbenchEnhancements keeps the normal export workflow compact.
// Expensive diagnostics belong in Settings/Advanced and report through the
// bottom task shelf instead of permanently occupying the Export sidebar.
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
		if state.summary != nil {
			state.summary.SetText(ms.optimizationSummaryText())
		}
	})
	state.preset.SetSelected("Custom")

	presetHint := widget.NewLabel("Presets configure the current export controls; Advanced contains diagnostics and manual cache tools.")
	presetHint.Wrapping = fyne.TextWrapWord
	presetCard := widget.NewCard("PRESET", "", container.NewVBox(state.preset, presetHint))

	state.summary = widget.NewLabel(ms.optimizationSummaryText())
	state.summary.Truncation = fyne.TextTruncateEllipsis
	applySafe := widget.NewButton("Apply safe optimization", func() {
		ms.settings.OptimizeOutputEnabled = true
		ms.settings.FlatterOptimizationEnabled = true
		ms.settings.HiddenBlockCullingEnabled = false
		if ms.optimizeCheck != nil {
			ms.optimizeCheck.SetChecked(true)
		}
		ms.applySettings(ms.settings)
		ms.refreshWorkbenchSettingsActivity()
		state.summary.SetText(ms.optimizationSummaryText())
		ms.beginWorkbenchTaskV3("OPTIMIZATION", "Applying safe optimization…", false)
		ms.finishWorkbenchTaskV3(true, "Safe optimization applied", "Face culling + FLATTER enabled · hidden-block culling disabled")
		ms.appendLog("Safe optimization applied: face culling + FLATTER; hidden-block culling disabled")
	})
	compactOptimization := container.NewVBox(state.summary, applySafe)

	holder.RemoveAll()
	holder.Add(container.NewBorder(
		presetCard,
		container.NewPadded(compactOptimization),
		nil,
		nil,
		base,
	))
	exportPreflightStates.Store(ms, state)
}

func cleanupWorkbenchEnhancements(ms *MinesportApp) {
	exportPreflightStates.Delete(ms)
}

func (ms *MinesportApp) optimizationSummaryText() string {
	face := ms.settings.OptimizeOutputEnabled || (ms.optimizeCheck != nil && ms.optimizeCheck.Checked)
	return fmt.Sprintf(
		"Optimization: face culling %s · FLATTER %s · hidden culling %s",
		enabledText(face),
		enabledText(ms.settings.FlatterOptimizationEnabled),
		enabledText(ms.settings.HiddenBlockCullingEnabled),
	)
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
	workbenchAssetCenterStates.Delete(ms)
	installWorkbenchAssetCenter(ms)
}

// buildAdvancedPreflightCard keeps heavyweight preview analysis out of the
// Export activity. Results are summarized here and reported in the task shelf;
// the full diagnostic text is written to the debug log.
func (ms *MinesportApp) buildAdvancedPreflightCard() fyne.CanvasObject {
	status := widget.NewLabel("Preflight: not run for the current selection.")
	status.Truncation = fyne.TextTruncateEllipsis
	run := widget.NewButtonWithIcon("Run manual preflight", theme.SearchIcon(), nil)
	run.OnTapped = func() { ms.runQuickPreflight(status, run) }
	return widget.NewCard(
		"MANUAL PREFLIGHT",
		"Diagnostic only · normal export does not require this step",
		container.NewVBox(status, run),
	)
}

func (ms *MinesportApp) buildAdvancedPipelineTools() fyne.CanvasObject {
	return container.NewVBox(
		workbenchSection("ADVANCED EXPORT TOOLS"),
		ms.buildAdvancedPreflightCard(),
		ms.buildBridgeRuntimeAdvancedCard(),
	)
}

func (ms *MinesportApp) runQuickPreflight(status *widget.Label, run *widget.Button) {
	if ms.worldPath == "" {
		status.SetText("Preflight: select a Minecraft world first.")
		return
	}
	if !ms.isEngineAvailable() {
		status.SetText("Preflight: core engine unavailable.")
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

	run.Disable()
	status.SetText("Preflight: scanning selected region…")
	ms.beginWorkbenchTaskV3("PREFLIGHT", "Scanning selected blocks…", false)

	go func() {
		defer run.Enable()
		file, count, err := ms.engine.ListBlocks(params)
		if err != nil {
			status.SetText("Preflight: failed · see task/debug log")
			ms.finishWorkbenchTaskV3(false, "Preflight failed", err.Error())
			return
		}
		defer os.Remove(file)

		analysis, err := analyzePreviewBlockFile(file)
		if err != nil {
			status.SetText("Preflight: diagnostics could not read the preview list")
			ms.finishWorkbenchTaskV3(false, "Preflight diagnostics failed", err.Error())
			return
		}
		if analysis.Blocks == 0 && count > 0 {
			analysis.Blocks = count
		}
		if stateValue, ok := exportPreflightStates.Load(ms); ok {
			if state, _ := stateValue.(*exportPreflightState); state != nil {
				state.lastAnalysis = &analysis
			}
		}

		short := fmt.Sprintf(
			"%s blocks · %s block types · %s unresolved preview textures",
			formatCount(analysis.Blocks),
			formatCount(analysis.UniqueTypes),
			formatCount(analysis.UnresolvedTextures),
		)
		status.SetText("Preflight: " + short)
		ms.finishWorkbenchTaskV3(true, "Preflight ready", short)
		ms.appendLog("Preflight diagnostics:\n" + ms.quickPreflightText(analysis) + "\n" + ms.optimizationAnalysisText(analysis))
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
		lines = append(lines, fmt.Sprintf("FLATTER: enabled · cell %d³ · exact eligibility/compression is evaluated during geometry compilation.", normalizeFlatterCellSize(ms.settings.FlatterCellSize)))
	} else {
		lines = append(lines, "FLATTER: disabled.")
	}
	if ms.customSelectionFile != "" {
		lines = append(lines, "Note: exact custom-selection filtering happens during export; this quick scan reports the enclosing selected region.")
	}
	if len(ms.settings.ResourcePackPaths) > 0 {
		lines = append(lines, fmt.Sprintf("Warning: %d resource pack(s) are configured; export applies them even when preview diagnostics cannot represent every custom renderer.", len(ms.settings.ResourcePackPaths)))
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
		fmt.Sprintf("FLATTER: %s · cell %d³", enabledText(ms.settings.FlatterOptimizationEnabled), normalizeFlatterCellSize(ms.settings.FlatterCellSize)),
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
