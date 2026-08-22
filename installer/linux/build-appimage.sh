#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${MINESPORT_VERSION:-0.2.0}"
BRIDGE_VERSION="0.2.0"
ARCH_RAW="$(uname -m)"
case "$ARCH_RAW" in
  x86_64) APPIMAGE_ARCH=x86_64; TOOL_ARCH=x86_64 ;;
  aarch64|arm64) APPIMAGE_ARCH=aarch64; TOOL_ARCH=aarch64 ;;
  *) echo "ERROR: unsupported AppImage architecture: $ARCH_RAW" >&2; exit 1 ;;
esac

BIN="$ROOT/wrapper/dist/minesport"
MANIFEST="$ROOT/bridge-versions/manifest.json"
BRIDGE="$ROOT/dist/bundled-bridge/minesport-bridge-${BRIDGE_VERSION}.jar"
OUT="$ROOT/dist/installer"
APPDIR="$ROOT/dist/Minesport.AppDir"
TOOL="$ROOT/dist/appimagetool-${TOOL_ARCH}.AppImage"

for file in "$BIN" "$MANIFEST" "$BRIDGE"; do
  [[ -f "$file" ]] || { echo "ERROR: required file is missing: $file" >&2; exit 1; }
done
command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required to obtain appimagetool." >&2; exit 1; }

rm -rf "$APPDIR"
mkdir -p \
  "$APPDIR/usr/bin" \
  "$APPDIR/usr/share/kastrick_software/minesport/bridge-data/bundled/1.21.10" \
  "$APPDIR/usr/share/kastrick_software/minesport/bridge-data/compiled" \
  "$APPDIR/usr/share/applications" \
  "$APPDIR/usr/share/icons/hicolor/scalable/apps" \
  "$OUT"

install -m 0755 "$BIN" "$APPDIR/usr/bin/minesport"
install -m 0644 "$MANIFEST" "$APPDIR/usr/share/kastrick_software/minesport/bridge-data/manifest.json"
install -m 0644 "$BRIDGE" "$APPDIR/usr/share/kastrick_software/minesport/bridge-data/bundled/1.21.10/minesport-bridge-${BRIDGE_VERSION}.jar"
install -m 0644 "$ROOT/installer/linux/minesport.desktop" "$APPDIR/minesport.desktop"
install -m 0644 "$ROOT/installer/linux/minesport.desktop" "$APPDIR/usr/share/applications/minesport.desktop"
install -m 0644 "$ROOT/installer/linux/minesport.svg" "$APPDIR/minesport.svg"
install -m 0644 "$ROOT/installer/linux/minesport.svg" "$APPDIR/usr/share/icons/hicolor/scalable/apps/minesport.svg"

cat > "$APPDIR/AppRun" <<'EOF'
#!/usr/bin/env bash
set -e
HERE="$(dirname "$(readlink -f "$0")")"
export MINESPORT_BRIDGE_DATA="$HERE/usr/share/kastrick_software/minesport/bridge-data"
exec "$HERE/usr/bin/minesport" "$@"
EOF
chmod 0755 "$APPDIR/AppRun"

if [[ ! -x "$TOOL" ]]; then
  echo "Downloading appimagetool..."
  curl -fL --retry 3 \
    "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-${TOOL_ARCH}.AppImage" \
    -o "$TOOL"
  chmod 0755 "$TOOL"
fi

OUTPUT="$OUT/Minesport-${VERSION}-${APPIMAGE_ARCH}.AppImage"
ARCH="$APPIMAGE_ARCH" APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$APPDIR" "$OUTPUT"
chmod 0755 "$OUTPUT"
echo "Built: $OUTPUT"
