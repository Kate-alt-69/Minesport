package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	"github.com/kastrick/minesport/ui"
	"github.com/kastrick/minesport/viewer"
)

func main() {
	// --viewer <blocksFile>: run the 3D preview window instead of the main
	// app. Always a separate process launched by the main app (see
	// ui.LaunchViewer) — never called in-process, because Fyne's own
	// driver is built on the same go-gl/glfw package this uses, and two
	// independent GLFW instances in one process is unsafe.
	if len(os.Args) > 2 && os.Args[1] == "--viewer" {
		if err := viewer.Run(os.Args[2]); err != nil {
			os.Exit(1)
		}
		return
	}

	// Find the engine jar — look next to the executable first,
	// then in common dev locations
	jarPath := findJar()

	// If --java flag passed, launch Java UI directly (dev mode)
	if len(os.Args) > 1 && os.Args[1] == "--java" {
		launchJavaUI(jarPath)
		return
	}

	// Otherwise launch Go/Fyne UI (with IPC to Java if jar found)
	if jarPath == "" {
		fmt.Println("[WARN] minesport-engine.jar not found next to executable.")
		fmt.Println("[WARN] IPC mode disabled — export will not work from Go UI.")
		fmt.Println("[HINT] Place minesport-engine-*.jar next to this executable.")
		fmt.Println("[HINT] Or run: java -jar minesport-engine-*.jar  for the Java UI directly.")
		fmt.Println()
	} else {
		fmt.Println("[INFO] Engine jar found:", jarPath)
	}

	ui.Run(jarPath)
}

// findJar locates the minesport engine jar.
func findJar() string {
	// 1. Next to the executable
	exe, err := os.Executable()
	if err == nil {
		exeDir := filepath.Dir(exe)
		if path := globFirst(filepath.Join(exeDir, "minesport-engine-*.jar")); path != "" {
			return path
		}
		// Also check plain name
		plain := filepath.Join(exeDir, "minesport-engine.jar")
		if _, err := os.Stat(plain); err == nil {
			return plain
		}
	}

	// 2. Current working directory
	if path := globFirst("minesport-engine-*.jar"); path != "" {
		return path
	}

	// 3. Dev location — engine/build/libs/
	devPath := globFirst(filepath.Join("engine", "build", "libs", "minesport-engine-*.jar"))
	if devPath != "" {
		return devPath
	}

	return ""
}

func globFirst(pattern string) string {
	matches, err := filepath.Glob(pattern)
	if err != nil || len(matches) == 0 {
		return ""
	}
	return matches[0]
}

// launchJavaUI opens the Java Swing UI directly (bypasses Go UI).
func launchJavaUI(jarPath string) {
	if jarPath == "" {
		fmt.Println("ERROR: No engine jar found. Build it first with: cd engine && gradlew jar")
		os.Exit(1)
	}

	javaExe := "java"
	if runtime.GOOS == "windows" {
		javaExe = "javaw" // no console window on Windows
	}

	cmd := exec.Command(javaExe, "-jar", jarPath)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Run(); err != nil {
		fmt.Println("ERROR launching Java UI:", err)
		os.Exit(1)
	}
}
