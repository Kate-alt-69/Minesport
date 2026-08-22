package ui

import "testing"

func TestMovePathEntryReordersWithoutMutatingInput(t *testing.T) {
	input := []string{"top", "middle", "bottom"}
	result := movePathEntry(input, 2, -1)
	if result == nil {
		t.Fatal("expected a reordered copy")
	}
	if got, want := result[0]+","+result[1]+","+result[2], "top,bottom,middle"; got != want {
		t.Fatalf("unexpected order: got %q want %q", got, want)
	}
	if got := input[0]+","+input[1]+","+input[2]; got != "top,middle,bottom" {
		t.Fatalf("input mutated: %q", got)
	}
}

func TestMovePathEntryRejectsOutOfRangeMove(t *testing.T) {
	if got := movePathEntry([]string{"a", "b"}, 0, -1); got != nil {
		t.Fatalf("expected nil for move above first entry, got %#v", got)
	}
	if got := movePathEntry([]string{"a", "b"}, 1, 1); got != nil {
		t.Fatalf("expected nil for move below last entry, got %#v", got)
	}
}
