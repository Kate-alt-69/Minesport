package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"runtime/pprof"
	"sort"
	"strings"
	"time"

	"github.com/kastrick/minesport/blendertranslator"
	"github.com/kastrick/minesport/bridgecompat"
	"github.com/kastrick/minesport/launcher"
	"github.com/kastrick/minesport/processutil"
	"github.com/kastrick/minesport/ui"
	"github.com/kastrick/minesport/viewer"
)

var diagnosticsDir string
var diagnosticsLogPath string

func main() {
	setupDiagnostics()
	defer shutdownDesktopBridgeCapture()
	defer func() {
		if r := recover(); r != nil {
			reportCrash(r)
		}
	}()

	if handled, code := handleUtilityCommand(os.Args[1:]); handled {
		if code != 0 {
			os.Exit(code)
		}
		return
	}

	if len(os.Args) > 2 && os.Args[1] == "--viewer" {
		if err := viewer.Run(os.Args[2]); err != nil {
			log.Printf("viewer failed: %v", err)
			os.Exit(1)
		}
		return
	}
	if len(os.Args) > 2 && os.Args[1] == "--viewer-embed" {
		if err := viewer.Run(os.Args[2], true); err != nil {
			log.Printf("embedded viewer failed: %v", err)
			os.Exit(1)
		}
		return
	}

	if len(os.Args) > 1 && os.Args[1] == "--java-e" {
		if err := launchEmbeddedJava(); err != nil {
			log.Printf("embedded Java engine failed: %v", err)
		}
		return
	}

	if len(os.Args) > 1 && os.Args[1] == "--java" {
		launchJavaUI(findJar())
		return
	}

	jarPath, cleanup, embeddedErr := materializeEmbeddedEngine()
	if embeddedErr == nil {
		defer cleanup()
		log.Printf("Embedded engine prepared: %s", jarPath)
	} else {
		jarPath = findJar()
		if jarPath == "" {
			log.Printf("WARNING: embedded engine unavailable: %v", embeddedErr)
			log.Printf("WARNING: minesport-engine.jar not found; IPC/export disabled")
		} else {
			log.Printf("External engine fallback: %s", jarPath)
		}
	}

	ui.RunModern(jarPath, diagnosticsLogPath)
}

func handleUtilityCommand(args []string) (bool, int) {
	if len(args) == 0 {
		return false, 0
	}

	switch args[0] {
	case "-h", "--help":
		printCLIHelp()
		return true, 0

	case "--install-blender-translator":
		installed, err := blendertranslator.Install()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Minesport Blender translator installation failed: %v\n", err)
			return true, 1
		}
		if len(installed) == 0 {
			fmt.Println("No compatible Blender 4.3+ installation/profile was detected.")
			return true, 0
		}
		fmt.Printf("Installed Minesport Blender translator for %d profile(s):\n", len(installed))
		for _, path := range installed {
			fmt.Printf("  %s\n", path)
		}
		return true, 0

	case "--build-bridge":
		if len(args) != 2 {
			fmt.Fprintln(os.Stderr, "usage: minesport --build-bridge <minecraft-version>")
			return true, 2
		}
		jar, err := bridgecompat.Ensure(args[1], printBridgeProgress)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Bridge preparation failed: %v\n", err)
			return true, 1
		}
		fmt.Printf("Bridge ready: %s\n", jar)
		return true, 0

	case "--build-bridges-detected":
		if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "usage: minesport --build-bridges-detected")
			return true, 2
		}
		if err := buildDetectedFabricBridges(); err != nil {
			fmt.Fprintf(os.Stderr, "Bridge preparation failed: %v\n", err)
			return true, 1
		}
		return true, 0
	}

	return false, 0
}

func printCLIHelp() {
	fmt.Print(`Minesport

Usage:
  minesport                         Open the Minesport UI
  minesport --build-bridge VERSION  Prepare/cache the Fabric bridge for VERSION
  minesport --build-bridges-detected
                                    Prepare bridges for detected Fabric instances
  minesport --install-blender-translator
                                    Install/repair the bundled Blender translator
  minesport -h | --help             Show this help

Minecraft 1.21.9 and 1.21.10 use the bundled 1.21.10 bridge. Other supported
versions are generated from the canonical 1.21.10 source and compiled only when
needed.
`)
}

