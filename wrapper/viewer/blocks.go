package viewer

import (
	"encoding/json"
	"os"
)

// Block is one solid voxel loaded for the 3D preview — position, the raw
// block ID (kept around for hover/selection info, not used for rendering
// yet), and a representative color (same palette the 2D heightmap uses,
// computed engine-side so both views agree visually).
type Block struct {
	X, Y, Z int
	ID      string
	R, G, B uint8
}

type rawBlock struct {
	X  int    `json:"x"`
	Y  int    `json:"y"`
	Z  int    `json:"z"`
	ID string `json:"id"`
	R  int    `json:"r"`
	G  int    `json:"g"`
	B  int    `json:"b"`
}

// LoadBlocks reads the JSON file written by the engine's "listBlocks"
// command (see IpcMode.handleListBlocks / ipc.Engine.ListBlocks).
func LoadBlocks(path string) ([]Block, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	var raw []rawBlock
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}

	blocks := make([]Block, len(raw))
	for i, r := range raw {
		blocks[i] = Block{
			X: r.X, Y: r.Y, Z: r.Z,
			ID: r.ID,
			R:  uint8(clamp255(r.R)), G: uint8(clamp255(r.G)), B: uint8(clamp255(r.B)),
		}
	}
	return blocks, nil
}

func clamp255(v int) int {
	if v < 0 {
		return 0
	}
	if v > 255 {
		return 255
	}
	return v
}

// PositionKey packs a block's integer coordinates into a single int64,
// used for fast neighbor/occupancy lookups (face culling, raycasting).
// Same offset-and-shift scheme the Java side uses for its own spatial
// indices — not load-bearing to match exactly here since this key never
// crosses the IPC boundary, but keeping it consistent avoids two different
// "how do we pack a block position" conventions in the same codebase.
func PositionKey(x, y, z int) int64 {
	return (int64(x+1048576) << 42) | (int64(y+1048576) << 21) | int64(z+1048576)
}

// BuildIndex creates a position → Block lookup for occupancy queries.
func BuildIndex(blocks []Block) map[int64]Block {
	idx := make(map[int64]Block, len(blocks))
	for _, b := range blocks {
		idx[PositionKey(b.X, b.Y, b.Z)] = b
	}
	return idx
}
