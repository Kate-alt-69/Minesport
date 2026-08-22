package ui

import (
	"image"
	"image/color"
	"math"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/driver/desktop"
	"fyne.io/fyne/v2/widget"
)

// ── WorldMap widget ───────────────────────────────────────────────────────────

type viewMode int

const (
	mode2D viewMode = iota
	mode3D
)

type WorldMap struct {
	widget.BaseWidget

	// Map image + bounds
	imgLock           sync.RWMutex
	img2D             *image.RGBA // flat top-down
	img3D             *image.RGBA // isometric render (generated from img2D)
	mapMinX, mapMinZ  int
	mapMaxX, mapMaxZ  int

	// View state
	mode    viewMode
	zoom    float64
	offsetX float64
	offsetY float64
	size    fyne.Size

	// Selection
	hasSelection          bool
	selStartX, selStartZ  int
	selEndX,   selEndZ    int
	isDragging            bool
	dragStartScreenX      float64
	dragStartScreenY      float64

	// Hover — the single block currently under the cursor, tracked
	// continuously (not just during a drag) so the map always shows
	// exactly which block you're about to act on. This is what makes
	// selection feel "per-block" instead of only reacting once a drag
	// finishes.
	hasHover     bool
	hoverX, hoverZ int

	// Bubble (center + radius) selection — an alternative to the drag-box
	// selection above. When bubbleMode is true, a single click sets the
	// center point instead of starting a drag. The X/Z radius values are
	// pushed in from the sidebar (Y has no top-down representation) purely
	// so the map can draw a preview; the actual ellipsoid math happens
	// engine-side against all three axes.
	bubbleMode                bool
	hasBubbleCenter           bool
	bubbleCenterX, bubbleCenterZ int
	bubbleRadiusX, bubbleRadiusZ int

	// Pan
	isPanning    bool
	panStartX    float64
	panStartY    float64
	panOffStartX float64
	panOffStartY float64

	// Callbacks
	OnSelectionChanged func(minX, minZ, maxX, maxZ int)
	OnCursorMoved      func(worldX, worldZ int)
	OnCenterPicked     func(worldX, worldZ int)
}

func NewWorldMap() *WorldMap {
	m := &WorldMap{
		zoom:    1.0,
		mode:    mode2D,
	}
	m.ExtendBaseWidget(m)
	return m
}

// ── Public API ────────────────────────────────────────────────────────────────

func (m *WorldMap) LoadHeightmap(img *image.RGBA, minX, minZ, maxX, maxZ int) {
	m.imgLock.Lock()
	m.img2D = img
	m.mapMinX = minX
	m.mapMinZ = minZ
	m.mapMaxX = maxX
	m.mapMaxZ = maxZ
	m.img3D = nil // will be generated on demand
	m.imgLock.Unlock()
	m.Refresh()
}

func (m *WorldMap) SetMode2D() {
	m.mode = mode2D
	m.Refresh()
}

// SetBubbleMode switches between drag-box selection (false, default) and
// click-to-set-center bubble selection (true).
func (m *WorldMap) SetBubbleMode(enabled bool) {
	m.bubbleMode = enabled
	m.Refresh()
}

// SetBubbleRadius updates the preview radius shown around the picked center.
// This does NOT change the actual selection sent to the engine — it only
// redraws the on-screen preview rectangle (see note in the renderer below
// about why it's a rectangle, not a true ellipse).
func (m *WorldMap) SetBubbleRadius(radiusX, radiusZ int) {
	m.bubbleRadiusX = radiusX
	m.bubbleRadiusZ = radiusZ
	m.Refresh()
}

// SetBubbleCenter sets the center point directly (e.g. when the sidebar
// fields are edited by hand rather than picked on the map).
func (m *WorldMap) SetBubbleCenter(x, z int) {
	m.bubbleCenterX = x
	m.bubbleCenterZ = z
	m.hasBubbleCenter = true
	m.Refresh()
}

func (m *WorldMap) SetMode3D() {
	m.imgLock.Lock()
	if m.img2D != nil && m.img3D == nil {
		m.img3D = renderIsometric(m.img2D)
	}
	m.imgLock.Unlock()
	m.mode = mode3D
	m.Refresh()
}

