# Minesport Documentation

This folder is the canonical user manual for Minesport 0.2.x. The desktop app's **Settings → Documentation** browser reads [`index.json`](index.json) and streams the selected Markdown page from this repository instead of bundling a copy into the installer.

## Pages

| Page | Beginner guide |
|---|---|
| [01](page/01.md) | Start Here |
| [02](page/02.md) | Minesport Main App |
| [10](page/10.md) | Blender Beginner Basics |
| [11](page/11.md) | Find the Minesport Panel |
| [12](page/12.md) | FLATTER for Blender Beginners |
| [13](page/13.md) | Minecraft Light Blocks in Blender |
| [20](page/20.md) | Troubleshooting |
| [90](page/90.md) | Runtime Model Cache |
| [200](releases/0.2.0.md) | Minesport 0.2.0 release notes |

## Linking to a specific answer

Prefer a page link instead of assuming the reader already knows Blender or Minesport terminology. For example:

> Never opened the Minesport panel before? Read **[Page 11 — Find the Minesport Panel](page/11.md)** first.

> Not sure what a FLATTER object is? Read **[Page 12 — FLATTER for Blender Beginners](page/12.md)**.

Stable GitHub URLs use the form:

`https://github.com/Kate-alt-69/Minesport/blob/main/doc/page/12.md`

## Tutorial videos

Video files are intentionally **not** packaged into Minesport. When a tutorial video is added to GitHub, put its URL in the matching `video_url` entry in [`index.json`](index.json). The desktop documentation browser can then expose it without increasing the installer size.

See [`media/README.md`](media/README.md) for the video convention.
