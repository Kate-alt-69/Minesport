package ui

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

const minesportProjectSchema = 1
const minesportProjectExtension = ".minesport-project"

type MinesportProject struct {
	Schema    int              `json:"schema"`
	ProjectID string           `json:"projectId"`
	World     ProjectWorld     `json:"world"`
	Selection ProjectSelection `json:"selection"`
	Export    ProjectExport    `json:"export"`
	Pipeline  ProjectPipeline  `json:"pipeline"`
	Preset    string           `json:"preset,omitempty"`
}

type ProjectWorld struct {
	Path             string `json:"path"`
	Name             string `json:"name"`
	MinecraftVersion string `json:"minecraftVersion,omitempty"`
	Loader           string `json:"loader,omitempty"`
	ModsPath         string `json:"modsPath,omitempty"`
}

type ProjectSelection struct {
	Mode   string `json:"mode"`
	Min    [3]int `json:"min"`
	Max    [3]int `json:"max"`
	Center [3]int `json:"center"`
	Radius [3]int `json:"radius"`
}

type ProjectExport struct {
	Name       string `json:"name"`
	OutputPath string `json:"outputPath"`
	Format     string `json:"format"`
	Objects    string `json:"objects"`
	Optimize   bool   `json:"optimize"`
}

type ProjectPipeline struct {
	FaceCulling        bool     `json:"faceCulling"`
	HiddenBlockCulling bool     `json:"hiddenBlockCulling"`
	Flatter            bool     `json:"flatter"`
	Blender            bool     `json:"blender"`
	SelectByModel      bool     `json:"selectByModel"`
	ResourcePacks      []string `json:"resourcePacks,omitempty"`
	DataPacks          []string `json:"dataPacks,omitempty"`
}

type minesportProjectState struct {
	path   string
	id     string
	status *widget.Label
}

var minesportProjectStates sync.Map

func newProjectID() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err == nil {
		return hex.EncodeToString(value[:])
	}
	return fmt.Sprintf("project-%x", value[:])
}

func normalizeProjectID(value string) string {
	value = strings.TrimSpace(value)
	if len(value) < 8 {
		return newProjectID()
	}
	return value
}

func shortProjectID(value string) string {
	value = strings.TrimSpace(value)
	if len(value) <= 8 {
		return value
	}
	return value[:8]
}

func readMinesportProject(path string) (MinesportProject, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return MinesportProject{}, err
	}
	var project MinesportProject
	if err := json.Unmarshal(data, &project); err != nil {
		return MinesportProject{}, fmt.Errorf("invalid Minesport project: %w", err)
	}
	if project.Schema != minesportProjectSchema {
		return MinesportProject{}, fmt.Errorf(
			"unsupported Minesport project schema %d (expected %d)",
			project.Schema,
			minesportProjectSchema,
		)
	}
	project.ProjectID = normalizeProjectID(project.ProjectID)
	if strings.TrimSpace(project.World.Path) == "" {
		return MinesportProject{}, fmt.Errorf("project has no Minecraft world path")
	}
	return project, nil
}

