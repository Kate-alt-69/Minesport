#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${MINESPORT_VERSION:-0.2.0}"
ARCH_RAW="$(uname -m)"
case "$ARCH_RAW" in
  x86_64) DEB_ARCH=amd64 ;;
  aarch64|arm64) DEB_ARCH=arm64 ;;
  *) echo "ERROR: unsupported Debian architecture: $ARCH_RAW" >&2; exit 1 ;;
esac

BIN="$ROOT/desktop/dist/minesport"
OUT="$ROOT/dist/installer"
PKGROOT="$ROOT/dist/linux-deb-root"

[[ -f "$BIN" ]] || { echo "ERROR: required file is missing: $BIN" >&2; exit 1; }
command -v dpkg-deb >/dev/null 2>&1 || { echo "ERROR: dpkg-deb is required to build a .deb package." >&2; exit 1; }

rm -rf "$PKGROOT"
mkdir -p \
  "$PKGROOT/DEBIAN" \
  "$PKGROOT/usr/lib/kastrick_software/minesport" \
  "$PKGROOT/usr/bin" \
  "$PKGROOT/usr/share/applications" \
  "$PKGROOT/usr/share/icons/hicolor/scalable/apps" \
  "$OUT"
chmod g-s "$PKGROOT/DEBIAN" || true
chmod 0755 "$PKGROOT/DEBIAN"

# The Fabric, Forge, NeoForge and Quilt 1.21.10 Bridge JARs are embedded in
# this executable by desktop/build.rs. Do not ship loose Bridge copies.
install -m 0755 "$BIN" "$PKGROOT/usr/lib/kastrick_software/minesport/minesport"
install -m 0644 "$ROOT/installer/linux/minesport.desktop" "$PKGROOT/usr/share/applications/minesport.desktop"
install -m 0644 "$ROOT/installer/linux/minesport.svg" "$PKGROOT/usr/share/icons/hicolor/scalable/apps/minesport.svg"

cat > "$PKGROOT/usr/bin/minesport" <<'EOF'
#!/usr/bin/env bash
exec /usr/lib/kastrick_software/minesport/minesport "$@"
EOF
chmod 0755 "$PKGROOT/usr/bin/minesport"

cat > "$PKGROOT/DEBIAN/control" <<EOF
Package: minesport
Version: $VERSION
Section: graphics
Priority: optional
Architecture: $DEB_ARCH
Maintainer: Kastrick
Depends: libgl1, libx11-6, libxcursor1, libxrandr2, libxinerama1, libxi6
Description: Minesport Minecraft world exporter
 Rust + Slint desktop for exporting Minecraft worlds and modded block geometry for DCC applications.
EOF
chmod 0644 "$PKGROOT/DEBIAN/control"

cat > "$PKGROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
fi
exit 0
EOF
chmod 0755 "$PKGROOT/DEBIAN/postinst"

PACKAGE="$OUT/Minesport-${VERSION}-${DEB_ARCH}.deb"
dpkg-deb --build --root-owner-group "$PKGROOT" "$PACKAGE"
echo "Built: $PACKAGE"
