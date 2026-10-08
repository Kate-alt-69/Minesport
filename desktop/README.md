# Minesport Desktop — Rust + Slint

This is the replacement for the archived Go/Fyne desktop UI.

## Workbench flow

1. **Open world** — choose a launcher, instance, and save, or browse directly to a world folder.
2. **Select area** — drag on the map, choose **Pan map** to move it with the left mouse button, or enable **Enter coordinates** for precise bounds. Bubble selections expose their center/radius through the same coordinate controls.
3. **Export** — choose a preset, format, name, and output folder. Mesh customization and advanced settings remain optional. A disabled export button explains what is needed next.

**Controls** opens the map/preview gesture guide. **Activity** keeps task details and errors visible after work stops. Export completion details appear in **Export**. **Help** opens the manual; Escape dismisses the guide, manual, or settings.

For UI-only builds and review screenshots, see [UX preview](ux-preview/README.md).

## Architecture

- Slint owns presentation and the native event loop only.
- Rust owns application state, file dialogs, process lifecycle and Java IPC.
- Heavy work always runs outside the Slint event-loop thread.
- Worker results return to Slint with `Weak::upgrade_in_event_loop`, so Java/Gradle/cache work cannot directly mutate widgets.
- The Java engine protocol is intentionally unchanged during the UI migration.
- Runtime Bridge/cache services are migrated in stages; existing headless Go packages remain available until equivalent Rust modules are proven.

## Build

The root build script builds the Java engine first. `desktop/build.rs` then embeds that JAR into the Rust executable.

Manual development build:

```powershell
cd desktop
cargo run
```

If the engine JAR is outside the normal `engine/build/libs` directory, set `MINESPORT_ENGINE_JAR` to it before running Cargo.

Pinned toolchain: Rust 1.92.0, Slint 1.17.1.