func writeMinesportProject(path string, project MinesportProject) error {
	if strings.TrimSpace(project.ProjectID) == "" {
		return fmt.Errorf("projectId is required")
	}
	project.Schema = minesportProjectSchema
	data, err := json.MarshalIndent(project, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}

func (ms *MinesportApp) installProjectControls() {
	wb := ms.workbenchState()
	if wb == nil {
		return
	}
	holderObject := wb.panes[workbenchPaneWorld]
	holder, ok := holderObject.(*fyne.Container)
	if !ok || len(holder.Objects) == 0 {
		return
	}
	if _, loaded := minesportProjectStates.Load(ms); loaded {
		return
	}

	state := &minesportProjectState{id: newProjectID()}
	state.status = widget.NewLabel("Unsaved project")
	state.status.Truncation = fyne.TextTruncateEllipsis

	open := widget.NewButtonWithIcon("Open", theme.FolderOpenIcon(), func() {
		ms.openProjectFile()
	})
	save := widget.NewButtonWithIcon("Save", theme.DocumentSaveIcon(), func() {
		ms.saveProjectFile(false)
	})
	saveAs := widget.NewButton("Save As…", func() {
		ms.saveProjectFile(true)
	})

	card := widget.NewCard(
		"PROJECT",
		"Reusable world + selection + DCC pipeline state",
		container.NewVBox(
			state.status,
			container.NewHBox(open, save, saveAs),
		),
	)
	base := holder.Objects[0]
	holder.RemoveAll()
	holder.Add(container.NewBorder(card, nil, nil, nil, base))
	minesportProjectStates.Store(ms, state)
}

func cleanupProjectControls(ms *MinesportApp) {
	minesportProjectStates.Delete(ms)
}

func (ms *MinesportApp) currentProjectState() *minesportProjectState {
	value, ok := minesportProjectStates.Load(ms)
	if !ok {
		return nil
	}
	state, _ := value.(*minesportProjectState)
	return state
}

func (ms *MinesportApp) projectPresetName() string {
	value, ok := exportPreflightStates.Load(ms)
	if !ok {
		return "Custom"
	}
	state, _ := value.(*exportPreflightState)
	if state == nil || strings.TrimSpace(state.lastPreset) == "" {
		return "Custom"
	}
	return state.lastPreset
}

func (ms *MinesportApp) projectSnapshot(projectID string) MinesportProject {
	projectID = normalizeProjectID(projectID)
	project := MinesportProject{
		Schema:    minesportProjectSchema,
		ProjectID: projectID,
		World: ProjectWorld{
			Path:             ms.worldPath,
			Name:             ms.worldName,
			MinecraftVersion: normalizedMinecraftVersion(ms.mcVersion),
			Loader:           normalizedLoader(ms.loaderType),
			ModsPath:         ms.modsPath,
		},
		Export: ProjectExport{
			Name:       ms.exportNameEntry.Text,
			OutputPath: ms.outputPath,
			Format:     ms.formatSelect.Selected,
			Objects:    ms.modeSelect.Selected,
			Optimize:   ms.optimizeCheck.Checked,
		},
		Pipeline: ProjectPipeline{
			FaceCulling:        ms.settings.OptimizeOutputEnabled,
			HiddenBlockCulling: ms.settings.HiddenBlockCullingEnabled,
			Flatter:            ms.settings.FlatterOptimizationEnabled,
			Blender:            ms.settings.BlenderExportEnabled,
			SelectByModel:      ms.settings.SelectByModel,
			ResourcePacks:      append([]string(nil), ms.settings.ResourcePackPaths...),
			DataPacks:          append([]string(nil), ms.settings.DataPackPaths...),
		},
		Preset: ms.projectPresetName(),
	}

	minX, maxX := ms.minXRange.Bounds()
	minY, maxY := ms.minYRange.Bounds()
	minZ, maxZ := ms.minZRange.Bounds()
	project.Selection.Min = [3]int{minX, minY, minZ}
	project.Selection.Max = [3]int{maxX, maxY, maxZ}
	project.Selection.Center = [3]int{
		ms.centerX.Int(0),
		ms.centerY.Int(64),
		ms.centerZ.Int(0),
	}
	project.Selection.Radius = [3]int{
		ms.radiusX.Int(32),
		ms.radiusY.Int(32),
		ms.radiusZ.Int(32),
	}
	if ms.selectionModeSelect.Selected == "Bubble selection" {
		project.Selection.Mode = "bubble"
	} else {
		project.Selection.Mode = "box"
	}
	return project
}

func (ms *MinesportApp) saveProjectFile(saveAs bool) {
	state := ms.currentProjectState()
	if state == nil {
		return
	}
	if ms.worldPath == "" {
		dialog.ShowError(fmt.Errorf("select a Minecraft world before saving a project"), ms.window)
		return
	}

	path := state.path
	if saveAs || strings.TrimSpace(path) == "" {
		name := sanitizeExportName(ms.worldName)
		if name == "" {
			name = "Minesport_Project"
		}
		defaultPath := filepath.Join(ms.outputPath, name+minesportProjectExtension)
		if ms.outputPath == "" {
			defaultPath = name + minesportProjectExtension
		}
		path = nativeSaveFile(
			"Save Minesport Project",
			defaultPath,
			"Minesport Project|*.minesport-project",
			minesportProjectExtension,
		)
		if path == "" {
			return
		}
	}

	project := ms.projectSnapshot(state.id)
	if err := writeMinesportProject(path, project); err != nil {
		dialog.ShowError(fmt.Errorf("save project: %w", err), ms.window)
		return
	}
	state.path = path
	state.id = project.ProjectID
	state.status.SetText(filepath.Base(path) + " · " + shortProjectID(project.ProjectID))
	ms.appendLog("Saved Minesport project: " + path)
}

func (ms *MinesportApp) openProjectFile() {
	path := nativeOpenFile(
		"Open Minesport Project",
		"Minesport Project|*.minesport-project",
	)
	if path == "" {
		return
	}
	project, err := readMinesportProject(path)
	if err != nil {
		dialog.ShowError(err, ms.window)
		return
	}
	if err := ms.applyProject(project); err != nil {
		dialog.ShowError(fmt.Errorf("open project: %w", err), ms.window)
		return
	}
	state := ms.currentProjectState()
	if state != nil {
		state.path = path
		state.id = project.ProjectID
		state.status.SetText(filepath.Base(path) + " · " + shortProjectID(project.ProjectID))
	}
	ms.appendLog("Opened Minesport project: " + path)
}

func (ms *MinesportApp) applyProject(project MinesportProject) error {
	worldPath := filepath.Clean(project.World.Path)
	if info, err := os.Stat(worldPath); err != nil || !info.IsDir() {
		return fmt.Errorf("Minecraft world folder is unavailable: %s", worldPath)
	}
	if _, err := os.Stat(filepath.Join(worldPath, "level.dat")); err != nil {
		return fmt.Errorf("level.dat is missing from project world: %s", worldPath)
	}

	if ms.embeddedViewer != nil {
		ms.embeddedViewer.Close()
		ms.viewerSession = nil
		ms.embeddedViewer = nil
	}
	ms.show2DPreview()
	ms.customSelectionFile = ""
	ms.customSelectionCount = 0

	ms.worldPath = worldPath
	ms.modsPath = project.World.ModsPath
	ms.worldName = project.World.Name
	if strings.TrimSpace(ms.worldName) == "" {
		ms.worldName = filepath.Base(worldPath)
	}
	ms.worldNameLabel.SetText(ms.worldName)

	storedVersion := normalizedMinecraftVersion(project.World.MinecraftVersion)
	storedLoader := normalizedLoader(project.World.Loader)
	meta := ms.detectWorldMeta(worldPath)
	ms.worldMetaLabel.SetText(meta)
	if storedVersion != "" && normalizedMinecraftVersion(ms.mcVersion) != storedVersion {
		ms.appendLog(fmt.Sprintf(
			"Project stored Minecraft %s; world currently reports %s. Using world metadata.",
			storedVersion,
			normalizedMinecraftVersion(ms.mcVersion),
		))
	}
	if storedLoader != "" && normalizedLoader(ms.loaderType) != storedLoader {
		ms.appendLog(fmt.Sprintf(
			"Project stored loader %s; world/instance currently reports %s. Using detected metadata.",
			storedLoader,
			normalizedLoader(ms.loaderType),
		))
	}

	if value, ok := exportPreflightStates.Load(ms); ok {
		if state, _ := value.(*exportPreflightState); state != nil && state.preset != nil {
			preset := project.Preset
			if strings.TrimSpace(preset) == "" {
				preset = "Custom"
			}
			state.preset.SetSelected(preset)
			state.lastPreset = preset
		}
	}

	ms.exportNameEntry.SetText(project.Export.Name)
	if strings.TrimSpace(ms.exportNameEntry.Text) == "" {
		ms.exportNameEntry.SetText(sanitizeExportName(ms.worldName) + "_export")
	}
	if project.Export.OutputPath != "" {
		ms.outputPath = project.Export.OutputPath
		ms.outputLabel.SetText(ms.outputPath)
	}
	if project.Export.Format != "" {
		ms.formatSelect.SetSelected(project.Export.Format)
	}
	if project.Export.Objects != "" {
		ms.modeSelect.SetSelected(project.Export.Objects)
	}
	ms.optimizeCheck.SetChecked(project.Export.Optimize)

	ms.settings.OptimizeOutputEnabled = project.Pipeline.FaceCulling
	ms.settings.HiddenBlockCullingEnabled = project.Pipeline.HiddenBlockCulling
	ms.settings.FlatterOptimizationEnabled = project.Pipeline.Flatter
	ms.settings.BlenderExportEnabled = project.Pipeline.Blender
	ms.settings.SelectByModel = project.Pipeline.SelectByModel
	ms.settings.ResourcePackPaths = append([]string(nil), project.Pipeline.ResourcePacks...)
	ms.settings.DataPackPaths = append([]string(nil), project.Pipeline.DataPacks...)
	ms.applySettings(ms.settings)
	ms.refreshWorkbenchSettingsActivity()

	ms.minXRange.Front.SetText(fmt.Sprintf("%d", project.Selection.Min[0]))
	ms.minXRange.Back.SetText(fmt.Sprintf("%d", project.Selection.Max[0]))
	ms.minYRange.Front.SetText(fmt.Sprintf("%d", project.Selection.Min[1]))
	ms.minYRange.Back.SetText(fmt.Sprintf("%d", project.Selection.Max[1]))
	ms.minZRange.Front.SetText(fmt.Sprintf("%d", project.Selection.Min[2]))
	ms.minZRange.Back.SetText(fmt.Sprintf("%d", project.Selection.Max[2]))
	ms.centerX.SetText(fmt.Sprintf("%d", project.Selection.Center[0]))
	ms.centerY.SetText(fmt.Sprintf("%d", project.Selection.Center[1]))
	ms.centerZ.SetText(fmt.Sprintf("%d", project.Selection.Center[2]))
	ms.radiusX.SetText(fmt.Sprintf("%d", project.Selection.Radius[0]))
	ms.radiusY.SetText(fmt.Sprintf("%d", project.Selection.Radius[1]))
	ms.radiusZ.SetText(fmt.Sprintf("%d", project.Selection.Radius[2]))
	if strings.EqualFold(project.Selection.Mode, "bubble") {
		ms.selectionModeSelect.SetSelected("Bubble selection")
	} else {
		ms.selectionModeSelect.SetSelected("Box selection")
	}

	ms.updateWorkbenchWorldContext()
	ms.updateMetaHUD(ms.selectionSizeText())
	ms.showLoaderWarning()
	if ms.isEngineAvailable() {
		ms.exportBtn.Enable()
		ms.autoDetectBtn.Enable()
		ms.viewToggle3D.Enable()
		go ms.generateHeightmap(worldPath)
	}
	return nil
}
