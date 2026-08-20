package bridgecompat

import (
	"path/filepath"
	"testing"
)

func TestLegacyLoomProfilesPinCompatibleFabricAPI(t *testing.T) {
	t.Setenv("MINESPORT_BRIDGE_REPO_ROOT", filepath.Clean(filepath.Join("..", "..")))
	manifest, err := LoadManifest()
	if err != nil {
		t.Fatal(err)
	}

	cases := map[string]string{
		"1.20.1": "0.91.0+1.20.1",
		"1.20.4": "0.91.3+1.20.4",
	}
	for version, want := range cases {
		profile, err := ProfileForVersion(version, manifest)
		if err != nil {
			t.Fatalf("%s: %v", version, err)
		}
		if profile.FabricAPI != want {
			t.Fatalf("%s Fabric API = %q, want %q", version, profile.FabricAPI, want)
		}
	}

	for _, version := range []string{"1.20", "1.20.2", "1.20.3", "1.20.5", "1.20.6"} {
		profile, err := ProfileForVersion(version, manifest)
		if err != nil {
			t.Fatalf("%s: %v", version, err)
		}
		if profile.FabricAPI != "dynamic" {
			t.Fatalf("%s Fabric API = %q, want dynamic", version, profile.FabricAPI)
		}
	}
}
