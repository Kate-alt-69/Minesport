package viewer

import (
	"fmt"
	"strings"

	"github.com/go-gl/gl/v2.1/gl"
)

func compileShader(source string, shaderType uint32) (uint32, error) {
	shader := gl.CreateShader(shaderType)

	csources, free := gl.Strs(source + "\x00")
	gl.ShaderSource(shader, 1, csources, nil)
	free()
	gl.CompileShader(shader)

	var status int32
	gl.GetShaderiv(shader, gl.COMPILE_STATUS, &status)
	if status == gl.FALSE {
		var logLength int32
		gl.GetShaderiv(shader, gl.INFO_LOG_LENGTH, &logLength)
		log := strings.Repeat("\x00", int(logLength+1))
		gl.GetShaderInfoLog(shader, logLength, nil, gl.Str(log))
		gl.DeleteShader(shader)
		return 0, fmt.Errorf("shader compile failed: %s", log)
	}
	return shader, nil
}

func newProgram(vertexSrc, fragmentSrc string) (uint32, error) {
	vs, err := compileShader(vertexSrc, gl.VERTEX_SHADER)
	if err != nil {
		return 0, fmt.Errorf("vertex: %w", err)
	}
	fs, err := compileShader(fragmentSrc, gl.FRAGMENT_SHADER)
	if err != nil {
		gl.DeleteShader(vs)
		return 0, fmt.Errorf("fragment: %w", err)
	}

	program := gl.CreateProgram()
	gl.AttachShader(program, vs)
	gl.AttachShader(program, fs)
	gl.LinkProgram(program)

	var status int32
	gl.GetProgramiv(program, gl.LINK_STATUS, &status)
	if status == gl.FALSE {
		var logLength int32
		gl.GetProgramiv(program, gl.INFO_LOG_LENGTH, &logLength)
		log := strings.Repeat("\x00", int(logLength+1))
		gl.GetProgramInfoLog(program, logLength, nil, gl.Str(log))
		return 0, fmt.Errorf("program link failed: %s", log)
	}

	gl.DeleteShader(vs)
	gl.DeleteShader(fs)
	return program, nil
}

// Phase 1 renders solid-colored voxels (the same palette the 2D heightmap
// uses), not per-model textures yet — see the note in buildMesh/window.go
// about why that's a deliberate, separately-scoped next phase rather than
// an oversight here.
const vertexShaderSource = `#version 120
attribute vec3 aPos;
attribute vec3 aColor;
attribute vec3 aNormal;

varying vec3 vColor;
varying vec3 vNormal;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProjection;

void main() {
    gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
    vColor = aColor;
    vNormal = aNormal;
}
`

const fragmentShaderSource = `#version 120
varying vec3 vColor;
varying vec3 vNormal;

void main() {
    // Simple directional shading so faces facing different directions read
    // as distinct — same idea as Minecraft's own per-face brightness cue
    // (top brightest, sides medium, bottom darkest), just with a fixed
    // light instead of the real sky.
    float brightness = 0.65 + 0.35 * max(vNormal.y, 0.0);
    if (vNormal.y < -0.5) {
        brightness = 0.5;
    }
    gl_FragColor = vec4(vColor * brightness, 1.0);
}
`

// Highlight shader — flat, unlit, used for the selection-preview overlay
// (picked block outline, flood-fill highlight, area-selection box) so it
// reads clearly regardless of the underlying voxel shading.
const highlightFragmentShaderSource = `#version 120
uniform vec3 uHighlightColor;
void main() {
    gl_FragColor = vec4(uHighlightColor, 0.6);
}
`