// FitToWindow centers the map and fits it to the current widget size.
func (m *WorldMap) FitToWindow() {
	m.imgLock.RLock()
	img := m.currentImg()
	m.imgLock.RUnlock()
	if img == nil {
		return
	}
	b := img.Bounds()
	if b.Dx() == 0 || b.Dy() == 0 {
		return
	}
	scaleX := float64(m.size.Width)  / float64(b.Dx())
	scaleY := float64(m.size.Height) / float64(b.Dy())
	m.zoom = math.Min(scaleX, scaleY) * 0.95
	m.offsetX = (float64(m.size.Width)  - float64(b.Dx())*m.zoom) / 2
	m.offsetY = (float64(m.size.Height) - float64(b.Dy())*m.zoom) / 2
	m.Refresh()
}

// ── Coordinate conversion ─────────────────────────────────────────────────────

func (m *WorldMap) screenToWorld(sx, sy float64) (int, int) {
	imgW := float64(m.mapMaxX - m.mapMinX)
	imgH := float64(m.mapMaxZ - m.mapMinZ)
	if imgW == 0 || imgH == 0 {
		return 0, 0
	}
	relX := (sx - m.offsetX) / (imgW * m.zoom)
	relZ := (sy - m.offsetY) / (imgH * m.zoom)
	wx := m.mapMinX + int(relX*imgW)
	wz := m.mapMinZ + int(relZ*imgH)
	return wx, wz
}

func (m *WorldMap) worldToScreen(wx, wz int) (float64, float64) {
	imgW := float64(m.mapMaxX - m.mapMinX)
	imgH := float64(m.mapMaxZ - m.mapMinZ)
	if imgW == 0 || imgH == 0 {
		return 0, 0
	}
	relX := float64(wx-m.mapMinX) / imgW
	relZ := float64(wz-m.mapMinZ) / imgH
	sx := m.offsetX + relX*imgW*m.zoom
	sz := m.offsetY + relZ*imgH*m.zoom
	return sx, sz
}

// worldBlockRectScreen returns the screen-space rectangle that visually
// covers the inclusive block range [minX,maxX]×[minZ,maxZ] — i.e. it
// spans all the way to the FAR edge of block maxX/maxZ, not just to that
// block's own origin corner.
//
// This matters more than it looks: worldToScreen(wx,wz) always returns a
// block's top-left corner (that's the natural, correct convention for
// placing a block on the map). But using worldToScreen(maxX,maxZ) as the
// bottom-right of a selection rectangle was silently cutting the box short
// by exactly one full block on the right/bottom edge — visually excluding
// the very last row/column you dragged over, which is exactly what made
// selection feel like it only "counted" near a block's top-left corner.
func (m *WorldMap) worldBlockRectScreen(minX, minZ, maxX, maxZ int) (x0, y0, x1, y1 float64) {
	x0, y0 = m.worldToScreen(minX, minZ)
	x1, y1 = m.worldToScreen(maxX+1, maxZ+1)
	return
}

func (m *WorldMap) currentImg() *image.RGBA {
	if m.mode == mode3D && m.img3D != nil {
		return m.img3D
	}
	return m.img2D
}

// ── Mouse events ──────────────────────────────────────────────────────────────

func (m *WorldMap) MouseDown(ev *desktop.MouseEvent) {
	if ev.Button == desktop.MouseButtonSecondary {
		m.isPanning = true
		m.panStartX = float64(ev.Position.X)
		m.panStartY = float64(ev.Position.Y)
		m.panOffStartX = m.offsetX
		m.panOffStartY = m.offsetY
		return
	}

	if m.bubbleMode {
		// A single click sets the center — no drag needed. Handled fully
		// on mouse-down so it feels immediate; MouseUp does nothing extra.
		wx, wz := m.screenToWorld(float64(ev.Position.X), float64(ev.Position.Y))
		m.bubbleCenterX, m.bubbleCenterZ = wx, wz
		m.hasBubbleCenter = true
		m.Refresh()
		if m.OnCenterPicked != nil {
			m.OnCenterPicked(wx, wz)
		}
		return
	}

	m.isDragging = true
	m.hasSelection = false
	wx, wz := m.screenToWorld(float64(ev.Position.X), float64(ev.Position.Y))
	m.selStartX, m.selStartZ = wx, wz
	m.selEndX,   m.selEndZ   = wx, wz
}