func printBridgeProgress(update bridgecompat.Progress) {
	if update.Detail == "" {
		fmt.Printf("[%3d%%] %s\n", update.Percent, update.Stage)
		return
	}
	fmt.Printf("[%3d%%] %s %s\n", update.Percent, update.Stage, update.Detail)
}

func buildDetectedFabricBridges() error {
	versions := map[string]struct{}{}
	for _, detectedLauncher := range launcher.DiscoverAll() {
		for _, instance := range launcher.DiscoverInstances(detectedLauncher) {
			if instance.Loader != launcher.LoaderFabric {
				continue
			}
			version := bridgecompat.NormalizeVersion(instance.Version)
			if version == "" {
				continue
			}
			versions[version] = struct{}{}
		}
	}

	if len(versions) == 0 {
		fmt.Println("No Fabric Minecraft installations with a known version were detected.")
		return nil
	}

	ordered := make([]string, 0, len(versions))
	for version := range versions {
		ordered = append(ordered, version)
	}
	sort.Strings(ordered)

	var failures []string
	for _, version := range ordered {
		fmt.Printf("\nMinecraft %s\n", version)
		jar, err := bridgecompat.Ensure(version, printBridgeProgress)
		if err != nil {
			failures = append(failures, fmt.Sprintf("%s: %v", version, err))
			continue
		}
		fmt.Printf("Bridge ready: %s\n", jar)
	}

	if len(failures) != 0 {
		return fmt.Errorf("%d bridge(s) could not be prepared: %s", len(failures), strings.Join(failures, "; "))
	}
	return nil
}

func setupDiagnostics() {
	base, err := os.UserCacheDir()
	if err != nil || base == "" {
		base = os.TempDir()
	}
	diagnosticsDir = filepath.Join(base, "kastrick_software", "minesport", "diagnostics")
	if err := os.MkdirAll(diagnosticsDir, 0o755); err != nil {
		diagnosticsDir = os.TempDir()
	}

	diagnosticsLogPath = filepath.Join(diagnosticsDir, "minesport.log")
	f, err := os.OpenFile(diagnosticsLogPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}
	log.SetOutput(f)
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Printf("Minesport starting (GOOS=%s GOARCH=%s)", runtime.GOOS, runtime.GOARCH)
}

