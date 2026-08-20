package ui

import "testing"

func TestInfoPopoverHeightGrowsAndStaysInsideCanvas(t *testing.T) {
	short := infoPopoverHeight("Short help text.", 800)
	long := infoPopoverHeight("A long paragraph that needs several wrapped lines in the information box.\n\nA second paragraph adds enough content to require more vertical space without escaping the popup.\n\nA final paragraph verifies the adaptive sizing behavior.", 800)
	if long <= short {
		t.Fatalf("long popover height %v should exceed short height %v", long, short)
	}
	if long > infoPopoverMaxHeight {
		t.Fatalf("popover height %v exceeds maximum %v", long, infoPopoverMaxHeight)
	}
	if got := infoPopoverHeight("Long enough text to need space.", 120); got != 104 {
		t.Fatalf("small canvas height = %v, want 104", got)
	}
}

func TestInfoPopoverWidthAdaptsToCanvas(t *testing.T) {
	if got := infoPopoverWidth(800); got != infoPopoverPreferredWidth {
		t.Fatalf("wide canvas popup width = %v, want %v", got, infoPopoverPreferredWidth)
	}
	if got := infoPopoverWidth(300); got != 284 {
		t.Fatalf("narrow canvas popup width = %v, want 284", got)
	}
}
