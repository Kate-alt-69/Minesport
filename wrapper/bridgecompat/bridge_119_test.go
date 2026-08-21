package bridgecompat

import (
	"path/filepath"
	"testing"
)

func TestManifestCoversMinecraft119Family(t *testing.T) {
	t.Setenv("MINESPORT_BRIDGE_REPO_ROOT", filepath.Clean(filepath.Join("..", "..")))
	manifest, err := LoadManifest()
	if err != nil {
		t.Fatal(err)
	}

	cases := map[string]struct {
		profile string
		api     string
	}{
		"1.19":   {"1.19.0-1.19.1", "0.58.0+1.19"},
		"1.19.0": {"1.19.0-1.19.1", "0.58.0+1.19"},
		"1.19.1": {"1.19.0-1.19.1", "0.58.5+1.19.1"},
		"1.19.2": {"1.19.2", "0.77.0+1.19.2"},
		"1.19.3": {"1.19.3-1.19.4", "0.76.1+1.19.3"},
		"1.19.4": {"1.19.3-1.19.4", "0.87.2+1.19.4"},
	}

	for version, want := range cases {
		profile, err := ProfileForVersion(version, manifest)
		if err != nil {
			t.Fatalf("%s: %v", version, err)
		}
		if profile.ID != want.profile {
			t.Fatalf("%s mapped to %s, want %s", version, profile.ID, want.profile)
		}
		if profile.Java != 17 {
			t.Fatalf("%s requests Java %d, want 17", version, profile.Java)
		}
		if profile.Gradle != "9.5.1" || profile.Loom != "1.17.18" || profile.Loader != "0.19.3" {
			t.Fatalf("%s has unexpected modern toolchain: Gradle=%s Loom=%s Loader=%s",
				version, profile.Gradle, profile.Loom, profile.Loader)
		}
		if profile.FabricAPI != want.api {
			t.Fatalf("%s uses Fabric API %s, want %s", version, profile.FabricAPI, want.api)
		}
	}
}
