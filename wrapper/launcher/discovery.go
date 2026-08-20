package launcher

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

// ── Data types ────────────────────────────────────────────────────────────────

type LauncherType string

const (
	LauncherOfficial   LauncherType = "Official"
	LauncherFreeSM     LauncherType = "FreesmLauncher"
	LauncherPrism      LauncherType = "PrismLauncher"
	LauncherMultiMC    LauncherType = "MultiMC"
	LauncherATLauncher LauncherType = "ATLauncher"
	LauncherCurseForge LauncherType = "CurseForge"
)

type ModLoader string

const (
	LoaderVanilla  ModLoader = "Vanilla"
	LoaderFabric   ModLoader = "Fabric"
	LoaderForge    ModLoader = "Forge"
	LoaderNeoForge ModLoader = "NeoForge"
	LoaderQuilt    ModLoader = "Quilt"
)

// Launcher represents a found MC launcher installation.
type Launcher struct {
	Type     LauncherType
	Name     string
	RootPath string // launcher's root data folder
}

// Instance represents a single MC game instance.
type Instance struct {
	Launcher     LauncherType
	Name         string // display name e.g. "1.21.10"
	Version      string // MC version e.g. "1.21.10"
	Loader       ModLoader
	ModsPath     string // absolute path to mods/ folder (empty if vanilla)
	MinecraftDir string // absolute path to the .minecraft-equivalent folder
	Worlds       []World
}

// World represents a save folder.
type World struct {
	Name       string
	Path       string
	LevelName  string // from level.dat if readable
	Version    string // MC version from level.dat
	HasPolymer bool   // detected from mods list
	// LastPlayed is level.dat's file modification time, used as a proxy
	// for "last played" — level.dat gets rewritten on every save, so this
	// tracks it closely without needing an NBT parser on the Go side (only
	// the Java engine currently reads NBT). Zero value if unavailable.
	LastPlayed time.Time
}

// ── Discovery entry point ─────────────────────────────────────────────────────

// DiscoverAll finds all MC launchers and their instances on the current system.
func DiscoverAll() []Launcher {
	var launchers []Launcher

	appdata := os.Getenv("APPDATA")
	home, _ := os.UserHomeDir()

	candidates := []struct {
		ltype LauncherType
		name  string
		paths []string
	}{
		{
			LauncherOfficial,
			"Minecraft (Official)",
			[]string{
				filepath.Join(appdata, ".minecraft"),
				filepath.Join(home, ".minecraft"),
				filepath.Join(home, "Library", "Application Support", "minecraft"),
			},
		},
		{
			LauncherFreeSM,
			"FreesmLauncher",
			[]string{
				filepath.Join(appdata, "FreesmLauncher"),
				filepath.Join(home, ".local", "share", "FreesmLauncher"),
			},
		},
		{
			LauncherPrism,
			"Prism Launcher",
			[]string{
				filepath.Join(appdata, "PrismLauncher"),
				filepath.Join(home, ".local", "share", "PrismLauncher"),
				filepath.Join(home, "Library", "Application Support", "PrismLauncher"),
			},
		},
		{
			LauncherMultiMC,
			"MultiMC",
			[]string{
				filepath.Join(home, "MultiMC"),
				filepath.Join(appdata, "MultiMC"),
			},
		},
		{
			LauncherATLauncher,
			"ATLauncher",
			[]string{
				filepath.Join(home, "ATLauncher"),
				filepath.Join(appdata, "ATLauncher"),
			},
		},
		{
			LauncherCurseForge,
			"CurseForge",
			[]string{
				filepath.Join(home, "curseforge", "minecraft"),
				filepath.Join(home, "Documents", "CurseForge", "Minecraft"),
			},
		},
	}

	for _, c := range candidates {
		for _, p := range c.paths {
			if dirExists(p) {
				launchers = append(launchers, Launcher{
					Type:     c.ltype,
					Name:     c.name,
					RootPath: p,
				})
				break // first valid path wins
			}
		}
	}

	return launchers
}

// DiscoverInstances returns all instances for a given launcher.
func DiscoverInstances(launcher Launcher) []Instance {
	switch launcher.Type {
	case LauncherOfficial:
		return discoverOfficialInstances(launcher)
	case LauncherFreeSM, LauncherPrism, LauncherMultiMC:
		return discoverMultiInstances(launcher)
	case LauncherATLauncher:
		return discoverATLauncherInstances(launcher)
	case LauncherCurseForge:
		return discoverCurseForgeInstances(launcher)
	}
	return nil
}

// ── Official Launcher ─────────────────────────────────────────────────────────

