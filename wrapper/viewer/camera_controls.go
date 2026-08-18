package viewer

// OrbitPan and Dolly keep the existing free-fly camera but expose Blender-like
// viewport gestures for the preview window.
func OrbitPan(c *Camera, dx, dy float32, pan bool) {
	if pan {
		right := Vec3{X: 1, Y: 0, Z: 0}
		forward := c.Forward()
		right = forward.Cross(Vec3{Y: 1}).Normalize()
		up := right.Cross(forward).Normalize()
		c.Position = c.Position.Add(right.Scale(-dx * 0.08)).Add(up.Scale(dy * 0.08))
		return
	}
	c.Look(dx, dy)
}

func Dolly(c *Camera, wheel float32) {
	if wheel == 0 { return }
	step := wheel * 0.9
	c.Position = c.Position.Add(c.Forward().Scale(step))
}
