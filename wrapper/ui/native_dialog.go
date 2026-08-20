package ui

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"github.com/ncruces/zenity"

	"github.com/kastrick/minesport/blendertranslator"
)

// nativeOpenFile uses a platform-native file dialog. ncruces/zenity provides
// native Windows and macOS dialogs and a Zenity-compatible Unix backend.
func nativeOpenFile(title, filter string) string {
	var filters zenity.FileFilters
	parts := strings.Split(filter, "|")
	for i := 0; i+1 < len(parts); i += 2 {
		patterns := strings.FieldsFunc(parts[i+1], func(r rune) bool { return r == ';' })
		filters = append(filters, zenity.FileFilter{Name: parts[i], Patterns: patterns})
	}

	path, err := zenity.SelectFile(
		zenity.Title(title),
		zenity.FileFilters(filters),
	)
	if err != nil {
		return ""
	}
	return path
}

func nativeOpenFolder(title string) string {
	path, err := zenity.SelectFile(
		zenity.Title(title),
		zenity.Directory(),
	)
	if err != nil {
		return ""
	}
	return path
}

func openPath(path string) error {
	if path == "" {
		return fmt.Errorf("empty path")
	}

	switch runtime.GOOS {
	case "windows":
		return exec.Command("explorer.exe", path).Start()
	case "darwin":
		return exec.Command("open", path).Start()
	default:
		return exec.Command("xdg-open", path).Start()
	}
}

// revealPath opens the containing folder and selects the exported file where
// the platform supports it. This is deliberately different from openPath,
// which launches the file's default application.
func revealPath(path string) error {
	if path == "" {
		return fmt.Errorf("empty path")
	}

	absolute, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	switch runtime.GOOS {
	case "windows":
		return exec.Command("explorer.exe", "/select,", absolute).Start()
	case "darwin":
		return exec.Command("open", "-R", absolute).Start()
	default:
		return exec.Command("xdg-open", filepath.Dir(absolute)).Start()
	}
}

func appAvailable(appName string) bool {
	if runtime.GOOS == "darwin" && appName != "Blender" {
		switch appName {
		case "Blender", "Blockbench", "MeshLab":
			return true
		}
	}
	_, err := resolveApp(appName)
	return err == nil
}

func openWithApp(appName, filePath string) error {
	if runtime.GOOS == "darwin" && appName != "Blender" {
		return exec.Command("open", "-a", appName, filePath).Start()
	}

	exe, err := resolveApp(appName)
	if err != nil {
		return err
	}
	if appName == "Blender" {
		if _, err := blendertranslator.Install(); err != nil {
			return fmt.Errorf("install Blender translator before import: %w", err)
		}
		pathLiteral := strconv.Quote(filepath.Clean(filePath))
		extension := strings.ToLower(filepath.Ext(filePath))
		operator := "bpy.ops.import_scene.gltf(filepath=" + pathLiteral + ")"
		if extension == ".obj" {
			operator = "bpy.ops.import_scene.minesport_obj(filepath=" + pathLiteral + ")"
		}
		// Enable the bundled one-shot translator before importing. This makes
		// the completion dialog's Blender action reliable even when the add-on
		// files were installed but Blender had not enabled them yet.
		expression := "import addon_utils,bpy; addon_utils.enable('minesport_translator', default_set=True, persistent=True); " + operator
		return exec.Command(exe, "--python-expr", expression).Start()
	}
	return exec.Command(exe, filePath).Start()
}

func resolveApp(appName string) (string, error) {
	candidates := map[string][]string{
		"Blender":    {"blender", "blender.exe"},
		"Blockbench": {"blockbench", "Blockbench.exe"},
		"MeshLab":    {"meshlab", "meshlab.exe"},
	}

	for _, name := range candidates[appName] {
		if path, err := exec.LookPath(name); err == nil {
			return path, nil
		}
	}

	if runtime.GOOS == "windows" {
		roots := []string{}
		if pf := os.Getenv("ProgramFiles"); pf != "" {
			roots = append(roots, pf)
		}
		if pf := os.Getenv("ProgramFiles(x86)"); pf != "" {
			roots = append(roots, pf)
		}
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			roots = append(roots, local)
		}

		switch appName {
		case "Blender":
			for _, root := range roots {
				for _, pattern := range []string{
					filepath.Join(root, "Blender Foundation", "Blender *", "blender.exe"),
					filepath.Join(root, "Blender Foundation", "Blender *", "blender-launcher.exe"),
				} {
					if matches, _ := filepath.Glob(pattern); len(matches) > 0 {
						return matches[len(matches)-1], nil
					}
				}
			}
		case "Blockbench":
			for _, root := range roots {
				for _, pattern := range []string{
					filepath.Join(root, "Programs", "Blockbench", "Blockbench.exe"),
					filepath.Join(root, "Blockbench", "Blockbench.exe"),
				} {
					if matches, _ := filepath.Glob(pattern); len(matches) > 0 {
						return matches[0], nil
					}
				}
			}
		case "MeshLab":
			for _, root := range roots {
				pattern := filepath.Join(root, "VCG", "MeshLab", "meshlab.exe")
				if matches, _ := filepath.Glob(pattern); len(matches) > 0 {
					return matches[0], nil
				}
			}
		}
	}
	if runtime.GOOS == "darwin" && appName == "Blender" {
		home, _ := os.UserHomeDir()
		for _, candidate := range []string{
			"/Applications/Blender.app/Contents/MacOS/Blender",
			filepath.Join(home, "Applications", "Blender.app", "Contents", "MacOS", "Blender"),
		} {
			if _, err := os.Stat(candidate); err == nil {
				return candidate, nil
			}
		}
	}

	return "", fmt.Errorf("%s is not installed or could not be found", appName)
}
