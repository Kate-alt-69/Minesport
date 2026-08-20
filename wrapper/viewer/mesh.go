package viewer

import (
	"fmt"
	"image"
	"image/color"
	"image/png"
	"math"
	"os"
	"strings"

	"github.com/go-gl/gl/v2.1/gl"
)

// cubeFace describes one face of a unit cube: its 4 corner offsets (in
// winding order) and its outward normal.
type cubeFace struct {
	corners    [4][3]float32
	normal     [3]float32
	dx, dy, dz int // neighbor offset this face is adjacent to
}

var cubeFaces = []cubeFace{
	{ // +X east
		corners: [4][3]float32{{1, 0, 0}, {1, 0, 1}, {1, 1, 1}, {1, 1, 0}},
		normal:  [3]float32{1, 0, 0}, dx: 1,
	},
	{ // -X west
		corners: [4][3]float32{{0, 0, 1}, {0, 0, 0}, {0, 1, 0}, {0, 1, 1}},
		normal:  [3]float32{-1, 0, 0}, dx: -1,
	},
	{ // +Y up
		corners: [4][3]float32{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},
		normal:  [3]float32{0, 1, 0}, dy: 1,
	},
	{ // -Y down
		corners: [4][3]float32{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}},
		normal:  [3]float32{0, -1, 0}, dy: -1,
	},
	{ // +Z south
		corners: [4][3]float32{{1, 0, 1}, {0, 0, 1}, {0, 1, 1}, {1, 1, 1}},
		normal:  [3]float32{0, 0, 1}, dz: 1,
	},
	{ // -Z north
		corners: [4][3]float32{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}},
		normal:  [3]float32{0, 0, -1}, dz: -1,
	},
}

// Mesh is one uploaded GPU buffer of voxel cube geometry.
type Mesh struct {
	vbo, ibo   uint32
	texture    uint32
	indexCount int32
}

// BuildMesh generates culled cube geometry for a set of blocks and uploads
// it to the GPU. The fragment shader turns the representative block palette
// into a crisp 16x16 pixel surface so the live preview reads like textured
// Minecraft blocks instead of flat plastic cubes. A face is only emitted if there's no solid neighbor block
// covering it — the same idea as the exporter's own hidden-face culling,
// much simpler here since every voxel is a plain unit cube (no per-model
// shapes yet — see the Phase 1 note in shader.go).
func BuildMesh(blocks []Block, index map[int64]Block) *Mesh {
	texture, tiles := buildTextureAtlas(blocks)
	// Interleaved: position(3) + color(3) + normal(3) + atlas UV(2).
	vertices := make([]float32, 0, len(blocks)*11*4)
	indices := make([]uint32, 0, len(blocks)*6)

	for _, b := range blocks {
		for _, f := range cubeFaces {
			nx, ny, nz := b.X+f.dx, b.Y+f.dy, b.Z+f.dz
			if _, occupied := index[PositionKey(nx, ny, nz)]; occupied {
				continue // fully hidden — neighbor covers this face
			}

			base := uint32(len(vertices) / 11)
			r := float32(b.R) / 255
			g := float32(b.G) / 255
			bl := float32(b.B) / 255
			uv := tiles[blockTextureKey(b, f)]
			faceUV := [4][2]float32{{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}}

			for i, c := range f.corners {
				vertices = append(vertices,
					float32(b.X)+c[0], float32(b.Y)+c[1], float32(b.Z)+c[2],
					r, g, bl,
					f.normal[0], f.normal[1], f.normal[2],
					faceUV[i][0], faceUV[i][1],
				)
			}

			indices = append(indices,
				base, base+1, base+2,
				base, base+2, base+3,
			)
		}
	}

	m := &Mesh{indexCount: int32(len(indices)), texture: texture}
	if len(indices) == 0 {
		return m
	}

	gl.GenBuffers(1, &m.vbo)
	gl.BindBuffer(gl.ARRAY_BUFFER, m.vbo)
	gl.BufferData(gl.ARRAY_BUFFER, len(vertices)*4, gl.Ptr(vertices), gl.STATIC_DRAW)

	gl.GenBuffers(1, &m.ibo)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, m.ibo)
	gl.BufferData(gl.ELEMENT_ARRAY_BUFFER, len(indices)*4, gl.Ptr(indices), gl.STATIC_DRAW)

	gl.BindBuffer(gl.ARRAY_BUFFER, 0)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, 0)

	return m
}

