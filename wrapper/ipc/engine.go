package ipc

import (
    "bufio"
    "encoding/json"
    "fmt"
    "io"
    "os"
    "os/exec"
    "sync"
)

type Request struct {
    Command string `json:"command,omitempty"`
    WorldPath string `json:"worldPath,omitempty"`
    OutputPath string `json:"outputPath,omitempty"`
    Format string `json:"format,omitempty"`
    MinX int `json:"minX,omitempty"`; MinY int `json:"minY,omitempty"`; MinZ int `json:"minZ,omitempty"`
    MaxX int `json:"maxX,omitempty"`; MaxY int `json:"maxY,omitempty"`; MaxZ int `json:"maxZ,omitempty"`
    ExportMode string `json:"exportMode,omitempty"`
    Options map[string]string `json:"options,omitempty"`
    CenterX *int `json:"centerX,omitempty"`; CenterY *int `json:"centerY,omitempty"`; CenterZ *int `json:"centerZ,omitempty"`
    RadiusX *int `json:"radiusX,omitempty"`; RadiusY *int `json:"radiusY,omitempty"`; RadiusZ *int `json:"radiusZ,omitempty"`
}

type Response struct {
    Type string `json:"type"`; Message string `json:"message,omitempty"`; Percent int `json:"percent,omitempty"`; Version string `json:"version,omitempty"`; Output string `json:"output,omitempty"`
    Image string `json:"image,omitempty"`; MinX int `json:"minX,omitempty"`; MinZ int `json:"minZ,omitempty"`; MaxX int `json:"maxX,omitempty"`; MaxZ int `json:"maxZ,omitempty"`; Scale int `json:"scale,omitempty"`
    File string `json:"file,omitempty"`; Count int `json:"count,omitempty"`
    BlockCount int `json:"blockCount,omitempty"`; QuadCount int `json:"quadCount,omitempty"`; VertexCount int `json:"vertexCount,omitempty"`
}

type Engine struct {
    cmd *exec.Cmd
    stdin io.WriteCloser
    mu sync.Mutex
    ready bool
    msgCh chan Response
    pendingMu sync.Mutex
    pendingReply chan Response
    commandMu sync.Mutex // SendCommand supports exactly one outstanding correlated request.
    OnLog func(string)
    OnProgress func(int,string)
    OnDone func(Response)
    OnError func(string)
}

func NewEngine(jarPath string) *Engine {
    return &Engine{msgCh:make(chan Response,64),OnLog:func(s string){fmt.Println("[engine]",s)},OnProgress:func(int,string){},OnDone:func(Response){},OnError:func(s string){fmt.Println("[engine ERROR]",s)}}
}

func (e *Engine) Start(jarPath string) error {
    e.mu.Lock(); defer e.mu.Unlock()
    javaExe := "java"
    if javaHome := os.Getenv("JAVA_HOME"); javaHome != "" { javaExe = javaHome + "/bin/java" }
    e.cmd = exec.Command(javaExe,"-jar",jarPath,"--ipc")
    var err error
    e.stdin, err = e.cmd.StdinPipe(); if err != nil { return fmt.Errorf("stdin pipe: %w",err) }
    stdout, err := e.cmd.StdoutPipe(); if err != nil { return fmt.Errorf("stdout pipe: %w",err) }
    e.cmd.Stderr = os.Stderr
    if err := e.cmd.Start(); err != nil { return fmt.Errorf("start java: %w",err) }
    go e.readLoop(bufio.NewScanner(stdout)); go e.dispatch(); e.ready=true
    return nil
}

func (e *Engine) readLoop(scanner *bufio.Scanner) {
    for scanner.Scan() {
        line:=scanner.Text(); if line=="" { continue }
        var resp Response
        if err:=json.Unmarshal([]byte(line),&resp); err!=nil { resp=Response{Type:"log",Message:line} }
        e.msgCh <- resp
    }
    close(e.msgCh)
}

