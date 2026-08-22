package ui

import (
	"path/filepath"
	"strings"

	"github.com/ncruces/zenity"
)

// nativeSaveFile mirrors nativeOpenFile but uses the platform-native save
// dialog. The requested extension is appended when the user omits it.
func nativeSaveFile(title, defaultName, filter, extension string) string {
	var filters zenity.FileFilters
	parts := strings.Split(filter, "|")
	for i := 0; i+1 < len(parts); i += 2 {
		patterns := strings.FieldsFunc(parts[i+1], func(r rune) bool { return r == ';' })
		filters = append(filters, zenity.FileFilter{Name: parts[i], Patterns: patterns})
	}

	path, err := zenity.SelectFileSave(
		zenity.Title(title),
		zenity.Filename(defaultName),
		zenity.FileFilters(filters),
		zenity.ConfirmOverwrite(),
	)
	if err != nil || strings.TrimSpace(path) == "" {
		return ""
	}
	path = filepath.Clean(path)
	if extension != "" && !strings.HasSuffix(strings.ToLower(path), strings.ToLower(extension)) {
		path += extension
	}
	return path
}
