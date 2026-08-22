package bridgecompat

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// This test protects the compatibility recipes from drifting when the canonical
// Bridge source changes. It intentionally applies each unique manifest recipe
// to a fresh copy of the current canonical source without resolving Minecraft
// dependencies or invoking Gradle.
func TestAllBridgeRecipesApplyToCurrentCanonicalSource(t *testing.T) {
	repoRoot := filepath.Clean(filepath.Join("..", ".."))
	t.Setenv("MINESPORT_BRIDGE_REPO_ROOT", repoRoot)

	manifest, err := LoadManifest()
	if err != nil {
		t.Fatal(err)
	}

	// One exact version for every unique patch family in manifest.json.
	representatives := []string{
		"1.19",
		"1.19.2",
		"1.19.3",
		"1.20",
		"1.20.2",
		"1.20.3",
		"1.20.5",
		"1.21",
		"1.21.2",
		"1.21.5",
		"1.21.6",
		"1.21.7",
		"1.21.11",
		"26.1",
		"26.2",
	}

	seen := make(map[string]bool)
	for _, version := range representatives {
		profile, err := ProfileForVersion(version, manifest)
		if err != nil {
			t.Fatalf("%s: %v", version, err)
		}
		if seen[profile.Patch] {
			continue
		}
		seen[profile.Patch] = true

		t.Run(profile.ID, func(t *testing.T) {
			workspace := t.TempDir()
			for _, relative := range manifest.Base.Files {
				source := filepath.Join(
					repoRoot,
					filepath.FromSlash(manifest.Base.SourceRoot),
					filepath.FromSlash(relative),
				)
				data, err := os.ReadFile(source)
				if err != nil {
					t.Fatalf("read canonical %s: %v", relative, err)
				}
				mode := os.FileMode(0o644)
				if relative == "gradlew" {
					mode = 0o755
				}
				if err := writeWorkspaceFile(workspace, relative, data, mode); err != nil {
					t.Fatalf("copy canonical %s: %v", relative, err)
				}
			}

			patchBytes, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(profile.Patch)))
			if err != nil {
				t.Fatalf("read recipe %s: %v", profile.Patch, err)
			}
			var patchSet PatchSet
			if err := json.Unmarshal(patchBytes, &patchSet); err != nil {
				t.Fatalf("parse recipe %s: %v", profile.Patch, err)
			}
			if patchSet.Schema != 1 {
				t.Fatalf("recipe %s has unsupported schema %d", profile.Patch, patchSet.Schema)
			}

			loader := profile.Loader
			if loader == "" || loader == "dynamic" {
				loader = "test-loader"
			}
			fabricAPI := profile.FabricAPI
			if fabricAPI == "" || fabricAPI == "dynamic" {
				fabricAPI = "test-fabric-api"
			}
			variables := map[string]string{
				"minecraft_version":  version,
				"loader_version":     loader,
				"fabric_api_version": fabricAPI,
				"fabric_version":     fabricAPI,
				"java_version":       strconv.Itoa(profile.Java),
				"gradle_version":     profile.Gradle,
				"loom_version":       profile.Loom,
			}

			for index, operation := range patchSet.Operations {
				if err := applyOperation(workspace, manifest, operation, variables); err != nil {
					t.Fatalf(
					"recipe %s operation %d (%s) no longer applies to canonical source: %v",
					profile.Patch,
					index+1,
					operation.Op,
					err,
				)
				}
			}
		})
	}
}
