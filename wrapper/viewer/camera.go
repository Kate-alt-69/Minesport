package viewer

import "math"

// Camera is a free-fly camera controlled exactly like Minecraft creative
// flight: hold right mouse button to look around, WASD to move on the
// horizontal plane relative to where you're facing, Space/Shift to move
// straight up/down regardless of look angle.
type Camera struct {
	Position Vec3
	Yaw      float32 // radians, 0 faces -Z
	Pitch    float32 // radians, clamped to just under ±90°

	MoveSpeed       float32 // blocks/second
	SprintMult      float32 // multiplier while sprinting
	LookSensitivity float32 // radians per pixel of mouse movement
}

func NewCamera(pos Vec3) *Camera {
	return &Camera{
		Position:        pos,
		MoveSpeed:       12,
		SprintMult:      3,
		LookSensitivity: 0.0025,
	}
}

const maxPitch = math.Pi/2 - 0.01

// Look applies mouse movement (in pixels) to yaw/pitch. Only feed this
// mouse delta while the look button (RMB) is actually held — the window
// layer is responsible for that gating, not the camera.
func (c *Camera) Look(dx, dy float32) {
	// Horizontal: increasing yaw rotates the view toward the camera's own
	// right (same convention Move()'s right-vector uses), so moving the
	// mouse right must increase yaw. This was backwards before — inverted
	// left/right look.
	c.Yaw += dx * c.LookSensitivity
	c.Pitch -= dy * c.LookSensitivity
	if c.Pitch > maxPitch {
		c.Pitch = maxPitch
	}
	if c.Pitch < -maxPitch {
		c.Pitch = -maxPitch
	}
}

// Input bundles which movement keys are down this frame.
type Input struct {
	Forward, Back, Left, Right bool // W A S D
	Up, Down                   bool // Space / Shift
	Sprint                     bool // e.g. Ctrl
}

// Move advances the camera position for one frame's worth of input.
// Horizontal movement (WASD) is relative to yaw only, so looking up or
// down doesn't change how fast you climb/descend or drift you into the
// ground — exactly how creative flight behaves in-game. Space/Shift are
// always world-up/world-down, independent of look direction.
func (c *Camera) Move(in Input, dt float32) {
	forward := Vec3{
		X: float32(math.Sin(float64(c.Yaw))),
		Y: 0,
		Z: float32(-math.Cos(float64(c.Yaw))),
	}
	right := forward.Cross(Vec3{Y: 1}).Normalize()

	var move Vec3
	if in.Forward {
		move = move.Add(forward)
	}
	if in.Back {
		move = move.Sub(forward)
	}
	if in.Right {
		move = move.Add(right)
	}
	if in.Left {
		move = move.Sub(right)
	}
	if in.Up {
		move.Y += 1
	}
	if in.Down {
		move.Y -= 1
	}

	if move.Length() > 1e-6 {
		move = move.Normalize()
	}

	speed := c.MoveSpeed
	if in.Sprint {
		speed *= c.SprintMult
	}

	c.Position = c.Position.Add(move.Scale(speed * dt))
}

// AdjustSpeed changes creative-flight speed in 10% steps and keeps it within
// a useful range for both tiny builds and very large worlds.
func (c *Camera) AdjustSpeed(steps float64) {
	if steps == 0 {
		return
	}
	c.MoveSpeed *= float32(math.Pow(1.1, steps))
	if c.MoveSpeed < 1 {
		c.MoveSpeed = 1
	}
	if c.MoveSpeed > 1200 {
		c.MoveSpeed = 1200
	}
}

// Forward returns the camera's full look direction (yaw AND pitch),
// for raycasting/picking — as opposed to Move's horizontal-only forward.
func (c *Camera) Forward() Vec3 {
	return DirFromYawPitch(c.Yaw, c.Pitch)
}

func (c *Camera) ViewMatrix() Mat4 {
	target := c.Position.Add(c.Forward())
	return LookAt(c.Position, target, Vec3{Y: 1})
}
