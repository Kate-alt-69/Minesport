package blendertranslator

import (
	"io/fs"
	"os"
	"path/filepath"
	"testing"
)

func TestInstallationStatusComplete(t *testing.T) {
	if (InstallationStatus{}).Complete() {
		t.Fatal("zero detected profiles must not be reported as complete")
	}
	if !(InstallationStatus{Detected: 2, Installed: 2, UpToDate: 2}).Complete() {
		t.Fatal("all detected profiles installed should be complete")
	}
	if (InstallationStatus{Detected: 2, Installed: 1}).Complete() {
		t.Fatal("partial installation must not be reported as complete")
	}
	if (InstallationStatus{Detected: 2, Installed: 2, UpToDate: 1}).Complete() {
		t.Fatal("outdated installations must not be reported as complete")
	}
}

func TestTranslatorFilesCurrentDetectsStaleInstall(t *testing.T) {
	destination := t.TempDir()
	err := fs.WalkDir(translatorFiles, "minesport_translator", func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil || entry.IsDir() {
			return walkErr
		}
		data, err := translatorFiles.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(filepath.Join(destination, filepath.Base(path)), data, 0o644)
	})
	if err != nil {
		t.Fatal(err)
	}
	if !translatorFilesCurrent(destination) {
		t.Fatal("matching translator files should be current")
	}
	if err := os.WriteFile(filepath.Join(destination, "translate.py"), []byte("# stale\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if translatorFilesCurrent(destination) {
		t.Fatal("modified translator files must be reported as stale")
	}
}
