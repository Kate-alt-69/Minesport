package viewer

import "math"

// Raycast walks a ray through the voxel grid (Amanatides & Woo fast voxel
// traversal) and returns the first occupied block it hits, if any, within
// maxDistance. index only contains solid blocks (see BuildIndex), so a
// missed lookup at any step is correctly treated as "empty, keep going."
func Raycast(index map[int64]Block, origin, dir Vec3, maxDistance float32) (hit Block, hitPos [3]int, ok bool) {
	dir = dir.Normalize()

	x := int(math.Floor(float64(origin.X)))
	y := int(math.Floor(float64(origin.Y)))
	z := int(math.Floor(float64(origin.Z)))

	stepX, tDeltaX, tMaxX := ddaAxis(origin.X, dir.X)
	stepY, tDeltaY, tMaxY := ddaAxis(origin.Y, dir.Y)
	stepZ, tDeltaZ, tMaxZ := ddaAxis(origin.Z, dir.Z)

	t := float32(0)
	for t < maxDistance {
		if b, exists := index[PositionKey(x, y, z)]; exists {
			return b, [3]int{x, y, z}, true
		}

		switch {
		case tMaxX < tMaxY && tMaxX < tMaxZ:
			x += stepX
			t = tMaxX
			tMaxX += tDeltaX
		case tMaxY < tMaxZ:
			y += stepY
			t = tMaxY
			tMaxY += tDeltaY
		default:
			z += stepZ
			t = tMaxZ
			tMaxZ += tDeltaZ
		}
	}

	return Block{}, [3]int{}, false
}

// ddaAxis computes the DDA step direction, the t-distance to cross one
// full voxel along this axis, and the t-distance to the FIRST voxel
// boundary crossing from the ray's actual starting position.
func ddaAxis(origin, dir float32) (step int, tDelta, tMax float32) {
	switch {
	case dir > 0:
		step = 1
		tDelta = 1 / dir
		boundary := float32(math.Floor(float64(origin))) + 1
		tMax = (boundary - origin) * tDelta
	case dir < 0:
		step = -1
		tDelta = 1 / -dir
		boundary := float32(math.Floor(float64(origin)))
		tMax = (origin - boundary) * tDelta
	default:
		step = 0
		tDelta = float32(math.Inf(1))
		tMax = float32(math.Inf(1))
	}
	return
}
