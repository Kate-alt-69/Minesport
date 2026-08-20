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

func init() { runtime.LockOSThread() }

type inCommand struct {
	Command string   `json:"command"`
	X, Y, Z int      `json:"x"`
	Power   int      `json:"power"`
	Blocks  [][3]int `json:"blocks"`
	Min     [3]int   `json:"min"`
	Max     [3]int   `json:"max"`
	Parent  uint64   `json:"parent"`
	Width   int      `json:"width"`
	Height  int      `json:"height"`
	Visible bool     `json:"visible"`
}

type outEvent struct {
	Type    string   `json:"type"`
	X, Y, Z int      `json:"x,omitempty"`
	Blocks  [][3]int `json:"blocks,omitempty"`
	Min     [3]int   `json:"min,omitempty"`
	Max     [3]int   `json:"max,omitempty"`
	Count   int      `json:"count,omitempty"`
	Message string   `json:"message,omitempty"`
}

func emit(e outEvent) {
	b, _ := json.Marshal(e)
	fmt.Println(string(b))
}

func Run(blocksPath string, embedded ...bool) error {
	wantEmbedded := len(embedded) > 0 && embedded[0]
	blocks, err := LoadBlocks(blocksPath)
	if err != nil {
		return err
	}
	if len(blocks) == 0 {
		return fmt.Errorf("no blocks")
	}
	index := BuildIndex(blocks)
	if err = glfw.Init(); err != nil {
		return err
	}
	defer glfw.Terminate()
	glfw.WindowHint(glfw.ContextVersionMajor, 2)
	glfw.WindowHint(glfw.ContextVersionMinor, 1)
	glfw.WindowHint(glfw.Resizable, glfw.True)
	if wantEmbedded {
		glfw.WindowHint(glfw.Visible, glfw.False)
		glfw.WindowHint(glfw.Decorated, glfw.False)
	}
	window, err := glfw.CreateWindow(1280, 720, "Minesport — 3D Preview", nil, nil)
	if err != nil {
		return err
	}
	defer window.Destroy()
	window.MakeContextCurrent()
	if err = gl.Init(); err != nil {
		return err
	}
	embed := newNativeEmbed(window)

	program, err := newProgram(vertexShaderSource, fragmentShaderSource)
	if err != nil {
		return err
	}
	posLoc := gl.GetAttribLocation(program, gl.Str("aPos\x00"))
	colorLoc := gl.GetAttribLocation(program, gl.Str("aColor\x00"))
	normalLoc := gl.GetAttribLocation(program, gl.Str("aNormal\x00"))
	uvLoc := gl.GetAttribLocation(program, gl.Str("aUV\x00"))
	modelLoc := gl.GetUniformLocation(program, gl.Str("uModel\x00"))
	viewLoc := gl.GetUniformLocation(program, gl.Str("uView\x00"))
	projLoc := gl.GetUniformLocation(program, gl.Str("uProjection\x00"))
	atlasLoc := gl.GetUniformLocation(program, gl.Str("uAtlas\x00"))

	highlightProgram, err := newProgram(vertexShaderSource, highlightFragmentShaderSource)
	if err != nil {
		return err
	}
	hPosLoc := gl.GetAttribLocation(highlightProgram, gl.Str("aPos\x00"))
	hColorLoc := gl.GetAttribLocation(highlightProgram, gl.Str("aColor\x00"))
	hNormalLoc := gl.GetAttribLocation(highlightProgram, gl.Str("aNormal\x00"))
	hUVLoc := gl.GetAttribLocation(highlightProgram, gl.Str("aUV\x00"))
	hModelLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uModel\x00"))
	hViewLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uView\x00"))
	hProjLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uProjection\x00"))
	hColorUniformLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uHighlightColor\x00"))

	mesh := BuildMesh(blocks, index)
	defer mesh.Destroy()
	var highlightMesh *Mesh
	setHighlight := func(h []Block) {
		if highlightMesh != nil {
			highlightMesh.Destroy()
			highlightMesh = nil
		}
		if len(h) > 0 {
			highlightMesh = buildHighlightMesh(h)
		}
	}

	minB, maxB := BoundingBox(blocks)
	cam := NewCamera(Vec3{X: float32(minB[0]+maxB[0]) / 2, Y: float32(maxB[1] + 10), Z: float32(minB[2]+maxB[2]) / 2})
	cam.Pitch = -0.5
	gl.Enable(gl.DEPTH_TEST)
	gl.Enable(gl.BLEND)
	gl.BlendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)

	initialCamera := Vec3{X: float32(minB[0]+maxB[0]) / 2, Y: float32(maxB[1] + 10), Z: float32(minB[2]+maxB[2]) / 2}
	resetCamera := func() {
		cam.Position = initialCamera
		cam.Yaw = 0
		cam.Pitch = -0.5
	}
	var point1, point2 *[3]int
	var resizeHeld bool
	var menuOpen bool
	var screenshotRequested bool
	menu := newPauseMenu()
	defer menu.Destroy()
	setTitleStats := func() { window.SetTitle(fmt.Sprintf("Minesport — 3D Preview · %d blocks", len(blocks))) }
	selectionBounds := func() ([3]int, [3]int, bool) {
		if point1 == nil || point2 == nil {
			return [3]int{}, [3]int{}, false
		}
		p1, p2 := *point1, *point2
		mn := [3]int{min(p1[0], p2[0]), min(p1[1], p2[1]), min(p1[2], p2[2])}
		mx := [3]int{max(p1[0], p2[0]), max(p1[1], p2[1]), max(p1[2], p2[2])}
		return mn, mx, true
	}
	updateSelection := func() {
		mn, mx, ok := selectionBounds()
		if !ok {
			return
		}
		boxed := blocksInBox(blocks, mn, mx)
		setHighlight(boxed)
		window.SetTitle(fmt.Sprintf("Minesport — 3D · %s selected · hold E + scroll to resize · LMB confirm", formatCount(len(boxed))))
	}
	confirmSelection := func() {
		mn, mx, ok := selectionBounds()
		if !ok {
			return
		}
		boxed := blocksInBox(blocks, mn, mx)
		emit(outEvent{Type: "boxSelected", Min: mn, Max: mx, Count: len(boxed)})
		setHighlight(boxed)
		resizeHeld = false
		window.SetTitle(fmt.Sprintf("Minesport — 3D · %s selected · confirmed", formatCount(len(boxed))))
	}
	adjustSelectionByLook := func(step int) {
		if point2 == nil || step == 0 {
			return
		}
		d := cam.Forward()
		ax, ay, az := math.Abs(float64(d.X)), math.Abs(float64(d.Y)), math.Abs(float64(d.Z))
		p := *point2
		if ax >= ay && ax >= az {
			if d.X >= 0 {
				p[0] += step
			} else {
				p[0] -= step
			}
		} else if ay >= ax && ay >= az {
			if d.Y >= 0 {
				p[1] += step
			} else {
				p[1] -= step
			}
		} else {
			if d.Z >= 0 {
				p[2] += step
			} else {
				p[2] -= step
			}
		}
		point2 = &p
		updateSelection()
	}

	var middleHeld, shiftPan bool
	var lastX, lastY float64
	first := true
	window.SetMouseButtonCallback(func(w *glfw.Window, button glfw.MouseButton, action glfw.Action, mods glfw.ModifierKey) {
		if menuOpen {
			if button == glfw.MouseButtonLeft && action == glfw.Press {
				mx, my := w.GetCursorPos()
				switch menu.Hit(w, mx, my) {
				case pauseResume:
					menuOpen = false
				case pauseFit:
					resetCamera()
					menuOpen = false
				case pauseClear:
					point1 = nil
					point2 = nil
					resizeHeld = false
					setHighlight(nil)
					setTitleStats()
					menuOpen = false
				case pauseClose:
					w.SetShouldClose(true)
				}
			}
			return
		}
		if button == glfw.MouseButtonMiddle {
			middleHeld = action == glfw.Press
			shiftPan = middleHeld && (mods&glfw.ModShift) != 0
			first = true
			if middleHeld {
				w.SetInputMode(glfw.CursorMode, glfw.CursorDisabled)
			} else {
				w.SetInputMode(glfw.CursorMode, glfw.CursorNormal)
			}
			return
		}
		if action != glfw.Press {
			return
		}
		if button == glfw.MouseButtonLeft {
			if point1 != nil && point2 != nil {
				confirmSelection()
				return
			}
			_, pos, ok := Raycast(index, cam.Position, cam.Forward(), 200)
			if !ok {
				return
			}
			p := pos
			point1 = &p
			point2 = nil
			setHighlight(nil)
			emit(outEvent{Type: "point1Set", X: p[0], Y: p[1], Z: p[2]})
			window.SetTitle("Minesport — 3D · point A set · RMB chooses point B")
			return
		}
		if button == glfw.MouseButtonRight {
			if point1 == nil {
				return
			}
			_, pos, ok := Raycast(index, cam.Position, cam.Forward(), 200)
			if !ok {
				return
			}
			p := pos
			point2 = &p
			updateSelection()
		}
	})
	window.SetCursorPosCallback(func(w *glfw.Window, x, y float64) {
		if menuOpen {
			return
		}
		if !middleHeld {
			return
		}
		if first {
			lastX, lastY = x, y
			first = false
			return
		}
		dx, dy := float32(x-lastX), float32(y-lastY)
		lastX, lastY = x, y
		OrbitPan(cam, dx, dy, shiftPan)
	})
	window.SetScrollCallback(func(w *glfw.Window, xoff, yoff float64) {
		if menuOpen {
			return
		}
		if yoff == 0 {
			return
		}
		moving := w.GetKey(glfw.KeyW) == glfw.Press || w.GetKey(glfw.KeyA) == glfw.Press || w.GetKey(glfw.KeyS) == glfw.Press || w.GetKey(glfw.KeyD) == glfw.Press
		if moving {
			cam.AdjustSpeed(yoff)
			return
		}
		if resizeHeld && point1 != nil && point2 != nil {
			if yoff > 0 {
				adjustSelectionByLook(1)
			} else {
				adjustSelectionByLook(-1)
			}
			return
		}
		Dolly(cam, float32(yoff))
	})
	window.SetKeyCallback(func(w *glfw.Window, key glfw.Key, scancode int, action glfw.Action, mods glfw.ModifierKey) {
		if action == glfw.Press && key == glfw.KeyEscape {
			menuOpen = !menuOpen
			middleHeld = false
			resizeHeld = false
			w.SetInputMode(glfw.CursorMode, glfw.CursorNormal)
			return
		}
		if menuOpen {
			return
		}
		if key == glfw.KeyE {
			resizeHeld = action == glfw.Press || action == glfw.Repeat
			if resizeHeld && point1 != nil && point2 != nil {
				updateSelection()
			}
			return
		}
		if action == glfw.Press && key == glfw.KeyC {
			point1 = nil
			point2 = nil
			resizeHeld = false
			setHighlight(nil)
			setTitleStats()
			return
		}
		if action == glfw.Press && key == glfw.KeyF {
			resetCamera()
		}
		if action == glfw.Press && key == glfw.KeyF6 {
			resetCamera()
		}
		if action == glfw.Press && key == glfw.KeyF8 {
			screenshotRequested = true
		}
	})

	cmdCh := make(chan inCommand, 16)
	go func() {
		s := bufio.NewScanner(os.Stdin)
		s.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
		for s.Scan() {
			var c inCommand
			if json.Unmarshal([]byte(s.Text()), &c) == nil {
				cmdCh <- c
			}
		}
		close(cmdCh)
	}()
	setTitleStats()
	emit(outEvent{Type: "ready", Count: len(blocks)})
	last := time.Now()
	quit := false
	for !window.ShouldClose() && !quit {
	drain:
		for {
			select {
			case cmd, ok := <-cmdCh:
				if !ok {
					quit = true
					break drain
				}
				switch cmd.Command {
				case "floodFill":
					r := FloodFillJoined(index, [3]int{cmd.X, cmd.Y, cmd.Z}, cmd.Power)
					setHighlight(r)
					coords := make([][3]int, len(r))
					for i, b := range r {
						coords[i] = [3]int{b.X, b.Y, b.Z}
					}
					emit(outEvent{Type: "selection", Blocks: coords, Count: len(coords)})
				case "highlightBox":
					setHighlight(blocksInBox(blocks, cmd.Min, cmd.Max))
				case "clearHighlight":
					setHighlight(nil)
					setTitleStats()
				case "embed":
					if err := embed.Attach(uintptr(cmd.Parent)); err != nil {
						emit(outEvent{Type: "error", Message: "could not embed the live 3D renderer: " + err.Error()})
						window.Show()
					} else {
						embed.SetRect(cmd.X, cmd.Y, cmd.Width, cmd.Height)
					}
				case "embedRect":
					embed.SetRect(cmd.X, cmd.Y, cmd.Width, cmd.Height)
				case "visible":
					embed.Show(cmd.Visible)
				case "fit":
					resetCamera()
				case "quit":
					quit = true
				}
			default:
				break drain
			}
		}
		now := time.Now()
		dt := float32(now.Sub(last).Seconds())
		last = now
		if dt > 0.1 {
			dt = 0.1
		}
		if !menuOpen {
			cam.Move(Input{Forward: window.GetKey(glfw.KeyW) == glfw.Press, Back: window.GetKey(glfw.KeyS) == glfw.Press, Left: window.GetKey(glfw.KeyA) == glfw.Press, Right: window.GetKey(glfw.KeyD) == glfw.Press, Up: window.GetKey(glfw.KeySpace) == glfw.Press, Down: window.GetKey(glfw.KeyLeftShift) == glfw.Press || window.GetKey(glfw.KeyRightShift) == glfw.Press, Sprint: window.GetKey(glfw.KeyLeftControl) == glfw.Press}, dt)
		}
		// Render and capture at the actual framebuffer size so the embedded
		// preview stays sharp on Windows display scaling / HiDPI screens.
		w, h := window.GetFramebufferSize()
		if h == 0 {
			h = 1
		}
		gl.Viewport(0, 0, int32(w), int32(h))
		gl.ClearColor(0.53, 0.71, 0.90, 1)
		gl.Clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
		proj := Perspective(float32(70*math.Pi/180), float32(w)/float32(h), 0.1, 2000)
		view := cam.ViewMatrix()
		model := Identity()
		gl.UseProgram(program)
		gl.UniformMatrix4fv(modelLoc, 1, false, &model[0])
		gl.UniformMatrix4fv(viewLoc, 1, false, &view[0])
		gl.UniformMatrix4fv(projLoc, 1, false, &proj[0])
		gl.Uniform1i(atlasLoc, 0)
		mesh.Draw(posLoc, colorLoc, normalLoc, uvLoc)
		if highlightMesh != nil {
			gl.UseProgram(highlightProgram)
			gl.UniformMatrix4fv(hModelLoc, 1, false, &model[0])
			gl.UniformMatrix4fv(hViewLoc, 1, false, &view[0])
			gl.UniformMatrix4fv(hProjLoc, 1, false, &proj[0])
			gl.Uniform3f(hColorUniformLoc, 0.25, 1, 0.45)
			highlightMesh.Draw(hPosLoc, hColorLoc, hNormalLoc, hUVLoc)
		}
		menu.DrawSpeed(w, h, cam.MoveSpeed)
		if menuOpen {
			menu.Draw(w, h)
		}
		if screenshotRequested {
			screenshotRequested = false
			path, err := saveScreenshot(w, h)
			if err != nil {
				emit(outEvent{Type: "error", Message: "screenshot failed: " + err.Error()})
			} else {
				emit(outEvent{Type: "screenshot", Message: path})
			}
		}
		window.SwapBuffers()
		glfw.PollEvents()
	}
	if highlightMesh != nil {
		highlightMesh.Destroy()
	}
	return nil
}

func buildHighlightMesh(blocks []Block) *Mesh {
	copied := make([]Block, len(blocks))
	copy(copied, blocks)
	return buildMeshInflated(copied, 0.03)
}
func blocksInBox(all []Block, mn, mx [3]int) []Block {
	lx, hx := min(mn[0], mx[0]), max(mn[0], mx[0])
	ly, hy := min(mn[1], mx[1]), max(mn[1], mx[1])
	lz, hz := min(mn[2], mx[2]), max(mn[2], mx[2])
	out := make([]Block, 0)
	for _, b := range all {
		if b.X >= lx && b.X <= hx && b.Y >= ly && b.Y <= hy && b.Z >= lz && b.Z <= hz {
			out = append(out, b)
		}
	}
	return out
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
func formatCount(n int) string {
	if n < 0 {
		n = 0
	}
	s := fmt.Sprintf("%d", n)
	o := ""
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			o += ","
		}
		o += string(c)
	}
	return o
}
