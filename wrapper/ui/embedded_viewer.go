package ui

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"math"
	"sort"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/widget"

	"github.com/kastrick/minesport/viewer"
)

const maxEmbeddedPreviewBlocks = 75_000

type embeddedFace struct {
	block   viewer.Block
	corners [4][3]float64
	shade   float64
}

type projectedFace struct {
	block  viewer.Block
	points [4]fyne.Position
	depth  float64
}

type EmbeddedViewer struct {
	widget.BaseWidget
	mu sync.Mutex

	blocks []viewer.Block
	faces  []embeddedFace
	hits   []projectedFace

	center [3]float64
	span   float64
	yaw    float64
	pitch  float64
	zoom   float64
	panX   float64
	panY   float64

	pointA *[3]int
	pointB *[3]int

	OnBoxSelected func([3]int, [3]int, int)
	OnHint        func(string)
}

var embeddedFaceDefs = []struct {
	corners  [4][3]float64
	neighbor [3]int
	shade    float64
}{
	{[4][3]float64{{1, 0, 0}, {1, 0, 1}, {1, 1, 1}, {1, 1, 0}}, [3]int{1, 0, 0}, .78},
	{[4][3]float64{{0, 0, 1}, {0, 0, 0}, {0, 1, 0}, {0, 1, 1}}, [3]int{-1, 0, 0}, .68},
	{[4][3]float64{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, [3]int{0, 1, 0}, 1.08},
	{[4][3]float64{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}}, [3]int{0, -1, 0}, .52},
	{[4][3]float64{{1, 0, 1}, {0, 0, 1}, {0, 1, 1}, {1, 1, 1}}, [3]int{0, 0, 1}, .88},
	{[4][3]float64{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, [3]int{0, 0, -1}, .62},
}

func NewEmbeddedViewer(blocks []viewer.Block) (*EmbeddedViewer, error) {
	if len(blocks) == 0 {
		return nil, fmt.Errorf("no solid blocks were found in the current selection")
	}
	if len(blocks) > maxEmbeddedPreviewBlocks {
		return nil, fmt.Errorf("the selection contains %s blocks; reduce it below %s blocks for the embedded 3D preview", formatCount(len(blocks)), formatCount(maxEmbeddedPreviewBlocks))
	}
	v := &EmbeddedViewer{blocks: blocks, yaw: -.72, pitch: -.55, zoom: 1}
	v.computeGeometry()
	v.ExtendBaseWidget(v)
	return v, nil
}

func (v *EmbeddedViewer) computeGeometry() {
	index := viewer.BuildIndex(v.blocks)
	minB := [3]int{v.blocks[0].X, v.blocks[0].Y, v.blocks[0].Z}
	maxB := minB
	for _, block := range v.blocks {
		coords := [3]int{block.X, block.Y, block.Z}
		for axis := range coords {
			if coords[axis] < minB[axis] {
				minB[axis] = coords[axis]
			}
			if coords[axis] > maxB[axis] {
				maxB[axis] = coords[axis]
			}
		}
		for _, def := range embeddedFaceDefs {
			if _, occupied := index[viewer.PositionKey(block.X+def.neighbor[0], block.Y+def.neighbor[1], block.Z+def.neighbor[2])]; occupied {
				continue
			}
			face := embeddedFace{block: block, shade: def.shade}
			for i, corner := range def.corners {
				face.corners[i] = [3]float64{float64(block.X) + corner[0], float64(block.Y) + corner[1], float64(block.Z) + corner[2]}
			}
			v.faces = append(v.faces, face)
		}
	}
	for axis := 0; axis < 3; axis++ {
		v.center[axis] = (float64(minB[axis]) + float64(maxB[axis]) + 1) / 2
		axisSpan := float64(maxB[axis] - minB[axis] + 1)
		if axisSpan > v.span {
			v.span = axisSpan
		}
	}
	if v.span < 1 {
		v.span = 1
	}
}

func (v *EmbeddedViewer) CreateRenderer() fyne.WidgetRenderer {
	raster := canvas.NewRaster(func(width, height int) image.Image { return v.render(width, height) })
	return &embeddedViewerRenderer{viewer: v, raster: raster, objects: []fyne.CanvasObject{raster}}
}

func (v *EmbeddedViewer) Fit() {
	v.mu.Lock()
	v.yaw, v.pitch, v.zoom, v.panX, v.panY = -.72, -.55, 1, 0, 0
	v.mu.Unlock()
	canvas.Refresh(v)
}

func (v *EmbeddedViewer) Tapped(event *fyne.PointEvent) {
	v.mu.Lock()
	var selected *viewer.Block
	for i := len(v.hits) - 1; i >= 0; i-- {
		if pointInQuad(event.Position, v.hits[i].points) {
			block := v.hits[i].block
			selected = &block
			break
		}
	}
	if selected == nil {
		v.mu.Unlock()
		return
	}
	point := [3]int{selected.X, selected.Y, selected.Z}
	var callback func([3]int, [3]int, int)
	var minB, maxB [3]int
	count := 0
	hint := ""
	if v.pointA == nil || v.pointB != nil {
		v.pointA, v.pointB = &point, nil
		hint = fmt.Sprintf("3D point A: %d, %d, %d · click point B", point[0], point[1], point[2])
	} else {
		v.pointB = &point
		minB, maxB = orderedBounds(*v.pointA, *v.pointB)
		for _, block := range v.blocks {
			if block.X >= minB[0] && block.X <= maxB[0] && block.Y >= minB[1] && block.Y <= maxB[1] && block.Z >= minB[2] && block.Z <= maxB[2] {
				count++
			}
		}
		callback = v.OnBoxSelected
		hint = fmt.Sprintf("%s blocks selected · click again to start a new box", formatCount(count))
	}
	hintCallback := v.OnHint
	v.mu.Unlock()
	canvas.Refresh(v)
	if hintCallback != nil {
		hintCallback(hint)
	}
	if callback != nil {
		callback(minB, maxB, count)
	}
}

func (v *EmbeddedViewer) TappedSecondary(*fyne.PointEvent) {
	v.mu.Lock()
	v.pointA, v.pointB = nil, nil
	v.mu.Unlock()
	canvas.Refresh(v)
	if v.OnHint != nil {
		v.OnHint("3D selection cleared")
	}
}

func (v *EmbeddedViewer) Dragged(event *fyne.DragEvent) {
	v.mu.Lock()
	v.yaw += float64(event.Dragged.DX) * .008
	v.pitch += float64(event.Dragged.DY) * .008
	if v.pitch < -1.45 {
		v.pitch = -1.45
	}
	if v.pitch > 1.45 {
		v.pitch = 1.45
	}
	v.mu.Unlock()
	canvas.Refresh(v)
}

func (v *EmbeddedViewer) DragEnd() {}

func (v *EmbeddedViewer) Scrolled(event *fyne.ScrollEvent) {
	v.mu.Lock()
	v.zoom *= math.Pow(1.12, float64(event.Scrolled.DY))
	if v.zoom < .2 {
		v.zoom = .2
	}
	if v.zoom > 12 {
		v.zoom = 12
	}
	v.mu.Unlock()
	canvas.Refresh(v)
}

func (v *EmbeddedViewer) render(width, height int) image.Image {
	if width < 2 {
		width = 2
	}
	if height < 2 {
		height = 2
	}
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	draw.Draw(img, img.Bounds(), image.NewUniform(color.NRGBA{R: 12, G: 16, B: 20, A: 255}), image.Point{}, draw.Src)

	v.mu.Lock()
	defer v.mu.Unlock()
	scale := math.Min(float64(width), float64(height)) * .72 / v.span * v.zoom
	cosY, sinY := math.Cos(v.yaw), math.Sin(v.yaw)
	cosP, sinP := math.Cos(v.pitch), math.Sin(v.pitch)
	project := func(point [3]float64) (fyne.Position, float64) {
		x := point[0] - v.center[0]
		y := point[1] - v.center[1]
		z := point[2] - v.center[2]
		rx := x*cosY - z*sinY
		rz := x*sinY + z*cosY
		ry := y*cosP - rz*sinP
		depth := y*sinP + rz*cosP
		return fyne.NewPos(float32(float64(width)/2+v.panX+rx*scale), float32(float64(height)/2+v.panY-ry*scale)), depth
	}

	projected := make([]projectedFace, 0, len(v.faces))
	shades := make([]float64, 0, len(v.faces))
	for _, face := range v.faces {
		entry := projectedFace{block: face.block}
		for i, corner := range face.corners {
			entry.points[i], entry.depth = project(corner)
		}
		projected = append(projected, entry)
		shades = append(shades, face.shade)
	}
	order := make([]int, len(projected))
	for i := range order {
		order[i] = i
	}
	sort.Slice(order, func(i, j int) bool { return projected[order[i]].depth < projected[order[j]].depth })
	hits := make([]projectedFace, 0, len(projected))
	for _, index := range order {
		face := projected[index]
		blockColor := shadedColor(face.block, shades[index])
		if v.blockSelected(face.block) {
			blockColor = color.RGBA{R: 45, G: 210, B: 105, A: 255}
		}
		fillQuad(img, face.points, blockColor)
		drawQuadOutline(img, face.points, color.RGBA{R: 5, G: 8, B: 12, A: 255})
		hits = append(hits, face)
	}
	v.hits = hits
	return img
}

func (v *EmbeddedViewer) blockSelected(block viewer.Block) bool {
	if v.pointA == nil {
		return false
	}
	if v.pointB == nil {
		return block.X == v.pointA[0] && block.Y == v.pointA[1] && block.Z == v.pointA[2]
	}
	minB, maxB := orderedBounds(*v.pointA, *v.pointB)
	return block.X >= minB[0] && block.X <= maxB[0] && block.Y >= minB[1] && block.Y <= maxB[1] && block.Z >= minB[2] && block.Z <= maxB[2]
}

func orderedBounds(a, b [3]int) ([3]int, [3]int) {
	minB, maxB := a, b
	for i := 0; i < 3; i++ {
		if minB[i] > maxB[i] {
			minB[i], maxB[i] = maxB[i], minB[i]
		}
	}
	return minB, maxB
}

func shadedColor(block viewer.Block, shade float64) color.RGBA {
	clamp := func(value float64) uint8 {
		if value < 0 {
			return 0
		}
		if value > 255 {
			return 255
		}
		return uint8(value)
	}
	return color.RGBA{R: clamp(float64(block.R) * shade), G: clamp(float64(block.G) * shade), B: clamp(float64(block.B) * shade), A: 255}
}

func pointInQuad(point fyne.Position, quad [4]fyne.Position) bool {
	return pointInTriangle(point, quad[0], quad[1], quad[2]) || pointInTriangle(point, quad[0], quad[2], quad[3])
}

func pointInTriangle(p, a, b, c fyne.Position) bool {
	sign := func(p1, p2, p3 fyne.Position) float32 { return (p1.X-p3.X)*(p2.Y-p3.Y) - (p2.X-p3.X)*(p1.Y-p3.Y) }
	d1, d2, d3 := sign(p, a, b), sign(p, b, c), sign(p, c, a)
	hasNeg, hasPos := d1 < 0 || d2 < 0 || d3 < 0, d1 > 0 || d2 > 0 || d3 > 0
	return !(hasNeg && hasPos)
}

func fillQuad(img *image.RGBA, points [4]fyne.Position, fill color.RGBA) {
	fillTriangle(img, points[0], points[1], points[2], fill)
	fillTriangle(img, points[0], points[2], points[3], fill)
}

func fillTriangle(img *image.RGBA, a, b, c fyne.Position, fill color.RGBA) {
	minX := embeddedMaxInt(0, int(math.Floor(float64(minFloat(a.X, minFloat(b.X, c.X))))))
	maxX := embeddedMinInt(img.Bounds().Max.X-1, int(math.Ceil(float64(maxFloat(a.X, maxFloat(b.X, c.X))))))
	minY := embeddedMaxInt(0, int(math.Floor(float64(minFloat(a.Y, minFloat(b.Y, c.Y))))))
	maxY := embeddedMinInt(img.Bounds().Max.Y-1, int(math.Ceil(float64(maxFloat(a.Y, maxFloat(b.Y, c.Y))))))
	for y := minY; y <= maxY; y++ {
		for x := minX; x <= maxX; x++ {
			if pointInTriangle(fyne.NewPos(float32(x)+.5, float32(y)+.5), a, b, c) {
				img.SetRGBA(x, y, fill)
			}
		}
	}
}

func drawQuadOutline(img *image.RGBA, points [4]fyne.Position, line color.RGBA) {
	for i := 0; i < 4; i++ {
		drawLine(img, points[i], points[(i+1)%4], line)
	}
}

func drawLine(img *image.RGBA, a, b fyne.Position, line color.RGBA) {
	x0, y0, x1, y1 := int(a.X), int(a.Y), int(b.X), int(b.Y)
	dx, sx := absInt(x1-x0), 1
	if x0 > x1 {
		sx = -1
	}
	dy, sy := -absInt(y1-y0), 1
	if y0 > y1 {
		sy = -1
	}
	err := dx + dy
	for {
		if image.Pt(x0, y0).In(img.Bounds()) {
			img.SetRGBA(x0, y0, line)
		}
		if x0 == x1 && y0 == y1 {
			break
		}
		e2 := 2 * err
		if e2 >= dy {
			err += dy
			x0 += sx
		}
		if e2 <= dx {
			err += dx
			y0 += sy
		}
	}
}

func minFloat(a, b float32) float32 {
	if a < b {
		return a
	}
	return b
}
func maxFloat(a, b float32) float32 {
	if a > b {
		return a
	}
	return b
}
func embeddedMinInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
func embeddedMaxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
func absInt(value int) int {
	if value < 0 {
		return -value
	}
	return value
}

type embeddedViewerRenderer struct {
	viewer  *EmbeddedViewer
	raster  *canvas.Raster
	objects []fyne.CanvasObject
}

func (r *embeddedViewerRenderer) Layout(size fyne.Size)        { r.raster.Resize(size) }
func (r *embeddedViewerRenderer) MinSize() fyne.Size           { return fyne.NewSize(320, 240) }
func (r *embeddedViewerRenderer) Refresh()                     { r.raster.Refresh() }
func (r *embeddedViewerRenderer) Objects() []fyne.CanvasObject { return r.objects }
func (r *embeddedViewerRenderer) Destroy()                     {}

var _ fyne.Tappable = (*EmbeddedViewer)(nil)
var _ fyne.SecondaryTappable = (*EmbeddedViewer)(nil)
var _ fyne.Draggable = (*EmbeddedViewer)(nil)
var _ fyne.Scrollable = (*EmbeddedViewer)(nil)
