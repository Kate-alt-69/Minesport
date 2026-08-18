package viewer

// floodFillDirs are the 6 face-adjacent neighbor offsets.
var floodFillDirs = [6][3]int{
	{1, 0, 0}, {-1, 0, 0},
	{0, 1, 0}, {0, -1, 0},
	{0, 0, 1}, {0, 0, -1},
}

// FloodFillJoined selects blocks connected (face-adjacent, 6-directionally)
// to the starting block, stopping once `power` blocks have been collected.
// This is a "grab this structure without leaking into the surrounding
// terrain" tool, not an unlimited connected-component fill — power is the
// leash length, not optional, since real terrain is almost always one
// giant connected blob and an uncapped fill would swallow the whole world.
//
// Never selects air: index only contains solid blocks in the first place
// (see BuildIndex / the engine's listBlocks handler, which drops air before
// writing), so any position not in the index is already known to be either
// air or outside the loaded region, and the fill simply doesn't cross it —
// no special-casing needed. Any block type can join the fill (grass next to
// stone next to wood, etc.) — this deliberately isn't a same-block-only
// fill like the exporter's own grouping logic.
func FloodFillJoined(index map[int64]Block, start [3]int, power int) []Block {
	if power < 1 {
		power = 1
	}

	startKey := PositionKey(start[0], start[1], start[2])
	startBlock, ok := index[startKey]
	if !ok {
		return nil
	}

	visited := map[int64]bool{startKey: true}
	queue := [][3]int{start}
	result := []Block{startBlock}

	for len(queue) > 0 && len(result) < power {
		cur := queue[0]
		queue = queue[1:]

		for _, d := range floodFillDirs {
			if len(result) >= power {
				break
			}

			npos := [3]int{cur[0] + d[0], cur[1] + d[1], cur[2] + d[2]}
			key := PositionKey(npos[0], npos[1], npos[2])
			if visited[key] {
				continue
			}
			visited[key] = true

			b, ok := index[key]
			if !ok {
				continue // air, or outside the loaded region — don't cross it
			}

			result = append(result, b)
			queue = append(queue, npos)
		}
	}

	return result
}

// BoundingBox returns the inclusive min/max coordinates spanning a set of blocks.
func BoundingBox(blocks []Block) (min, max [3]int) {
	if len(blocks) == 0 {
		return
	}
	min = [3]int{blocks[0].X, blocks[0].Y, blocks[0].Z}
	max = min
	for _, b := range blocks[1:] {
		if b.X < min[0] { min[0] = b.X }
		if b.Y < min[1] { min[1] = b.Y }
		if b.Z < min[2] { min[2] = b.Z }
		if b.X > max[0] { max[0] = b.X }
		if b.Y > max[1] { max[1] = b.Y }
		if b.Z > max[2] { max[2] = b.Z }
	}
	return
}
