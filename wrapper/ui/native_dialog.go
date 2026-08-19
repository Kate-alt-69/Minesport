package ui

import (
    "fmt"
    "os"
    "os/exec"
    "path/filepath"
    "runtime"
    "strings"

    "github.com/ncruces/zenity"
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

func appAvailable(appName string) bool {
    if runtime.GOOS == "darwin" {
        switch appName {
        case "Blender", "Blockbench", "MeshLab":
            return true
        }
    }
    _, err := resolveApp(appName)
    return err == nil
}

func openWithApp(appName, filePath string) error {
    if runtime.GOOS == "darwin" {
        return exec.Command("open", "-a", appName, filePath).Start()
    }

    exe, err := resolveApp(appName)
    if err != nil {
        return err
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

    return "", fmt.Errorf("%s is not installed or could not be found", appName)
}
