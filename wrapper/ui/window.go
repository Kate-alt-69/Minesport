package ui

import (
    "bytes"
    "encoding/base64"
    "fmt"
    "image"
    "image/color"
    "os"
    "path/filepath"
    "strconv"
    "strings"
    "sync"

    "fyne.io/fyne/v2"
    "fyne.io/fyne/v2/app"
    "fyne.io/fyne/v2/canvas"
    "fyne.io/fyne/v2/container"
    "fyne.io/fyne/v2/dialog"
    "fyne.io/fyne/v2/layout"
    "fyne.io/fyne/v2/theme"
    "fyne.io/fyne/v2/widget"

    "github.com/kastrick/minesport/ipc"
    "github.com/kastrick/minesport/launcher"
    _ "image/png"
)

type MinesportApp struct {
    window fyne.Window
    fyneApp fyne.App
    engine *ipc.Engine
    mu sync.Mutex
    settings Settings
    debugWindow fyne.Window
    viewerSession *ViewerSession
    customSelectionFile string
    customSelectionCount int
    suppressSelectionClear bool
    worldPath, worldName, mcVersion, loaderType, modsPath, outputPath string

    worldNameLabel *widget.Label
    worldMetaLabel *widget.Label
    formatSelect *widget.Select
    modeSelect *widget.Select
    minXRange, minYRange, minZRange *AxisRange
    minXEntry, minYEntry, minZEntry *StepperEntry
    maxXEntry, maxYEntry, maxZEntry *StepperEntry
    centerX, centerY, centerZ, radiusX, radiusY, radiusZ *StepperEntry
    outputLabel *widget.Label
    exportBtn *widget.Button
    autoDetectBtn *widget.Button
    optimizeCheck *widget.Check
    optimizeHint *widget.Label
    selectionModeSelect *widget.Select
    boxCoordGroup, bubbleCoordGroup *fyne.Container

    worldMap *WorldMapV2
    logContent *widget.Label
    logScroll *container.Scroll
    progressBar *widget.ProgressBar
    statusLabel *widget.Label
    stateIcon *widget.Icon
    cursorLabel *widget.Label
    metaHUD *widget.Label
    viewToggle2D, viewToggle3D, fitBtn, settingsBtn *widget.Button

    exportWindow fyne.Window
    exportTitle *widget.Label
    exportStage *widget.Label
    exportDetail *widget.Label
    exportBar *widget.ProgressBar
    exportStats *widget.Label
}

func Run(jarPath string) {
    a := app.NewWithID("kastrick.dev.minesport")
    w := a.NewWindow("Minesport — by Kastrick")
    w.Resize(fyne.NewSize(1180, 740))
    w.SetMaster()
    ms := &MinesportApp{window:w, fyneApp:a}
    ms.settings = LoadSettings()
    ms.engine = ipc.NewEngine(jarPath)
    w.SetContent(ms.buildUI())
    if ms.settings.DebugMode { ms.openDebugConsole() }
    ms.engine.OnLog = func(msg string) { ms.appendLog(msg) }
    ms.engine.OnProgress = func(pct int, msg string) { ms.progressBar.SetValue(float64(pct)/100); ms.statusLabel.SetText(msg); ms.stateIcon.SetResource(theme.ViewRefreshIcon()); ms.updateExportProgress(pct,msg) }
    ms.engine.OnDone = func(resp ipc.Response) { ms.finishExport(resp,true,"") }
    ms.engine.OnError = func(msg string) { ms.finishExport(ipc.Response{},false,msg) }
    if jarPath=="" { ms.statusLabel.SetText("Engine jar not found"); ms.exportBtn.Disable() } else if err:=ms.engine.Start(jarPath); err!=nil { dialog.ShowError(fmt.Errorf("engine failed to start: %s",err),w) } else { ms.statusLabel.SetText("Ready") }
    w.ShowAndRun()
}

func (ms *MinesportApp) buildUI() fyne.CanvasObject {
    side:=ms.buildInspector(); main:=ms.buildMainArea(); ms.updateMetaHUD(ms.selectionSizeText()); sp:=container.NewHSplit(side,main); sp.SetOffset(0.27); return sp
}

