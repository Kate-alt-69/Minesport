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

func init() {
	// GLFW requires its calls to happen on the OS thread that initialized
	// it — this MUST be a dedicated process (see Run's doc comment for why),
	// so locking here is safe and doesn't fight with anything else.
	runtime.LockOSThread()
}

// ── stdin/stdout protocol ────────────────────────────────────────────────────
// Deliberately mirrors ipc.Engine's Go↔Java protocol: JSON lines, one
// message per line. The viewer runs as its own OS process (see Run's doc
// comment), so this is how the main app talks to it — not function calls.

type inCommand struct {
	Command string   `json:"command"`
	X       int      `json:"x"`
	Y       int      `json:"y"`
	Z       int      `json:"z"`
	Power   int      `json:"power"`
	Blocks  [][3]int `json:"blocks"`
	Min     [3]int   `json:"min"`
	Max     [3]int   `json:"max"`
}

type outEvent struct {
	Type    string   `json:"type"`
	X       int      `json:"x,omitempty"`
	Y       int      `json:"y,omitempty"`
	Z       int      `json:"z,omitempty"`
	Blocks  [][3]int `json:"blocks,omitempty"`
	Min     [3]int   `json:"min,omitempty"`
	Max     [3]int   `json:"max,omitempty"`
	Count   int      `json:"count,omitempty"`
	Message string   `json:"message,omitempty"`
}

func emit(e outEvent) {
	data, _ := json.Marshal(e)
	fmt.Println(string(data))
}

