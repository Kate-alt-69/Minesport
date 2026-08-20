package viewer

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"

	"github.com/go-gl/gl/v2.1/gl"
	"github.com/go-gl/glfw/v3.3/glfw"
	"golang.org/x/image/font"
	"golang.org/x/image/font/basicfont"
	"golang.org/x/image/math/fixed"
)

type pauseAction int

const (
	pauseNone pauseAction = iota
	pauseResume
	pauseFit
	pauseClear
	pauseClose
)

type pauseButton struct {
	action      pauseAction
	x, y, width int
	height      int
	label       string
}

type pauseMenu struct {
	texture             uint32
	hudTexture          uint32
	width, height       int
	hudWidth, hudHeight int
	hudSpeed            float32
	buttons             []pauseButton
}

func newPauseMenu() *pauseMenu { return &pauseMenu{} }

func (m *pauseMenu) Destroy() {
	if m.texture != 0 {
		gl.DeleteTextures(1, &m.texture)
		m.texture = 0
	}
	if m.hudTexture != 0 {
		gl.DeleteTextures(1, &m.hudTexture)
		m.hudTexture = 0
	}
}

func (m *pauseMenu) Draw(width, height int) {
	if width < 2 || height < 2 {
		return
	}
	if m.texture == 0 || width != m.width || height != m.height {
		m.rebuild(width, height)
	}

	drawOverlayTexture(m.texture, width, height)
}

func (m *pauseMenu) DrawSpeed(width, height int, speed float32) {
	if width < 2 || height < 2 {
		return
	}
	if m.hudTexture == 0 || width != m.hudWidth || height != m.hudHeight || speed != m.hudSpeed {
		m.rebuildSpeed(width, height, speed)
	}
	drawOverlayTexture(m.hudTexture, width, height)
}

func drawOverlayTexture(texture uint32, width, height int) {
	gl.UseProgram(0)
	gl.Disable(gl.DEPTH_TEST)
	gl.Enable(gl.BLEND)
	gl.BlendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
	gl.Enable(gl.TEXTURE_2D)
	gl.BindTexture(gl.TEXTURE_2D, texture)
	gl.Color4f(1, 1, 1, 1)

	gl.MatrixMode(gl.PROJECTION)
	gl.PushMatrix()
	gl.LoadIdentity()
	gl.Ortho(0, float64(width), float64(height), 0, -1, 1)
	gl.MatrixMode(gl.MODELVIEW)
	gl.PushMatrix()
	gl.LoadIdentity()
	gl.Begin(gl.QUADS)
	gl.TexCoord2f(0, 0)
	gl.Vertex2f(0, 0)
	gl.TexCoord2f(1, 0)
	gl.Vertex2f(float32(width), 0)
	gl.TexCoord2f(1, 1)
	gl.Vertex2f(float32(width), float32(height))
	gl.TexCoord2f(0, 1)
	gl.Vertex2f(0, float32(height))
	gl.End()
	gl.PopMatrix()
	gl.MatrixMode(gl.PROJECTION)
	gl.PopMatrix()
	gl.MatrixMode(gl.MODELVIEW)
	gl.Disable(gl.TEXTURE_2D)
	gl.Enable(gl.DEPTH_TEST)
}

func (m *pauseMenu) Hit(window *glfw.Window, mouseX, mouseY float64) pauseAction {
	windowWidth, windowHeight := window.GetSize()
	frameWidth, frameHeight := window.GetFramebufferSize()
	if windowWidth > 0 && windowHeight > 0 {
		mouseX *= float64(frameWidth) / float64(windowWidth)
		mouseY *= float64(frameHeight) / float64(windowHeight)
	}
	for _, button := range m.buttons {
		if int(mouseX) >= button.x && int(mouseX) <= button.x+button.width &&
			int(mouseY) >= button.y && int(mouseY) <= button.y+button.height {
			return button.action
		}
	}
	return pauseNone
}

