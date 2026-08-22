# Minesport Documentation Media

Tutorial videos are intentionally kept **out of the Minesport installer**.

When a tutorial video is ready:

1. Add the video to GitHub (this repository, a GitHub Release asset, or another GitHub-hosted location appropriate for the file size).
2. Obtain a URL that can be opened/streamed by the user's browser/player.
3. Edit [`../index.json`](../index.json).
4. Put that URL into the matching page's `video_url` field.

Example:

```json
{
  "id": "12",
  "title": "FLATTER for Blender Beginners",
  "path": "doc/page/12.md",
  "video_url": "https://github.com/Kate-alt-69/Minesport/releases/download/docs/flatter-beginner.mp4"
}
```

The Minesport Documentation browser reads the index from GitHub. A newly added/changed video can therefore appear to installed 0.2.x clients without repackaging the application.

## Suggested video topics

- Page 10 — Blender Beginner Basics
- Page 11 — Find the Minesport panel
- Page 12 — FLATTER selection / green outline / Materialize / Liquid Merge
- Page 13 — Place a Minecraft Light block
- Page 90 — Runtime Model Cache explanation

Keep the Markdown page useful without the video. The video should demonstrate the instructions, not contain information that exists nowhere else.