// Run opens the 3D preview window and blocks until it's closed. This is
// meant to be the entire body of a dedicated process (main.go's --viewer
// flag launches exactly this) — NOT called from within the main Fyne
// process. Fyne's own driver is built on the same go-gl/glfw package this
// uses; running two independent GLFW instances in one process is unsafe
// (GLFW calls must own the process's main thread, especially strict on
// macOS), so this stays a separate subprocess, the same way the Java
// engine already is.
func Run(blocksPath string) error {
	blocks, err := LoadBlocks(blocksPath)
	if err != nil {
		emit(outEvent{Type: "error", Message: "failed to load blocks: " + err.Error()})
		return err
	}
	if len(blocks) == 0 {
		emit(outEvent{Type: "error", Message: "no blocks in selection"})
		return fmt.Errorf("no blocks")
	}

	index := BuildIndex(blocks)

	if err := glfw.Init(); err != nil {
		emit(outEvent{Type: "error", Message: "glfw init failed: " + err.Error()})
		return err
	}
	defer glfw.Terminate()

	glfw.WindowHint(glfw.ContextVersionMajor, 2)
	glfw.WindowHint(glfw.ContextVersionMinor, 1)
	glfw.WindowHint(glfw.Resizable, glfw.True)

	window, err := glfw.CreateWindow(1280, 720, "Minesport — 3D Preview", nil, nil)
	if err != nil {
		emit(outEvent{Type: "error", Message: "window creation failed: " + err.Error()})
		return err
	}
	window.MakeContextCurrent()

	if err := gl.Init(); err != nil {
		emit(outEvent{Type: "error", Message: "opengl init failed: " + err.Error()})
		return err
	}

	program, err := newProgram(vertexShaderSource, fragmentShaderSource)
	if err != nil {
		emit(outEvent{Type: "error", Message: "shader error: " + err.Error()})
		return err
	}
	gl.UseProgram(program)

	posLoc := uint32(gl.GetAttribLocation(program, gl.Str("aPos\x00")))
	colorLoc := uint32(gl.GetAttribLocation(program, gl.Str("aColor\x00")))
	normalLoc := uint32(gl.GetAttribLocation(program, gl.Str("aNormal\x00")))
	modelLoc := gl.GetUniformLocation(program, gl.Str("uModel\x00"))
	viewLoc := gl.GetUniformLocation(program, gl.Str("uView\x00"))
	projLoc := gl.GetUniformLocation(program, gl.Str("uProjection\x00"))

	highlightProgram, err := newProgram(vertexShaderSource, highlightFragmentShaderSource)
	if err != nil {
		emit(outEvent{Type: "error", Message: "highlight shader error: " + err.Error()})
		return err
	}
	hPosLoc := uint32(gl.GetAttribLocation(highlightProgram, gl.Str("aPos\x00")))
	hColorLoc := uint32(gl.GetAttribLocation(highlightProgram, gl.Str("aColor\x00")))
	hNormalLoc := uint32(gl.GetAttribLocation(highlightProgram, gl.Str("aNormal\x00")))
	hModelLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uModel\x00"))
	hViewLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uView\x00"))
	hProjLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uProjection\x00"))
	hColorUniformLoc := gl.GetUniformLocation(highlightProgram, gl.Str("uHighlightColor\x00"))

	mesh := BuildMesh(blocks, index)
	defer mesh.Destroy()

	var highlightMesh *Mesh
	setHighlight := func(highlighted []Block) {
		if highlightMesh != nil {
			highlightMesh.Destroy()
			highlightMesh = nil
		}
		if len(highlighted) == 0 {
			return
		}
		// Slightly inflate each highlighted cube outward so it renders as
		// a visible shell around the real geometry instead of z-fighting
		// with it — rebuilt fresh each time rather than reusing BuildMesh's
		// tight cube corners.
		highlightMesh = buildHighlightMesh(highlighted)
	}

	minB, maxB := BoundingBox(blocks)
	startPos := Vec3{
		X: float32(minB[0]+maxB[0]) / 2,
		Y: float32(maxB[1] + 10),
		Z: float32(minB[2]+maxB[2]) / 2,
	}
	cam := NewCamera(startPos)
	cam.Pitch = -0.5

	gl.Enable(gl.DEPTH_TEST)
	gl.Enable(gl.BLEND)
	gl.BlendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
	// Deliberately not enabling face culling — see mesh.go's note.

	var lookHeld bool
	var lastMouseX, lastMouseY float64
	firstMouse := true

	window.SetMouseButtonCallback(func(w *glfw.Window, button glfw.MouseButton, action glfw.Action, mods glfw.ModifierKey) {
		if button == glfw.MouseButtonRight {
			lookHeld = action == glfw.Press
			if lookHeld {
				w.SetInputMode(glfw.CursorMode, glfw.CursorDisabled)
				firstMouse = true
			} else {
				w.SetInputMode(glfw.CursorMode, glfw.CursorNormal)
			}
		}
		if button == glfw.MouseButtonLeft && action == glfw.Press {
			_, pos, ok := Raycast(index, cam.Position, cam.Forward(), 200)
			if ok {
				emit(outEvent{Type: "pick", X: pos[0], Y: pos[1], Z: pos[2]})
			}
		}
	})

	window.SetCursorPosCallback(func(w *glfw.Window, xpos, ypos float64) {
		if !lookHeld {
			return
		}
		if firstMouse {
			lastMouseX, lastMouseY = xpos, ypos
			firstMouse = false
			return
		}
		dx := float32(xpos - lastMouseX)
		dy := float32(ypos - lastMouseY)
		lastMouseX, lastMouseY = xpos, ypos
		cam.Look(dx, dy)
	})

	// Quick box selection: E sets point 1, Q sets point 2, both off
	// whatever block you're currently looking at. As soon as both are set,
	// the box between them highlights live and gets sent to the main app —
	// a faster alternative to the pick-then-popup flow for "just grab this
	// rectangular region" cases.
	var point1, point2 *[3]int

	updateBoxSelection := func() {
		if point1 == nil || point2 == nil {
			return
		}
		p1, p2 := *point1, *point2
		boxMin := [3]int{min(p1[0], p2[0]), min(p1[1], p2[1]), min(p1[2], p2[2])}
		boxMax := [3]int{max(p1[0], p2[0]), max(p1[1], p2[1]), max(p1[2], p2[2])}
		boxed := blocksInBox(blocks, boxMin, boxMax)
		setHighlight(boxed)
		window.SetTitle(fmt.Sprintf("Minesport — 3D Preview — %d blocks total · %d selected (E/Q box)", len(blocks), len(boxed)))
		emit(outEvent{Type: "boxSelected", Min: boxMin, Max: boxMax, Count: len(boxed)})
	}

	window.SetKeyCallback(func(w *glfw.Window, key glfw.Key, scancode int, action glfw.Action, mods glfw.ModifierKey) {
		if action != glfw.Press {
			return
		}
		switch key {
		case glfw.KeyE:
			if _, pos, ok := Raycast(index, cam.Position, cam.Forward(), 200); ok {
				point1 = &pos
				emit(outEvent{Type: "point1Set", X: pos[0], Y: pos[1], Z: pos[2]})
				updateBoxSelection()
			}
		case glfw.KeyQ:
			if _, pos, ok := Raycast(index, cam.Position, cam.Forward(), 200); ok {
				point2 = &pos
				emit(outEvent{Type: "point2Set", X: pos[0], Y: pos[1], Z: pos[2]})
				updateBoxSelection()
			}
		}
	})

	// stdin reader — runs on its own goroutine, pushes parsed commands into
	// a channel so the render loop (which must stay on the locked OS
	// thread) can apply them safely between frames instead of racing GL
	// state from a different goroutine.
	cmdCh := make(chan inCommand, 16)
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		scanner.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
		for scanner.Scan() {
			line := scanner.Text()
			if line == "" {
				continue
			}
			var cmd inCommand
			if err := json.Unmarshal([]byte(line), &cmd); err == nil {
				cmdCh <- cmd
			}
		}
		close(cmdCh)
	}()

	setTitleStats := func() {
		faces := mesh.indexCount / 6 // 6 indices per quad face
		window.SetTitle(fmt.Sprintf("Minesport — 3D Preview — %d blocks · %d visible faces (culled)", len(blocks), faces))
	}
	setTitleStats()
	emit(outEvent{Type: "ready", Count: len(blocks)})

	lastFrame := time.Now()
	shouldQuit := false

	for !window.ShouldClose() && !shouldQuit {
		// Drain any pending commands from the main app without blocking.
		drainLoop:
		for {
			select {
			case cmd, chOk := <-cmdCh:
				if !chOk {
					break drainLoop
				}
				switch cmd.Command {
				case "floodFill":
					result := FloodFillJoined(index, [3]int{cmd.X, cmd.Y, cmd.Z}, cmd.Power)
					coords := make([][3]int, len(result))
					for i, b := range result {
						coords[i] = [3]int{b.X, b.Y, b.Z}
					}
					setHighlight(result)
					window.SetTitle(fmt.Sprintf("Minesport — 3D Preview — %d blocks total · %d selected (joined)", len(blocks), len(result)))
					emit(outEvent{Type: "selection", Blocks: coords, Count: len(result)})
				case "highlightBox":
					boxed := blocksInBox(blocks, cmd.Min, cmd.Max)
					setHighlight(boxed)
					window.SetTitle(fmt.Sprintf("Minesport — 3D Preview — %d blocks total · %d selected (area)", len(blocks), len(boxed)))
				case "clearHighlight":
					setHighlight(nil)
					setTitleStats()
				case "quit":
					shouldQuit = true
				}
			default:
				break drainLoop
			}
		}

		now := time.Now()
		dt := float32(now.Sub(lastFrame).Seconds())
		lastFrame = now
		if dt > 0.1 {
			dt = 0.1 // clamp spikes from window drags/stalls
		}

		in := Input{
			Forward: window.GetKey(glfw.KeyW) == glfw.Press,
			Back:    window.GetKey(glfw.KeyS) == glfw.Press,
			Left:    window.GetKey(glfw.KeyA) == glfw.Press,
			Right:   window.GetKey(glfw.KeyD) == glfw.Press,
			Up:      window.GetKey(glfw.KeySpace) == glfw.Press,
			Down:    window.GetKey(glfw.KeyLeftShift) == glfw.Press || window.GetKey(glfw.KeyRightShift) == glfw.Press,
			Sprint:  window.GetKey(glfw.KeyLeftControl) == glfw.Press,
		}
		cam.Move(in, dt)

		width, height := window.GetSize()
		if height == 0 {
			height = 1
		}
		gl.Viewport(0, 0, int32(width), int32(height))
		gl.ClearColor(0.53, 0.71, 0.90, 1.0) // sky blue
		gl.Clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)

		aspect := float32(width) / float32(height)
		fov := float32(70)
		if window.GetKey(glfw.KeyC) == glfw.Press {
			fov = 15 // narrow FOV = telephoto zoom, same idea as vanilla's C-to-zoom
		}
		proj := Perspective(fov*float32(math.Pi)/180, aspect, 0.1, 2000)
		view := cam.ViewMatrix()
		model := Identity()

		gl.UseProgram(program)
		gl.UniformMatrix4fv(modelLoc, 1, false, &model[0])
		gl.UniformMatrix4fv(viewLoc, 1, false, &view[0])
		gl.UniformMatrix4fv(projLoc, 1, false, &proj[0])
		mesh.Draw(posLoc, colorLoc, normalLoc)

		if highlightMesh != nil {
			gl.UseProgram(highlightProgram)
			gl.UniformMatrix4fv(hModelLoc, 1, false, &model[0])
			gl.UniformMatrix4fv(hViewLoc, 1, false, &view[0])
			gl.UniformMatrix4fv(hProjLoc, 1, false, &proj[0])
			gl.Uniform3f(hColorUniformLoc, 1.0, 0.85, 0.1) // bright yellow, distinct from any block color
			highlightMesh.Draw(hPosLoc, hColorLoc, hNormalLoc)
		}

		window.SwapBuffers()
		glfw.PollEvents()
	}

	if highlightMesh != nil {
		highlightMesh.Destroy()
	}
	return nil
}

// buildHighlightMesh builds a slightly-inflated cube shell for each block
// so the highlight renders as a visible outline instead of z-fighting with
// the real geometry occupying the exact same space.
func buildHighlightMesh(blocks []Block) *Mesh {
	const inflate = 0.03
	inflated := make([]Block, len(blocks))
	copy(inflated, blocks)
	// Reuse BuildMesh's culling against an EMPTY index (nil) so every face
	// of every highlighted block always draws — we want the whole shell
	// visible, not just the exterior of the highlighted group.
	return buildMeshInflated(inflated, inflate)
}

func blocksInBox(all []Block, min, max [3]int) []Block {
	lx, hx := min[0], max[0]
	ly, hy := min[1], max[1]
	lz, hz := min[2], max[2]
	if lx > hx { lx, hx = hx, lx }
	if ly > hy { ly, hy = hy, ly }
	if lz > hz { lz, hz = hz, lz }

	var out []Block
	for _, b := range all {
		if b.X >= lx && b.X <= hx && b.Y >= ly && b.Y <= hy && b.Z >= lz && b.Z <= hz {
			out = append(out, b)
		}
	}
	return out
}
