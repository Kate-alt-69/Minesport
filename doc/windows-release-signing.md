# Signed Windows release setup

Minesport's Windows engine updater intentionally refuses unsigned release installers. The signed release workflow therefore requires a Windows Authenticode code-signing certificate before it can publish a GitHub Release.

## Required repository secrets

- `WINDOWS_SIGNING_PFX_BASE64` — base64 encoding of the code-signing `.pfx`/`.p12` certificate file.
- `WINDOWS_SIGNING_PFX_PASSWORD` — password for that PFX.

The workflow never commits the certificate. It writes the decoded PFX only under the GitHub-hosted runner temporary directory and removes it in an `always()` cleanup step.

## Release flow

Run **Minesport Signed Windows Release** manually and provide:

- `run_id` — a completed, successful **Minesport Compile Check** run from `main`.
- `tag` — the release tag to create, for example `v0.2.1`.
- `prerelease` — whether GitHub should mark the Release as a prerelease.

The release workflow:

1. Verifies the supplied CI run is green and came from `main`.
2. Downloads the Windows standalone artifact from that exact commit.
3. Authenticode-signs `Minesport.exe` and `minesport-engine.exe`.
4. Regenerates `minesport-engine.json` **after** signing the engine so its SHA-256 and size remain correct.
5. Rebuilds NSIS and MSI using the signed runtime binaries.
6. Authenticode-signs both installers.
7. Requires the GUI, engine, NSIS installer, and MSI to validate under the same publisher certificate.
8. Revalidates the engine identity/protocol/hash/size against `minesport-engine.json`.
9. Creates the GitHub Release and uploads the NSIS installer, MSI, GUI executable, engine executable, and engine manifest.

GitHub Release asset digests plus Authenticode publisher matching are the trust inputs consumed by the automatic engine updater. If the signing secrets are not configured, publication fails instead of producing an updater-compatible unsigned Release.
