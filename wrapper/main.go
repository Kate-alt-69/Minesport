package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	"github.com/kastrick/minesport/ui"
	"github.com/kastrick/minesport/viewer"
)

func main() {
	setupDiagnostics()
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC: %v", r)
		}
	}()

	if len(os.Args) > 2 && os.Args[1] == "--viewer" {
		if err := viewer.Run(os.Args[2]); err != nil {
			log.Printf("viewer failed: %v", err)
			os.Exit(1)
		}
		return
	}

	jarPath := findJar()

	if len(os.Args) > 1 && os.Args[1] == "--java" {
		launchJavaUI(jarPath)
		return
	}

	if jarPath == "" {
		log.Printf("WARNING: minesport-engine.jar not found; IPC/export disabled")
	} else {
		log.Printf("Engine jar found: %s", jarPath)
	}

	ui.Run(jarPath)
}

func setupDiagnostics() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	logPath := filepath.Join(filepath.Dir(exe), "minesport.log")
	f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err == nil {
		log.SetOutput(f)
		log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
		log.Printf("Minesport starting (GOOS=%s GOARCH=%s)", runtime.GOOS, runtime.GOARCH)
	}
}

func findJar() string {
	exe, err := os.Executable()
	if err == nil {
		exeDir := filepath.Dir(exe)
		if path := globFirst(filepath.Join(exeDir, "minesport-engine-*.jar")); path != "" {
			return path
		}
		plain := filepath.Join(exeDir, "minesport-engine.jar")
		if _, err := os.Stat(plain); err == nil {
			return plain
		}
	}
	if path := globFirst("minesport-engine-*.jar"); path != "" {
		return path
	}
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

func launchJavaUI(jarPath string) {
	if jarPath == "" {
		log.Printf("ERROR: No engine jar found")
		fmt.Println("ERROR: No engine jar found. Build it first with: cd engine && gradlew jar")
		os.Exit(1)
	}

	javaExe := "java"
	if runtime.GOOS == "windows" {
		javaExe = "javaw"
	}

	cmd := exec.Command(javaExe, "-jar", jarPath)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		log.Printf("ERROR launching Java UI: %v", err)
		fmt.Println("ERROR launching Java UI:", err)
		os.Exit(1)
	}
}
