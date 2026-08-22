# Archived Go/Fyne desktop UI

This directory preserves the Minesport 0.2.0 Go/Fyne desktop implementation as it existed immediately before the Rust + Slint desktop migration.

It is reference/archive code, not the active desktop application.

- `ui/` is the complete former `wrapper/ui` package, including its tests.
- `main.go` is the former Go desktop entrypoint.
- `go.mod` / `go.sum` preserve the dependency snapshot used by that implementation.
- Headless Go backend packages such as bridge compatibility/capture and IPC remain outside this archive during the staged migration so they can be reused until their Rust replacements are ready.

The active replacement lives under `/desktop/` and uses Rust + Slint.
