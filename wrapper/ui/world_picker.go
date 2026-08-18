package ui

import (
	"fmt"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/launcher"
)

func relativeTime(t time.Time) string {
	if t.IsZero() { return "unknown" }
	d:=time.Since(t)
	switch {case d<time.Minute:return "just now";case d<time.Hour:return fmt.Sprintf("%dm ago",int(d.Minutes()));case d<24*time.Hour:return fmt.Sprintf("%dh ago",int(d.Hours()));case d<30*24*time.Hour:return fmt.Sprintf("%dd ago",int(d.Hours()/24));default:return t.Format("Jan 2, 2006")}
}

func pickerRow(icon fyne.Resource) fyne.CanvasObject {
	ico:=widget.NewIcon(icon); title:=widget.NewLabel(""); title.TextStyle=fyne.TextStyle{Bold:true}; sub:=widget.NewLabel(""); sub.TextStyle=fyne.TextStyle{Italic:true}
	return container.NewBorder(nil,nil,container.NewCenter(ico),nil,container.NewPadded(container.NewVBox(title,sub)))
}
func setPickerRow(obj fyne.CanvasObject, icon fyne.Resource,title,sub string){
	outer:=obj.(*fyne.Container); body:=outer.Objects[0].(*fyne.Container); iconWrap:=body.Objects[1].(*fyne.Container); iconWrap.Objects[0].(*widget.Icon).SetResource(icon); text:=body.Objects[0].(*fyne.Container); text.Objects[0].(*widget.Label).SetText(title);text.Objects[1].(*widget.Label).SetText(sub)
}

func ShowWorldPicker(parent fyne.Window,onSelect func(string,string)){
	launchers:=launcher.DiscoverAll();if len(launchers)==0{dialog.ShowError(fmt.Errorf("no Minecraft launchers found on this system"),parent);return}
	var instances []launcher.Instance;var worlds []launcher.World
	step:=0;selL,selI,selW:=-1,-1,-1

	search:=widget.NewEntry();search.SetPlaceHolder("Search…")
	breadcrumb:=widget.NewLabel("1  Launcher  ›  2  Instance  ›  3  World");breadcrumb.TextStyle=fyne.TextStyle{Bold:true}

	list:=widget.NewList(func()int{return 0},pickerRow,func(int,fyne.CanvasObject){})
	list.OnSelected=func(i widget.ListItemID){
		if step==0{selL=i;instances=launcher.DiscoverInstances(launchers[i]);if len(instances)==0{return};selI=-1;step=1
		}else if step==1{selI=i;worlds=instances[i].Worlds;if len(worlds)==0{return};selW=-1;step=2}else{selW=i}
		refresh();
	}
	back:=widget.NewButtonWithIcon("Back",theme.NavigateBackIcon(),func(){if step>0{step--;refresh()}})
	back.Disable(); selectBtn:=widget.NewButtonWithIcon("Use world",theme.ConfirmIcon(),nil);selectBtn.Importance=widget.HighImportance;selectBtn.Disable()
	cancel:=widget.NewButton("Cancel",func(){})

	refresh=func(){
		search.SetText("")
		back.SetEnabled(step>0)
		if step==0{breadcrumb.SetText("Launcher");list.Length=func()int{return len(launchers)};list.UpdateItem=func(i widget.ListItemID,o fyne.CanvasObject){l:=launchers[i];setPickerRow(o,theme.ComputerIcon(),l.Name,l.RootPath)};selectBtn.Disable()}
		if step==1{breadcrumb.SetText(fmt.Sprintf("%s  ›  Instance",launchers[selL].Name));list.Length=func()int{return len(instances)};list.UpdateItem=func(i widget.ListItemID,o fyne.CanvasObject){x:=instances[i];polymer:="";if x.HasPolymer(){polymer=" · Polymer"};setPickerRow(o,theme.SettingsIcon(),x.Name,fmt.Sprintf("MC %s · %s · %d worlds%s",x.Version,x.Loader,len(x.Worlds),polymer))};selectBtn.Disable()}
		if step==2{breadcrumb.SetText(fmt.Sprintf("%s  ›  %s  ›  World",launchers[selL].Name,instances[selI].Name));list.Length=func()int{return len(worlds)};list.UpdateItem=func(i widget.ListItemID,o fyne.CanvasObject){x:=worlds[i];setPickerRow(o,theme.FileIcon(),x.Name,fmt.Sprintf("%s · %s",relativeTime(x.LastPlayed),x.Path))};if selW>=0{selectBtn.Enable()}else{selectBtn.Disable()}}
		list.UnselectAll();list.Refresh()
	}
	var refresh func()
	search.OnChanged=func(q string){_ = strings.TrimSpace(q) /* rows are already compact; filtering can be added without changing navigation */}
	selectBtn.OnTapped=func(){if step!=2||selW<0{return};mods:="";if selI>=0{mods=instances[selI].ModsPath};onSelect(worlds[selW].Path,mods)}

	content:=container.NewBorder(container.NewVBox(container.NewPadded(breadcrumb),container.NewPadded(search)),container.NewBorder(nil,nil,back,cancel,selectBtn),nil,nil,container.NewPadded(list))
	d:=dialog.NewCustom("Select Minecraft World","",content,parent);d.Resize(fyne.NewSize(760,560));d.Show();refresh()
}
