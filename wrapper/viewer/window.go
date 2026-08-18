package viewer

import (
	"bufio"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"runtime"
	"time"

	"github.com/go-gl/gl/v2.1/gl"
	"github.com/go-gl/glfw/v3.3/glfw"
)

func init(){runtime.LockOSThread()}

type inCommand struct{Command string `json:"command"`;X,Y,Z int `json:"x"`;Power int `json:"power"`;Blocks [][3]int `json:"blocks"`;Min,Max [3]int `json:"min"`}
type outEvent struct{Type string `json:"type"`;X,Y,Z int `json:"x,omitempty"`;Blocks [][3]int `json:"blocks,omitempty"`;Min,Max [3]int `json:"min,omitempty"`;Count int `json:"count,omitempty"`;Message string `json:"message,omitempty"`}
func emit(e outEvent){b,_:=json.Marshal(e);fmt.Println(string(b))}

// Run owns the dedicated GLFW/OpenGL process. Fyne stays in the main process;
// this renderer never touches Fyne state or GL from another goroutine.
func Run(blocksPath string) error{
	blocks,err:=LoadBlocks(blocksPath);if err!=nil{emit(outEvent{Type:"error",Message:"failed to load blocks: "+err.Error()});return err};if len(blocks)==0{return fmt.Errorf("no blocks")}
	index:=BuildIndex(blocks);if err=glfw.Init();err!=nil{return err};defer glfw.Terminate()
	glfw.WindowHint(glfw.ContextVersionMajor,2);glfw.WindowHint(glfw.ContextVersionMinor,1);glfw.WindowHint(glfw.Resizable,glfw.True)
	window,err:=glfw.CreateWindow(1280,720,"Minesport — 3D Preview",nil,nil);if err!=nil{return err};defer window.Destroy();window.MakeContextCurrent();if err=gl.Init();err!=nil{return err}
	program,err:=newProgram(vertexShaderSource,fragmentShaderSource);if err!=nil{return err};gl.UseProgram(program)
	posLoc:=uint32(gl.GetAttribLocation(program,gl.Str("aPos\x00")));colorLoc:=uint32(gl.GetAttribLocation(program,gl.Str("aColor\x00")));normalLoc:=uint32(gl.GetAttribLocation(program,gl.Str("aNormal\x00")))
	modelLoc:=gl.GetUniformLocation(program,gl.Str("uModel\x00"));viewLoc:=gl.GetUniformLocation(program,gl.Str("uView\x00"));projLoc:=gl.GetUniformLocation(program,gl.Str("uProjection\x00"))
	highlightProgram,err:=newProgram(vertexShaderSource,highlightFragmentShaderSource);if err!=nil{return err}
	hPosLoc:=uint32(gl.GetAttribLocation(highlightProgram,gl.Str("aPos\x00")));hColorLoc:=uint32(gl.GetAttribLocation(highlightProgram,gl.Str("aColor\x00")));hNormalLoc:=uint32(gl.GetAttribLocation(highlightProgram,gl.Str("aNormal\x00")))
	hModelLoc:=gl.GetUniformLocation(highlightProgram,gl.Str("uModel\x00"));hViewLoc:=gl.GetUniformLocation(highlightProgram,gl.Str("uView\x00"));hProjLoc:=gl.GetUniformLocation(highlightProgram,gl.Str("uProjection\x00"));hColorUniformLoc:=gl.GetUniformLocation(highlightProgram,gl.Str("uHighlightColor\x00"))
	mesh:=BuildMesh(blocks,index);defer mesh.Destroy()
	var highlightMesh *Mesh
	setHighlight:=func(h []Block){if highlightMesh!=nil{highlightMesh.Destroy();highlightMesh=nil};if len(h)>0{highlightMesh=buildHighlightMesh(h)}}
	minB,maxB:=BoundingBox(blocks);cam:=NewCamera(Vec3{X:float32(minB[0]+maxB[0])/2,Y:float32(maxB[1]+10),Z:float32(minB[2]+maxB[2])/2});cam.Pitch=-0.5
	gl.Enable(gl.DEPTH_TEST);gl.Enable(gl.BLEND);gl.BlendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA)

	// Blender-style viewport controls: MMB orbit, Shift+MMB pan, wheel zoom.
	var middleHeld,shiftPan bool;var lastX,lastY float64;var first=true
	window.SetMouseButtonCallback(func(w *glfw.Window,button glfw.MouseButton,action glfw.Action,mods glfw.ModifierKey){
		if button==glfw.MouseButtonMiddle{middleHeld=action==glfw.Press;shiftPan=middleHeld&&(mods&glfw.ModifierShift)!=0;first=true;if middleHeld{w.SetInputMode(glfw.CursorMode,glfw.CursorDisabled)}else{w.SetInputMode(glfw.CursorMode,glfw.CursorNormal)}}
		if button==glfw.MouseButtonLeft&&action==glfw.Press{_,pos,ok:=Raycast(index,cam.Position,cam.Forward(),200);if !ok{return};if point1==nil{p:=pos;point1=&p;emit(outEvent{Type:"point1Set",X:p[0],Y:p[1],Z:p[2]});updateSelection()}else if resizeMode{confirmSelection()}else{confirmSelection()}}
		if button==glfw.MouseButtonRight&&action==glfw.Press{_,pos,ok:=Raycast(index,cam.Position,cam.Forward(),200);if !ok{return};p:=pos;point2=&p;resizeMode=false;emit(outEvent{Type:"point2Set",X:p[0],Y:p[1],Z:p[2]});updateSelection()}
	})
	window.SetCursorPosCallback(func(w *glfw.Window,x,y float64){if !middleHeld{return};if first{lastX,lastY=x,y;first=false;return};dx,dy:=float32(x-lastX),float32(y-lastY);lastX,lastY=x,y;OrbitPan(cam,dx,dy,shiftPan)})
	window.SetScrollCallback(func(w *glfw.Window,xoff,yoff float64){
		if resizeMode&&point1!=nil&&point2!=nil{adjustSelectionByLook(int(math.Copysign(1,yoff)));return}
		Dolly(cam,float32(yoff))
	})

	var point1,point2 *[3]int
	var resizeMode bool
	updateSelection:=func(){if point1==nil||point2==nil{return};p1,p2:=*point1,*point2;mn:=[3]int{min(p1[0],p2[0]),min(p1[1],p2[1]),min(p1[2],p2[2])};mx:=[3]int{max(p1[0],p2[0]),max(p1[1],p2[1]),max(p1[2],p2[2])};boxed:=blocksInBox(blocks,mn,mx);setHighlight(boxed);window.SetTitle(fmt.Sprintf("Minesport — 3D Preview · %d selected · E+scroll resize · LMB confirm",len(boxed)))}
	confirmSelection:=func(){if point1==nil||point2==nil{return};p1,p2:=*point1,*point2;mn:=[3]int{min(p1[0],p2[0]),min(p1[1],p2[1]),min(p1[2],p2[2])};mx:=[3]int{max(p1[0],p2[0]),max(p1[1],p2[1]),max(p1[2],p2[2])};boxed:=blocksInBox(blocks,mn,mx);coords:=make([][3]int,len(boxed));for i,b:=range boxed{coords[i]=[3]int{b.X,b.Y,b.Z}};emit(outEvent{Type:"selection",Blocks:coords,Min:mn,Max:mx,Count:len(coords)});setHighlight(boxed);window.SetTitle(fmt.Sprintf("Minesport — 3D Preview · %d selected · confirmed",len(boxed)));resizeMode=false}
	adjustSelectionByLook:=func(step int){
		if point2==nil{return};dir:=cam.Forward();ax,ay,az:=math.Abs(float64(dir.X)),math.Abs(float64(dir.Y)),math.Abs(float64(dir.Z));p:=*point2
		switch {case ax>=ay&&ax>=az: if dir.X>=0{p[0]+=step}else{p[0]-=step};case ay>=ax&&ay>=az:if dir.Y>=0{p[1]+=step}else{p[1]-=step};default:if dir.Z>=0{p[2]+=step}else{p[2]-=step}}
		point2=&p;updateSelection()
	}

	window.SetKeyCallback(func(w *glfw.Window,key glfw.Key,scancode int,action glfw.Action,mods glfw.ModifierKey){if action==glfw.Press&&key==glfw.KeyE{resizeMode=point1!=nil&&point2!=nil;emit(outEvent{Type:"resizeMode",Count:boolInt(resizeMode)})};if action==glfw.Press&&key==glfw.KeyEscape{point1=nil;point2=nil;resizeMode=false;setHighlight(nil);setTitleStats()}})

	cmdCh:=make(chan inCommand,16);go func(){s:=bufio.NewScanner(os.Stdin);s.Buffer(make([]byte,0,64*1024),8*1024*1024);for s.Scan(){var c inCommand;if json.Unmarshal([]byte(s.Text()),&c)==nil{cmdCh<-c}};close(cmdCh)}()
	setTitleStats:=func(){faces:=mesh.indexCount/6;window.SetTitle(fmt.Sprintf("Minesport — 3D Preview · %d blocks · %d visible faces",len(blocks),faces))};setTitleStats();emit(outEvent{Type:"ready",Count:len(blocks)})
	last:=time.Now();quit:=false
	for !window.ShouldClose()&&!quit{
		drain:
		for{select{case cmd,ok:=<-cmdCh:if !ok{break drain};switch cmd.Command{case "floodFill":r:=FloodFillJoined(index,[3]int{cmd.X,cmd.Y,cmd.Z},cmd.Power);setHighlight(r);coords:=make([][3]int,len(r));for i,b:=range r{coords[i]=[3]int{b.X,b.Y,b.Z}};emit(outEvent{Type:"selection",Blocks:coords,Count:len(coords)});case "highlightBox":r:=blocksInBox(blocks,cmd.Min,cmd.Max);setHighlight(r);case "clearHighlight":setHighlight(nil);setTitleStats();case "quit":quit=true}};default:break drain}}
		now:=time.Now();dt:=float32(now.Sub(last).Seconds());last=now;if dt>0.1{dt=0.1}
		cam.Move(Input{Forward:window.GetKey(glfw.KeyW)==glfw.Press,Back:window.GetKey(glfw.KeyS)==glfw.Press,Left:window.GetKey(glfw.KeyA)==glfw.Press,Right:window.GetKey(glfw.KeyD)==glfw.Press,Up:window.GetKey(glfw.KeySpace)==glfw.Press,Down:window.GetKey(glfw.KeyLeftShift)==glfw.Press||window.GetKey(glfw.KeyRightShift)==glfw.Press,Sprint:window.GetKey(glfw.KeyLeftControl)==glfw.Press},dt)
		w,h:=window.GetSize();if h==0{h=1};gl.Viewport(0,0,int32(w),int32(h));gl.ClearColor(0.53,0.71,0.90,1);gl.Clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);proj:=Perspective(float32(70*math.Pi/180),float32(w)/float32(h),0.1,2000);view:=cam.ViewMatrix();model:=Identity();gl.UseProgram(program);gl.UniformMatrix4fv(modelLoc,1,false,&model[0]);gl.UniformMatrix4fv(viewLoc,1,false,&view[0]);gl.UniformMatrix4fv(projLoc,1,false,&proj[0]);mesh.Draw(posLoc,colorLoc,normalLoc)
		if highlightMesh!=nil{gl.UseProgram(highlightProgram);gl.UniformMatrix4fv(hModelLoc,1,false,&model[0]);gl.UniformMatrix4fv(hViewLoc,1,false,&view[0]);gl.UniformMatrix4fv(hProjLoc,1,false,&proj[0]);gl.Uniform3f(hColorUniformLoc,0.25,1.0,0.45);highlightMesh.Draw(hPosLoc,hColorLoc,hNormalLoc)}
		window.SwapBuffers();glfw.PollEvents()
	}
	if highlightMesh!=nil{highlightMesh.Destroy()};return nil
}

func buildHighlightMesh(blocks []Block)*Mesh{const inflate=0.03;copied:=make([]Block,len(blocks));copy(copied,blocks);return buildMeshInflated(copied,inflate)}
func blocksInBox(all []Block,minv,maxv [3]int)[]Block{lx,hx:=minv[0],maxv[0];ly,hy:=minv[1],maxv[1];lz,hz:=minv[2],maxv[2];if lx>hx{lx,hx=hx,lx};if ly>hy{ly,hy=hy,ly};if lz>hz{lz,hz=hz,lz};var out []Block;for _,b:=range all{if b.X>=lx&&b.X<=hx&&b.Y>=ly&&b.Y<=hy&&b.Z>=lz&&b.Z<=hz{out=append(out,b)}};return out}
func min(a,b int)int{if a<b{return a};return b};func max(a,b int)int{if a>b{return a};return b};func boolInt(v bool)int{if v{return 1};return 0}