func (m *WorldMap) MouseUp(ev *desktop.MouseEvent) {
	if ev.Button == desktop.MouseButtonSecondary {
		m.isPanning = false
		return
	}
	if m.bubbleMode {
		return // center already set on MouseDown
	}
	if m.isDragging {
		m.isDragging = false
		if m.selStartX != m.selEndX || m.selStartZ != m.selEndZ {
			m.hasSelection = true
			if m.OnSelectionChanged != nil {
				m.OnSelectionChanged(
					min2(m.selStartX, m.selEndX), min2(m.selStartZ, m.selEndZ),
					max2(m.selStartX, m.selEndX), max2(m.selStartZ, m.selEndZ),
				)
			}
		}
	}
}

func (m *WorldMap) MouseMoved(ev *desktop.MouseEvent) {
	wx, wz := m.screenToWorld(float64(ev.Position.X), float64(ev.Position.Y))

	// Cursor coordinate readout
	if m.OnCursorMoved != nil {
		m.OnCursorMoved(wx, wz)
	}

	if wx != m.hoverX || wz != m.hoverZ || !m.hasHover {
		m.hoverX, m.hoverZ = wx, wz
		m.hasHover = true
		m.Refresh()
	}

	if m.isPanning {
		m.offsetX = m.panOffStartX + float64(ev.Position.X) - m.panStartX
		m.offsetY = m.panOffStartY + float64(ev.Position.Y) - m.panStartY
		m.Refresh()
		return
	}
	if m.isDragging {
		m.selEndX, m.selEndZ = wx, wz
		m.Refresh()
	}
}

func (m *WorldMap) MouseIn(*desktop.MouseEvent) {}

func (m *WorldMap) MouseOut() {
	m.hasHover = false
	m.Refresh()
}

func (m *WorldMap) Scrolled(ev *fyne.ScrollEvent) {
	factor := 1.0 + float64(ev.Scrolled.DY)*0.1
	if factor < 0.5 { factor = 0.5 }
	cx := float64(ev.Position.X)
	cy := float64(ev.Position.Y)
	// Zoom toward cursor
	m.offsetX = cx - (cx-m.offsetX)*factor
	m.offsetY = cy - (cy-m.offsetY)*factor
	m.zoom *= factor
	if m.zoom < 0.05 { m.zoom = 0.05 }
	if m.zoom > 64   { m.zoom = 64 }
	m.Refresh()
}

// ── Fyne widget interface ─────────────────────────────────────────────────────

func (m *WorldMap) CreateRenderer() fyne.WidgetRenderer {
	return newWorldMapRenderer(m)
}

func (m *WorldMap) MinSize() fyne.Size {
	return fyne.NewSize(300, 200)
}

// ── Renderer ──────────────────────────────────────────────────────────────────

type worldMapRenderer struct {
	m              *WorldMap
	background     *canvas.Rectangle
	mapImg         *canvas.Image
	selection      *canvas.Rectangle
	hoverOutline   *canvas.Rectangle
	blockGrid      *canvas.Raster
	bubblePreview  *canvas.Rectangle
	bubbleCenter   *canvas.Circle
}

func newWorldMapRenderer(m *WorldMap) *worldMapRenderer {
	bg := canvas.NewRectangle(color.NRGBA{20, 20, 26, 255})

	mapImg := canvas.NewImageFromImage(image.NewRGBA(image.Rect(0, 0, 1, 1)))
	mapImg.ScaleMode = canvas.ImageScalePixels
	mapImg.FillMode = canvas.ImageFillStretch

	sel := canvas.NewRectangle(color.NRGBA{255, 80, 80, 50})
	sel.StrokeColor = color.NRGBA{255, 80, 80, 220}
	sel.StrokeWidth = 1.5
	sel.Hide()

	// Hover outline: exactly the one block under the cursor right now,
	// visible whether or not a drag is in progress. This is what makes
	// selection feel precise — you always see exactly which block you're
	// about to act on, not just a rough area after the fact.
	hover := canvas.NewRectangle(color.NRGBA{0, 0, 0, 0})
	hover.StrokeColor = color.NRGBA{255, 255, 255, 200}
	hover.StrokeWidth = 2
	hover.Hide()

	// Per-block grid overlay for an active multi-block selection — a
	// checkerboard fill + gridlines so a 2x2 (or NxM) selection visibly
	// reads as that many distinct highlighted cells, not one blurry box.
	// Raster because the cell count is dynamic; regenerated on demand from
	// live WorldMap state each time it's asked to redraw.
	grid := canvas.NewRaster(func(w, h int) image.Image {
		return renderSelectionGrid(m, w, h)
	})
	grid.ScaleMode = canvas.ImageScalePixels
	grid.Hide()

	// Bubble preview: drawn as a rectangle spanning ±radiusX/±radiusZ around
	// the picked center. The real selection sent to the engine is a proper
	// ellipsoid (it also factors in Y) — this rectangle is a fast, honest-
	// enough visual guide for "roughly this area on the X/Z plane", not a
	// pixel-accurate outline of the actual cut.
	bubble := canvas.NewRectangle(color.NRGBA{80, 180, 255, 40})
	bubble.StrokeColor = color.NRGBA{80, 180, 255, 220}
	bubble.StrokeWidth = 1.5
	bubble.Hide()

	center := canvas.NewCircle(color.NRGBA{80, 180, 255, 255})
	center.Hide()

	return &worldMapRenderer{
		m: m, background: bg, mapImg: mapImg,
		selection: sel, hoverOutline: hover, blockGrid: grid,
		bubblePreview: bubble, bubbleCenter: center,
	}
}

