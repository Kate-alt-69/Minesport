package bridgecompat

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNormalizeVersion(t *testing.T) {
	cases := map[string]string{
		"fabric-loader-0.19.3-1.21.11": "1.21.11",
		"fabric-loader-0.19.3-26.2": "26.2",
		"26.3-snapshot-7": "26.3-snapshot-7",
		"1.21.10": "1.21.10",
	}
	for input, want := range cases {
		if got := NormalizeVersion(input); got != want { t.Fatalf("NormalizeVersion(%q) = %q, want %q", input, got, want) }
	}
}

func TestDeclarativeOperations(t *testing.T) {
	workspace := t.TempDir()
	props := filepath.Join(workspace, "gradle.properties")
	if err := os.WriteFile(props, []byte("minecraft_version=1.21.10\nloader_version=old\n"), 0o644); err != nil { t.Fatal(err) }
	javaFile := filepath.Join(workspace, "src", "Example.java")
	if err := os.MkdirAll(filepath.Dir(javaFile), 0o755); err != nil { t.Fatal(err) }
	if err := os.WriteFile(javaFile, []byte("import net.minecraft.resources.ResourceLocation;\n"), 0o644); err != nil { t.Fatal(err) }

	manifest := Manifest{}
	vars := map[string]string{"minecraft_version": "26.2"}
	ops := []PatchOperation{
		{Op: "set_property", File: "gradle.properties", Key: "minecraft_version", Value: "${minecraft_version}"},
		{Op: "replace_tree", Root: "src", Extensions: []string{".java"}, From: "ResourceLocation", To: "Identifier"},
	}
	for _, op := range ops { if err := applyOperation(workspace, manifest, op, vars); err != nil { t.Fatal(err) } }

	gotProps, _ := os.ReadFile(props)
	if string(gotProps) != "minecraft_version=26.2\nloader_version=old\n" { t.Fatalf("unexpected properties: %q", string(gotProps)) }
	gotJava, _ := os.ReadFile(javaFile)
	if string(gotJava) != "import net.minecraft.resources.Identifier;\n" { t.Fatalf("unexpected Java patch result: %q", string(gotJava)) }
}

func TestSafeJoinRejectsTraversal(t *testing.T) {
	if _, err := safeJoin(t.TempDir(), "../escape"); err == nil { t.Fatal("safeJoin allowed a path traversal") }
}