// Draw binds this mesh's buffers, wires up the given shader's attribute
// locations, and issues the draw call. No VAO is used — GL 2.1 core makes
// no guarantee they're available, so this re-binds plain VBOs/attrib
// pointers every draw. Fine at this scale (one draw call per mesh, not
// per-block).
func (m *Mesh) Draw(posLoc, colorLoc, normalLoc, uvLoc int32) {
	if m.indexCount == 0 {
		return
	}

	const stride = 11 * 4

	gl.BindBuffer(gl.ARRAY_BUFFER, m.vbo)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, m.ibo)

	if posLoc >= 0 {
		gl.EnableVertexAttribArray(uint32(posLoc))
		gl.VertexAttribPointer(uint32(posLoc), 3, gl.FLOAT, false, stride, gl.PtrOffset(0))
	}
	if colorLoc >= 0 {
		gl.EnableVertexAttribArray(uint32(colorLoc))
		gl.VertexAttribPointer(uint32(colorLoc), 3, gl.FLOAT, false, stride, gl.PtrOffset(3*4))
	}
	if normalLoc >= 0 {
		gl.EnableVertexAttribArray(uint32(normalLoc))
		gl.VertexAttribPointer(uint32(normalLoc), 3, gl.FLOAT, false, stride, gl.PtrOffset(6*4))
	}
	if uvLoc >= 0 {
		gl.EnableVertexAttribArray(uint32(uvLoc))
		gl.VertexAttribPointer(uint32(uvLoc), 2, gl.FLOAT, false, stride, gl.PtrOffset(9*4))
	}
	if m.texture != 0 {
		gl.ActiveTexture(gl.TEXTURE0)
		gl.BindTexture(gl.TEXTURE_2D, m.texture)
	}

	gl.DrawElements(gl.TRIANGLES, m.indexCount, gl.UNSIGNED_INT, gl.PtrOffset(0))

	if posLoc >= 0 {
		gl.DisableVertexAttribArray(uint32(posLoc))
	}
	if colorLoc >= 0 {
		gl.DisableVertexAttribArray(uint32(colorLoc))
	}
	if normalLoc >= 0 {
		gl.DisableVertexAttribArray(uint32(normalLoc))
	}
	if uvLoc >= 0 {
		gl.DisableVertexAttribArray(uint32(uvLoc))
	}

	gl.BindBuffer(gl.ARRAY_BUFFER, 0)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, 0)
}

// buildMeshInflated builds an uncullled, slightly-inflated cube mesh —
// used for the selection highlight overlay, where every face of every
// highlighted block should render regardless of neighbors (unlike the
// main voxel mesh, which culls faces hidden by solid neighbors).
func buildMeshInflated(blocks []Block, inflate float32) *Mesh {
	vertices := make([]float32, 0, len(blocks)*11*4*6)
	indices := make([]uint32, 0, len(blocks)*6*6)

	for _, b := range blocks {
		for _, f := range cubeFaces {
			base := uint32(len(vertices) / 11)

			for _, c := range f.corners {
				// Push each corner outward along the face normal so the
				// highlight sits just outside the real block surface
				// instead of z-fighting with it.
				x := float32(b.X) + c[0] + f.normal[0]*inflate
				y := float32(b.Y) + c[1] + f.normal[1]*inflate
				z := float32(b.Z) + c[2] + f.normal[2]*inflate

				vertices = append(vertices,
					x, y, z,
					1, 1, 0, // highlight tint — the shader's uHighlightColor governs actual color
					f.normal[0], f.normal[1], f.normal[2],
					0, 0,
				)
			}

			indices = append(indices,
				base, base+1, base+2,
				base, base+2, base+3,
			)
		}
	}

	m := &Mesh{indexCount: int32(len(indices))}
	if len(indices) == 0 {
		return m
	}

	gl.GenBuffers(1, &m.vbo)
	gl.BindBuffer(gl.ARRAY_BUFFER, m.vbo)
	gl.BufferData(gl.ARRAY_BUFFER, len(vertices)*4, gl.Ptr(vertices), gl.STATIC_DRAW)

	gl.GenBuffers(1, &m.ibo)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, m.ibo)
	gl.BufferData(gl.ELEMENT_ARRAY_BUFFER, len(indices)*4, gl.Ptr(indices), gl.STATIC_DRAW)

	gl.BindBuffer(gl.ARRAY_BUFFER, 0)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, 0)

	return m
}