func (r *worldMapRenderer) Layout(size fyne.Size) {
	r.m.size = size
	r.background.Resize(size)
	r.Refresh()
}

func (r *worldMapRenderer) MinSize() fyne.Size { return r.m.MinSize() }
func (r *worldMapRenderer) Destroy()           {}

func (r *worldMapRenderer) Objects() []fyne.CanvasObject {
	return []fyne.CanvasObject{r.background, r.mapImg, r.blockGrid, r.selection, r.hoverOutline, r.bubblePreview, r.bubbleCenter}
}

func (r *worldMapRenderer) Refresh() {
	r.m.imgLock.RLock()
	img := r.m.currentImg()
	r.m.imgLock.RUnlock()

	r.background.Resize(r.m.size)

	if img != nil {
		r.mapImg.Image = img
		r.mapImg.ScaleMode = canvas.ImageScalePixels

		mapW := float32(img.Bounds().Dx()) * float32(r.m.zoom)
		mapH := float32(img.Bounds().Dy()) * float32(r.m.zoom)
		r.mapImg.Move(fyne.NewPos(float32(r.m.offsetX), float32(r.m.offsetY)))
		r.mapImg.Resize(fyne.NewSize(mapW, mapH))
		r.mapImg.Show()
	} else {
		r.mapImg.Hide()
	}

	// Selection rectangle — worldBlockRectScreen spans to the FAR edge of
	// the max block, not just its origin corner (see that helper's comment
	// for why the old worldToScreen(max,max) version was cutting the last
	// row/column short).
	if r.m.hasSelection || r.m.isDragging {
		minX, maxX := min2(r.m.selStartX, r.m.selEndX), max2(r.m.selStartX, r.m.selEndX)
		minZ, maxZ := min2(r.m.selStartZ, r.m.selEndZ), max2(r.m.selStartZ, r.m.selEndZ)
		sx1, sz1, sx2, sz2 := r.m.worldBlockRectScreen(minX, minZ, maxX, maxZ)
		r.selection.Move(fyne.NewPos(float32(sx1), float32(sz1)))
		r.selection.Resize(fyne.NewSize(float32(sx2-sx1), float32(sz2-sz1)))
		r.selection.Show()

		r.blockGrid.Move(fyne.NewPos(0, 0))
		r.blockGrid.Resize(r.m.size)
		r.blockGrid.Show()
	} else {
		r.selection.Hide()
		r.blockGrid.Hide()
	}

	// Hover outline — exactly the one block under the cursor, always on
	// (not just while dragging), so it's obvious what you're about to
	// click before you click it.
	if r.m.hasHover && !r.m.isPanning {
		hx1, hz1, hx2, hz2 := r.m.worldBlockRectScreen(r.m.hoverX, r.m.hoverZ, r.m.hoverX, r.m.hoverZ)
		r.hoverOutline.Move(fyne.NewPos(float32(hx1), float32(hz1)))
		r.hoverOutline.Resize(fyne.NewSize(float32(hx2-hx1), float32(hz2-hz1)))
		r.hoverOutline.Show()
	} else {
		r.hoverOutline.Hide()
	}

	// Bubble (center + radius) preview
	if r.m.bubbleMode && r.m.hasBubbleCenter {
		rx, rz := r.m.bubbleRadiusX, r.m.bubbleRadiusZ
		if rx < 1 {
			rx = 1
		}
		if rz < 1 {
			rz = 1
		}
		bx1, bz1, bx2, bz2 := r.m.worldBlockRectScreen(
			r.m.bubbleCenterX-rx, r.m.bubbleCenterZ-rz,
			r.m.bubbleCenterX+rx, r.m.bubbleCenterZ+rz,
		)
		r.bubblePreview.Move(fyne.NewPos(float32(bx1), float32(bz1)))
		r.bubblePreview.Resize(fyne.NewSize(float32(bx2-bx1), float32(bz2-bz1)))
		r.bubblePreview.Show()

		cx, cz := r.m.worldToScreen(r.m.bubbleCenterX, r.m.bubbleCenterZ)
		const dotRadius = 4
		r.bubbleCenter.Move(fyne.NewPos(float32(cx-dotRadius), float32(cz-dotRadius)))
		r.bubbleCenter.Resize(fyne.NewSize(dotRadius*2, dotRadius*2))
		r.bubbleCenter.Show()
	} else {
		r.bubblePreview.Hide()
		r.bubbleCenter.Hide()
	}

	r.mapImg.Refresh()
	r.blockGrid.Refresh()
	r.selection.Refresh()
	r.hoverOutline.Refresh()
	r.bubblePreview.Refresh()
	r.bubbleCenter.Refresh()
	r.background.Refresh()
}

