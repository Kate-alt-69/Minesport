package bridgecompat

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNormalizeVersion(t *testing.T) {
	cases := map[string]string{
		"fabric-loader-0.19.3-1.21.11": "1.21.11",
		"fabric-loader-0.19.3-26.2":    "26.2",
		"fabric-loader-0.19.3-26.1.2":  "26.1.2",
		"26.3-snapshot-7":              "26.3-snapshot-7",
		"1.21.10":                      "1.21.10",
	}
	for input, want := range cases {
		if got := NormalizeVersion(input); got != want {
			t.Fatalf("NormalizeVersion(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestDeclarativeOperations(t *testing.T) {
	workspace := t.TempDir()
	props := filepath.Join(workspace, "gradle.properties")
	if err := os.WriteFile(props, []byte("minecraft_version=1.21.10\nloader_version=old\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	javaFile := filepath.Join(workspace, "src", "Example.java")
	if err := os.MkdirAll(filepath.Dir(javaFile), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(javaFile, []byte("import net.minecraft.resources.ResourceLocation;\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	manifest := Manifest{}
	vars := map[string]string{"minecraft_version": "26.2"}
	ops := []PatchOperation{
		{Op: "set_property", File: "gradle.properties", Key: "minecraft_version", Value: "${minecraft_version}"},
		{Op: "replace_tree", Root: "src", Extensions: []string{".java"}, From: "ResourceLocation", To: "Identifier"},
	}
	for _, op := range ops {
		if err := applyOperation(workspace, manifest, op, vars); err != nil {
			t.Fatal(err)
		}
	}

	gotProps, _ := os.ReadFile(props)
	if string(gotProps) != "minecraft_version=26.2\nloader_version=old\n" {
		t.Fatalf("unexpected properties: %q", string(gotProps))
	}
	gotJava, _ := os.ReadFile(javaFile)
	if string(gotJava) != "import net.minecraft.resources.Identifier;\n" {
		t.Fatalf("unexpected Java patch result: %q", string(gotJava))
	}
}

func TestRenameAtUsesOneBasedLineAndColumn(t *testing.T) {
	workspace := t.TempDir()
	file := filepath.Join(workspace, "Example.java")
	content := "first line\n    oldName();\nlast line\n"
	if err := os.WriteFile(file, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}

	op := PatchOperation{
		Op:     "rename_at",
		File:   "Example.java",
		Line:   2,
		Column: 5,
		From:   "oldName",
		To:     "newName",
	}
	if err := applyOperation(workspace, Manifest{}, op, nil); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(file)
	if string(got) != "first line\n    newName();\nlast line\n" {
		t.Fatalf("unexpected rename_at result: %q", string(got))
	}
}

func TestRenameAtRejectsStaleCoordinates(t *testing.T) {
	workspace := t.TempDir()
	file := filepath.Join(workspace, "Example.java")
	if err := os.WriteFile(file, []byte("alpha\nbeta\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := applyOperation(workspace, Manifest{}, PatchOperation{
		Op: "rename_at", File: "Example.java", Line: 2, Column: 1, From: "gamma", To: "delta",
	}, nil); err == nil {
		t.Fatal("rename_at accepted stale line/column text")
	}
}

func TestProjectMacroStaysInsideWorkspace(t *testing.T) {
	want := filepath.Join("dependencies", "Compat.java")
	if got := projectRelative("&PROJECT&/dependencies/Compat.java"); got != want {
		t.Fatalf("projectRelative returned %q, want %q", got, want)
	}
	if _, err := safeJoin(t.TempDir(), projectRelative("&PROJECT&/../escape.java")); err == nil {
		t.Fatal("project macro allowed path traversal")
	}
}

func TestModuleRequiresHTTPSAndPinnedSHA(t *testing.T) {
	if _, err := downloadPinnedModule("http://example.com/Compat.java", stringsOf('a', 64)); err == nil {
		t.Fatal("module downloader accepted non-HTTPS URL")
	}
	if _, err := downloadPinnedModule("https://example.com/Compat.java", "not-a-sha"); err == nil {
		t.Fatal("module downloader accepted invalid SHA-256")
	}
}

func TestManifestCoversRequestedCompatibilityFamilies(t *testing.T) {
	t.Setenv("MINESPORT_BRIDGE_REPO_ROOT", filepath.Clean(filepath.Join("..", "..")))
	manifest, err := LoadManifest()
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"1.21":            "1.21.0-1.21.1",
		"1.21.1":          "1.21.0-1.21.1",
		"1.21.2":          "1.21.2-1.21.4",
		"1.21.3":          "1.21.2-1.21.4",
		"1.21.4":          "1.21.2-1.21.4",
		"1.21.5":          "1.21.5",
		"1.21.6":          "1.21.6",
		"1.21.7":          "1.21.7-1.21.8",
		"1.21.8":          "1.21.7-1.21.8",
		"1.21.11":         "1.21.11",
		"26.1":            "26.1",
		"26.1.2":          "26.1",
		"26.2":            "26.2",
		"26.3-snapshot-7": "26.3-snapshot-experimental",
	}
	for version, want := range cases {
		profile, err := ProfileForVersion(version, manifest)
		if err != nil {
			t.Fatalf("%s: %v", version, err)
		}
		if profile.ID != want {
			t.Fatalf("%s mapped to %s, want %s", version, profile.ID, want)
		}
	}
	if !IsBundledCompatible("1.21.9", manifest) || !IsBundledCompatible("1.21.10", manifest) {
		t.Fatal("1.21.9/1.21.10 must remain bundled-compatible")
	}
}

func TestSafeJoinRejectsTraversal(t *testing.T) {
	if _, err := safeJoin(t.TempDir(), "../escape"); err == nil {
		t.Fatal("safeJoin allowed a path traversal")
	}
}

func stringsOf(r rune, count int) string {
	out := make([]rune, count)
	for i := range out {
		out[i] = r
	}
	return string(out)
}
