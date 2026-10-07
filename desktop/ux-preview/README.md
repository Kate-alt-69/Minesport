# Desktop UX preview

Compile and render the real workbench and world picker without building Java,
launching Minecraft, loading mods, or opening a user's save:

```sh
cargo run --manifest-path desktop/ux-preview/Cargo.toml -- ux-snapshots
```

Run from the repository root with a graphical display available. On Linux CI:

```sh
SLINT_BACKEND=winit-software SLINT_COLOR_SCHEME=dark xvfb-run -a \
  cargo run --manifest-path desktop/ux-preview/Cargo.toml -- ux-snapshots
```

The screenshots use synthetic world/map data. They cover welcome, selection,
export setup, failure, success, settings, a 960 × 640 window, and populated/empty
world pickers. The picker markup is read from the production Rust file; the
preview does not maintain a separate copy of the UI.

The **Desktop UX preview** workflow publishes these images as a review artifact
on relevant pull requests. The normal repository build still validates the full
Rust/Java integration.