// renderSelectionGrid draws a per-block checkerboard fill + gridlines over
// the current selection, in screen space, sized (w,h) to match the whole
// widget canvas. Returns a fully transparent image when there's nothing to
// draw, when blocks are too small on screen to show a grid usefully (avoids
// a wall of 1px noise at extreme zoom-out), or when the selection is huge
// enough that per-cell drawing would just be wasted work.
func renderSelectionGrid(m *WorldMap, w, h int) image.Image {
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	if w <= 0 || h <= 0 {
		return img
	}
	if !m.hasSelection && !m.isDragging {
		return img
	}

	minX, maxX := min2(m.selStartX, m.selEndX), max2(m.selStartX, m.selEndX)
	minZ, maxZ := min2(m.selStartZ, m.selEndZ), max2(m.selStartZ, m.selEndZ)
	blocksX := maxX - minX + 1
	blocksZ := maxZ - minZ + 1

	blockPx := m.zoom
	if blockPx < 5 || blocksX*blocksZ > 20000 || blocksX <= 1 && blocksZ <= 1 {
		return img // too small to be useful, too big to be cheap, or just a single block (hover already covers that case)
	}

	fillA := color.NRGBA{255, 80, 80, 26}
	fillB := color.NRGBA{255, 80, 80, 12}
	lineColor := color.NRGBA{255, 255, 255, 90}

	x0, y0 := m.worldToScreen(minX, minZ)

	for bx := 0; bx < blocksX; bx++ {
		px0 := int(math.Round(x0 + float64(bx)*blockPx))
		px1 := int(math.Round(x0 + float64(bx+1)*blockPx))
		for bz := 0; bz < blocksZ; bz++ {
			py0 := int(math.Round(y0 + float64(bz)*blockPx))
			py1 := int(math.Round(y0 + float64(bz+1)*blockPx))
			fill := fillA
			if (bx+bz)%2 == 1 {
				fill = fillB
			}
			fillRectSafe(img, px0, py0, px1, py1, fill)
		}
	}

	x1 := x0 + float64(blocksX)*blockPx
	y1 := y0 + float64(blocksZ)*blockPx
	for bx := 0; bx <= blocksX; bx++ {
		px := int(math.Round(x0 + float64(bx)*blockPx))
		drawVLineSafe(img, px, int(math.Round(y0)), int(math.Round(y1)), lineColor)
	}
	for bz := 0; bz <= blocksZ; bz++ {
		py := int(math.Round(y0 + float64(bz)*blockPx))
		drawHLineSafe(img, int(math.Round(x0)), int(math.Round(x1)), py, lineColor)
	}

	return img
}

