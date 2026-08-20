package launcher

import (
	"os"
	"path/filepath"
	"testing"
)

func TestReadMultiMCLoaderUsesComponentMetadata(t *testing.T) {
	tests := []struct {
		name string
		uid  string
		want ModLoader
	}{
		{"fabric", "net.fabricmc.fabric-loader", LoaderFabric},
		{"forge", "net.minecraftforge", LoaderForge},
		{"neoforge", "net.neoforged.neoforge", LoaderNeoForge},
		{"quilt", "org.quiltmc.quilt-loader", LoaderQuilt},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			instance := t.TempDir()
			minecraft := filepath.Join(instance, "minecraft")
			if err := os.MkdirAll(minecraft, 0o755); err != nil {
				t.Fatal(err)
			}
			pack := `{"components":[{"uid":"net.minecraft"},{"uid":"` + test.uid + `"}]}`
			if err := os.WriteFile(filepath.Join(instance, "mmc-pack.json"), []byte(pack), 0o644); err != nil {
				t.Fatal(err)
			}
			if got := readMultiMCLoader(instance, minecraft); got != test.want {
				t.Fatalf("readMultiMCLoader() = %q, want %q", got, test.want)
			}
		})
	}
}

func TestDetectLoaderPrefersFabricMarkerOverModFilename(t *testing.T) {
	minecraft := t.TempDir()
	if err := os.MkdirAll(filepath.Join(minecraft, "mods"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(minecraft, ".fabric"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(minecraft, "mods", "forgeconfigapiport.jar"), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	if got := detectLoader(minecraft); got != LoaderFabric {
		t.Fatalf("detectLoader() = %q, want Fabric", got)
	}
}
