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
	"strings"
	"time"

	"github.com/kastrick/minesport/ui"
	"github.com/kastrick/minesport/viewer"
)

var diagnosticsDir string

func main() {
	setupDiagnostics()
	defer func() {
		if r := recover(); r != nil {
			reportCrash(r)
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
	diagnosticsDir = filepath.Dir(exe)
	logPath := filepath.Join(diagnosticsDir, "minesport.log")
	f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
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

	if err := os.WriteFile(panicPath, []byte(b.String()), 0644); err != nil {
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