func (m *Mesh) Destroy() {
	if m.vbo != 0 {
		gl.DeleteBuffers(1, &m.vbo)
	}
	if m.ibo != 0 {
		gl.DeleteBuffers(1, &m.ibo)
	}
	if m.texture != 0 {
		gl.DeleteTextures(1, &m.texture)
	}
}

const previewTileSize = 16

func blockTextureKey(block Block, face cubeFace) string {
	texture := block.TextureSide
	if face.dy > 0 {
		texture = block.TextureTop
	} else if face.dy < 0 {
		texture = block.TextureBottom
	}
	if texture != "" {
		return texture
	}
	return fmt.Sprintf("fallback:%02x%02x%02x", block.R, block.G, block.B)
}

// buildTextureAtlas packs the actual PNGs resolved by the Java engine into a
// nearest-filtered atlas. Missing/custom-renderer textures receive a stable
// 16x16 palette tile rather than making the whole preview fail.
func buildTextureAtlas(blocks []Block) (uint32, map[string][4]float32) {
	tileColors := make(map[string]color.NRGBA)
	keys := make([]string, 0)
	for _, block := range blocks {
		for _, face := range cubeFaces {
			key := blockTextureKey(block, face)
			if _, exists := tileColors[key]; exists {
				continue
			}
			tileColors[key] = color.NRGBA{R: block.R, G: block.G, B: block.B, A: 255}
			keys = append(keys, key)
		}
	}
	if len(keys) == 0 {
		return 0, map[string][4]float32{}
	}
	columns := int(math.Ceil(math.Sqrt(float64(len(keys)))))
	rows := (len(keys) + columns - 1) / columns
	atlas := image.NewRGBA(image.Rect(0, 0, columns*previewTileSize, rows*previewTileSize))
	uvs := make(map[string][4]float32, len(keys))
	for i, key := range keys {
		x0 := (i % columns) * previewTileSize
		y0 := (i / columns) * previewTileSize
		fillPreviewTile(atlas, x0, y0, key, tileColors[key])
		// Half-pixel inset prevents adjacent atlas tiles bleeding at edges.
		u0 := float32(x0) + 0.5
		v0 := float32(y0) + 0.5
		u1 := float32(x0+previewTileSize) - 0.5
		v1 := float32(y0+previewTileSize) - 0.5
		uvs[key] = [4]float32{u0 / float32(atlas.Bounds().Dx()), v0 / float32(atlas.Bounds().Dy()), u1 / float32(atlas.Bounds().Dx()), v1 / float32(atlas.Bounds().Dy())}
	}
	var texture uint32
	gl.GenTextures(1, &texture)
	gl.BindTexture(gl.TEXTURE_2D, texture)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
	gl.PixelStorei(gl.UNPACK_ALIGNMENT, 1)
	gl.TexImage2D(gl.TEXTURE_2D, 0, gl.RGBA, int32(atlas.Bounds().Dx()), int32(atlas.Bounds().Dy()), 0, gl.RGBA, gl.UNSIGNED_BYTE, gl.Ptr(atlas.Pix))
	return texture, uvs
}

func fillPreviewTile(atlas *image.RGBA, x0, y0 int, key string, fallback color.NRGBA) {
	var source image.Image
	if key != "" && !strings.HasPrefix(key, "fallback:") {
		if file, err := os.Open(key); err == nil {
			source, _ = png.Decode(file)
			_ = file.Close()
		}
	}
	for y := 0; y < previewTileSize; y++ {
		for x := 0; x < previewTileSize; x++ {
			pixel := fallback
			if source != nil {
				bounds := source.Bounds()
				sx := bounds.Min.X + x*bounds.Dx()/previewTileSize
				// Animated Minecraft PNGs stack square frames vertically. Use
				// the first frame for the static panorama preview.
				frameHeight := minInt(bounds.Dy(), bounds.Dx())
				sy := bounds.Min.Y + y*frameHeight/previewTileSize
				pixel = color.NRGBAModel.Convert(source.At(sx, sy)).(color.NRGBA)
			} else if (x+y)%4 == 0 {
				pixel.R = uint8(float32(pixel.R) * 0.88)
				pixel.G = uint8(float32(pixel.G) * 0.88)
				pixel.B = uint8(float32(pixel.B) * 0.88)
			}
			atlas.SetRGBA(x0+x, y0+y, color.RGBA{R: pixel.R, G: pixel.G, B: pixel.B, A: pixel.A})
		}
	}
}
