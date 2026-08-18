package viewer

import (
	"github.com/go-gl/gl/v2.1/gl"
)

// cubeFace describes one face of a unit cube: its 4 corner offsets (in
// winding order) and its outward normal.
type cubeFace struct {
	corners [4][3]float32
	normal  [3]float32
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
	vbo, ibo  uint32
	indexCount int32
}

// BuildMesh generates culled cube geometry for a set of blocks and uploads
// it to the GPU. A face is only emitted if there's no solid neighbor block
// covering it — the same idea as the exporter's own hidden-face culling,
// much simpler here since every voxel is a plain unit cube (no per-model
// shapes yet — see the Phase 1 note in shader.go).
func BuildMesh(blocks []Block, index map[int64]Block) *Mesh {
	// Interleaved: position(3) + color(3, 0..1) + normal(3) = 9 floats/vertex
	vertices := make([]float32, 0, len(blocks)*9*4)
	indices := make([]uint32, 0, len(blocks)*6)

	for _, b := range blocks {
		for _, f := range cubeFaces {
			nx, ny, nz := b.X+f.dx, b.Y+f.dy, b.Z+f.dz
			if _, occupied := index[PositionKey(nx, ny, nz)]; occupied {
				continue // fully hidden — neighbor covers this face
			}

			base := uint32(len(vertices) / 9)
			r := float32(b.R) / 255
			g := float32(b.G) / 255
			bl := float32(b.B) / 255

			for _, c := range f.corners {
				vertices = append(vertices,
					float32(b.X)+c[0], float32(b.Y)+c[1], float32(b.Z)+c[2],
					r, g, bl,
					f.normal[0], f.normal[1], f.normal[2],
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

// Draw binds this mesh's buffers, wires up the given shader's attribute
// locations, and issues the draw call. No VAO is used — GL 2.1 core makes
// no guarantee they're available, so this re-binds plain VBOs/attrib
// pointers every draw. Fine at this scale (one draw call per mesh, not
// per-block).
func (m *Mesh) Draw(posLoc, colorLoc, normalLoc uint32) {
	if m.indexCount == 0 {
		return
	}

	const stride = 9 * 4 // 9 floats * 4 bytes

	gl.BindBuffer(gl.ARRAY_BUFFER, m.vbo)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, m.ibo)

	gl.EnableVertexAttribArray(posLoc)
	gl.VertexAttribPointer(posLoc, 3, gl.FLOAT, false, stride, gl.PtrOffset(0))

	gl.EnableVertexAttribArray(colorLoc)
	gl.VertexAttribPointer(colorLoc, 3, gl.FLOAT, false, stride, gl.PtrOffset(3*4))

	gl.EnableVertexAttribArray(normalLoc)
	gl.VertexAttribPointer(normalLoc, 3, gl.FLOAT, false, stride, gl.PtrOffset(6*4))

	gl.DrawElements(gl.TRIANGLES, m.indexCount, gl.UNSIGNED_INT, gl.PtrOffset(0))

	gl.DisableVertexAttribArray(posLoc)
	gl.DisableVertexAttribArray(colorLoc)
	gl.DisableVertexAttribArray(normalLoc)

	gl.BindBuffer(gl.ARRAY_BUFFER, 0)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, 0)
}

// buildMeshInflated builds an uncullled, slightly-inflated cube mesh —
// used for the selection highlight overlay, where every face of every
// highlighted block should render regardless of neighbors (unlike the
// main voxel mesh, which culls faces hidden by solid neighbors).
func buildMeshInflated(blocks []Block, inflate float32) *Mesh {
	vertices := make([]float32, 0, len(blocks)*9*4*6)
	indices := make([]uint32, 0, len(blocks)*6*6)

	for _, b := range blocks {
		for _, f := range cubeFaces {
			base := uint32(len(vertices) / 9)

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
}
