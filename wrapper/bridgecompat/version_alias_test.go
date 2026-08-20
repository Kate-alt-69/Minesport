package bridgecompat

import (
	"path/filepath"
	"testing"
)

func TestManifestAcceptsDotZeroAliases(t *testing.T) {
	t.Setenv("MINESPORT_BRIDGE_REPO_ROOT", filepath.Clean(filepath.Join("..", "..")))
	manifest, err := LoadManifest()
	if err != nil {
		t.Fatal(err)
	}

	cases := map[string]string{
		"1.20":   "1.20.0-1.20.1",
		"1.20.0": "1.20.0-1.20.1",
		"1.20.1": "1.20.0-1.20.1",
		"1.21":   "1.21.0-1.21.1",
		"1.21.0": "1.21.0-1.21.1",
		"1.21.1": "1.21.0-1.21.1",
	}
	for version, expectedProfile := range cases {
		profile, err := ProfileForVersion(version, manifest)
		if err != nil {
			t.Fatalf("%s: %v", version, err)
		}
		if profile.ID != expectedProfile {
			t.Fatalf("%s mapped to %s, want %s", version, profile.ID, expectedProfile)
		}
	}
}
