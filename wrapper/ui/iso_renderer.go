package ui

import (
	"image"
	"image/color"
	"math"
)

// IsoRenderer converts a flat RGBA top-down heightmap into an isometric
// 3D view. Each pixel in the source map represents one block column.
// We use the pixel brightness as a proxy for height.
type IsoRenderer struct {
	TileW int // isometric tile width  (default 8px)
	TileH int // isometric tile height (default 4px)
	TileD int // tile depth/thickness  (default 4px)
}

func NewIsoRenderer() *IsoRenderer {
	return &IsoRenderer{TileW: 8, TileH: 4, TileD: 4}
}

// Render converts a top-down RGBA map image into an isometric view.
// srcScale is how many source pixels per block (1 for 1:1 maps).
func (r *IsoRenderer) Render(src *image.RGBA, srcW, srcH int) *image.RGBA {
	// Output size
	outW := (srcW + srcH) * r.TileW / 2
	outH := (srcW+srcH)*r.TileH/2 + 64 // extra height for tall columns

	out := image.NewRGBA(image.Rect(0, 0, outW, outH))

	// Fill background
	for y := 0; y < outH; y++ {
		for x := 0; x < outW; x++ {
			out.SetRGBA(x, y, color.RGBA{20, 20, 24, 255})
		}
	}

	// Draw back-to-front (painter's algorithm — iso order)
	for row := 0; row < srcH; row++ {
		for col := 0; col < srcW; col++ {
			px := src.RGBAAt(col, row)
			if px.A == 0 {
				continue
			}

			// Derive height from brightness (0–12 blocks tall)
			brightness := (int(px.R) + int(px.G) + int(px.B)) / 3
			h := 1 + (brightness*12)/255

			// Isometric screen position
			screenX := (col-row)*r.TileW/2 + outW/2
			screenY := (col+row)*r.TileH/2

			// Darken sides for depth illusion
			topC := px
			leftC := darken(px, 0.65)
			rightC := darken(px, 0.45)

			r.drawIsoBlock(out, screenX, screenY, h, topC, leftC, rightC)
		}
	}

	return out
}

// drawIsoBlock draws a single isometric block at screen position.
func (r *IsoRenderer) drawIsoBlock(dst *image.RGBA, sx, sy, h int, top, left, right color.RGBA) {
	tw := r.TileW
	th := r.TileH
	td := h * r.TileD

	// Top face — diamond shape
	for dy := 0; dy < th; dy++ {
		rowWidth := tw - abs(dy-th/2)*2
		startX := sx - rowWidth/2
		y := sy - td - dy
		for dx := 0; dx < rowWidth; dx++ {
			setPixelSafe(dst, startX+dx, y, top)
		}
	}

	// Left face
	for dy := 0; dy < td; dy++ {
		rowWidth := tw / 2
		startX := sx - tw/2
		slope := dy * th / (td * 2)
		y := sy - td + dy + slope
		for dx := 0; dx < rowWidth; dx++ {
			setPixelSafe(dst, startX+dx, y, left)
		}
	}

	// Right face
	for dy := 0; dy < td; dy++ {
		rowWidth := tw / 2
		startX := sx
		slope := dy * th / (td * 2)
		y := sy - td + dy + slope
		for dx := 0; dx < rowWidth; dx++ {
			setPixelSafe(dst, startX+dx, y, right)
		}
	}
}

func setPixelSafe(img *image.RGBA, x, y int, c color.RGBA) {
	b := img.Bounds()
	if x >= b.Min.X && x < b.Max.X && y >= b.Min.Y && y < b.Max.Y {
		img.SetRGBA(x, y, c)
	}
}

func darken(c color.RGBA, factor float64) color.RGBA {
	return color.RGBA{
		R: uint8(math.Round(float64(c.R) * factor)),
		G: uint8(math.Round(float64(c.G) * factor)),
		B: uint8(math.Round(float64(c.B) * factor)),
		A: c.A,
	}
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
