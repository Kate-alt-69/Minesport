package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/kastrick/minesport/bridgecompat"
)

func main() {
	version := flag.String("version", "", "Minecraft version to prepare")
	output := flag.String("output", "", "optional directory to copy the compiled bridge into")
	sourceOnly := flag.Bool("source-only", false, "prepare patched source but do not compile it")
	flag.Parse()
	if *version == "" { fmt.Fprintln(os.Stderr, "usage: bridge-prepare -version <minecraft-version> [-output <directory>] [-source-only]"); os.Exit(2) }
	progress := func(update bridgecompat.Progress) {
		if update.Detail != "" { fmt.Printf("[%3d%%] %s %s\n", update.Percent, update.Stage, update.Detail) } else { fmt.Printf("[%3d%%] %s\n", update.Percent, update.Stage) }
	}
	if *sourceOnly {
		prepared, err := bridgecompat.PrepareSource(*version, progress); if err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
		fmt.Println(prepared.Workspace); return
	}
	jar, err := bridgecompat.Ensure(*version, progress); if err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
	if *output == "" { fmt.Println(jar); return }
	if err := os.MkdirAll(*output, 0o755); err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
	destination := filepath.Join(*output, filepath.Base(jar)); input, err := os.ReadFile(jar); if err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
	if err := os.WriteFile(destination, input, 0o644); err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }
	fmt.Println(destination)
}
