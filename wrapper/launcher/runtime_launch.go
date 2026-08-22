package launcher

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/kastrick/minesport/processutil"
)

// FindInstanceForWorld returns the launcher instance whose Minecraft directory
// contains the selected world. It reuses normal launcher discovery so capture
// cannot accidentally target an unrelated installation with the same version.
func FindInstanceForWorld(worldPath string) (Launcher, Instance, bool) {
	worldPath = filepath.Clean(strings.TrimSpace(worldPath))
	if worldPath == "" || worldPath == "." {
		return Launcher{}, Instance{}, false
	}
	for _, foundLauncher := range DiscoverAll() {
		for _, instance := range DiscoverInstances(foundLauncher) {
			root := filepath.Clean(instance.MinecraftDir)
			if root == "" || root == "." {
				continue
			}
			relative, err := filepath.Rel(root, worldPath)
			if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
				continue
			}
			return foundLauncher, instance, true
		}
	}
	return Launcher{}, Instance{}, false
}

// LaunchInstance starts an existing Prism-family launcher instance. Minesport
// does not synthesize launcher profiles or credentials; it only asks the user's
// installed launcher to start the already-configured instance.
func LaunchInstance(foundLauncher Launcher, instance Instance) error {
	if instance.Loader != LoaderFabric {
		return fmt.Errorf("runtime bridge capture currently requires a Fabric instance")
	}
	if foundLauncher.Type != LauncherFreeSM && foundLauncher.Type != LauncherPrism && foundLauncher.Type != LauncherMultiMC {
		return fmt.Errorf("automatic runtime capture launch is not supported for %s yet", foundLauncher.Name)
	}

	executable, err := launcherExecutable(foundLauncher)
	if err != nil {
		return err
	}
	instanceID := prismFamilyInstanceID(instance)
	if instanceID == "" {
		return fmt.Errorf("could not determine launcher instance id for %s", instance.Name)
	}

	command := exec.Command(executable, "--launch", instanceID)
	processutil.HideWindow(command)
	if err := command.Start(); err != nil {
		return fmt.Errorf("start %s instance %s: %w", foundLauncher.Name, instanceID, err)
	}
	go func() { _ = command.Wait() }()
	return nil
}

func prismFamilyInstanceID(instance Instance) string {
	minecraftDir := filepath.Clean(instance.MinecraftDir)
	if minecraftDir == "" || minecraftDir == "." {
		return ""
	}
	instanceDir := filepath.Dir(minecraftDir)
	id := filepath.Base(instanceDir)
	if id == "." || id == string(filepath.Separator) {
		return ""
	}
	return id
}

func launcherExecutable(foundLauncher Launcher) (string, error) {
	var names []string
	switch foundLauncher.Type {
	case LauncherFreeSM:
		names = []string{"FreesmLauncher", "freesmlauncher", "FreesmLauncher.exe", "freesmlauncher.exe"}
	case LauncherPrism:
		names = []string{"prismlauncher", "PrismLauncher", "prismlauncher.exe", "PrismLauncher.exe"}
	case LauncherMultiMC:
		names = []string{"multimc", "MultiMC", "multimc.exe", "MultiMC.exe"}
	default:
		return "", fmt.Errorf("unsupported launcher %s", foundLauncher.Name)
	}

	for _, name := range names {
		if path, err := exec.LookPath(name); err == nil {
			return path, nil
		}
	}

	for _, candidate := range launcherExecutableCandidates(foundLauncher, names) {
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("%s executable was not found", foundLauncher.Name)
}

func launcherExecutableCandidates(foundLauncher Launcher, names []string) []string {
	seen := map[string]bool{}
	var directories []string
	addDir := func(path string) {
		path = filepath.Clean(strings.TrimSpace(path))
		if path == "" || path == "." || seen[path] {
			return
		}
		seen[path] = true
		directories = append(directories, path)
	}

	addDir(foundLauncher.RootPath)
	addDir(filepath.Dir(foundLauncher.RootPath))
	if executable, err := os.Executable(); err == nil {
		addDir(filepath.Dir(executable))
	}
	if runtime.GOOS == "windows" {
		for _, base := range []string{
			os.Getenv("LOCALAPPDATA"),
			os.Getenv("PROGRAMFILES"),
			os.Getenv("PROGRAMFILES(X86)"),
		} {
			if base == "" {
				continue
			}
			for _, folder := range []string{"FreesmLauncher", "PrismLauncher", "MultiMC"} {
				addDir(filepath.Join(base, folder))
			}
		}
	}

	result := make([]string, 0, len(directories)*len(names))
	for _, directory := range directories {
		for _, name := range names {
			result = append(result, filepath.Join(directory, name))
		}
	}
	return result
}
