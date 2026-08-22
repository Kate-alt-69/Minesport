package bridgecompat

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRuntimeWorkerEnvironmentOverridesInheritedJavaHome(t *testing.T) {
	t.Setenv("JAVA_HOME", filepath.Join("C:", "broken-java"))
	t.Setenv("JDK_HOME", filepath.Join("C:", "also-broken"))
	t.Setenv("GRADLE_JAVA_HOME", filepath.Join("C:", "gradle-broken"))
	t.Setenv("PATH", strings.Join([]string{"C:\\Windows\\System32", "C:\\Tools"}, string(os.PathListSeparator)))

	javaHome := filepath.Join("C:", "Minesport", "jdk-21")
	env := runtimeWorkerEnvironment(javaHome, 25590)

	seenJavaHome := 0
	seenPath := 0
	for _, item := range env {
		key, value, ok := strings.Cut(item, "=")
		if !ok {
			continue
		}
		switch {
		case strings.EqualFold(key, "JAVA_HOME"):
			seenJavaHome++
			if value != javaHome {
				t.Fatalf("JAVA_HOME = %q, want %q", value, javaHome)
			}
		case strings.EqualFold(key, "JDK_HOME"), strings.EqualFold(key, "GRADLE_JAVA_HOME"):
			t.Fatalf("stale Java selector survived worker environment: %q", item)
		case strings.EqualFold(key, "PATH"):
			seenPath++
			wantPrefix := filepath.Join(javaHome, "bin") + string(os.PathListSeparator)
			if !strings.HasPrefix(value, wantPrefix) {
				t.Fatalf("PATH = %q, want selected JDK bin prefix %q", value, wantPrefix)
			}
		}
	}
	if seenJavaHome != 1 {
		t.Fatalf("saw %d JAVA_HOME entries, want exactly 1", seenJavaHome)
	}
	if seenPath != 1 {
		t.Fatalf("saw %d PATH entries, want exactly 1", seenPath)
	}
}
