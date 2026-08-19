package main

import (
    "fmt"
    "os"
    "os/exec"
    "path/filepath"
    "runtime"

    "github.com/kastrick/minesport/blendertranslator"
)

// init handles installer/integration commands before the Fyne application is
// created. Normal Minesport launches do not enter this path.
func init() {
    if len(os.Args) < 2 {
        return
    }

    switch os.Args[1] {
    case "--install-blender-translator":
        installed, err := blendertranslator.Install()
        if err != nil {
            fmt.Fprintln(os.Stderr, "Minesport Blender translator install failed:", err)
            os.Exit(1)
        }
        if len(installed) == 0 {
            fmt.Fprintln(os.Stdout, "No Blender 4.3+ profile was detected; nothing was installed.")
        } else {
            for _, path := range installed {
                fmt.Fprintln(os.Stdout, "Installed Minesport Blender translator:", path)
            }
        }
        os.Exit(0)

    case "--build-bridge":
        if len(os.Args) < 3 {
            fmt.Fprintln(os.Stderr, "Usage: minesport --build-bridge <26.x Minecraft version> [output directory]")
            os.Exit(2)
        }
        output := ""
        if len(os.Args) >= 4 {
            output = os.Args[3]
        }
        if err := buildFabricBridge(os.Args[2], output); err != nil {
            fmt.Fprintln(os.Stderr, "Minesport Fabric bridge build failed:", err)
            os.Exit(1)
        }
        os.Exit(0)
    }
}

func buildFabricBridge(version, output string) error {
    bridgeRoot, err := findBridge26Root()
    if err != nil {
        return err
    }

    var command *exec.Cmd
    if runtime.GOOS == "windows" {
        script := filepath.Join(bridgeRoot, "tools", "build-target.ps1")
        args := []string{
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", script,
            "-MinecraftVersion", version,
        }
        if output != "" {
            args = append(args, "-OutputDirectory", output)
        }
        command = exec.Command("powershell.exe", args...)
    } else {
        script := filepath.Join(bridgeRoot, "tools", "build-target.sh")
        args := []string{script, version}
        if output != "" {
            args = append(args, output)
        }
        command = exec.Command("bash", args...)
    }

    command.Stdout = os.Stdout
    command.Stderr = os.Stderr
    command.Stdin = os.Stdin
    command.Env = os.Environ()
    if err := command.Run(); err != nil {
        return fmt.Errorf("bridge builder failed: %w", err)
    }
    return nil
}

func findBridge26Root() (string, error) {
    var candidates []string

    if executable, err := os.Executable(); err == nil {
        candidates = append(candidates, filepath.Join(filepath.Dir(executable), "bridge26"))
    }

    if cwd, err := os.Getwd(); err == nil {
        candidates = append(
            candidates,
            filepath.Join(cwd, "bridge26"),
            filepath.Join(cwd, "..", "bridge26"),
        )
    }

    for _, candidate := range candidates {
        buildFile := filepath.Join(candidate, "build.gradle")
        if info, err := os.Stat(buildFile); err == nil && !info.IsDir() {
            return filepath.Clean(candidate), nil
        }
    }

    return "", fmt.Errorf(
        "bridge26 build sources were not found; reinstall Minesport or run from the repository checkout",
    )
}