func (ms *MinesportApp) buildInspector() fyne.CanvasObject {
    ms.worldNameLabel=widget.NewLabel("No world selected"); ms.worldNameLabel.TextStyle=fyne.TextStyle{Bold:true}
    ms.worldMetaLabel=widget.NewLabel("Select a Minecraft save to begin")
    selectBtn:=widget.NewButtonWithIcon("Select world",theme.FolderOpenIcon(),ms.onSelectWorld); selectBtn.Importance=widget.HighImportance
    worldCard:=widget.NewCard("WORLD INSPECTOR","",container.NewVBox(ms.worldNameLabel,ms.worldMetaLabel,container.NewPadded(selectBtn)))

    ms.selectionModeSelect=widget.NewSelect([]string{"Box selection","Bubble selection"},func(choice string){
        if ms.boxCoordGroup == nil || ms.bubbleCoordGroup == nil {
            return
        }
        bubble:=choice=="Bubble selection"
        if bubble {
            ms.boxCoordGroup.Hide()
            ms.bubbleCoordGroup.Show()
        } else {
            ms.bubbleCoordGroup.Hide()
            ms.boxCoordGroup.Show()
        }
        if ms.worldMap!=nil { ms.worldMap.SetBubbleMode(bubble) }
        ms.updateMetaHUD(ms.selectionSizeText())
    })

    ms.minXRange=NewAxisRange("X",-256,256,func(){ms.updateMetaHUD(ms.selectionSizeText())})
    ms.minYRange=NewAxisRange("Y",-64,320,func(){ms.updateMetaHUD(ms.selectionSizeText())})
    ms.minZRange=NewAxisRange("Z",-256,256,func(){ms.updateMetaHUD(ms.selectionSizeText())})
    ms.minXEntry, ms.maxXEntry = ms.minXRange.Front, ms.minXRange.Back
    ms.minYEntry, ms.maxYEntry = ms.minYRange.Front, ms.minYRange.Back
    ms.minZEntry, ms.maxZEntry = ms.minZRange.Front, ms.minZRange.Back
    ms.boxCoordGroup=container.NewVBox(ms.minXRange.Container,ms.minYRange.Container,ms.minZRange.Container)

    ms.centerX=NewStepperEntry("0");ms.centerY=NewStepperEntry("64");ms.centerZ=NewStepperEntry("0");ms.radiusX=NewStepperEntry("32");ms.radiusY=NewStepperEntry("32");ms.radiusZ=NewStepperEntry("32")
    for _,e:=range []*StepperEntry{ms.centerX,ms.centerY,ms.centerZ,ms.radiusX,ms.radiusY,ms.radiusZ}{e.SetBounds(-30000000,30000000);e.OnChanged=func(string){ms.syncBubblePreview();ms.updateMetaHUD(ms.selectionSizeText())}}
    ms.bubbleCoordGroup=container.NewVBox(compactNumberRow("Center X",ms.centerX),compactNumberRow("Center Y",ms.centerY),compactNumberRow("Center Z",ms.centerZ),widget.NewSeparator(),compactNumberRow("Radius X",ms.radiusX),compactNumberRow("Radius Y",ms.radiusY),compactNumberRow("Radius Z",ms.radiusZ))
    ms.bubbleCoordGroup.Hide()
    ms.selectionModeSelect.SetSelected("Box selection")

    ms.autoDetectBtn=widget.NewButtonWithIcon("Auto-detect world bounds",theme.SearchIcon(),ms.onAutoDetect);ms.autoDetectBtn.Disable()
    selectionCard:=widget.NewCard("SELECTION","",container.NewVBox(ms.selectionModeSelect,ms.boxCoordGroup,ms.bubbleCoordGroup,container.NewPadded(ms.autoDetectBtn)))

    ms.formatSelect=widget.NewSelect([]string{"glTF 2.0","OBJ + MTL"},nil);ms.formatSelect.SetSelected("glTF 2.0")
    ms.modeSelect=widget.NewSelect([]string{"Grouped","Individual blocks","Merged"},nil);ms.modeSelect.SetSelected("Grouped")
    ms.optimizeCheck=widget.NewCheck("Optimize mesh output",nil);ms.optimizeHint=widget.NewLabel("Vertex welding / atlas optimization. Face culling is controlled separately in Basic Settings.");ms.optimizeHint.TextStyle=fyne.TextStyle{Italic:true};ms.applyOptimizeGate()
    exportCard:=widget.NewCard("EXPORT","",container.NewVBox(compactSelectRow("Format",ms.formatSelect),compactSelectRow("Objects",ms.modeSelect),ms.optimizeCheck,ms.optimizeHint))

    ms.outputLabel=widget.NewLabel("~/Minesport_Exports");ms.outputLabel.Truncation=fyne.TextTruncateEllipsis
    outputCard:=widget.NewCard("OUTPUT","",container.NewVBox(ms.outputLabel,widget.NewButtonWithIcon("Change folder",theme.FolderIcon(),ms.onSelectOutput)))
    ms.exportBtn=widget.NewButtonWithIcon("Export world",theme.DownloadIcon(),ms.onExport);ms.exportBtn.Importance=widget.HighImportance;ms.exportBtn.Disable()
    side:=container.NewVBox(worldCard,selectionCard,exportCard,outputCard,container.NewPadded(ms.exportBtn));return container.NewVScroll(container.NewPadded(side))
}

