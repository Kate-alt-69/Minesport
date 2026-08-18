package viewer

func OrbitPan(c *Camera,dx,dy float32,pan bool){if pan{forward:=c.Forward();right:=forward.Cross(Vec3{Y:1}).Normalize();up:=right.Cross(forward).Normalize();c.Position=c.Position.Add(right.Scale(-dx*.08)).Add(up.Scale(dy*.08));return};c.Look(dx,dy)}
func Dolly(c *Camera,wheel float32){if wheel==0{return};c.Position=c.Position.Add(c.Forward().Scale(wheel*.9))}