func fillRectSafe(img *image.NRGBA, x0, y0, x1, y1 int, c color.NRGBA) {
	b := img.Bounds()
	if x0 < b.Min.X { x0 = b.Min.X }
	if y0 < b.Min.Y { y0 = b.Min.Y }
	if x1 > b.Max.X { x1 = b.Max.X }
	if y1 > b.Max.Y { y1 = b.Max.Y }
	for y := y0; y < y1; y++ {
		for x := x0; x < x1; x++ {
			img.SetNRGBA(x, y, c)
		}
	}
}

func drawVLineSafe(img *image.NRGBA, x, y0, y1 int, c color.NRGBA) {
	b := img.Bounds()
	if x < b.Min.X || x >= b.Max.X { return }
	if y0 < b.Min.Y { y0 = b.Min.Y }
	if y1 > b.Max.Y { y1 = b.Max.Y }
	for y := y0; y < y1; y++ {
		img.SetNRGBA(x, y, c)
	}
}

func drawHLineSafe(img *image.NRGBA, x0, x1, y int, c color.NRGBA) {
	b := img.Bounds()
	if y < b.Min.Y || y >= b.Max.Y { return }
	if x0 < b.Min.X { x0 = b.Min.X }
	if x1 > b.Max.X { x1 = b.Max.X }
	for x := x0; x < x1; x++ {
		img.SetNRGBA(x, y, c)
	}
}

// ── Isometric 3D renderer ─────────────────────────────────────────────────────
// Generates a simple isometric view from the 2D top-down heightmap.
// Each pixel in the 2D map = one column of blocks.
// We scan from back-to-front (painter's algorithm) to handle occlusion.

func renderIsometric(src *image.RGBA) *image.RGBA {
	srcW := src.Bounds().Dx()
	srcH := src.Bounds().Dy()

	// Isometric tile dimensions
	const tileW = 2  // pixels wide per block (x-axis)
	const tileH = 1  // pixels tall per block (z-axis)
	const blockH = 1 // pixels per Y unit

	// Output size
	outW := (srcW + srcH) * tileW
	outH := (srcW + srcH) * tileH + 64

	out := image.NewRGBA(image.Rect(0, 0, outW, outH))

	// Dark background
	for y := 0; y < outH; y++ {
		for x := 0; x < outW; x++ {
			out.SetRGBA(x, y, color.RGBA{14, 14, 20, 255})
		}
	}

	// We use the brightness of each pixel as a proxy for height
	// (brighter = higher terrain due to our height shading in HeightmapGenerator)
	// Scan back-to-front: high x+z first
	for iz := srcH - 1; iz >= 0; iz-- {
		for ix := srcW - 1; ix >= 0; ix-- {
			c := src.RGBAAt(ix, iz)
			if c.A == 0 {
				continue
			}

			// Estimate height from brightness (0-255 → 0-32 blocks)
			brightness := (int(c.R) + int(c.G) + int(c.B)) / 3
			h := brightness / 8 // 0-32 range

			// Isometric screen position
			screenX := outW/2 + (ix-iz)*tileW
			screenY := outH/2 + (ix+iz)*tileH - h*blockH

			// Draw top face (lighter)
			topR := min255(int(c.R) + 40)
			topG := min255(int(c.G) + 40)
			topB := min255(int(c.B) + 40)
			setPixelSafe(out, screenX, screenY, color.RGBA{uint8(topR), uint8(topG), uint8(topB), 255})
			setPixelSafe(out, screenX+1, screenY, color.RGBA{uint8(topR), uint8(topG), uint8(topB), 255})

			// Draw left face (darker — shadow)
			leftR := int(c.R) * 6 / 10
			leftG := int(c.G) * 6 / 10
			leftB := int(c.B) * 6 / 10
			for dy := 1; dy <= blockH+h/4+1; dy++ {
				setPixelSafe(out, screenX, screenY+dy, color.RGBA{uint8(leftR), uint8(leftG), uint8(leftB), 255})
			}

			// Draw right face (medium)
			rightR := int(c.R) * 8 / 10
			rightG := int(c.G) * 8 / 10
			rightB := int(c.B) * 8 / 10
			for dy := 1; dy <= blockH+h/4+1; dy++ {
				setPixelSafe(out, screenX+1, screenY+dy, color.RGBA{uint8(rightR), uint8(rightG), uint8(rightB), 255})
			}
		}
	}

	return out
}

func min255(v int) int {
	if v > 255 { return 255 }
	return v
}

func min2(a, b int) int {
	if a < b { return a }
	return b
}

func max2(a, b int) int {
	if a > b { return a }
	return b
}