func compactNumberRow(label string,e *StepperEntry) fyne.CanvasObject{return container.NewBorder(nil,nil,widget.NewLabel(label),nil,e)}
func compactSelectRow(label string,s *widget.Select) fyne.CanvasObject{return container.NewBorder(nil,nil,widget.NewLabel(label),nil,s)}
func (ms *MinesportApp) applyOptimizeGate(){if ms.optimizeCheck==nil{return};ms.optimizeCheck.Enable();ms.optimizeHint.Show()}

func (ms *MinesportApp) buildMainArea() fyne.CanvasObject {
    ms.viewToggle2D=widget.NewButtonWithIcon("2D",theme.GridIcon(),func(){ms.worldMap.SetMode2D()});ms.viewToggle2D.Importance=widget.HighImportance
    ms.viewToggle3D=widget.NewButtonWithIcon("3D preview",theme.ViewFullScreenIcon(),ms.onExplore3D);ms.viewToggle3D.Disable()
    ms.fitBtn=widget.NewButtonWithIcon("Fit",theme.ZoomFitIcon(),func(){ms.worldMap.FitToWindow()});ms.settingsBtn=widget.NewButtonWithIcon("Settings",theme.SettingsIcon(),ms.onOpenSettings)
    toolbar:=container.NewBorder(nil,nil,container.NewHBox(ms.viewToggle2D,ms.viewToggle3D),container.NewHBox(ms.fitBtn,ms.settingsBtn),widget.NewLabel("LMB select · MMB pan · scroll zoom"))

    ms.worldMap=NewWorldMapV2()
    ms.worldMap.OnSelectionChanged=func(minX,minZ,maxX,maxZ int){ms.minXRange.Front.SetText(fmt.Sprintf("%d",minX));ms.minXRange.Back.SetText(fmt.Sprintf("%d",maxX));ms.minZRange.Front.SetText(fmt.Sprintf("%d",minZ));ms.minZRange.Back.SetText(fmt.Sprintf("%d",maxZ));ms.updateMetaHUD(ms.selectionSizeText())}
    ms.worldMap.OnCursorMoved=func(x,z int){ms.cursorLabel.SetText(fmt.Sprintf("X %d  ·  Z %d",x,z))}
    ms.worldMap.OnCenterPicked=func(x,z int){ms.centerX.SetText(fmt.Sprintf("%d",x));ms.centerZ.SetText(fmt.Sprintf("%d",z))}
    mapArea:=container.NewStack(ms.worldMap,container.NewVBox(layout.NewSpacer(),container.NewHBox(layout.NewSpacer(),ms.buildMetaHUD())))
    ms.logContent=widget.NewLabel("");ms.logContent.TextStyle=fyne.TextStyle{Monospace:true};ms.logContent.Wrapping=fyne.TextWrapWord;ms.logScroll=container.NewVScroll(ms.logContent)
    ms.progressBar=widget.NewProgressBar();ms.statusLabel=widget.NewLabel("Ready");ms.stateIcon=widget.NewIcon(theme.InfoIcon());ms.cursorLabel=widget.NewLabel("")
    state:=container.NewBorder(nil,nil,container.NewHBox(ms.stateIcon,ms.statusLabel),ms.cursorLabel,ms.progressBar)
    return container.NewBorder(toolbar,state,nil,nil,mapArea)
}

func(ms *MinesportApp)buildMetaHUD()fyne.CanvasObject{bg:=canvas.NewRectangle(color.NRGBA{12,16,20,220});bg.CornerRadius=6;ms.metaHUD=widget.NewLabel("");ms.metaHUD.TextStyle=fyne.TextStyle{Monospace:true,Bold:true};return container.NewStack(bg,container.NewPadded(ms.metaHUD))}
func(ms *MinesportApp)updateMetaHUD(text string){if ms.metaHUD!=nil{ms.metaHUD.SetText(text)}}
func(ms *MinesportApp)selectionSizeText()string{var w,h,d int;if ms.selectionModeSelect!=nil&&ms.selectionModeSelect.Selected=="Bubble selection"{w=2*ms.radiusX.Int(32)+1;h=2*ms.radiusY.Int(32)+1;d=2*ms.radiusZ.Int(32)+1}else{a,b:=ms.minXRange.Bounds();c,e:=ms.minYRange.Bounds();f,g:=ms.minZRange.Bounds();w=b-a+1;h=e-c+1;d=g-f+1};if w<0{w=-w};if h<0{h=-h};if d<0{d=-d};return fmt.Sprintf("%s blocks  ·  %s × %s × %s",formatCount(w*h*d),formatCount(w),formatCount(h),formatCount(d))}
func formatCount(n int)string{if n<0{n=0};s:=fmt.Sprintf("%d",n);out:="";for i,c:=range s{if i>0&&(len(s)-i)%3==0{out+=","};out+=string(c)};return out}

