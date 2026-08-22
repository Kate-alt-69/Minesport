package ui

import (
	"strings"
	"testing"
)

func TestDocumentationFallbackPagesAreStableAndSafe(t *testing.T) {
	if len(fallbackDocumentationPages) < 5 {
		t.Fatalf("expected beginner documentation fallback pages, got %d", len(fallbackDocumentationPages))
	}
	seen := map[string]bool{}
	for _, page := range fallbackDocumentationPages {
		if page.ID == "" || page.Title == "" {
			t.Fatalf("documentation page is missing ID/title: %#v", page)
		}
		if seen[page.ID] {
			t.Fatalf("duplicate documentation page ID %q", page.ID)
		}
		seen[page.ID] = true
		cleaned, ok := safeDocumentationPath(page.Path)
		if !ok {
			t.Fatalf("fallback page %s has unsafe path %q", page.ID, page.Path)
		}
		if cleaned != page.Path {
			t.Fatalf("fallback page %s path changed after cleaning: %q -> %q", page.ID, page.Path, cleaned)
		}
	}
	for _, required := range []string{"01", "11", "12", "13", "20", "90", "200"} {
		if !seen[required] {
			t.Fatalf("missing documentation fallback page %s", required)
		}
	}
}

func TestSafeDocumentationPathRejectsTraversalAndNonMarkdown(t *testing.T) {
	for _, value := range []string{
		"../README.md",
		"doc/../README.md",
		"doc/page/../../README.md",
		"README.md",
		"doc/index.json",
		"https://example.com/doc/page/12.md",
		"",
	} {
		if cleaned, ok := safeDocumentationPath(value); ok {
			t.Fatalf("expected %q to be rejected, got %q", value, cleaned)
		}
	}
}

func TestDocumentationURLsStayOnMinesportGitHub(t *testing.T) {
	page := documentationPage{ID: "12", Title: "FLATTER", Path: "doc/page/12.md"}
	raw, ok := documentationRawURL(page)
	if !ok {
		t.Fatal("expected FLATTER page path to be valid")
	}
	if !strings.HasPrefix(raw, documentationRawBase) {
		t.Fatalf("raw URL escaped documentation base: %q", raw)
	}
	github := documentationGitHubURL(page)
	if !strings.HasPrefix(github, documentationGitBase) {
		t.Fatalf("GitHub URL escaped documentation base: %q", github)
	}
}
