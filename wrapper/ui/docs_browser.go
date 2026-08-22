package ui

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

const (
	documentationIndexURL = "https://raw.githubusercontent.com/Kate-alt-69/Minesport/main/doc/index.json"
	documentationRawBase  = "https://raw.githubusercontent.com/Kate-alt-69/Minesport/main/"
	documentationGitBase  = "https://github.com/Kate-alt-69/Minesport/blob/main/"
	documentationMaxBytes = 2 << 20
)

type documentationIndex struct {
	Schema   int                 `json:"schema"`
	Version  string              `json:"version"`
	Pages    []documentationPage `json:"pages"`
}

type documentationPage struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Summary  string `json:"summary"`
	Path     string `json:"path"`
	VideoURL string `json:"video_url"`
}

var fallbackDocumentationPages = []documentationPage{
	{ID: "01", Title: "Start Here", Summary: "Minecraft world to Blender, without assuming prior Minesport knowledge.", Path: "doc/page/01.md"},
	{ID: "02", Title: "Minesport Main App", Summary: "World selection, Export, Settings, tasks, and the 0.2 workflow.", Path: "doc/page/02.md"},
	{ID: "10", Title: "Blender Beginner Basics", Summary: "Only the Blender vocabulary needed for Minesport.", Path: "doc/page/10.md"},
	{ID: "11", Title: "Find the Minesport Panel", Summary: "3D Viewport → N → Minesport, with exact beginner steps.", Path: "doc/page/11.md"},
	{ID: "12", Title: "FLATTER for Blender Beginners", Summary: "Logical voxels, green selection, materializing, and Liquid Merge.", Path: "doc/page/12.md"},
	{ID: "13", Title: "Minecraft Light Blocks in Blender", Summary: "Place and understand the 1×1×1 helper and real Blender light.", Path: "doc/page/13.md"},
	{ID: "20", Title: "Troubleshooting", Summary: "Common UI, FLATTER, light, cache, and addon problems.", Path: "doc/page/20.md"},
	{ID: "90", Title: "Runtime Model Cache", Summary: "Why the isolated Fabric worker exists and when its cache rebuilds.", Path: "doc/page/90.md"},
	{ID: "200", Title: "Minesport 0.2.0 Release Notes", Summary: "The official bug-free* 0.2.0 release notes.", Path: "doc/releases/0.2.0.md"},
}

func documentationHTTPClient() *http.Client {
	return &http.Client{Timeout: 10 * time.Second}
}

func fetchDocumentationBytes(rawURL string) ([]byte, error) {
	request, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "Minesport/0.2.0 documentation")
	response, err := documentationHTTPClient().Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("GitHub returned HTTP %d", response.StatusCode)
	}
	limited := io.LimitReader(response.Body, documentationMaxBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return nil, err
	}
	if len(data) > documentationMaxBytes {
		return nil, fmt.Errorf("documentation page is larger than %d MiB", documentationMaxBytes>>20)
	}
	return data, nil
}

func safeDocumentationPath(value string) (string, bool) {
	value = strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
	cleaned := path.Clean(value)
	if cleaned == "." || cleaned == "doc" || strings.HasPrefix(cleaned, "../") || strings.Contains(cleaned, "/../") {
		return "", false
	}
	if !strings.HasPrefix(cleaned, "doc/") || !strings.HasSuffix(strings.ToLower(cleaned), ".md") {
		return "", false
	}
	return cleaned, true
}

func documentationGitHubURL(page documentationPage) string {
	cleaned, ok := safeDocumentationPath(page.Path)
	if !ok {
		return documentationGitBase + "doc/README.md"
	}
	return documentationGitBase + cleaned
}

func documentationRawURL(page documentationPage) (string, bool) {
	cleaned, ok := safeDocumentationPath(page.Path)
	if !ok {
		return "", false
	}
	return documentationRawBase + cleaned, true
}

func openExternalURL(app fyne.App, raw string) error {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return err
	}
	if parsed.Scheme != "https" || parsed.Host == "" {
		return fmt.Errorf("unsupported documentation URL")
	}
	return app.OpenURL(parsed)
}

func (ms *MinesportApp) buildDocumentationSettingsSection() fyne.CanvasObject {
	openDocs := widget.NewButtonWithIcon("Open Minesport documentation", theme.InfoIcon(), ms.openDocumentationBrowser)
	openDocs.Importance = widget.HighImportance
	return container.NewVBox(
		workbenchSection("DOCUMENTATION"),
		workbenchHelp("Beginner guides are streamed from the Minesport GitHub /doc/ folder. Tutorial videos stay on GitHub too, so documentation updates do not bloat or require rebuilding the installer."),
		openDocs,
	)
}