func (m *pauseMenu) rebuild(width, height int) {
	m.width, m.height = width, height
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	draw.Draw(img, img.Bounds(), image.NewUniform(color.NRGBA{R: 0, G: 0, B: 0, A: 155}), image.Point{}, draw.Src)

	panelWidth := minInt(width-32, 640)
	panelHeight := minInt(height-32, 570)
	panelX := (width - panelWidth) / 2
	panelY := (height - panelHeight) / 2
	drawRect(img, panelX, panelY, panelWidth, panelHeight, color.NRGBA{R: 24, G: 24, B: 24, A: 245})
	drawBorder(img, panelX, panelY, panelWidth, panelHeight, color.NRGBA{R: 112, G: 112, B: 112, A: 255})

	drawCenteredText(img, "MINESPORT 3D", panelX+panelWidth/2, panelY+34, color.White)
	drawCenteredText(img, "CONTROLS", panelX+panelWidth/2, panelY+61, color.NRGBA{R: 255, G: 255, B: 85, A: 255})

	controls := []string{
		"W A S D       FLY",
		"SPACE / SHIFT UP / DOWN",
		"CTRL           SPRINT",
		"WASD + SCROLL  SPEED +/- 10%",
		"MMB DRAG       LOOK / ORBIT",
		"SHIFT + MMB    PAN",
		"SCROLL         FORWARD / BACK",
		"LMB            POINT A / CONFIRM",
		"RMB            POINT B",
		"E + SCROLL     RESIZE SELECTION",
		"C              CLEAR SELECTION",
		"F              FIT / RESET CAMERA",
		"F6             CENTER CURRENT VIEW",
		"F8             SAVE PNG SCREENSHOT",
		"ESC            THIS MENU",
	}
	for i, line := range controls {
		drawText(img, line, panelX+48, panelY+91+i*19, color.NRGBA{R: 225, G: 225, B: 225, A: 255})
	}

	buttonWidth := minInt(panelWidth-72, 420)
	buttonHeight := 34
	buttonX := panelX + (panelWidth-buttonWidth)/2
	buttonY := panelY + panelHeight - 166
	buttons := []pauseButton{
		{action: pauseResume, label: "BACK TO PREVIEW"},
		{action: pauseFit, label: "FIT / RESET CAMERA"},
		{action: pauseClear, label: "CLEAR SELECTION"},
		{action: pauseClose, label: "CLOSE 3D PREVIEW"},
	}
	for i := range buttons {
		buttons[i].x = buttonX
		buttons[i].y = buttonY + i*(buttonHeight+6)
		buttons[i].width = buttonWidth
		buttons[i].height = buttonHeight
		drawRect(img, buttons[i].x, buttons[i].y, buttonWidth, buttonHeight, color.NRGBA{R: 78, G: 78, B: 78, A: 255})
		drawBorder(img, buttons[i].x, buttons[i].y, buttonWidth, buttonHeight, color.NRGBA{R: 190, G: 190, B: 190, A: 255})
		drawCenteredText(img, buttons[i].label, buttons[i].x+buttonWidth/2, buttons[i].y+22, color.White)
	}
	m.buttons = buttons

	if m.texture == 0 {
		gl.GenTextures(1, &m.texture)
	}
	gl.BindTexture(gl.TEXTURE_2D, m.texture)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
	gl.PixelStorei(gl.UNPACK_ALIGNMENT, 1)
	gl.TexImage2D(gl.TEXTURE_2D, 0, gl.RGBA, int32(width), int32(height), 0, gl.RGBA, gl.UNSIGNED_BYTE, gl.Ptr(img.Pix))
}

func (m *pauseMenu) rebuildSpeed(width, height int, speed float32) {
	m.hudWidth, m.hudHeight, m.hudSpeed = width, height, speed
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	drawRect(img, 12, 12, 184, 34, color.NRGBA{R: 12, G: 12, B: 12, A: 205})
	drawBorder(img, 12, 12, 184, 34, color.NRGBA{R: 145, G: 145, B: 145, A: 255})
	drawText(img, fmt.Sprintf("FLIGHT SPEED  %.1f BLOCKS/S", speed), 24, 34, color.White)
	if m.hudTexture == 0 {
		gl.GenTextures(1, &m.hudTexture)
	}
	gl.BindTexture(gl.TEXTURE_2D, m.hudTexture)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
	gl.PixelStorei(gl.UNPACK_ALIGNMENT, 1)
	gl.TexImage2D(gl.TEXTURE_2D, 0, gl.RGBA, int32(width), int32(height), 0, gl.RGBA, gl.UNSIGNED_BYTE, gl.Ptr(img.Pix))
}

func drawText(img draw.Image, text string, x, baseline int, fill color.Color) {
	d := font.Drawer{Dst: img, Src: image.NewUniform(fill), Face: basicfont.Face7x13, Dot: fixed.P(x, baseline)}
	d.DrawString(text)
}

func drawCenteredText(img draw.Image, text string, centerX, baseline int, fill color.Color) {
	width := font.MeasureString(basicfont.Face7x13, text).Ceil()
	drawText(img, text, centerX-width/2, baseline, fill)
}

func drawRect(img draw.Image, x, y, width, height int, fill color.Color) {
	draw.Draw(img, image.Rect(x, y, x+width, y+height), image.NewUniform(fill), image.Point{}, draw.Src)
}

func drawBorder(img draw.Image, x, y, width, height int, fill color.Color) {
	drawRect(img, x, y, width, 2, fill)
	drawRect(img, x, y+height-2, width, 2, fill)
	drawRect(img, x, y, 2, height, fill)
	drawRect(img, x+width-2, y, 2, height, fill)
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