func reportCrash(panicValue any) {
	now := time.Now()
	stamp := now.Format("20060102-150405.000")
	panicPath := filepath.Join(diagnosticsDir, "minesport-crash-"+stamp+".tmp")
	heapPath := filepath.Join(diagnosticsDir, "minesport-heap-"+stamp+".tmp")

	stack := debug.Stack()
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)

	var goroutines strings.Builder
	if p := pprof.Lookup("goroutine"); p != nil {
		_ = p.WriteTo(&goroutines, 2)
	}

	var b strings.Builder
	fmt.Fprintln(&b, "Minesport crash report")
	fmt.Fprintf(&b, "Time: %s\n", now.Format(time.RFC3339Nano))
	fmt.Fprintf(&b, "OS: %s/%s\n", runtime.GOOS, runtime.GOARCH)
	fmt.Fprintf(&b, "Go: %s\n", runtime.Version())
	fmt.Fprintf(&b, "Executable: %s\n", executablePath())
	fmt.Fprintf(&b, "Working directory: %s\n", workingDirectory())
	fmt.Fprintf(&b, "Command line: %q\n\n", os.Args)
	fmt.Fprintf(&b, "PANIC: %v\n\n", panicValue)
	fmt.Fprintf(&b, "=== PANIC STACK ===\n%s\n", stack)
	fmt.Fprintf(&b, "=== ALL GOROUTINES ===\n%s\n", goroutines.String())
	fmt.Fprintln(&b, "=== MEMORY STATS ===")
	fmt.Fprintf(&b, "Alloc=%d bytes\n", mem.Alloc)
	fmt.Fprintf(&b, "TotalAlloc=%d bytes\n", mem.TotalAlloc)
	fmt.Fprintf(&b, "Sys=%d bytes\n", mem.Sys)
	fmt.Fprintf(&b, "HeapAlloc=%d bytes\n", mem.HeapAlloc)
	fmt.Fprintf(&b, "HeapSys=%d bytes\n", mem.HeapSys)
	fmt.Fprintf(&b, "HeapInuse=%d bytes\n", mem.HeapInuse)
	fmt.Fprintf(&b, "HeapIdle=%d bytes\n", mem.HeapIdle)
	fmt.Fprintf(&b, "HeapReleased=%d bytes\n", mem.HeapReleased)
	fmt.Fprintf(&b, "HeapObjects=%d\n", mem.HeapObjects)
	fmt.Fprintf(&b, "StackInuse=%d bytes\n", mem.StackInuse)
	fmt.Fprintf(&b, "StackSys=%d bytes\n", mem.StackSys)
	fmt.Fprintf(&b, "MSpanInuse=%d bytes\n", mem.MSpanInuse)
	fmt.Fprintf(&b, "MCacheInuse=%d bytes\n", mem.MCacheInuse)
	fmt.Fprintf(&b, "NumGC=%d\n", mem.NumGC)
	fmt.Fprintf(&b, "Goroutines=%d\n", runtime.NumGoroutine())

	if err := os.WriteFile(panicPath, []byte(b.String()), 0o644); err != nil {
		log.Printf("CRASH REPORT WRITE FAILED: %v", err)
	} else {
		log.Printf("Crash report: %s", panicPath)
	}

	runtime.GC()
	if f, err := os.Create(heapPath); err == nil {
		if err := pprof.WriteHeapProfile(f); err != nil {
			log.Printf("HEAP DUMP WRITE FAILED: %v", err)
		} else {
			log.Printf("Heap dump: %s", heapPath)
		}
		_ = f.Close()
	} else {
		log.Printf("HEAP DUMP CREATE FAILED: %v", err)
	}

	log.Printf("PANIC: %v", panicValue)
	log.Printf("PANIC STACK:\n%s", stack)
	log.Printf("Crash diagnostics written; exiting.")
}

func materializeEmbeddedEngine() (string, func(), error) {
	data := embeddedEngineBytes()
	if len(data) == 0 {
		return "", func() {}, fmt.Errorf("this build has no embedded engine; rebuild with the Minesport build script")
	}

	dir, err := os.MkdirTemp("", "minesport-engine-")
	if err != nil {
		return "", func() {}, fmt.Errorf("create engine temp directory: %w", err)
	}
	path := filepath.Join(dir, "minesport-engine.jar")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		_ = os.RemoveAll(dir)
		return "", func() {}, fmt.Errorf("write embedded engine: %w", err)
	}
	return path, func() { _ = os.RemoveAll(dir) }, nil
}

func launchEmbeddedJava() error {
	jarPath, cleanup, err := materializeEmbeddedEngine()
	if err != nil {
		return err
	}
	defer cleanup()
	log.Printf("Launching embedded Java engine: %s", jarPath)
	return runJava(jarPath)
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
		log.Printf("ERROR: No external engine jar found")
		return
	}
	if err := runJava(jarPath); err != nil {
		log.Printf("ERROR launching Java UI: %v", err)
	}
}

func runJava(jarPath string) error {
	javaExe := "java"
	if runtime.GOOS == "windows" {
		javaExe = "javaw"
	}

	cmd := exec.Command(javaExe, "-jar", jarPath)
	processutil.HideWindow(cmd)
	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("%s failed: %w", javaExe, err)
	}
	return nil
}

func executablePath() string {
	if p, err := os.Executable(); err == nil {
		return p
	}
	return "unknown"
}

func workingDirectory() string {
	if p, err := os.Getwd(); err == nil {
		return p
	}
	return "unknown"
}
