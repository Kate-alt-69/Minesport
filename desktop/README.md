# Minesport Desktop — Rust + Slint

This is the replacement for the archived Go/Fyne desktop UI.

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
