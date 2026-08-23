# Fyne V3 workbench icons

These SVGs are copied from `fyne.io/fyne/v2` v2.5.2 `theme/icons` so the Rust + Slint desktop can reproduce the archived Fyne V3 workbench and world picker exactly.

Included mappings:
- `folder-open.svg` → `theme.FolderOpenIcon()`
- `download.svg` → `theme.DownloadIcon()`
- `media-video.svg` → `theme.MediaVideoIcon()`
- `history.svg` → `theme.HistoryIcon()`
- `settings.svg` → `theme.SettingsIcon()`
- `computer.svg` → `theme.ComputerIcon()`
- `file.svg` → `theme.FileIcon()`

The SVG geometry is kept unchanged; Slint applies the Minesport workbench foreground tint at render time using `Image.colorize`.

Upstream: Fyne v2.5.2 (`fyne-io/fyne`). See `LICENSE.fyne.txt` in this directory.
