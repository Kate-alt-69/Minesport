package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"

	"github.com/kastrick/minesport/blendertranslator"
	"github.com/kastrick/minesport/bridgecompat"
	"github.com/kastrick/minesport/launcher"
)

func init() {
	if len(os.Args) < 2 { return }
	switch os.Args[1] {
	case "--install-blender-translator":
		installed, err := blendertranslator.Install()
		if err != nil { fmt.Fprintln(os.Stderr, "Minesport Blender translator install failed:", err); os.Exit(1) }
		if len(installed) == 0 { fmt.Fprintln(os.Stdout, "No Blender 4.3+ profile was detected; nothing was installed.") } else { for _, path := range installed { fmt.Fprintln(os.Stdout, "Installed Minesport Blender translator:", path) } }
		os.Exit(0)
	case "--build-bridge":
		if len(os.Args) < 3 { fmt.Fprintln(os.Stderr, "Usage: minesport --build-bridge <Minecraft version> [output directory]"); os.Exit(2) }
		output := ""; if len(os.Args) >= 4 { output = os.Args[3] }
		if err := buildFabricBridge(os.Args[2], output); err != nil { fmt.Fprintln(os.Stderr, "Minesport Fabric bridge build failed:", err); os.Exit(1) }
		os.Exit(0)
	case "--build-bridges-detected":
		if err := buildDetectedFabricBridges(); err != nil { fmt.Fprintln(os.Stderr, "Minesport detected bridge build failed:", err); os.Exit(1) }
		os.Exit(0)
	}
}

func buildDetectedFabricBridges() error {
	versions := map[string]struct{}{}
	for _, foundLauncher := range launcher.DiscoverAll() {
		for _, instance := range launcher.DiscoverInstances(foundLauncher) {
			version := bridgecompat.NormalizeVersion(instance.Version)
			if version == "" || !bridgecompat.NeedsPreparation(version) { continue }
			versions[version] = struct{}{}
		}
	}
	if len(versions) == 0 { fmt.Fprintln(os.Stdout, "All detected Minecraft versions already have compatible Minesport bridge support."); return nil }
	ordered := make([]string, 0, len(versions)); for version := range versions { ordered = append(ordered, version) }; sort.Strings(ordered)
	for _, version := range ordered { fmt.Fprintln(os.Stdout, "Preparing Minesport bridge support for Minecraft", version); if err := buildFabricBridge(version, ""); err != nil { return fmt.Errorf("Minecraft %s: %w", version, err) } }
	return nil
}

func buildFabricBridge(version, output string) error {
	jar, err := bridgecompat.Ensure(version, func(update bridgecompat.Progress) {
		if update.Detail != "" { fmt.Fprintf(os.Stdout, "[%3d%%] %s %s\n", update.Percent, update.Stage, update.Detail) } else { fmt.Fprintf(os.Stdout, "[%3d%%] %s\n", update.Percent, update.Stage) }
	})
	if err != nil { return err }
	if output == "" { fmt.Fprintln(os.Stdout, "Bridge ready:", jar); return nil }
	if err := os.MkdirAll(output, 0o755); err != nil { return err }
	destination := filepath.Join(output, filepath.Base(jar)); data, err := os.ReadFile(jar); if err != nil { return err }
	if err := os.WriteFile(destination, data, 0o644); err != nil { return err }
	fmt.Fprintln(os.Stdout, "Bridge ready:", destination); return nil
}