func(ms *MinesportApp)onSelectWorld(){ShowWorldPicker(ms.window,func(worldPath,modsPath string){ms.worldPath=worldPath;ms.modsPath=modsPath;ms.worldName=filepath.Base(worldPath);ms.worldNameLabel.SetText(ms.worldName);ms.worldMetaLabel.SetText(ms.detectWorldMeta(worldPath));if ms.outputPath==""{home,_:=os.UserHomeDir();ms.outputPath=filepath.Join(home,"Minesport_Exports");ms.outputLabel.SetText(ms.outputPath)};ms.exportBtn.Enable();ms.autoDetectBtn.Enable();ms.viewToggle3D.Enable();go ms.generateHeightmap(worldPath)})}
func(ms *MinesportApp)onSelectOutput(){go func(){f:=nativeOpenFolder("Select Output Folder");if f!=""{ms.outputPath=f;ms.outputLabel.SetText(f)}}()}
func(ms *MinesportApp)onAutoDetect(){if ms.worldPath==""{return};go func(){r,e:=ms.engine.SendCommand(map[string]interface{}{"command":"heightmap","worldPath":ms.worldPath,"scale":1});if e!=nil||r==nil||r.Type!="heightmap"{return};ms.minXRange.Front.SetText(fmt.Sprintf("%d",r.MinX));ms.minXRange.Back.SetText(fmt.Sprintf("%d",r.MaxX));ms.minZRange.Front.SetText(fmt.Sprintf("%d",r.MinZ));ms.minZRange.Back.SetText(fmt.Sprintf("%d",r.MaxZ))}()}

func(ms *MinesportApp)onExport(){
    if ms.worldPath==""{dialog.ShowError(fmt.Errorf("no world selected"),ms.window);return}
    ms.exportBtn.Disable();ms.progressBar.SetValue(0);ms.statusLabel.SetText("Preparing export…");ms.showExportProgress()
    format:="gltf";if strings.Contains(ms.formatSelect.Selected,"OBJ"){format="obj"};mode:="grouped";switch ms.modeSelect.Selected{case "Individual blocks":mode="individual";case "Merged":mode="merged"}
    p:=ipc.ExportParams{WorldPath:ms.worldPath,OutputPath:filepath.Join(ms.outputPath,ms.worldName+"_export."+format),Format:format,ExportMode:mode}
    if ms.selectionModeSelect.Selected=="Bubble selection"{cx,cy,cz:=ms.centerX.Int(0),ms.centerY.Int(64),ms.centerZ.Int(0);rx,ry,rz:=ms.radiusX.Int(32),ms.radiusY.Int(32),ms.radiusZ.Int(32);p.MinX,p.MaxX=cx-rx,cx+rx;p.MinY,p.MaxY=cy-ry,cy+ry;p.MinZ,p.MaxZ=cz-rz,cz+rz;p.CenterX,p.CenterY,p.CenterZ=&cx,&cy,&cz;p.RadiusX,p.RadiusY,p.RadiusZ=&rx,&ry,&rz}else{p.MinX,p.MaxX=ms.minXRange.Bounds();p.MinY,p.MaxY=ms.minYRange.Bounds();p.MinZ,p.MaxZ=ms.minZRange.Bounds()}
    options:=map[string]string{"faceCulling":strconv.FormatBool(ms.settings.OptimizeOutputEnabled)}
    if ms.optimizeCheck.Checked{options["optimize"]="true"};if ms.settings.HiddenBlockCullingEnabled{options["hiddenBlockCulling"]="true"};if ms.customSelectionFile!=""{options["customSelectionFile"]=ms.customSelectionFile};if len(ms.settings.ResourcePackPaths)>0{options["resourcePacks"]=PathListString(ms.settings.ResourcePackPaths)};if len(ms.settings.DataPackPaths)>0{options["dataPacks"]=PathListString(ms.settings.DataPackPaths)};p.Options=options
    if err:=ms.engine.Export(p);err!=nil{ms.finishExport(ipc.Response{},false,err.Error())}
}

func(ms *MinesportApp)showExportProgress(){if ms.exportWindow!=nil{return};w:=ms.fyneApp.NewWindow("Minesport — Exporting");w.Resize(fyne.NewSize(620,330));w.SetFixedSize(true);ms.exportWindow=w;ms.exportTitle=widget.NewLabel("Exporting world…");ms.exportTitle.TextStyle=fyne.TextStyle{Bold:true};...