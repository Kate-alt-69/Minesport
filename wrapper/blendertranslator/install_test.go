package blendertranslator

import "testing"

func TestInstallationStatusComplete(t *testing.T) {
	if (InstallationStatus{}).Complete() {
		t.Fatal("zero detected profiles must not be reported as complete")
	}
	if !(InstallationStatus{Detected: 2, Installed: 2}).Complete() {
		t.Fatal("all detected profiles installed should be complete")
	}
	if (InstallationStatus{Detected: 2, Installed: 1}).Complete() {
		t.Fatal("partial installation must not be reported as complete")
	}
}
