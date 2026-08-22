package ipc

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// The desktop app must not blindly inherit a stale system JAVA_HOME. Engine.Start
// intentionally falls back to `java` on PATH when JAVA_HOME is absent, so remove
// only a JAVA_HOME that cannot actually provide a Java executable. This keeps a
// valid user-selected JDK untouched while preventing old Oracle/common-files
// environment entries from breaking Minesport before its own toolchain logic can
// run.
func init() {
	sanitizeJavaHomeEnvironment()
}

func sanitizeJavaHomeEnvironment() {
	home := strings.TrimSpace(os.Getenv("JAVA_HOME"))
	if home == "" {
		return
	}
	if javaHomeExecutableExists(home) {
		return
	}
	_ = os.Unsetenv("JAVA_HOME")
}

func javaHomeExecutableExists(home string) bool {
	name := "java"
	if runtime.GOOS == "windows" {
		name = "java.exe"
	}
	candidate := filepath.Join(strings.TrimSpace(home), "bin", name)
	info, err := os.Stat(candidate)
	return err == nil && !info.IsDir()
}