func discoverOfficialInstances(launcher Launcher) []Instance {
	// Official launcher has one "instance" — the .minecraft folder itself
	modsPath := filepath.Join(launcher.RootPath, "mods")
	inst := Instance{
		Launcher:     launcher.Type,
		Name:         "Default (.minecraft)",
		Version:      "?",
		Loader:       detectLoader(launcher.RootPath),
		ModsPath:     modsPath,
		MinecraftDir: launcher.RootPath,
	}

	// Try to get version from launcher_profiles.json
	inst.Version = readOfficialVersion(launcher.RootPath)
	inst.Worlds = discoverWorlds(filepath.Join(launcher.RootPath, "saves"))
	return []Instance{inst}
}

func readOfficialVersion(root string) string {
	data, err := os.ReadFile(filepath.Join(root, "launcher_profiles.json"))
	if err != nil {
		return "?"
	}
	var profiles map[string]interface{}
	if json.Unmarshal(data, &profiles) != nil {
		return "?"
	}
	// Find selected profile's lastVersionId
	if selected, ok := profiles["selectedProfile"].(string); ok {
		if profsMap, ok := profiles["profiles"].(map[string]interface{}); ok {
			if prof, ok := profsMap[selected].(map[string]interface{}); ok {
				if v, ok := prof["lastVersionId"].(string); ok {
					return v
				}
			}
		}
	}
	return "?"
}

// ── MultiMC-style launchers (FreeSM, Prism, MultiMC) ─────────────────────────

func discoverMultiInstances(launcher Launcher) []Instance {
	instancesDir := filepath.Join(launcher.RootPath, "instances")
	if !dirExists(instancesDir) {
		return nil
	}

	entries, err := os.ReadDir(instancesDir)
	if err != nil {
		return nil
	}

	var instances []Instance
	for _, entry := range entries {
		if !entry.IsDir() || strings.HasPrefix(entry.Name(), ".") {
			continue
		}

		instanceDir := filepath.Join(instancesDir, entry.Name())
		mcDir := filepath.Join(instanceDir, "minecraft")
		if !dirExists(mcDir) {
			continue
		}

		inst := Instance{
			Launcher:     launcher.Type,
			Name:         entry.Name(),
			MinecraftDir: mcDir,
			ModsPath:     filepath.Join(mcDir, "mods"),
			Loader:       readMultiMCLoader(instanceDir, mcDir),
		}

		// Read version from instance.cfg or mmc-pack.json
		inst.Version = readMultiMCVersion(instanceDir)
		inst.Worlds = discoverWorlds(filepath.Join(mcDir, "saves"))

		instances = append(instances, inst)
	}

	// Sort by name
	sort.Slice(instances, func(i, j int) bool {
		return instances[i].Name < instances[j].Name
	})

	return instances
}

func readMultiMCLoader(instanceDir, mcDir string) ModLoader {
	data, err := os.ReadFile(filepath.Join(instanceDir, "mmc-pack.json"))
	if err == nil {
		var pack struct {
			Components []struct {
				UID string `json:"uid"`
			} `json:"components"`
		}
		if json.Unmarshal(data, &pack) == nil {
			for _, component := range pack.Components {
				uid := strings.ToLower(component.UID)
				switch {
				case strings.Contains(uid, "neoforge"):
					return LoaderNeoForge
				case strings.Contains(uid, "forge"):
					return LoaderForge
				case strings.Contains(uid, "fabric-loader") || strings.Contains(uid, "fabricloader"):
					return LoaderFabric
				case strings.Contains(uid, "quilt-loader") || strings.Contains(uid, "quiltloader"):
					return LoaderQuilt
				}
			}
		}
	}
	return detectLoader(mcDir)
}

func readMultiMCVersion(instanceDir string) string {
	// Try mmc-pack.json first (Prism/MultiMC format)
	data, err := os.ReadFile(filepath.Join(instanceDir, "mmc-pack.json"))
	if err == nil {
		var pack map[string]interface{}
		if json.Unmarshal(data, &pack) == nil {
			if comps, ok := pack["components"].([]interface{}); ok {
				for _, c := range comps {
					if comp, ok := c.(map[string]interface{}); ok {
						if comp["uid"] == "net.minecraft" {
							if v, ok := comp["version"].(string); ok {
								return v
							}
						}
					}
				}
			}
		}
	}

	// Fallback: instance.cfg
	data, err = os.ReadFile(filepath.Join(instanceDir, "instance.cfg"))
	if err != nil {
		return "?"
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "IntendedVersion=") {
			return strings.TrimPrefix(line, "IntendedVersion=")
		}
	}
	return "?"
}

// ── ATLauncher ────────────────────────────────────────────────────────────────

