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
	result     *widget.Label
	run        *widget.Button
	preset     *widget.Select
	lastPreset string
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
	})
	state.preset.SetSelected("Custom")

	state.run = widget.NewButtonWithIcon("Run quick preflight", theme.SearchIcon(), func() {
		ms.runQuickPreflight()
	})
	state.result = widget.NewLabel("Preflight has not been run for this selection.")
	state.result.Wrapping = fyne.TextWrapWord

	presetHint := widget.NewLabel("Presets configure the current export controls; Advanced settings remain available underneath.")
	presetHint.Wrapping = fyne.TextWrapWord
	presetCard := widget.NewCard("PRESET", "", container.NewVBox(state.preset, presetHint))
	preflightCard := widget.NewCard("QUICK PREFLIGHT", "", container.NewVBox(state.run, state.result))

	holder.RemoveAll()
	holder.Add(container.NewBorder(presetCard, preflightCard, nil, nil, base))
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

		ms.setPreflightBusy(state, false)
		state.result.SetText(ms.quickPreflightText(analysis))
		ms.finishWorkbenchTaskV3(true, "Preflight ready", fmt.Sprintf("%s solid blocks · %s block types", formatCount(analysis.Blocks), formatCount(analysis.UniqueTypes)))
	}()
}

type previewBlockAnalysis struct {
	Blocks             int
	UniqueTypes        int
	UnresolvedTextures int
	TopTypes           []string
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
	result := previewBlockAnalysis{}
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
		lines = append(lines, fmt.Sprintf("Note: %d configured resource pack(s) are applied during export; quick preview texture diagnostics currently use vanilla/mod resolvers.", len(ms.settings.ResourcePackPaths)))
	}
	return strings.Join(lines, "\n")
}
