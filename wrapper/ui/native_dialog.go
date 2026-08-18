package ui

import (
	"os/exec"
	"runtime"
	"strings"
)

// nativeOpenFile opens the OS native file picker and returns the selected path.
// filter format on Windows: "Description|*.ext|Description2|*.ext2"
func nativeOpenFile(title, filter string) string {
	switch runtime.GOOS {
	case "windows":
		return winOpenFile(title, filter)
	case "linux":
		return zenityOpenFile(title)
	case "darwin":
		return macOpenFile(title)
	default:
		return ""
	}
}

// nativeOpenFolder opens the OS native folder picker.
func nativeOpenFolder(title string) string {
	switch runtime.GOOS {
	case "windows":
		return winOpenFolder(title)
	case "linux":
		return zenityOpenFolder(title)
	case "darwin":
		return macOpenFolder(title)
	default:
		return ""
	}
}

// ── Windows ───────────────────────────────────────────────────────────────────

func winOpenFile(title, filter string) string {
	// Build PowerShell filter string from "Desc|*.ext|Desc2|*.ext2"
	// PowerShell OpenFileDialog uses the same pipe-separated format
	script := `
Add-Type -AssemblyName System.Windows.Forms
$d = New-Object System.Windows.Forms.OpenFileDialog
$d.Title = '` + title + `'
$d.Filter = '` + filter + `'
$d.Multiselect = $false
$d.RestoreDirectory = $true
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
    Write-Output $d.FileName
}
`
	return runPowerShell(script)
}

func winOpenFolder(title string) string {
	script := `
Add-Type -AssemblyName System.Windows.Forms
$d = New-Object System.Windows.Forms.FolderBrowserDialog
$d.Description = '` + title + `'
$d.UseDescriptionForTitle = $true
$d.ShowNewFolderButton = $true
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
    Write-Output $d.SelectedPath
}
`
	return runPowerShell(script)
}

func runPowerShell(script string) string {
	cmd := exec.Command("powershell",
		"-NoProfile",
		"-NonInteractive",
		"-WindowStyle", "Hidden",
		"-Command", script,
	)
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// ── Linux (zenity) ────────────────────────────────────────────────────────────

func zenityOpenFile(title string) string {
	out, err := exec.Command("zenity",
		"--file-selection",
		"--title="+title,
		"--file-filter=level.dat",
	).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func zenityOpenFolder(title string) string {
	out, err := exec.Command("zenity",
		"--file-selection",
		"--directory",
		"--title="+title,
	).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// ── macOS ─────────────────────────────────────────────────────────────────────

func macOpenFile(title string) string {
	out, err := exec.Command("osascript", "-e",
		`choose file with prompt "`+title+`" of type {"dat"}`,
	).Output()
	if err != nil {
		return ""
	}
	// osascript returns "alias MacHD:Users:..." — convert to POSIX
	path := strings.TrimSpace(string(out))
	if strings.HasPrefix(path, "alias ") {
		path = strings.TrimPrefix(path, "alias ")
		posix, err2 := exec.Command("osascript", "-e",
			`POSIX path of ("`+path+`" as alias)`,
		).Output()
		if err2 == nil {
			return strings.TrimSpace(string(posix))
		}
	}
	return path
}

func macOpenFolder(title string) string {
	out, err := exec.Command("osascript", "-e",
		`choose folder with prompt "`+title+`"`,
	).Output()
	if err != nil {
		return ""
	}
	path := strings.TrimSpace(string(out))
	if strings.HasPrefix(path, "alias ") {
		path = strings.TrimPrefix(path, "alias ")
		posix, err2 := exec.Command("osascript", "-e",
			`POSIX path of ("`+path+`" as alias)`,
		).Output()
		if err2 == nil {
			return strings.TrimSpace(string(posix))
		}
	}
	return path
}