func discoverATLauncherInstances(launcher Launcher) []Instance {
	instancesDir := filepath.Join(launcher.RootPath, "instances")
	if !dirExists(instancesDir) {
		return nil
	}

	entries, err := os.ReadDir(instancesDir)
	if err != nil {
		return nil
	}

	var instances []Instance
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		mcDir := filepath.Join(instancesDir, entry.Name(), ".minecraft")
		if !dirExists(mcDir) {
			// ATLauncher sometimes uses the instance root directly
			mcDir = filepath.Join(instancesDir, entry.Name())
		}
		if !fileExists(filepath.Join(mcDir, "saves")) {
			continue
		}

		inst := Instance{
			Launcher:     launcher.Type,
			Name:         entry.Name(),
			MinecraftDir: mcDir,
			ModsPath:     filepath.Join(mcDir, "mods"),
			Loader:       detectLoader(mcDir),
			Worlds:       discoverWorlds(filepath.Join(mcDir, "saves")),
		}
		instances = append(instances, inst)
	}
	return instances
}

// ── CurseForge ────────────────────────────────────────────────────────────────

func discoverCurseForgeInstances(launcher Launcher) []Instance {
	// CurseForge stores instances in Instances/ subdir
	instancesDir := filepath.Join(launcher.RootPath, "Instances")
	if !dirExists(instancesDir) {
		instancesDir = launcher.RootPath
	}

	entries, err := os.ReadDir(instancesDir)
	if err != nil {
		return nil
	}

	var instances []Instance
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		mcDir := filepath.Join(instancesDir, entry.Name(), ".minecraft")
		if !dirExists(mcDir) {
			continue
		}

		inst := Instance{
			Launcher:     launcher.Type,
			Name:         entry.Name(),
			MinecraftDir: mcDir,
			ModsPath:     filepath.Join(mcDir, "mods"),
			Loader:       detectLoader(mcDir),
			Worlds:       discoverWorlds(filepath.Join(mcDir, "saves")),
		}
		instances = append(instances, inst)
	}
	return instances
}

// ── World discovery ───────────────────────────────────────────────────────────

func discoverWorlds(savesDir string) []World {
	entries, err := os.ReadDir(savesDir)
	if err != nil {
		return nil
	}

	var worlds []World
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		worldPath := filepath.Join(savesDir, entry.Name())
		levelDat := filepath.Join(worldPath, "level.dat")
		if !fileExists(levelDat) {
			continue
		}

		world := World{
			Name: entry.Name(),
			Path: worldPath,
		}
		if info, err := os.Stat(levelDat); err == nil {
			world.LastPlayed = info.ModTime()
		}

		// Try to detect polymer from mods folder presence
		// (worlds don't directly tell us this — we detect from instance)

		worlds = append(worlds, world)
	}

	sort.Slice(worlds, func(i, j int) bool {
		return worlds[i].LastPlayed.After(worlds[j].LastPlayed)
	})

	return worlds
}

// ── Loader detection ──────────────────────────────────────────────────────────

func detectLoader(mcDir string) ModLoader {
	// Check libraries or mods folder for loader fingerprints
	modsDir := filepath.Join(mcDir, "mods")
	if !dirExists(modsDir) {
		return LoaderVanilla
	}

	entries, err := os.ReadDir(modsDir)
	if err != nil {
		return LoaderVanilla
	}

	// Loader-created directories are stronger evidence than arbitrary mod names
	// such as forgeconfigapiport, which is itself commonly used on Fabric.
	if dirExists(filepath.Join(mcDir, ".fabric")) {
		return LoaderFabric
	}

	for _, e := range entries {
		name := strings.ToLower(e.Name())
		switch {
		case strings.Contains(name, "fabric-loader") || strings.Contains(name, "fabric_loader"):
			return LoaderFabric
		case strings.Contains(name, "neoforge"):
			return LoaderNeoForge
		case strings.Contains(name, "forge"):
			return LoaderForge
		case strings.Contains(name, "quilt"):
			return LoaderQuilt
		}
	}

	// If mods folder exists with content, assume Fabric (most common)
	if len(entries) > 0 {
		return LoaderFabric
	}

	return LoaderVanilla
}

// ── Summary ───────────────────────────────────────────────────────────────────

// Summary returns a human-readable description of an instance.
func (inst Instance) Summary() string {
	worlds := fmt.Sprintf("%d world(s)", len(inst.Worlds))
	return fmt.Sprintf("[%s] %s — MC %s (%s) — %s",
		inst.Launcher, inst.Name, inst.Version, inst.Loader, worlds)
}

// HasPolymer checks if the instance's mods folder contains polymer.
func (inst Instance) HasPolymer() bool {
	if inst.ModsPath == "" {
		return false
	}
	entries, err := os.ReadDir(inst.ModsPath)
	if err != nil {
		return false
	}
	for _, e := range entries {
		if strings.Contains(strings.ToLower(e.Name()), "polymer") {
			return true
		}
	}
	return false
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

// PlatformNote returns a note if running on unexpected OS.
func PlatformNote() string {
	if runtime.GOOS != "windows" {
		return fmt.Sprintf("Running on %s — some launcher paths may differ", runtime.GOOS)
	}
	return ""
}
