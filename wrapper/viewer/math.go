package viewer

import "math"

// Vec3 is a minimal float32 3-vector. Not a general-purpose math library —
// just the handful of operations the camera and picking code actually need.
type Vec3 struct{ X, Y, Z float32 }

func (a Vec3) Add(b Vec3) Vec3    { return Vec3{a.X + b.X, a.Y + b.Y, a.Z + b.Z} }
func (a Vec3) Sub(b Vec3) Vec3    { return Vec3{a.X - b.X, a.Y - b.Y, a.Z - b.Z} }
func (a Vec3) Scale(s float32) Vec3 { return Vec3{a.X * s, a.Y * s, a.Z * s} }

func (a Vec3) Length() float32 {
	return float32(math.Sqrt(float64(a.X*a.X + a.Y*a.Y + a.Z*a.Z)))
}

func (a Vec3) Normalize() Vec3 {
	l := a.Length()
	if l < 1e-6 {
		return Vec3{}
	}
	return a.Scale(1 / l)
}

func (a Vec3) Cross(b Vec3) Vec3 {
	return Vec3{
		a.Y*b.Z - a.Z*b.Y,
		a.Z*b.X - a.X*b.Z,
		a.X*b.Y - a.Y*b.X,
	}
}

func (a Vec3) Dot(b Vec3) float32 {
	return a.X*b.X + a.Y*b.Y + a.Z*b.Z
}

// Mat4 is a column-major 4x4 matrix, matching OpenGL's expected layout —
// m[col*4+row]. Safe to pass its [16]float32 array straight to
// gl.UniformMatrix4fv.
type Mat4 [16]float32

func Identity() Mat4 {
	return Mat4{
		1, 0, 0, 0,
		0, 1, 0, 0,
		0, 0, 1, 0,
		0, 0, 0, 1,
	}
}

// Mul returns a*b (apply b first, then a — standard column-major convention).
func (a Mat4) Mul(b Mat4) Mat4 {
	var r Mat4
	for col := 0; col < 4; col++ {
		for row := 0; row < 4; row++ {
			var sum float32
			for k := 0; k < 4; k++ {
				sum += a[k*4+row] * b[col*4+k]
			}
			r[col*4+row] = sum
		}
	}
	return r
}

func Translate(t Vec3) Mat4 {
	m := Identity()
	m[12], m[13], m[14] = t.X, t.Y, t.Z
	return m
}

func Scale(s Vec3) Mat4 {
	m := Identity()
	m[0], m[5], m[10] = s.X, s.Y, s.Z
	return m
}

// Perspective builds a standard OpenGL perspective projection matrix.
// fovYRadians is the full vertical field of view, aspect is width/height.
func Perspective(fovYRadians, aspect, near, far float32) Mat4 {
	f := float32(1 / math.Tan(float64(fovYRadians)/2))
	var m Mat4
	m[0] = f / aspect
	m[5] = f
	m[10] = (far + near) / (near - far)
	m[11] = -1
	m[14] = (2 * far * near) / (near - far)
	return m
}

// LookAt builds a standard right-handed view matrix.
func LookAt(eye, center, up Vec3) Mat4 {
	f := center.Sub(eye).Normalize()
	s := f.Cross(up).Normalize()
	u := s.Cross(f)

	return Mat4{
		s.X, u.X, -f.X, 0,
		s.Y, u.Y, -f.Y, 0,
		s.Z, u.Z, -f.Z, 0,
		-s.Dot(eye), -u.Dot(eye), f.Dot(eye), 1,
	}
}

// DirFromYawPitch converts camera yaw/pitch (radians) into a forward-facing
// unit vector. Yaw 0 faces -Z (matches LookAt's default forward), pitch is
// clamped by the caller, not here.
func DirFromYawPitch(yaw, pitch float32) Vec3 {
	return Vec3{
		X: float32(math.Cos(float64(pitch)) * math.Sin(float64(yaw))),
		Y: float32(math.Sin(float64(pitch))),
		Z: float32(-math.Cos(float64(pitch)) * math.Cos(float64(yaw))),
	}.Normalize()
}
