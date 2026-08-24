#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="0.2.0"
BRIDGE_VERSION="0.2.0"
BUILD_DEB=false
BUILD_APPIMAGE=false

show_help() {
  cat <<'EOF'
Minesport build script

Usage:
  ./build.sh [options]

Default behavior:
  Builds the Minecraft 1.21.10 Fabric, Forge, NeoForge and Quilt Bridges,
  builds the Java engine, then tests/builds the Rust + Slint desktop binary
  with all four loader Bridges embedded. The archived Go/Fyne UI is not part
  of the active build.
  No installer/package is built unless an installer flag is supplied.

Installer options (Linux):
  --build-installer           Build the default Linux installer (.deb)
  --build-installer-all       Build all Linux formats (.deb + .AppImage)
  --build-installer-deb       Build a Debian/Ubuntu/Mint .deb package
  --build-installer-appimage  Build a portable AppImage

Help:
  -h, --help                  Show this help text
EOF
}

for arg in "$@"; do
  case "$arg" in
    -h|--help) show_help; exit 0 ;;
    --build-installer) BUILD_DEB=true ;;
    --build-installer-all) BUILD_DEB=true; BUILD_APPIMAGE=true ;;
    --build-installer-deb) BUILD_DEB=true ;;
    --build-installer-appimage) BUILD_APPIMAGE=true ;;
    --build-installer-exe|--build-installer-msi)
      echo "ERROR: $arg is Windows-only; use build.ps1/build.bat on Windows." >&2
      exit 2
      ;;
    *)
      echo "ERROR: unknown option: $arg" >&2
      echo "Run ./build.sh --help for supported options." >&2
      exit 2
      ;;
  esac
done

OS_NAME="$(uname -s)"
if [[ "$OS_NAME" != "Linux" ]] && { $BUILD_DEB || $BUILD_APPIMAGE; }; then
  echo "ERROR: Linux installer flags require Linux. Current OS: $OS_NAME" >&2
  exit 2
fi

cd "$ROOT"
echo "============================================"
echo " Minesport $VERSION Build Script"
echo "============================================"
echo "Target: ${OS_NAME} / $(uname -m)"
echo "Desktop: Rust + Slint 1.17.1"
if ! $BUILD_DEB && ! $BUILD_APPIMAGE; then
  echo "Packaging: disabled (binary only)"
else
  formats=()
  $BUILD_DEB && formats+=("DEB")
  $BUILD_APPIMAGE && formats+=("AppImage")
  echo "Packaging: ${formats[*]}"
fi
echo

BUNDLED_DIR="$ROOT/dist/bundled-bridge"
FABRIC_BRIDGE="$BUNDLED_DIR/minesport-bridge-fabric-${BRIDGE_VERSION}.jar"
FORGE_BRIDGE="$BUNDLED_DIR/minesport-bridge-forge-${BRIDGE_VERSION}.jar"
NEOFORGE_BRIDGE="$BUNDLED_DIR/minesport-bridge-neoforge-${BRIDGE_VERSION}.jar"
QUILT_BRIDGE="$BUNDLED_DIR/minesport-bridge-quilt-${BRIDGE_VERSION}.jar"
mkdir -p "$BUNDLED_DIR"

build_bridge() {
  local label="$1"
  local project="$2"
  local destination="$3"
  echo "  -> ${label}"
  cd "$ROOT/$project"
  chmod +x gradlew
  ./gradlew --no-daemon --stacktrace clean build
  local jar
  jar="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' -print -quit)"
  if [[ -z "$jar" ]]; then
    echo "ERROR: ${label} Bridge JAR was not produced under ${project}/build/libs." >&2
    exit 1
  fi
  cp "$jar" "$destination"
  [[ -s "$destination" ]] || { echo "ERROR: staged ${label} Bridge is empty: $destination" >&2; exit 1; }
}

echo "[1/3] Building bundled Minecraft 1.21.10 loader Bridges..."
build_bridge "Fabric" "minesport-bridge-fabric" "$FABRIC_BRIDGE"
build_bridge "Forge" "bridge-forge" "$FORGE_BRIDGE"
build_bridge "NeoForge" "bridge-neoforge" "$NEOFORGE_BRIDGE"
build_bridge "Quilt" "bridge-quilt" "$QUILT_BRIDGE"
echo "Bundled Bridges staged under dist/bundled-bridge/"
echo

echo "[2/3] Building Java engine..."
cd "$ROOT/engine"
chmod +x gradlew
./gradlew --no-daemon --stacktrace clean build
ENGINE_JAR="$(find build/libs -maxdepth 1 -type f -name 'minesport-engine-*.jar' ! -name '*sources*' -print -quit)"
if [[ -z "$ENGINE_JAR" ]]; then
  echo "ERROR: Java engine JAR was not produced!" >&2
  exit 1
fi
ENGINE_JAR_ABS="$(pwd)/${ENGINE_JAR}"
echo "Java engine built: ${ENGINE_JAR_ABS}"
echo

echo "[3/3] Testing and building Rust + Slint desktop..."
command -v cargo >/dev/null 2>&1 || { echo "ERROR: Rust/Cargo is required. Install rustup from https://rustup.rs and rerun build.sh." >&2; exit 1; }
command -v rustc >/dev/null 2>&1 || { echo "ERROR: rustc is unavailable. Repair the Rust toolchain with rustup." >&2; exit 1; }
cd "$ROOT/desktop"
export MINESPORT_ENGINE_JAR="$ENGINE_JAR_ABS"
export MINESPORT_BRIDGE_JAR="$FABRIC_BRIDGE"
export MINESPORT_BRIDGE_FABRIC_JAR="$FABRIC_BRIDGE"
export MINESPORT_BRIDGE_FORGE_JAR="$FORGE_BRIDGE"
export MINESPORT_BRIDGE_NEOFORGE_JAR="$NEOFORGE_BRIDGE"
export MINESPORT_BRIDGE_QUILT_JAR="$QUILT_BRIDGE"
echo "  -> Rust toolchain..."
rustc --version
cargo --version
echo "  -> running Rust + Slint tests..."
cargo test
echo "  -> building optimized Rust + Slint desktop..."
cargo build --release
RUST_BIN="$ROOT/desktop/target/release/minesport"
[[ -x "$RUST_BIN" ]] || { echo "ERROR: Rust build did not produce $RUST_BIN" >&2; exit 1; }
mkdir -p "$ROOT/desktop/dist" "$ROOT/dist/source"
cp "$RUST_BIN" "$ROOT/desktop/dist/minesport"
cp "$RUST_BIN" "$ROOT/dist/source/minesport"
cd "$ROOT"
echo "Minesport built: desktop/dist/minesport"
echo "Standalone binary staged: dist/source/minesport"

if $BUILD_DEB; then
  echo
  echo "Building Debian package..."
  chmod +x installer/linux/build-deb.sh
  MINESPORT_VERSION="$VERSION" installer/linux/build-deb.sh
fi

if $BUILD_APPIMAGE; then
  echo
  echo "Building AppImage..."
  chmod +x installer/linux/build-appimage.sh
  MINESPORT_VERSION="$VERSION" installer/linux/build-appimage.sh
fi

echo
echo "============================================"
echo " Build complete!"
echo "============================================"
echo " desktop/dist/minesport"
echo " dist/source/minesport"
$BUILD_DEB && echo " dist/installer/Minesport-*.deb"
$BUILD_APPIMAGE && echo " dist/installer/Minesport-*.AppImage"
echo
echo "Run ./build.sh --help to see packaging options."
