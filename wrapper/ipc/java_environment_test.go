package ipc

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestSanitizeJavaHomeEnvironmentRemovesInvalidHome(t *testing.T) {
	old, hadOld := os.LookupEnv("JAVA_HOME")
	t.Cleanup(func() {
		if hadOld {
			_ = os.Setenv("JAVA_HOME", old)
		} else {
			_ = os.Unsetenv("JAVA_HOME")
		}
	})

	invalid := filepath.Join(t.TempDir(), "missing-jdk")
	if err := os.Setenv("JAVA_HOME", invalid); err != nil {
		t.Fatal(err)
	}
	sanitizeJavaHomeEnvironment()
	if got := os.Getenv("JAVA_HOME"); got != "" {
		t.Fatalf("invalid JAVA_HOME survived sanitization: %q", got)
	}
}

func TestSanitizeJavaHomeEnvironmentPreservesValidHome(t *testing.T) {
	old, hadOld := os.LookupEnv("JAVA_HOME")
	t.Cleanup(func() {
		if hadOld {
			_ = os.Setenv("JAVA_HOME", old)
		} else {
			_ = os.Unsetenv("JAVA_HOME")
		}
	})

	home := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	name := "java"
	if runtime.GOOS == "windows" {
		name = "java.exe"
	}
	if err := os.WriteFile(filepath.Join(home, "bin", name), []byte("test"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Setenv("JAVA_HOME", home); err != nil {
		t.Fatal(err)
	}
	sanitizeJavaHomeEnvironment()
	if got := os.Getenv("JAVA_HOME"); got != home {
		t.Fatalf("valid JAVA_HOME changed: got %q want %q", got, home)
	}
}