func (ms *MinesportApp) openDocumentationBrowser() {
	pages := append([]documentationPage(nil), fallbackDocumentationPages...)
	selected := 0

	status := widget.NewLabel("Loading the current documentation index from GitHub…")
	status.Wrapping = fyne.TextWrapWord
	title := widget.NewLabelWithStyle("Minesport Documentation", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	summary := widget.NewLabel("")
	summary.Wrapping = fyne.TextWrapWord
	pageHost := container.NewMax(widget.NewLabel("Choose a page from the list."))

	openGitHub := widget.NewButtonWithIcon("Open page on GitHub", theme.ComputerIcon(), func() {})
	watchVideo := widget.NewButtonWithIcon("Watch tutorial video", theme.MediaVideoIcon(), func() {})
	watchVideo.Disable()

	var list *widget.List
	var showPage func(int)

	showPage = func(index int) {
		if index < 0 || index >= len(pages) {
			return
		}
		selected = index
		page := pages[index]
		title.SetText(fmt.Sprintf("Page %s · %s", page.ID, page.Title))
		summary.SetText(page.Summary)
		status.SetText("Loading " + page.Path + " from GitHub…")
		pageHost.RemoveAll()
		loading := widget.NewProgressBarInfinite()
		loading.Start()
		pageHost.Add(container.NewCenter(container.NewVBox(loading, widget.NewLabel("Streaming Markdown…"))))

		openGitHub.OnTapped = func() {
			if err := openExternalURL(ms.fyneApp, documentationGitHubURL(page)); err != nil {
				dialog.ShowError(err, ms.window)
			}
		}
		if strings.TrimSpace(page.VideoURL) == "" {
			watchVideo.Disable()
			watchVideo.OnTapped = nil
		} else {
			watchVideo.Enable()
			videoURL := page.VideoURL
			watchVideo.OnTapped = func() {
				if err := openExternalURL(ms.fyneApp, videoURL); err != nil {
					dialog.ShowError(err, ms.window)
				}
			}
		}

		rawURL, ok := documentationRawURL(page)
		if !ok {
			loading.Stop()
			pageHost.RemoveAll()
			pageHost.Add(widget.NewLabel("This documentation entry has an invalid repository path."))
			status.SetText("Invalid page path · open the documentation index on GitHub to report it.")
			return
		}

		go func(expectedPath string) {
			data, err := fetchDocumentationBytes(rawURL)
			if selected != index || pages[index].Path != expectedPath {
				return
			}
			loading.Stop()
			pageHost.RemoveAll()
			if err != nil {
				message := widget.NewLabel("Could not stream this page right now. Check your connection or use ‘Open page on GitHub’.\n\n" + err.Error())
				message.Wrapping = fyne.TextWrapWord
				pageHost.Add(container.NewVScroll(container.NewPadded(message)))
				status.SetText("Documentation stream failed; GitHub link is still available.")
				return
			}
			rich := widget.NewRichTextFromMarkdown(string(data))
			rich.Wrapping = fyne.TextWrapWord
			pageHost.Add(container.NewVScroll(container.NewPadded(rich)))
			status.SetText("Loaded from GitHub · " + expectedPath)
		}(page.Path)
	}

	list = widget.NewList(
		func() int { return len(pages) },
		func() fyne.CanvasObject {
			id := widget.NewLabel("000")
			id.TextStyle = fyne.TextStyle{Monospace: true, Bold: true}
			name := widget.NewLabel("Documentation page")
			name.Truncation = fyne.TextTruncateEllipsis
			return container.NewBorder(nil, nil, id, nil, name)
		},
		func(item widget.ListItemID, object fyne.CanvasObject) {
			if item < 0 || item >= len(pages) {
				return
			}
			row := object.(*fyne.Container)
			id := row.Objects[0].(*widget.Label)
			name := row.Objects[1].(*widget.Label)
			id.SetText(pages[item].ID)
			name.SetText(pages[item].Title)
		},
	)
	list.OnSelected = func(item widget.ListItemID) { showPage(item) }

	previous := widget.NewButtonWithIcon("Previous", theme.NavigateBackIcon(), func() {
		if selected > 0 {
			list.Select(selected - 1)
		}
	})
	next := widget.NewButtonWithIcon("Next", theme.NavigateNextIcon(), func() {
		if selected+1 < len(pages) {
			list.Select(selected + 1)
		}
	})

	left := container.NewBorder(
		container.NewVBox(widget.NewLabelWithStyle("PAGES", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}), widget.NewSeparator()),
		nil, nil, nil,
		list,
	)
	left.SetMinSize(fyne.NewSize(285, 520))
	right := container.NewBorder(
		container.NewVBox(title, summary, widget.NewSeparator()),
		container.NewVBox(widget.NewSeparator(), status, container.NewHBox(previous, next, openGitHub, watchVideo)),
		nil, nil,
		pageHost,
	)
	split := container.NewHSplit(left, right)
	split.SetOffset(0.30)

	d := dialog.NewCustom("Minesport Documentation · 0.2.x", "Close", split, ms.window)
	d.Resize(fyne.NewSize(980, 680))
	d.Show()
	list.Select(0)

	go func() {
		data, err := fetchDocumentationBytes(documentationIndexURL)
		if err != nil {
			status.SetText("Using built-in page list; GitHub index could not be refreshed: " + err.Error())
			return
		}
		var index documentationIndex
		if err := json.Unmarshal(data, &index); err != nil || index.Schema != 1 || len(index.Pages) == 0 {
			status.SetText("Using built-in page list; GitHub documentation index is invalid.")
			return
		}
		cleaned := make([]documentationPage, 0, len(index.Pages))
		for _, page := range index.Pages {
			if strings.TrimSpace(page.ID) == "" || strings.TrimSpace(page.Title) == "" {
				continue
			}
			if _, ok := safeDocumentationPath(page.Path); !ok {
				continue
			}
			cleaned = append(cleaned, page)
		}
		if len(cleaned) == 0 {
			status.SetText("Using built-in page list; GitHub index contained no usable pages.")
			return
		}
		pages = cleaned
		list.Refresh()
		status.SetText(fmt.Sprintf("Documentation index refreshed from GitHub · %d pages", len(pages)))
		if selected >= len(pages) {
			selected = 0
		}
		list.Select(selected)
	}()
}