func (e *Engine) dispatch() {
    for resp := range e.msgCh {
        e.pendingMu.Lock(); pending:=e.pendingReply; e.pendingMu.Unlock()
        switch resp.Type {
        case "log": if e.OnLog!=nil { e.OnLog(resp.Message) }; continue
        case "progress": if e.OnProgress!=nil { e.OnProgress(resp.Percent,resp.Message) }; continue
        case "info": if e.OnLog!=nil { e.OnLog("Engine version: "+resp.Version) }; continue
        }
        if pending != nil { pending <- resp; continue }
        switch resp.Type {
        case "done": if e.OnDone!=nil { e.OnDone(resp) }
        case "error": if e.OnError!=nil { e.OnError(resp.Message) }
        }
    }
}

func (e *Engine) Send(req Request) error {
    e.mu.Lock(); defer e.mu.Unlock()
    if !e.ready { return fmt.Errorf("engine not started") }
    data,err:=json.Marshal(req); if err!=nil{return err}
    _,err=fmt.Fprintln(e.stdin,string(data)); return err
}

func (e *Engine) SendCommand(payload map[string]interface{}) (*Response,error) {
    e.commandMu.Lock()
    defer e.commandMu.Unlock()

    reply:=make(chan Response,1)
    e.pendingMu.Lock(); e.pendingReply=reply; e.pendingMu.Unlock()
    defer func(){e.pendingMu.Lock();e.pendingReply=nil;e.pendingMu.Unlock()}()

    e.mu.Lock()
    data,err:=json.Marshal(payload)
    if err!=nil { e.mu.Unlock(); return nil,err }
    _,err=fmt.Fprintln(e.stdin,string(data)); e.mu.Unlock()
    if err!=nil{return nil,err}
    resp,ok:=<-reply
    if !ok{return nil,fmt.Errorf("engine closed before response")}
    return &resp,nil
}

type ExportParams struct {
    WorldPath string; OutputPath string; Format string; ExportMode string
    MinX,MinY,MinZ int; MaxX,MaxY,MaxZ int
    Options map[string]string
    CenterX,CenterY,CenterZ *int; RadiusX,RadiusY,RadiusZ *int
}

func (e *Engine) Export(p ExportParams) error {
    return e.Send(Request{Command:"export",WorldPath:p.WorldPath,OutputPath:p.OutputPath,Format:p.Format,MinX:p.MinX,MinY:p.MinY,MinZ:p.MinZ,MaxX:p.MaxX,MaxY:p.MaxY,MaxZ:p.MaxZ,ExportMode:p.ExportMode,Options:p.Options,CenterX:p.CenterX,CenterY:p.CenterY,CenterZ:p.CenterZ,RadiusX:p.RadiusX,RadiusY:p.RadiusY,RadiusZ:p.RadiusZ})
}

type ListBlocksParams struct { WorldPath string; MinX,MinY,MinZ int; MaxX,MaxY,MaxZ int; CenterX,CenterY,CenterZ *int; RadiusX,RadiusY,RadiusZ *int }
func (e *Engine) ListBlocks(p ListBlocksParams) (string,int,error) {
    resp,err:=e.SendCommand(map[string]interface{}{"command":"listBlocks","worldPath":p.WorldPath,"minX":p.MinX,"minY":p.MinY,"minZ":p.MinZ,"maxX":p.MaxX,"maxY":p.MaxY,"maxZ":p.MaxZ,"centerX":p.CenterX,"centerY":p.CenterY,"centerZ":p.CenterZ,"radiusX":p.RadiusX,"radiusY":p.RadiusY,"radiusZ":p.RadiusZ})
    if err!=nil{return "",0,err}; if resp.Type=="error"{return "",0,fmt.Errorf("%s",resp.Message)}; return resp.File,resp.Count,nil
}
func (e *Engine) Ping() error { return e.Send(Request{Command:"ping"}) }
func (e *Engine) Stop() {
    e.mu.Lock(); defer e.mu.Unlock()
    if e.stdin!=nil{_ = json.NewEncoder(e.stdin).Encode(Request{Command:"quit"}); _=e.stdin.Close()}
    if e.cmd!=nil&&e.cmd.Process!=nil{_=e.cmd.Process.Kill()}; e.ready=false
}
