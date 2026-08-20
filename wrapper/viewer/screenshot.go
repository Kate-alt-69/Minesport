package viewer

import (
	"fmt"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"time"

	"github.com/go-gl/gl/v2.1/gl"
)

func saveScreenshot(width, height int) (string, error) {
	if width < 1 || height < 1 {
		return "", fmt.Errorf("viewport has no drawable size")
	}
	pixels := make([]byte, width*height*4)
	gl.PixelStorei(gl.PACK_ALIGNMENT, 1)
	gl.ReadPixels(0, 0, int32(width), int32(height), gl.RGBA, gl.UNSIGNED_BYTE, gl.Ptr(pixels))

	img := image.NewRGBA(image.Rect(0, 0, width, height))
	rowBytes := width * 4
	for y := 0; y < height; y++ {
		source := (height - 1 - y) * rowBytes
		copy(img.Pix[y*img.Stride:y*img.Stride+rowBytes], pixels[source:source+rowBytes])
	}

	pictures, err := picturesDirectory()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(pictures, "Minesport")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	path := filepath.Join(dir, "Minesport-3D-"+time.Now().Format("20060102-150405")+".png")
	file, err := os.Create(path)
	if err != nil {
		return "", err
	}
	if err := png.Encode(file, img); err != nil {
		_ = file.Close()
		return "", err
	}
	if err := file.Close(); err != nil {
		return "", err
	}
	return path, nil
}
